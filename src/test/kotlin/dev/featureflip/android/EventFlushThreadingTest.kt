package dev.featureflip.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Regression guards for #2478.
 *
 * `HttpClient.postEvents` is an inline OkHttp `execute()`, so whichever thread
 * enters `EventProcessor.flush()` is the thread that performs the request. Both
 * `flush()` and `close()` reached it directly from the caller — and on Android's
 * main thread that is a `NetworkOnMainThreadException`, i.e. a crash in the host
 * app rather than a dropped event. The background-transition path already did it
 * correctly by handing the call to a coroutine scope.
 *
 * These tests assert the thread the request actually ran on, not merely that it
 * ran: a flush that still blocks the caller sends exactly the same request.
 */
class EventFlushThreadingTest {

    private val server = MockWebServer()

    /** Every thread that has entered [Call.execute], in order. */
    private val executeThreads = LinkedBlockingQueue<Thread>()

    @BeforeEach
    fun setUp() = server.start()

    @AfterEach
    fun tearDown() = server.close()

    /** Wraps a real factory, recording the thread each [Call.execute] runs on. */
    private inner class RecordingCallFactory(private val delegate: Call.Factory) : Call.Factory {
        override fun newCall(request: Request): Call = RecordingCall(delegate.newCall(request))

        private inner class RecordingCall(private val call: Call) : Call by call {
            override fun execute(): Response {
                executeThreads.put(Thread.currentThread())
                return call.execute()
            }

            override fun clone(): Call = RecordingCall(call.clone())
        }
    }

    /** Holds every events request open until [gate] opens. */
    private fun gateEventsRequestOn(gate: CountDownLatch) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                gate.await()
                return MockResponse.Builder().code(200).build()
            }
        }
    }

    private fun coreWithPendingEvent(enqueueResponse: Boolean = true): SharedFeatureflipCore {
        if (enqueueResponse) server.enqueue(MockResponse.Builder().code(200).build())
        val config = FeatureflipConfig(
            clientKey = "threading-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            streaming = false,
            // Pinned, not defaulted: at a batch size of 1 the size trigger would
            // flush on EventProcessor's own scope and every assertion below would
            // hold with the fix reverted.
            flushBatchSize = 100,
        )
        // initialize() is deliberately not called: it would start the periodic
        // flush job, and the request under test has to be the one the caller asked for.
        val core = SharedFeatureflipCore.create(
            config,
            callFactory = RecordingCallFactory(OkHttpClient()),
            anonymousKeyStore = InMemoryAnonymousKeyStore(),
        )
        core.track("threading-event", null)
        return core
    }

    /** Blocks until the events request is sent, and returns the thread that sent it. */
    private fun threadThatSentEvents(): Thread =
        requireNotNull(executeThreads.poll(5, TimeUnit.SECONDS)) {
            "no events request was sent within 5s"
        }

    @Test
    fun `flush does not send events on the calling thread`() {
        val core = coreWithPendingEvent()

        core.flush()

        assertThat(threadThatSentEvents()).isNotSameAs(Thread.currentThread())
    }

    @Test
    fun `close does not send the final batch on the calling thread`() {
        val core = coreWithPendingEvent()

        // Sole reference, so this runs the real shutdown and its final flush.
        core.release()

        assertThat(threadThatSentEvents()).isNotSameAs(Thread.currentThread())
    }

    @Test
    fun `releaseAndAwait finishes shutdown even when the caller is cancelled mid-send`() = runBlocking<Unit> {
        val gate = CountDownLatch(1)
        gateEventsRequestOn(gate)
        val core = coreWithPendingEvent(enqueueResponse = false)
        val liveCores = ConcurrentHashMap<String, SharedFeatureflipCore>()
        liveCores["threading-key"] = core
        core.setOwningMap(liveCores, "threading-key")

        val job = launch(Dispatchers.Default) { core.releaseAndAwait() }
        threadThatSentEvents() // the final flush is now inside the held request
        job.cancel()
        gate.countDown()
        job.join()

        // Shutdown is cleanup: cancelling the caller must not strand the core in
        // the factory map with a refcount of zero, which is what a plain
        // withContext does — it throws on resume and skips everything after it.
        assertThat(liveCores).isEmpty()
    }

    @Test
    fun `events are refused once the shutdown flush is under way`() {
        val gate = CountDownLatch(1)
        gateEventsRequestOn(gate)
        val core = coreWithPendingEvent(enqueueResponse = false)

        core.release()
        threadThatSentEvents() // the final flush is now inside the held request

        core.track("late-event", null)

        // The batch under way was already taken from the buffer, so anything here
        // is an event accepted after shutdown began.
        assertThat(core.debugBufferedEventCount()).isZero()
        gate.countDown()
    }

    @Test
    fun `flushAndAwait has sent the batch by the time it returns`() = runBlocking<Unit> {
        val core = coreWithPendingEvent()

        core.flushAndAwait()

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(threadThatSentEvents()).isNotSameAs(Thread.currentThread())
    }

    @Test
    fun `releaseAndAwait has sent the final batch by the time it returns`() = runBlocking<Unit> {
        val core = coreWithPendingEvent()

        core.releaseAndAwait()

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(threadThatSentEvents()).isNotSameAs(Thread.currentThread())
    }
}
