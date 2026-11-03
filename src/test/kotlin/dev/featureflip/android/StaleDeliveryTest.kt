package dev.featureflip.android

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A response requested for one user must never land on top of a later user's flags
 * (#4886). A poll, a stream frame, the initial fetch and an overlapping identify()
 * all carry flags evaluated for whatever context was current when they were
 * requested. Once identify() has swapped in the next user, any of them still on the
 * wire is stale: applied, it serves the previous user's values and, since 3.5.0,
 * broadcasts the flip to onUpdate listeners (the OpenFeature provider forwards it as
 * PROVIDER_CONFIGURATION_CHANGED).
 *
 * Each test holds the stale response at a gate until identify() has finished, then
 * releases it. Proving the release did NOT revert anything needs a bounded wait; when
 * the bug is present the revert lands within milliseconds of the release.
 */
class StaleDeliveryTest {

    private val server = MockWebServer()

    // Every gate a test opens, released in tearDown so no dispatcher thread is left
    // parked when the server shuts down.
    private val gates = CopyOnWriteArrayList<CountDownLatch>()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        gates.forEach { it.countDown() }
        FeatureflipClient.resetForTesting()
        server.close()
    }

    private fun gate() = CountDownLatch(1).also { gates.add(it) }

    private fun flagsBody(x: String, vararg extra: Pair<String, String>): String {
        val entries = (listOf("x" to x) + extra).joinToString(",") { (k, v) ->
            """"$k":{"value":"$v","variation":"v-$v","reason":"RULE"}"""
        }
        return """{"flags":{$entries}}"""
    }

    private fun ok(body: String) = MockResponse.Builder().body(body).build()

    private fun held() = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .bodyDelay(30, TimeUnit.SECONDS)
        .body("")
        .build()

    private fun path(request: RecordedRequest) = request.target.substringBefore('?')

    private fun client(streaming: Boolean, inspectors: List<EvaluationInspector> = emptyList()) =
        FeatureflipClient.get(
            FeatureflipConfig(
                clientKey = "stale-${UUID.randomUUID()}",
                baseUrl = server.url("/").toString().trimEnd('/'),
                context = mapOf("user_id" to "user-a"),
                streaming = streaming,
                pollIntervalMs = 60_000,
                sendEvaluationEvents = false,
                inspectors = inspectors,
            ),
        )

    @Test
    fun `a poll in flight for the previous user does not overwrite identify's flags`() {
        val pollArrived = CountDownLatch(1)
        val releasePoll = gate()
        val evaluates = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (path(request)) {
                "/v1/client/identify" -> ok(flagsBody("user-b"))
                "/v1/client/evaluate" -> {
                    // The first evaluate is initialize's fetch, the second the poller's
                    // immediate first poll, which is held on the wire.
                    if (evaluates.incrementAndGet() == 2) {
                        pollArrived.countDown()
                        releasePoll.await(10, TimeUnit.SECONDS)
                    }
                    ok(flagsBody("user-a"))
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val client = client(streaming = false)
        client.initialize()
        assertThat(pollArrived.await(5, TimeUnit.SECONDS)).isTrue()

        client.identify(mapOf("user_id" to "user-b"))
        assertThat(client.stringVariation("x", "default")).isEqualTo("user-b")

        val reverted = CountDownLatch(1)
        client.onUpdate { reverted.countDown() }
        releasePoll.countDown()

        assertThat(reverted.await(1, TimeUnit.SECONDS))
            .`as`("a poll requested for user-a must not notify after identify(user-b)")
            .isFalse()
        assertThat(client.stringVariation("x", "default")).isEqualTo("user-b")
        client.close()
    }

    @Test
    fun `a frame from the previous user's stream does not overwrite identify's flags`() {
        val streams = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (path(request)) {
                "/v1/client/identify" -> ok(flagsBody("user-b"))
                "/v1/client/evaluate" -> ok(flagsBody("user-a"))
                "/v1/client/stream" ->
                    if (streams.incrementAndGet() == 1) {
                        // Two deltas in one write, so the second is already buffered on
                        // the client when the first one's delivery is held below.
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/event-stream")
                            .body(
                                "event: flags-updated\ndata: " +
                                    """{"flags":{"y":{"value":"y1","variation":"v-y1","reason":"RULE"}}}""" +
                                    "\n\nevent: flags-updated\ndata: " +
                                    """{"flags":{"x":{"value":"stale-a","variation":"v-stale","reason":"RULE"}}}""" +
                                    "\n\n",
                            )
                            .build()
                    } else {
                        held()
                    }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val client = client(streaming = true)

        // Hold the old stream's reader thread inside its first delivery. The listener
        // runs on the delivering thread, so the second frame waits behind it.
        val frameHeld = CountDownLatch(1)
        val releaseFrame = gate()
        val holding = AtomicBoolean(true)
        client.onUpdate {
            if (holding.compareAndSet(true, false)) {
                frameHeld.countDown()
                releaseFrame.await(10, TimeUnit.SECONDS)
            }
        }
        client.initialize()
        assertThat(frameHeld.await(5, TimeUnit.SECONDS)).isTrue()

        client.identify(mapOf("user_id" to "user-b"))
        assertThat(client.stringVariation("x", "default")).isEqualTo("user-b")

        val reverted = CountDownLatch(1)
        client.onUpdate { keys -> if ("x" in keys) reverted.countDown() }
        releaseFrame.countDown()

        assertThat(reverted.await(1, TimeUnit.SECONDS))
            .`as`("a frame from user-a's stream must not notify after identify(user-b)")
            .isFalse()
        assertThat(client.stringVariation("x", "default")).isEqualTo("user-b")
        client.close()
    }

    @Test
    fun `an initial fetch for the previous user does not overwrite identify's flags`() {
        val fetchArrived = CountDownLatch(1)
        val releaseFetch = gate()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (path(request)) {
                "/v1/client/identify" -> ok(flagsBody("user-b"))
                "/v1/client/evaluate" ->
                    if (request.body!!.utf8().contains("user-a")) {
                        fetchArrived.countDown()
                        releaseFetch.await(10, TimeUnit.SECONDS)
                        ok(flagsBody("user-a"))
                    } else {
                        // The poller starts after identify, for user-b. Failing it keeps
                        // it from repairing a stale initial fetch and hiding the bug.
                        MockResponse.Builder().code(500).build()
                    }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val client = client(streaming = false)
        val init = thread { client.initialize() }
        assertThat(fetchArrived.await(5, TimeUnit.SECONDS)).isTrue()

        client.identify(mapOf("user_id" to "user-b"))
        releaseFetch.countDown()
        init.join(10_000)

        assertThat(client.stringVariation("x", "default"))
            .`as`("initialize's fetch was for user-a; identify(user-b) already replaced it")
            .isEqualTo("user-b")
        client.close()
    }

    @Test
    fun `an identify overtaken by a later one is not applied`() {
        val firstArrived = CountDownLatch(1)
        val releaseFirst = gate()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (path(request)) {
                "/v1/client/identify" ->
                    if (request.body!!.utf8().contains("user-a2")) {
                        firstArrived.countDown()
                        releaseFirst.await(10, TimeUnit.SECONDS)
                        ok(flagsBody("user-a2"))
                    } else {
                        ok(flagsBody("user-b"))
                    }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val seenContext = AtomicReference<Map<String, Any?>>()
        // No initialize(): identify needs no data source, so only the two identifies
        // race here.
        val client = client(streaming = false, inspectors = listOf { event -> seenContext.set(event.context) })
        val calls = CopyOnWriteArrayList<List<String>>()
        client.onUpdate { calls.add(it) }

        val first = thread { client.identify(mapOf("user_id" to "user-a2")) }
        assertThat(firstArrived.await(5, TimeUnit.SECONDS)).isTrue()
        client.identify(mapOf("user_id" to "user-b"))
        releaseFirst.countDown()
        first.join(10_000)

        assertThat(client.stringVariation("x", "default")).isEqualTo("user-b")
        assertThat(seenContext.get()?.get("user_id"))
            .`as`("the context must stay the one the LAST identify() asked for")
            .isEqualTo("user-b")
        assertThat(calls)
            .`as`("only user-b's swap notifies; the overtaken response is dropped")
            .containsExactly(listOf("x"))
        client.close()
    }
}
