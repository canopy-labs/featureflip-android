package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ReadRecorderTest {

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val MINUTE_MS = 60_000L
    }

    private val events: MutableList<SdkEvent> = Collections.synchronizedList(mutableListOf())
    private val now = AtomicLong(1_000_000L)

    private fun recorder() = ReadRecorder(
        enqueue = { events.add(it) },
        timestamp = { "2026-10-07T00:00:00.000Z" },
        clock = WindowClock { now.get() },
    )

    private fun reads() = events.map { Triple(it.flagKey, it.variation, it.userId) }

    @Test
    fun `repeated reads in one window enqueue one Evaluation event`() {
        val r = recorder()

        repeat(50) { r.record("flag-a", "on", "user-1") }

        assertThat(events).hasSize(1)
        val e = events.single()
        assertThat(e.type).isEqualTo(SdkEventType.Evaluation)
        assertThat(e.flagKey).isEqualTo("flag-a")
        assertThat(e.variation).isEqualTo("on")
        assertThat(e.userId).isEqualTo("user-1")
        assertThat(e.timestamp).isEqualTo("2026-10-07T00:00:00.000Z")
        assertThat(e.metadata).isNull()
    }

    @Test
    fun `a different flag, variation or user is a separate event`() {
        val r = recorder()

        r.record("flag-a", "on", "user-1")
        r.record("flag-a", "off", "user-1")
        r.record("flag-a", "on", "user-2")
        r.record("flag-b", "on", "user-1")
        r.record("flag-a", "on", "user-1")

        assertThat(reads()).containsExactly(
            Triple("flag-a", "on", "user-1"),
            Triple("flag-a", "off", "user-1"),
            Triple("flag-a", "on", "user-2"),
            Triple("flag-b", "on", "user-1"),
        )
    }

    @Test
    fun `a read of a missing key is recorded once with no variation`() {
        // An old build reading an archived flag must stay visible to the archive guard.
        val r = recorder()

        r.record("gone", null, "user-1")
        r.record("gone", null, "user-1")

        assertThat(reads()).containsExactly(Triple("gone", null, "user-1"))
    }

    @Test
    fun `a read with no user is recorded once with no user`() {
        val r = recorder()

        r.record("flag-a", "on", null)
        r.record("flag-a", "on", null)

        assertThat(reads()).containsExactly(Triple("flag-a", "on", null))
    }

    @Test
    fun `the window is a fixed hour, not the flush interval`() {
        assertThat(ReadRecorder.WINDOW_MS).isEqualTo(HOUR_MS)
        val r = recorder()

        r.record("flag-a", "on", "user-1")
        now.addAndGet(30_000) // one default flush interval
        r.record("flag-a", "on", "user-1")
        now.addAndGet(HOUR_MS - 30_001) // one ms short of the hour
        r.record("flag-a", "on", "user-1")
        assertThat(events).hasSize(1)

        now.addAndGet(1) // exactly one hour after the window opened
        r.record("flag-a", "on", "user-1")
        r.record("flag-a", "on", "user-1")
        assertThat(events).hasSize(2)
    }

    @Test
    fun `the window is measured from its first read, so a flag read constantly is re-reported hourly`() {
        val r = recorder()

        // Reads every 20 minutes from 0 to 180 minutes. Windows open at 0, 60, 120 and 180.
        repeat(10) {
            r.record("flag-a", "on", "user-1")
            now.addAndGet(20 * MINUTE_MS)
        }

        assertThat(events).hasSize(4)
    }

    @Test
    fun `a clock that steps backwards starts a new window instead of stalling it`() {
        val r = recorder()

        r.record("flag-a", "on", "user-1")
        now.addAndGet(-MINUTE_MS)
        r.record("flag-a", "on", "user-1")

        assertThat(events).hasSize(2)
    }

    @Test
    fun `the default clock is wall time`() {
        // Wall time keeps advancing through deep sleep; System.nanoTime (CLOCK_MONOTONIC
        // on Android) does not, and has an arbitrary origin far from the epoch.
        val before = System.currentTimeMillis()
        val r = ReadRecorder(enqueue = {}, timestamp = { "" })
        val after = System.currentTimeMillis()

        assertThat(r.windowStartedAtMs).isBetween(before - 5_000, after + 5_000)
    }

    @Test
    fun `resetWindow starts a new window without the clock moving`() {
        // The core calls this on every return to the foreground: a new window that does
        // not depend on the clock, as a second defence beside the wall clock.
        val r = recorder()

        r.record("flag-a", "on", "user-1")
        r.resetWindow()
        r.record("flag-a", "on", "user-1")
        r.record("flag-a", "on", "user-1")

        assertThat(events).hasSize(2)
    }

    @Test
    fun `concurrent reads of one key from many threads enqueue exactly one event`() {
        val r = recorder()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.execute {
                start.await()
                repeat(1_000) { r.record("hot-flag", "on", "user-1") }
                done.countDown()
            }
        }
        start.countDown()

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        assertThat(events).hasSize(1)
    }

    @Test
    fun `concurrent reads of distinct keys lose none`() {
        val r = recorder()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        for (i in 0 until threads) {
            pool.execute {
                start.await()
                repeat(100) { r.record("flag-$i", "on", "user-1") }
                done.countDown()
            }
        }
        start.countDown()

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        assertThat(events.map { it.flagKey })
            .containsExactlyInAnyOrderElementsOf((0 until threads).map { "flag-$it" })
    }

    @Test
    fun `readers racing a window rollover see a whole window and report the new one once`() {
        val r = recorder()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val phase = CyclicBarrier(threads + 1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.execute {
                try {
                    phase.await() // start together
                    repeat(5_000) { r.record("hot-flag", "on", "user-1") }
                    phase.await() // first window done
                    phase.await() // clock has moved past the hour
                    repeat(5_000) { r.record("hot-flag", "on", "user-1") }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    done.countDown()
                }
            }
        }
        phase.await(10, TimeUnit.SECONDS)
        phase.await(10, TimeUnit.SECONDS)
        now.addAndGet(HOUR_MS)
        phase.await(10, TimeUnit.SECONDS)

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        assertThat(errors).isEmpty()
        // Every second-phase reader rolls over or adopts the winner's window, so the new
        // window reports exactly once.
        assertThat(events).hasSize(2)
    }

    @Test
    fun `a repeat read allocates nothing`() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(
            bean != null && bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled,
            "per-thread allocation counting is unavailable on this JVM",
        )
        checkNotNull(bean)
        val r = recorder()
        r.record("flag-a", "on", "user-1") // the first read may allocate; it creates the entry
        repeat(10_000) { r.record("flag-a", "on", "user-1") }

        val before = bean.currentThreadAllocatedBytes
        repeat(100_000) { r.record("flag-a", "on", "user-1") }
        val allocated = bean.currentThreadAllocatedBytes - before

        // One boxed Long, key object or built string per read would be >= 1.6 MB here.
        // The slack covers the counter's own bookkeeping, not per-read garbage.
        assertThat(allocated).isLessThan(64L * 1024)
        assertThat(events).hasSize(1)
    }
}
