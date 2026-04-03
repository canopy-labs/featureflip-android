package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StreamingDataSourceTest {

    @Test
    fun `buildStreamUrl constructs correct URL`() {
        val url = StreamingDataSource.buildStreamUrl(
            "https://eval.example.com",
            "client-key-123",
            mapOf("user_id" to "user-1"),
        )

        assertThat(url).isNotNull
        assertThat(url).contains("/v1/client/stream")
        assertThat(url).contains("authorization=")
        assertThat(url).contains("context=")
    }

    @Test
    fun `parseSSEEvent parses event with type and data`() {
        val lines = listOf("event: flags-updated", "data: {\"flags\":{}}")
        val event = StreamingDataSource.parseSSEEvent(lines)

        assertThat(event).isNotNull
        assertThat(event!!.eventType).isEqualTo("flags-updated")
        assertThat(event.data).isEqualTo("{\"flags\":{}}")
    }

    @Test
    fun `parseSSEEvent handles multiple data lines`() {
        val lines = listOf("event: flags-updated", "data: line1", "data: line2")
        val event = StreamingDataSource.parseSSEEvent(lines)

        assertThat(event).isNotNull
        assertThat(event!!.data).isEqualTo("line1\nline2")
    }

    @Test
    fun `parseSSEEvent returns null when no event type`() {
        val lines = listOf("data: some-data")
        val event = StreamingDataSource.parseSSEEvent(lines)
        assertThat(event).isNull()
    }

    @Test
    fun `parseSSEEvent handles empty lines list`() {
        val event = StreamingDataSource.parseSSEEvent(emptyList())
        assertThat(event).isNull()
    }

    @Test
    fun `parseSSEEvent handles event with no data`() {
        val lines = listOf("event: heartbeat")
        val event = StreamingDataSource.parseSSEEvent(lines)

        assertThat(event).isNotNull
        assertThat(event!!.eventType).isEqualTo("heartbeat")
        assertThat(event.data).isEmpty()
    }

    @Test
    fun `parseSSEEvent parses connection-ready event`() {
        val lines = listOf(
            "event: connection-ready",
            """data: {"connectionId":"abc-123-def"}"""
        )
        val event = StreamingDataSource.parseSSEEvent(lines)

        assertThat(event).isNotNull
        assertThat(event!!.eventType).isEqualTo("connection-ready")
        assertThat(event.data).contains("abc-123-def")
    }

    @Test
    fun `stop shuts down OkHttpClient thread pools`() {
        val ds = StreamingDataSource(
            baseUrl = "https://eval.example.com",
            clientKey = "key",
            context = mapOf("user_id" to "u1"),
            onChange = {},
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.SupervisorJob()
            ),
        )
        ds.start()
        ds.stop()

        val client = ds.sseClient
        assertThat(client).isNotNull
        assertThat(client!!.dispatcher.executorService.isShutdown).isTrue()
    }
}
