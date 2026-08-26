package dev.featureflip.android

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression guards for #2477: at most one drain loop may run at a time.
 *
 * #2456 added a backoff gate plus an in-flight latch, but the latch guarded only
 * the batch-size trigger. Nothing stopped the periodic job, an explicit `flush()`
 * and a size-triggered flush from entering the drain together — two request
 * streams against the endpoint the gate exists to protect, and a success in one
 * clearing the gate a failure in the other had just armed.
 *
 * The only externally visible evidence of a second drain is a second request
 * arriving while the first is still unanswered, so the dispatcher below parks its
 * first request and records the greatest number ever in flight at once.
 */
class EventFlushCoalescingTest {

    private val server = MockWebServer()

    /** Parks the FIRST request until released, tracking concurrent depth. */
    private class ParkingDispatcher : Dispatcher() {
        val firstArrived = CountDownLatch(1)
        val gate = CountDownLatch(1)
        private val parked = AtomicBoolean()
        private val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val delivered = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val depth = inFlight.incrementAndGet()
            peak.accumulateAndGet(depth) { a, b -> maxOf(a, b) }

            // Only the very FIRST request is parked; re-parking a later one would
            // wait on a gate nothing opens again and hang the test.
            if (parked.compareAndSet(false, true)) {
                firstArrived.countDown()
                gate.await(20, TimeUnit.SECONDS)
            }

            inFlight.decrementAndGet()
            delivered.incrementAndGet()
            return MockResponse.Builder().code(200).build()
        }
    }

    @BeforeEach
    fun setUp() = server.start()

    @AfterEach
    fun tearDown() = server.close()

    private fun event(key: String) = SdkEvent(
        type = SdkEventType.Custom,
        flagKey = key,
        userId = "u1",
        timestamp = "2025-01-01T00:00:00Z",
    )

    /**
     * Batch size 1 so a seeded buffer needs one round-trip per event: plenty of room
     * for a second loop to interleave if one is allowed to start.
     */
    private fun processor() = EventProcessor(
        HttpClient(server.url("/").toString().trimEnd('/'), "test-key"),
        flushIntervalMs = 3_600_000,
        batchSize = 1,
        maxBufferSize = 1000,
    )

    @Test
    fun `a concurrent flush waits for the in-flight drain instead of starting a second`() {
        val dispatcher = ParkingDispatcher()
        server.dispatcher = dispatcher

        val processor = processor()
        // enqueue does not flush inline — the size trigger hands the drain to the
        // processor's own scope — so seeding here is safe, and the first explicit
        // flush below is the drain the dispatcher parks.
        repeat(6) { processor.enqueue(event("flag-$it")) }

        val first = Thread { processor.flush() }
        first.start()
        assertThat(dispatcher.firstArrived.await(20, TimeUnit.SECONDS))
            .`as`("the first event request never reached the server")
            .isTrue()

        val released = AtomicBoolean()
        val secondSawRelease = AtomicBoolean()
        val secondDone = CountDownLatch(1)
        val second = Thread {
            processor.flush()
            secondSawRelease.set(released.get())
            secondDone.countDown()
        }
        second.start()

        // Room for the second caller to misbehave: uncoalesced it takes a batch off
        // the front and posts it, which the peak counter catches.
        Thread.sleep(300)

        released.set(true)
        dispatcher.gate.countDown()

        assertThat(secondDone.await(20, TimeUnit.SECONDS))
            .`as`("the second flush never returned")
            .isTrue()
        first.join(TimeUnit.SECONDS.toMillis(20))
        second.join(TimeUnit.SECONDS.toMillis(20))

        assertThat(dispatcher.peak.get())
            .`as`("peak concurrent event requests — a second drain loop ran")
            .isEqualTo(1)
        // A caller that asked for a flush is asking for its events to be sent, so it
        // waits for the drain rather than returning early. Matches the js/node SDKs.
        assertThat(secondSawRelease.get())
            .`as`("the second flush returned before the in-flight drain finished")
            .isTrue()
        assertThat(processor.bufferedEventCount()).isZero()
    }

    @Test
    fun `stop still drains while a flush is in flight`() {
        val dispatcher = ParkingDispatcher()
        server.dispatcher = dispatcher

        val processor = processor()
        processor.enqueue(event("flag-1"))
        processor.enqueue(event("flag-2"))

        val first = Thread { processor.flush() }
        first.start()
        assertThat(dispatcher.firstArrived.await(20, TimeUnit.SECONDS)).isTrue()

        // Released as stop() runs, so stop() genuinely overlaps the in-flight drain
        // rather than waiting it out first.
        Thread {
            Thread.sleep(100)
            dispatcher.gate.countDown()
        }.start()

        processor.stop()
        first.join(TimeUnit.SECONDS.toMillis(20))

        assertThat(dispatcher.delivered.get())
            .`as`("stop() lost events to coalescing")
            .isEqualTo(2)
    }
}
