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
            onSnapshot = {},
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
    fun `stream that stays down arms the fallback once and keeps retrying underneath`() {
        // The fallback is ADDITIVE, never terminal (#3075). Returning out of the
        // connect loop at the cap left the app blind to real-time updates — kill
        // switches included — until it was restarted, after ~31s of unreachability.
        val server = MockWebServer()
        repeat(40) { server.enqueue(MockResponse.Builder().code(500).build()) }
        server.start()

        val armings = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = CountDownLatch(1)
        val ds = StreamingDataSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            clientKey = "key",
            context = mapOf("user_id" to "u1"),
            onChange = {},
            onSnapshot = {},
            onFallbackToPolling = { armings.incrementAndGet(); latch.countDown() },
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
            ),
            initialBackoffMs = 1L,
        )
        ds.start()

        val fired = latch.await(5, TimeUnit.SECONDS)
        assertThat(fired)
            .`as`("onFallbackToPolling should fire so the core can start polling")
            .isTrue()

        // Keep going well past the cap: the connect loop must still be reconnecting.
        val attemptsAtCap = server.requestCount
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (server.requestCount <= StreamingDataSource.MAX_RETRIES + 2 &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(10)
        }
        val attemptsAfter = server.requestCount
        ds.stop()
        server.close()

        assertThat(attemptsAfter)
            .`as`(
                "the stream must keep retrying past the cap (attempts at arming: %d)",
                attemptsAtCap,
            )
            .isGreaterThan(StreamingDataSource.MAX_RETRIES)
        assertThat(armings.get())
            .`as`("the fallback arms once per outage, not once per retry")
            .isEqualTo(1)
    }

    @Test
    fun `a recovered stream retires the fallback poller on its first delivered frame`() {
        val server = MockWebServer()
        repeat(StreamingDataSource.MAX_RETRIES) {
            server.enqueue(MockResponse.Builder().code(500).build())
        }
        // Then a live stream that actually delivers the connect snapshot.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body("event: flags-updated\ndata: ${fullSnapshotJson("flag-a")}\n\n")
                .build(),
        )
        repeat(20) { server.enqueue(MockResponse.Builder().code(500).build()) }
        server.start()

        val armed = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val recoveries = java.util.concurrent.atomic.AtomicInteger(0)
        val ds = StreamingDataSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            clientKey = "key",
            context = mapOf("user_id" to "u1"),
            onChange = {},
            onSnapshot = {},
            onFallbackToPolling = { armed.countDown() },
            onStreamRecovered = { recoveries.incrementAndGet(); recovered.countDown() },
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
            ),
            initialBackoffMs = 1L,
        )
        ds.start()

        val didArm = armed.await(5, TimeUnit.SECONDS)
        val didRecover = recovered.await(5, TimeUnit.SECONDS)
        ds.stop()
        server.close()

        assertThat(didArm).`as`("the fallback should arm while the stream is down").isTrue()
        assertThat(didRecover)
            .`as`("a delivered frame must retire the fallback poller")
            .isTrue()
        assertThat(recoveries.get())
            .`as`("recovery is signalled once per outage, not once per frame")
            .isEqualTo(1)
    }

    @Test
    fun `a stream that opens but delivers nothing still arms the fallback`() {
        // Regression: resetting the retry counter on the 200 rather than on a
        // delivered frame let an accept-then-close server clear it every cycle, so
        // the budget was never exhausted, the fallback never armed, and the app saw
        // nothing at all for the whole outage (#3074).
        val server = MockWebServer()
        repeat(40) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .body("")
                    .build(),
            )
        }
        server.start()

        val armed = CountDownLatch(1)
        val ds = StreamingDataSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            clientKey = "key",
            context = mapOf("user_id" to "u1"),
            onChange = {},
            onSnapshot = {},
            onFallbackToPolling = { armed.countDown() },
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
            ),
            initialBackoffMs = 1L,
        )
        ds.start()

        val didArm = armed.await(5, TimeUnit.SECONDS)
        ds.stop()
        server.close()

        assertThat(didArm)
            .`as`("a 200 that delivers no frame is not a recovery")
            .isTrue()
    }

    @Test
    fun `a stream that only ever sends connection-ready still arms the fallback`() {
        // connection-ready is the client stream's FIRST frame and carries no config —
        // a ~40-byte handshake. Counting it as a recovery would let a server that
        // accepts, greets and dies reset the retry budget every cycle, which is
        // exactly the accept-then-close hole #3074 closed. The fleet keys this on
        // delivered CONFIG (js and java on `sync`, go on its first complete frame,
        // which for the server stream IS `sync`), so the mobile SDKs key on
        // `flags-updated`.
        val server = MockWebServer()
        repeat(40) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .body("event: connection-ready\ndata: {\"connectionId\":\"c1\"}\n\n")
                    .build(),
            )
        }
        server.start()

        val armed = CountDownLatch(1)
        val recoveries = java.util.concurrent.atomic.AtomicInteger(0)
        val ds = StreamingDataSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            clientKey = "key",
            context = mapOf("user_id" to "u1"),
            onChange = {},
            onSnapshot = {},
            onFallbackToPolling = { armed.countDown() },
            onStreamRecovered = { recoveries.incrementAndGet() },
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
            ),
            initialBackoffMs = 1L,
        )
        ds.start()

        val didArm = armed.await(5, TimeUnit.SECONDS)
        ds.stop()
        server.close()

        assertThat(didArm)
            .`as`("a greeting frame carrying no config is not a recovery")
            .isTrue()
        assertThat(recoveries.get())
            .`as`("connection-ready must never retire the fallback poller")
            .isZero()
    }
}
