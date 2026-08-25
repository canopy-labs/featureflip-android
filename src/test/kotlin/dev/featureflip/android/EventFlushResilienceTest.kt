package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression guards for #2456.
 *
 * Android already restored a failed batch to the front of the buffer, so unlike
 * the server SDKs it never lost events outright. What it lacked was every guard
 * around that restore: it retried a permanently rejected batch forever, it
 * re-fired the size trigger on every subsequent event, it posted the whole
 * buffer in one request, and the buffer had no bound at all.
 */
class EventFlushResilienceTest {

    private val json = jacksonObjectMapper()
    private val server = MockWebServer()

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

    private fun alwaysRespond(code: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(code).build()
        }
    }

    private fun batchSizesOf(count: Int): List<Int> =
        (0 until count).map { server.takeRequest() }.map { req ->
            val body: Map<String, Any> = json.readValue(req.body!!.utf8())
            @Suppress("UNCHECKED_CAST")
            (body["events"] as List<Map<String, Any>>).size
        }

    private fun processor(batchSize: Int = 100, maxBufferSize: Int = 1000) =
        EventProcessor(
            HttpClient(server.url("/").toString().trimEnd('/'), "test-key"),
            flushIntervalMs = 60_000,
            batchSize = batchSize,
            maxBufferSize = maxBufferSize,
        )

    @Test
    fun `a 503 keeps the batch for the next flush`() {
        server.dispatcher = object : Dispatcher() {
            private var n = 0
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(if (n++ == 0) 503 else 200).build()
        }

        val processor = processor()
        processor.enqueue(event("flag-a"))
        processor.flush()
        processor.flush()

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(batchSizesOf(2)).containsExactly(1, 1)
    }

    @Test
    fun `a permanently rejected batch is dropped rather than retried forever`() {
        alwaysRespond(401)

        val processor = processor()
        processor.enqueue(event("flag-a"))
        processor.flush()
        processor.flush()

        // Retrying a rejected SDK key forever would pin the buffer at its bound
        // and starve every later event.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a failing endpoint does not get one request per recorded event`() {
        alwaysRespond(503)

        // batchSize 1: every enqueue trips the size trigger.
        val processor = processor(batchSize = 1)
        repeat(10) {
            processor.enqueue(event("flag-$it"))
            Thread.sleep(20)
        }
        Thread.sleep(200)

        assertThat(server.requestCount)
            .`as`("the restored batch leaves the buffer at the batch size, so without a backoff every later event starts another flush")
            .isEqualTo(1)
    }

    @Test
    fun `never puts more than a batch in one request`() {
        // Record what each request carried, and whether it was accepted, from
        // inside the dispatcher — reading it back afterwards races the coroutine
        // the size trigger launches.
        val delivered = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val attempted = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val failSends = java.util.concurrent.atomic.AtomicBoolean(true)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body: Map<String, Any> = json.readValue(request.body!!.utf8())
                @Suppress("UNCHECKED_CAST")
                val size = (body["events"] as List<Map<String, Any>>).size
                attempted.add(size)
                if (failSends.get()) return MockResponse.Builder().code(503).build()
                delivered.add(size)
                return MockResponse.Builder().code(200).build()
            }
        }

        // Every send fails while the backlog builds; the first failure arms the
        // backoff gate, so the rest simply pile up behind it.
        val processor = processor(batchSize = 2)
        repeat(5) {
            processor.enqueue(event("flag-$it"))
            Thread.sleep(20)
        }
        Thread.sleep(200)

        failSends.set(false)
        processor.flush()

        // A backlog must never go out as one oversized request: a 413 is not
        // retryable, so the path meant to preserve it would be the one that
        // discarded it.
        assertThat(attempted).allSatisfy { assertThat(it).isLessThanOrEqualTo(2) }
        assertThat(delivered.sum())
            .`as`("the whole 5-event backlog must arrive, in batch-sized requests")
            .isEqualTo(5)
    }

    @Test
    fun `the buffer is bounded and sheds the oldest events`() {
        alwaysRespond(503)

        // A bound of 3 with a batch size above it: nothing auto-flushes, so the
        // events simply pile up and the bound is what has to hold.
        val processor = processor(batchSize = 100, maxBufferSize = 3)
        repeat(5) { processor.enqueue(event("flag-$it")) }

        assertThat(processor.bufferedEventCount()).isEqualTo(3)
        assertThat(processor.bufferedFlagKeys()).containsExactly("flag-2", "flag-3", "flag-4")
    }
}
