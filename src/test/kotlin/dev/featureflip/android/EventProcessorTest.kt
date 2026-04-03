package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EventProcessorTest {

    private val json = jacksonObjectMapper()
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `flush sends buffered events`() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val processor = EventProcessor(httpClient, flushIntervalMs = 60_000, batchSize = 100)

        processor.enqueue(
            SdkEvent(type = "Custom", flagKey = "test-event", userId = "user-1", timestamp = "2025-01-01T00:00:00Z"),
        )
        processor.enqueue(
            SdkEvent(type = "Custom", flagKey = "test-event", userId = "user-2", timestamp = "2025-01-01T00:00:01Z"),
        )
        processor.flush()

        val request = server.takeRequest()
        assertThat(request.requestLine).contains("/v1/sdk/events")

        val body: Map<String, Any> = json.readValue(request.body!!.utf8())
        @Suppress("UNCHECKED_CAST")
        val events = body["events"] as List<Map<String, Any>>
        assertThat(events).hasSize(2)
    }

    @Test
    fun `flush does nothing when buffer is empty`() {
        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val processor = EventProcessor(httpClient, flushIntervalMs = 60_000, batchSize = 100)

        processor.flush() // Should not throw or make requests

        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `flush re-queues events on POST failure`() {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(200).build())

        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val processor = EventProcessor(httpClient, flushIntervalMs = 60_000, batchSize = 100)

        processor.enqueue(
            SdkEvent(type = "Custom", flagKey = "test-event", userId = "user-1", timestamp = "2025-01-01T00:00:00Z"),
        )
        processor.flush() // Fails with 500
        processor.flush() // Retries with 200

        assertThat(server.requestCount).isEqualTo(2)

        val retryRequest = server.takeRequest() // first (failed)
        server.takeRequest() // second (succeeded)
        val body: Map<String, Any> = json.readValue(retryRequest.body!!.utf8())
        @Suppress("UNCHECKED_CAST")
        val events = body["events"] as List<Map<String, Any>>
        assertThat(events).hasSize(1)
    }

    @Test
    fun `enqueue batch flush re-queues events on POST failure`() {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(200).build())

        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        // Use Unconfined dispatcher so the coroutine in enqueue runs synchronously
        val testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val processor = EventProcessor(httpClient, flushIntervalMs = 60_000, batchSize = 2, scope = testScope)

        processor.enqueue(
            SdkEvent(type = "Custom", flagKey = "test-event", userId = "user-1", timestamp = "2025-01-01T00:00:00Z"),
        )
        processor.enqueue(
            SdkEvent(type = "Custom", flagKey = "test-event", userId = "user-2", timestamp = "2025-01-01T00:00:01Z"),
        )

        // Events should be back in buffer — flush again
        processor.flush()

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `stop flushes remaining events`() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val processor = EventProcessor(httpClient, flushIntervalMs = 60_000, batchSize = 100)
        processor.start()

        processor.enqueue(
            SdkEvent(type = "Custom", flagKey = "test-event", userId = "user-1", timestamp = "2025-01-01T00:00:00Z"),
        )
        processor.stop()

        assertThat(server.requestCount).isEqualTo(1)
    }
}
