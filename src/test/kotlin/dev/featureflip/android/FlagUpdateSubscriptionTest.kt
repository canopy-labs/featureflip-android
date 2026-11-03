package dev.featureflip.android

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FlagUpdateSubscriptionTest {

    private val server = MockWebServer()
    private lateinit var originalErr: PrintStream
    private lateinit var captured: ByteArrayOutputStream

    @BeforeEach
    fun setUp() {
        server.start()
        captured = ByteArrayOutputStream()
        originalErr = System.err
        System.setErr(PrintStream(captured, true))
    }

    @AfterEach
    fun tearDown() {
        System.setErr(originalErr)
        FeatureflipClient.resetForTesting()
        server.close()
    }

    private fun fv(v: Any?) = FlagValue(v, "", "FALLTHROUGH")

    // No initialize(): no data source runs, so only the test drives updates.
    private fun client(key: String = "k-test"): FeatureflipClient =
        FeatureflipClient.get(
            FeatureflipConfig(
                clientKey = key,
                baseUrl = server.url("/").toString().trimEnd('/'),
                streaming = false,
                pollIntervalMs = 60_000,
            ),
        )

    private fun recorder() = CopyOnWriteArrayList<List<String>>()

    @Test
    fun `streaming delta notifies the changed keys`() {
        val c = client("k-delta")
        c.applyFullSnapshot(mapOf("a" to fv(true), "b" to fv(true)))
        val calls = recorder()
        c.onUpdate { calls.add(it) }

        c.mergeSnapshot(mapOf("b" to fv(false)))

        assertThat(calls).containsExactly(listOf("b"))
    }

    @Test
    fun `FLAG_REMOVED delta notifies the removed key`() {
        val c = client("k-removed")
        c.applyFullSnapshot(mapOf("a" to fv(true), "b" to fv(true)))
        val calls = recorder()
        c.onUpdate { calls.add(it) }

        c.mergeSnapshot(mapOf("a" to FlagValue(null, "", "FLAG_REMOVED")))

        assertThat(calls).containsExactly(listOf("a"))
    }

    @Test
    fun `FLAG_REMOVED delta for an unknown key does not notify`() {
        val c = client("k-unknown")
        c.applyFullSnapshot(mapOf("a" to fv(true)))
        val calls = recorder()
        c.onUpdate { calls.add(it) }

        c.mergeSnapshot(mapOf("zzz" to FlagValue(null, "", "FLAG_REMOVED")))

        assertThat(calls).isEmpty()
    }

    @Test
    fun `full snapshot notifies only real changes`() {
        val c = client("k-full")
        val first = mapOf("a" to fv(true), "b" to fv(true))
        c.applyFullSnapshot(first)
        val calls = recorder()
        c.onUpdate { calls.add(it) }

        c.applyFullSnapshot(first.toMap())
        assertThat(calls).isEmpty()

        c.applyFullSnapshot(mapOf("b" to fv(false)))
        assertThat(calls).containsExactly(listOf("a", "b"))
    }

    @Test
    fun `listener reading flags sees the new values`() {
        val c = client("k-read")
        c.applyFullSnapshot(mapOf("b" to fv(true)))
        val latch = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val seen = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            c.onUpdate {
                // Read from a DIFFERENT thread: the merging thread may hold the write
                // lock, and a reentrant read on that thread would hide a notify-under-lock bug.
                try {
                    seen.set(executor.submit<Boolean> { c.boolVariation("b", true) }.get(10, TimeUnit.SECONDS))
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    latch.countDown()
                }
            }

            Thread { c.mergeSnapshot(mapOf("b" to fv(false))) }.start()

            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue()
            assertThat(failure.get()).isNull()
            assertThat(seen.get()).isFalse()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a listener subscribed during notification runs from the next update`() {
        val c = client("k-subscribe-during")
        c.applyFullSnapshot(mapOf("a" to fv(1)))
        val late = recorder()
        var subscribed = false
        c.onUpdate {
            if (!subscribed) {
                subscribed = true
                c.onUpdate { late.add(it) }
            }
        }

        c.mergeSnapshot(mapOf("a" to fv(2)))
        assertThat(late).isEmpty()

        c.mergeSnapshot(mapOf("a" to fv(3)))
        assertThat(late).containsExactly(listOf("a"))
    }

    @Test
    fun `a throwing listener is reported and others still run`() {
        val c = client("k-throw")
        c.applyFullSnapshot(mapOf("a" to fv(true)))
        val calls = recorder()
        c.onUpdate { throw IllegalStateException("boom") }
        c.onUpdate { calls.add(it) }

        c.mergeSnapshot(mapOf("a" to fv(false)))

        assertThat(calls).containsExactly(listOf("a"))
        val logs = captured.toString()
        assertThat(logs).contains("[featureflip] flag update listener threw")
        assertThat(logs).contains("boom")
    }

    @Test
    fun `unsubscribing from inside a callback does not disturb the others`() {
        val c = client("k-selfunsub")
        c.applyFullSnapshot(mapOf("a" to fv(1)))
        val first = recorder()
        val second = recorder()
        lateinit var unsub: () -> Unit
        unsub = c.onUpdate {
            first.add(it)
            unsub()
        }
        c.onUpdate { second.add(it) }

        c.mergeSnapshot(mapOf("a" to fv(2)))
        c.mergeSnapshot(mapOf("a" to fv(3)))

        assertThat(first).hasSize(1)
        assertThat(second).hasSize(2)
    }

    @Test
    fun `unsubscribe is idempotent`() {
        val c = client("k-idem")
        c.applyFullSnapshot(mapOf("a" to fv(1)))
        val calls = recorder()
        val unsub = c.onUpdate { calls.add(it) }

        unsub()
        unsub()
        c.mergeSnapshot(mapOf("a" to fv(2)))

        assertThat(calls).isEmpty()
    }

    @Test
    fun `closed handle onUpdate is a no-op`() {
        val h1 = client("k-closed")
        val h2 = client("k-closed")
        h2.applyFullSnapshot(mapOf("a" to fv(1)))
        h1.close()
        val calls = recorder()

        val unsub = h1.onUpdate { calls.add(it) }
        unsub()
        h2.mergeSnapshot(mapOf("a" to fv(2)))

        assertThat(calls).isEmpty()
    }

    @Test
    fun `close drops this handle's listeners only`() {
        val h1 = client("k-shared")
        val h2 = client("k-shared")
        h1.applyFullSnapshot(mapOf("a" to fv(1)))
        val c1 = recorder()
        val c2 = recorder()
        h1.onUpdate { c1.add(it) }
        h2.onUpdate { c2.add(it) }

        h1.close()
        h2.mergeSnapshot(mapOf("a" to fv(2)))

        assertThat(c1).isEmpty()
        assertThat(c2).containsExactly(listOf("a"))
    }

    @Test
    fun `forTesting client never notifies`() {
        val c = FeatureflipClient.forTesting(mapOf("a" to true))
        val calls = recorder()
        c.onUpdate { calls.add(it) }

        c.identify(mapOf("user_id" to "u"))

        assertThat(calls).isEmpty()
    }

    // -- Through the network --

    private fun body(vararg flags: Pair<String, Any?>): String =
        "{\"flags\":{" + flags.joinToString(",") { (k, v) ->
            "\"$k\":{\"value\":$v,\"variation\":\"v1\",\"reason\":\"RULE\"}"
        } + "}}"

    // Matches what body() puts on the wire, so a seeded flag compares equal to a served one.
    private fun wire(v: Any?) = FlagValue(v, "v1", "RULE")

    private fun enqueue(b: String) = server.enqueue(MockResponse.Builder().body(b).build())

    // Drains initialize's request and the poller's immediate first poll, so the next
    // enqueued response can only be consumed by identify().
    private fun drainInit() {
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
    }

    @Test
    fun `initialize does not notify`() {
        enqueue(body("x" to true))
        enqueue(body("x" to true))
        val c = client("k-init-silent")
        val latch = CountDownLatch(1)
        c.onUpdate { latch.countDown() }

        c.initialize()
        drainInit()

        assertThat(latch.await(500, TimeUnit.MILLISECONDS)).isFalse()
    }

    @Test
    fun `a poll that changes values notifies`() {
        enqueue(body("x" to true))
        enqueue(body("x" to false, "y" to true))
        val c = client("k-poll-change")
        val calls = recorder()
        val latch = CountDownLatch(1)
        c.onUpdate {
            calls.add(it)
            latch.countDown()
        }

        c.initialize()

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(calls).containsExactly(listOf("x", "y"))
    }

    // identify() needs no data source: with none running it goes straight to the HTTP
    // identify call, so these tests seed a snapshot and enqueue only the identify
    // response. No poller or stream can race the swap.
    //
    // The notification is delivered synchronously on the identify caller's thread,
    // BEFORE identify() returns. The OpenFeature provider's identify-suppression
    // counter depends on that ordering; moving delivery onto a dispatcher would
    // silently break it, and the "no waiting" assertions below pin it.
    @Test
    fun `identify notifies the new user's changed keys`() {
        val c = client("k-identify")
        c.applyFullSnapshot(mapOf("x" to wire(true)))
        val calls = recorder()
        c.onUpdate { calls.add(it) }
        enqueue(body("x" to false))

        c.identify(mapOf("user_id" to "u2"))

        // Synchronous: no waiting after identify returns.
        assertThat(calls).containsExactly(listOf("x"))
    }

    @Test
    fun `identify with identical values does not notify`() {
        val c = client("k-identify-same")
        c.applyFullSnapshot(mapOf("x" to wire(true)))
        val calls = recorder()
        c.onUpdate { calls.add(it) }
        enqueue(body("x" to true))

        c.identify(mapOf("user_id" to "u2"))

        assertThat(calls).isEmpty()
    }

    @Test
    fun `failed identify does not notify`() {
        val c = client("k-identify-fail")
        c.applyFullSnapshot(mapOf("x" to wire(true)))
        val calls = recorder()
        c.onUpdate { calls.add(it) }
        server.enqueue(MockResponse.Builder().code(500).build())

        assertThrows<Exception> { c.identify(mapOf("user_id" to "u2")) }

        assertThat(calls).isEmpty()
        assertThat(c.boolVariation("x", false)).isTrue()
    }

    @Test
    fun `a listener closing its own handle during delivery does not disturb another handle`() {
        val h1 = client("k-selfclose")
        val h2 = client("k-selfclose")
        h1.applyFullSnapshot(mapOf("a" to fv(1)))
        val first = recorder()
        val second = recorder()
        h1.onUpdate {
            first.add(it)
            h1.close()
        }
        h2.onUpdate { second.add(it) }

        h2.mergeSnapshot(mapOf("a" to fv(2)))
        h2.mergeSnapshot(mapOf("a" to fv(3)))

        assertThat(first).hasSize(1)
        assertThat(second).containsExactly(listOf("a"), listOf("a"))
    }
}
