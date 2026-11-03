package dev.featureflip.android

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Closing the last handle releases the core and unregisters it from the factory, and
 * nothing can reach it again. Anything started on it afterwards runs for the life of
 * the process: the streaming source retries forever (#3075). So nothing that is still
 * in flight when close() lands may start a data source on the released core (#4901).
 */
class CloseDuringStartTest {

    private val server = MockWebServer()
    private val gates = CopyOnWriteArrayList<CountDownLatch>()
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
        gates.forEach { it.countDown() }
        FeatureflipClient.resetForTesting()
        server.close()
    }

    private fun gate() = CountDownLatch(1).also { gates.add(it) }

    private val flagsBody = """{"flags":{"x":{"value":true,"variation":"on","reason":"RULE"}}}"""

    private fun ok() = MockResponse.Builder().body(flagsBody).build()

    private fun held() = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .bodyDelay(30, TimeUnit.SECONDS)
        .body("")
        .build()

    private fun path(request: RecordedRequest) = request.target.substringBefore('?')

    private fun config(streaming: Boolean) = FeatureflipConfig(
        clientKey = "close-${UUID.randomUUID()}",
        baseUrl = server.url("/").toString().trimEnd('/'),
        context = mapOf("user_id" to "user-a"),
        streaming = streaming,
        pollIntervalMs = 60_000,
        sendEvaluationEvents = false,
    )

    @Test
    fun `closing the only handle during initialize's fetch starts no data source`() {
        val fetchArrived = CountDownLatch(1)
        val releaseFetch = gate()
        val laterRequests = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (fetchArrived.count > 0 && path(request) == "/v1/client/evaluate") {
                    fetchArrived.countDown()
                    releaseFetch.await(10, TimeUnit.SECONDS)
                    return ok()
                }
                laterRequests.add(path(request))
                return if (path(request) == "/v1/client/stream") held() else ok()
            }
        }
        val client = FeatureflipClient.get(config(streaming = true))
        val init = thread { client.initialize() }
        assertThat(fetchArrived.await(5, TimeUnit.SECONDS)).isTrue()

        client.close()
        assertThat(FeatureflipClient.debugLiveCoreCount()).isZero()
        releaseFetch.countDown()
        init.join(10_000)
        assertThat(init.isAlive).`as`("initialize() must have returned").isFalse()

        assertThat(client.hasStreamingSource()).`as`("no stream on a released core").isFalse()
        assertThat(client.hasPollingSource()).`as`("no poller on a released core").isFalse()
        assertThat(client.lifecycleObserver).`as`("no lifecycle observer on a released core").isNull()
        // A source that did start connects on an IO thread within milliseconds.
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull() // initialize's fetch
        assertThat(server.takeRequest(1, TimeUnit.SECONDS))
            .`as`("no stream or poll request may follow the release")
            .isNull()
        assertThat(laterRequests).isEmpty()
        assertThat(captured.toString()).contains("[featureflip] client closed during initialize()")
    }

    @Test
    fun `closing the only handle during identify does not restart the stream`() {
        val streamConnects = CopyOnWriteArrayList<String>()
        val firstStream = CountDownLatch(1)
        val secondStream = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (path(request)) {
                "/v1/client/stream" -> {
                    streamConnects.add(request.target)
                    if (streamConnects.size == 1) firstStream.countDown() else secondStream.countDown()
                    held()
                }
                else -> ok()
            }
        }
        val core = SharedFeatureflipCore.create(config(streaming = true))
        core.initialize()
        assertThat(firstStream.await(5, TimeUnit.SECONDS)).isTrue()

        // The last handle goes away after identify's response has been applied, but
        // before the data sources are pointed at the new user.
        core.afterIdentifyAppliedForTest = { core.release() }
        core.identify(mapOf("user_id" to "user-b"))

        assertThat(core.debugIsShutDown).isTrue()
        assertThat(core.hasStreamingSource()).isFalse()
        assertThat(secondStream.await(1, TimeUnit.SECONDS))
            .`as`("identify must not reconnect the stream of a released core")
            .isFalse()
    }

    @Test
    fun `initialize on a closed handle does nothing`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (path(request) == "/v1/client/stream") held() else ok()
        }
        val config = config(streaming = false)
        val closed = FeatureflipClient.get(config)
        val open = FeatureflipClient.get(config)
        closed.close()

        closed.initialize()

        assertThat(server.requestCount).`as`("a closed handle must not fetch").isZero()
        assertThat(open.isInitialized).isFalse()
        assertThat(open.hasPollingSource()).isFalse()
        open.close()
    }
}
