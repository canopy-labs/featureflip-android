package dev.featureflip.android

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private fun flagEntries(vararg keys: String): String =
        keys.joinToString(",") { """"$it":{"value":true,"variation":"on","reason":"FALLTHROUGH"}""" }

    // Connect-time snapshot payload — carries the `full: true` marker (#1873).
    private fun fullSnapshotJson(vararg keys: String): String =
        """{"full":true,"flags":{${flagEntries(*keys)}}}"""

    // Delta payload — no `full` marker.
    private fun deltaJson(vararg keys: String): String =
        """{"flags":{${flagEntries(*keys)}}}"""

    @Test
    fun `flags-updated with full=true replaces, without full merges`() {
        val snapshots = mutableListOf<Map<String, FlagValue>>()
        val deltas = mutableListOf<Map<String, FlagValue>>()
        val ds = StreamingDataSource(
            baseUrl = "https://eval.example.com",
            clientKey = "key",
            context = mapOf("user_id" to "u1"),
            onChange = { deltas.add(it) },
            onSnapshot = { snapshots.add(it) },
        )

        // The server marks the connect-time snapshot with `full: true`; deltas omit it.
        ds.handleEvent(StreamingDataSource.SSEEvent("flags-updated", fullSnapshotJson("flag-a")))
        ds.handleEvent(StreamingDataSource.SSEEvent("flags-updated", deltaJson("flag-b")))

        assertThat(snapshots).hasSize(1)
        assertThat(snapshots[0]).containsKey("flag-a")
        assertThat(deltas).hasSize(1)
        assertThat(deltas[0]).containsKey("flag-b")
    }

    @Test
    fun `a delta racing ahead of the snapshot is merged, not replaced (keyed off full, not order)`() {
        val snapshots = mutableListOf<Map<String, FlagValue>>()
        val deltas = mutableListOf<Map<String, FlagValue>>()
        val ds = StreamingDataSource(
            baseUrl = "https://eval.example.com",
            clientKey = "key",
            context = mapOf("user_id" to "u1"),
            onChange = { deltas.add(it) },
            onSnapshot = { snapshots.add(it) },
        )

        // A broadcast delta can arrive before the connect snapshot (the connection
        // registers before its snapshot is sent). It must MERGE, not replace.
        ds.handleEvent(StreamingDataSource.SSEEvent("flags-updated", deltaJson("flag-x")))
        ds.handleEvent(StreamingDataSource.SSEEvent("flags-updated", fullSnapshotJson("flag-y")))

        assertThat(deltas).hasSize(1)
        assertThat(deltas[0]).containsKey("flag-x")
        assertThat(snapshots).hasSize(1)
        assertThat(snapshots[0]).containsKey("flag-y")
    }

    @Test
    fun `stream that stays down triggers onMaxRetriesReached (never terminal)`() {
        val server = MockWebServer()
        repeat(8) { server.enqueue(MockResponse.Builder().code(500).build()) }
        server.start()

        val latch = CountDownLatch(1)
        val ds = StreamingDataSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            clientKey = "key",
            context = mapOf("user_id" to "u1"),
            onChange = {},
            onSnapshot = {},
            onMaxRetriesReached = { latch.countDown() },
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
            ),
            initialBackoffMs = 1L,
        )
        ds.start()

        val fired = latch.await(5, TimeUnit.SECONDS)
        ds.stop()
        server.close()

        assertThat(fired)
            .`as`("onMaxRetriesReached should fire so the core can fall back to polling")
            .isTrue()
    }
}
