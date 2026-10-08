package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Read reporting through the shared core (#3545): which calls count as a read, which
 * user a read is attributed to, and that the header and the events are switched by the
 * same option.
 */
class ReadReportingTest {

    private companion object {
        const val HEADER = "X-Featureflip-Reports-Evaluations"
    }

    private val json = jacksonObjectMapper()
    private val server = MockWebServer()

    /** Every event the SDK posted, in order. */
    private val postedEvents = LinkedBlockingQueue<Map<String, Any?>>()

    /** Every evaluate and identify request, in order. */
    private val flagRequests = LinkedBlockingQueue<RecordedRequest>()

    private val servedFlags = mapOf("flag-a" to FlagValue(value = true, variation = "on", reason = "RULE"))

    @BeforeEach
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    target.startsWith("/v1/client/events") -> {
                        val body: Map<String, Any?> = json.readValue(request.body!!.utf8())
                        @Suppress("UNCHECKED_CAST")
                        (body["events"] as List<Map<String, Any?>>).forEach { postedEvents.put(it) }
                        MockResponse.Builder().code(202).build()
                    }
                    target.startsWith("/v1/client/evaluate") || target.startsWith("/v1/client/identify") -> {
                        flagRequests.put(request)
                        MockResponse.Builder().body(json.writeValueAsString(mapOf("flags" to servedFlags))).build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        FeatureflipClient.resetForTesting()
        server.close()
    }

    private fun config(
        sendEvaluationEvents: Boolean = true,
        context: Map<String, Any?> = mapOf("user_id" to "user-1"),
    ) = FeatureflipConfig(
        clientKey = "read-reporting-${UUID.randomUUID()}",
        baseUrl = server.url("/").toString().trimEnd('/'),
        context = context,
        streaming = false,
        pollIntervalMs = 60_000,
        sendEvaluationEvents = sendEvaluationEvents,
    )

    /** Drains what has been posted so far as (flagKey, variation, userId). */
    private fun reads(): List<Triple<Any?, Any?, Any?>> {
        val events = generateSequence { postedEvents.poll() }.toList()
        events.forEach { assertThat(it["type"]).isEqualTo("Evaluation") }
        return events.map { Triple(it["flagKey"], it["variation"], it["userId"]) }
    }

    /**
     * Flushes until an event arrives, or fails after 5s. A single flushAndAwait() can
     * coalesce into a drain the background transition already started, which may have
     * taken the buffer before the event under test was queued. Looping guarantees a
     * fresh drain, and needs no sleep.
     */
    private suspend fun flushUntilPosted(client: FeatureflipClient): Map<String, Any?> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            client.flushAndAwait()
            postedEvents.poll(50, TimeUnit.MILLISECONDS)?.let { return it }
        }
        error("no read was posted within 5s")
    }

    @Test
    fun `each typed accessor and flagDetail report a read with the served variation`() = runBlocking<Unit> {
        val client = FeatureflipClient.get(config())
        client.mergeSnapshot(
            mapOf(
                "flag-bool" to FlagValue(value = true, variation = "on", reason = "RULE"),
                "flag-string" to FlagValue(value = "blue", variation = "blue-arm", reason = "RULE"),
                "flag-number" to FlagValue(value = 3.0, variation = "three", reason = "RULE"),
                "flag-json" to FlagValue(value = mapOf("k" to 1), variation = "obj", reason = "RULE"),
                "flag-detail" to FlagValue(value = true, variation = "detail-on", reason = "RULE"),
            ),
        )

        client.boolVariation("flag-bool", false)
        client.stringVariation("flag-string", "")
        client.numberVariation("flag-number", 0.0)
        client.jsonVariation("flag-json", null)
        client.flagDetail("flag-detail")
        client.flushAndAwait()

        assertThat(reads()).containsExactly(
            Triple("flag-bool", "on", "user-1"),
            Triple("flag-string", "blue-arm", "user-1"),
            Triple("flag-number", "three", "user-1"),
            Triple("flag-json", "obj", "user-1"),
            Triple("flag-detail", "detail-on", "user-1"),
        )
    }

    @Test
    fun `a read of a key the snapshot lacks is reported with no variation`() = runBlocking<Unit> {
        val client = FeatureflipClient.get(config())

        assertThat(client.boolVariation("never-served", true)).isTrue()
        client.flushAndAwait()

        val event = postedEvents.single()
        assertThat(event).containsEntry("flagKey", "never-served")
        assertThat(event).containsEntry("userId", "user-1")
        assertThat(event).doesNotContainKey("variation")
    }

    @Test
    fun `allFlags does not report reads`() = runBlocking<Unit> {
        val client = FeatureflipClient.get(config())
        client.mergeSnapshot(servedFlags)

        assertThat(client.allFlags()).containsKey("flag-a")
        client.flushAndAwait()

        assertThat(postedEvents).isEmpty()
    }

    @Test
    fun `repeated reads of one flag through different accessors are reported once`() = runBlocking<Unit> {
        val client = FeatureflipClient.get(config())
        client.mergeSnapshot(servedFlags)

        repeat(100) { client.boolVariation("flag-a", false) }
        client.flagDetail("flag-a")
        client.jsonVariation("flag-a", null)
        client.flushAndAwait()

        assertThat(reads()).containsExactly(Triple("flag-a", "on", "user-1"))
    }

    @Test
    fun `handles on one client key share one dedupe window`() = runBlocking<Unit> {
        val cfg = config()
        val h1 = FeatureflipClient.get(cfg)
        val h2 = FeatureflipClient.get(cfg)
        h1.mergeSnapshot(servedFlags)

        h1.boolVariation("flag-a", false)
        h2.boolVariation("flag-a", false)
        h1.flushAndAwait()

        assertThat(reads()).containsExactly(Triple("flag-a", "on", "user-1"))
    }

    @Test
    fun `concurrent reads from many threads are reported once`() = runBlocking<Unit> {
        val client = FeatureflipClient.get(config())
        client.mergeSnapshot(servedFlags)
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.execute {
                start.await()
                repeat(500) { client.boolVariation("flag-a", false) }
                done.countDown()
            }
        }
        start.countDown()

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        client.flushAndAwait()
        assertThat(reads()).containsExactly(Triple("flag-a", "on", "user-1"))
    }

    @Test
    fun `after identify the new user's read is its own event in the same window`() = runBlocking<Unit> {
        // Pins the cached userId: if identify() did not refresh it, the second user's
        // read would be attributed to user-1 and swallowed by user-1's dedupe entry.
        val client = FeatureflipClient.get(config())
        client.mergeSnapshot(servedFlags)

        client.boolVariation("flag-a", false)
        client.identify(mapOf("user_id" to "user-2"))
        client.boolVariation("flag-a", false)
        client.boolVariation("flag-a", false)
        client.flushAndAwait()

        assertThat(reads()).containsExactly(
            Triple("flag-a", "on", "user-1"),
            Triple("flag-a", "on", "user-2"),
        )
    }

    @Test
    fun `an anonymous user's reads carry the anonymous id evaluate sent`() = runBlocking<Unit> {
        val client = FeatureflipClient.get(config(context = emptyMap()))
        client.initialize()
        val evaluate = requireNotNull(flagRequests.poll(5, TimeUnit.SECONDS)) { "no evaluate request" }
        val body: Map<String, Any?> = json.readValue(evaluate.body!!.utf8())
        @Suppress("UNCHECKED_CAST")
        val anonymousId = (body["context"] as Map<String, Any?>)["user_id"] as String
        assertThat(anonymousId).isNotBlank()

        client.boolVariation("flag-a", false)
        client.flushAndAwait()

        assertThat(reads()).containsExactly(Triple("flag-a", "on", anonymousId))
        client.close()
    }

    @Test
    fun `a read before initialize is reported with no variation and reaches the first flush`() = runBlocking<Unit> {
        val client = FeatureflipClient.get(config())

        client.boolVariation("flag-a", false) // nothing fetched yet
        client.initialize()
        client.boolVariation("flag-a", false) // now served "on": a different variation
        client.flushAndAwait()

        assertThat(reads()).containsExactly(
            Triple("flag-a", null, "user-1"),
            Triple("flag-a", "on", "user-1"),
        )
        client.close()
    }

    @Test
    fun `going to the background sends the recorded reads`() {
        val client = FeatureflipClient.get(config())
        client.initialize()
        client.boolVariation("flag-a", false)

        requireNotNull(client.lifecycleObserver) { "initialize() registers the observer" }.simulateBackground()

        // Well inside the 30s periodic flush, so only the background flush can send it.
        val event = requireNotNull(postedEvents.poll(5, TimeUnit.SECONDS)) { "background did not flush the read" }
        assertThat(event).containsEntry("type", "Evaluation")
        assertThat(event).containsEntry("flagKey", "flag-a")
        assertThat(event).containsEntry("variation", "on")
        client.close()
    }

    @Test
    fun `returning to the foreground starts a new window, so a read is reported again`() = runBlocking<Unit> {
        // The spec requires a new window on every return to the foreground, as a defence
        // that does not depend on the clock. No clock is advanced here: real time moves
        // only seconds, far under the hour, so only the foreground reset can produce the
        // second event.
        val client = FeatureflipClient.get(config())
        client.initialize()
        val observer = requireNotNull(client.lifecycleObserver) { "initialize() registers the observer" }

        client.boolVariation("flag-a", false)
        observer.simulateBackground()
        assertThat(flushUntilPosted(client)).containsEntry("flagKey", "flag-a")

        observer.simulateForeground()
        client.boolVariation("flag-a", false)
        val again = flushUntilPosted(client)
        assertThat(again).containsEntry("flagKey", "flag-a")
        assertThat(again).containsEntry("variation", "on")
        assertThat(again).containsEntry("userId", "user-1")
        client.close()
    }

    @Test
    fun `evaluate and identify tell the server this client reports its reads`() {
        val client = FeatureflipClient.get(config())
        client.initialize()
        client.identify(mapOf("user_id" to "user-2"))

        val requests = flagRequests.toList()
        assertThat(requests.map { it.target.substringBefore('?') })
            .contains("/v1/client/evaluate", "/v1/client/identify")
        requests.forEach { assertThat(it.headers[HEADER]).isEqualTo("1") }
        client.close()
    }

    @Test
    fun `with sendEvaluationEvents off there are no read events and no header`() = runBlocking<Unit> {
        val client = FeatureflipClient.get(config(sendEvaluationEvents = false))
        client.initialize()
        client.identify(mapOf("user_id" to "user-2"))

        client.boolVariation("flag-a", false)
        client.boolVariation("never-served", false)
        client.flagDetail("flag-a")
        client.flushAndAwait()

        assertThat(postedEvents).isEmpty()
        val requests = flagRequests.toList()
        assertThat(requests).isNotEmpty()
        requests.forEach { assertThat(it.headers[HEADER]).isNull() }
        client.close()
    }

    @Test
    fun `a test client reports nothing`() {
        val core = SharedFeatureflipCore.createForTesting(mapOf("flag-a" to true))

        core.boolVariation("flag-a", false)
        core.stringVariation("missing", "")

        assertThat(core.debugBufferedEventCount()).isZero()
    }

    @Test
    fun `a read still returns its value when recording it throws`() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val core = SharedFeatureflipCore.create(
            config(),
            anonymousKeyStore = InMemoryAnonymousKeyStore(),
            enqueueRead = {
                attempts.incrementAndGet()
                throw IllegalStateException("enqueue failed")
            },
        )
        core.applyFlagUpdateForTest(
            mapOf(
                "flag-a" to FlagValue(value = false, variation = "off", reason = "RULE"),
                "flag-s" to FlagValue(value = "blue", variation = "blue", reason = "RULE"),
                "flag-n" to FlagValue(value = 7, variation = "seven", reason = "RULE"),
                "flag-j" to FlagValue(value = mapOf("k" to "v"), variation = "obj", reason = "RULE"),
            ),
        )

        assertThat(core.boolVariation("flag-a", true)).isFalse()
        assertThat(core.stringVariation("flag-s", "red")).isEqualTo("blue")
        assertThat(core.numberVariation("flag-n", 0.0)).isEqualTo(7.0)
        assertThat(core.jsonVariation("flag-j", null)).isEqualTo(mapOf("k" to "v"))
        assertThat(core.flagDetail("flag-a")?.variation).isEqualTo("off")
        // Four first reads reached the throwing enqueue; flagDetail repeated flag-a's.
        assertThat(attempts.get()).isEqualTo(4)
        core.release()
    }
}
