package dev.featureflip.android

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Every error boundary emits a diagnostic (packages/CLAUDE.md), and a healthy run stays
 * quiet. Covers the data sources' catches that used to swallow (#4937).
 */
class ErrorBoundaryLoggingTest {

    private lateinit var originalErr: PrintStream
    private lateinit var captured: ByteArrayOutputStream

    @BeforeEach
    fun capture() {
        captured = ByteArrayOutputStream()
        originalErr = System.err
        System.setErr(PrintStream(captured, true))
    }

    @AfterEach
    fun restore() {
        System.setErr(originalErr)
    }

    private fun stream(onChange: (Map<String, FlagValue>, Long) -> Unit = { _, _ -> }) = StreamingDataSource(
        baseUrl = "https://eval.example.com",
        clientKey = "key",
        context = mapOf("user_id" to "u1"),
        onChange = onChange,
        onSnapshot = { _, _ -> },
        generation = 5L,
    )

    @Test
    fun `a failed poll logs the error, a healthy poll does not`() {
        MockWebServer().use { server ->
            server.start()
            val poller = PollingDataSource(
                httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key"),
                context = mapOf("user_id" to "u1"),
                intervalMs = 60_000,
                onChange = { _, _ -> },
            )
            server.enqueue(MockResponse.Builder().body("""{"flags":{}}""").build())
            poller.pollOnce()
            assertThat(captured.toString()).doesNotContain("poll failed")

            server.enqueue(MockResponse.Builder().code(500).build())
            poller.pollOnce()
            assertThat(captured.toString()).contains("[featureflip] poll failed")

            // A second consecutive failure is the same outage: no second line.
            server.enqueue(MockResponse.Builder().code(500).build())
            poller.pollOnce()
            assertThat(Regex("poll failed").findAll(captured.toString()).count()).isEqualTo(1)

            // Recovery re-arms the log for the next outage.
            server.enqueue(MockResponse.Builder().body("""{"flags":{}}""").build())
            poller.pollOnce()
            server.enqueue(MockResponse.Builder().code(500).build())
            poller.pollOnce()
            assertThat(Regex("poll failed").findAll(captured.toString()).count()).isEqualTo(2)
        }
    }

    @Test
    fun `an unparseable flags-updated event logs and delivers nothing`() {
        val delivered = mutableListOf<Map<String, FlagValue>>()
        val ds = stream { flags, _ -> delivered.add(flags) }

        ds.handleEvent(StreamingDataSource.SSEEvent("flags-updated", "{not json"), 5L)

        assertThat(delivered).isEmpty()
        assertThat(captured.toString()).contains("[featureflip] ignoring unparseable flags-updated event")
    }

    @Test
    fun `an unparseable connection-ready event logs`() {
        stream().handleEvent(StreamingDataSource.SSEEvent("connection-ready", "{not json"), 5L)

        assertThat(captured.toString()).contains("[featureflip] ignoring unparseable connection-ready event")
    }

    @Test
    fun `a healthy connection-ready is quiet and sets the connection id`() {
        val ds = stream()
        ds.handleEvent(StreamingDataSource.SSEEvent("connection-ready", """{"connectionId":"c1"}"""), 5L)

        assertThat(ds.connectionId).isEqualTo("c1")
        assertThat(captured.toString()).doesNotContain("connection-ready")
    }

    @Test
    fun `a connection-ready from a replaced connection does not overwrite the connection id`() {
        val ds = stream()
        ds.handleEvent(StreamingDataSource.SSEEvent("connection-ready", """{"connectionId":"current"}"""), 5L)

        // A late one on the previous user's connection (generation 4).
        ds.handleEvent(StreamingDataSource.SSEEvent("connection-ready", """{"connectionId":"old"}"""), 4L)

        assertThat(ds.connectionId).isEqualTo("current")
    }

    @Test
    fun `a rejected stream connect logs the status once per outage`() {
        MockWebServer().use { server ->
            server.start()
            repeat(3) { server.enqueue(MockResponse.Builder().code(401).build()) }
            val ds = StreamingDataSource(
                baseUrl = server.url("/").toString().trimEnd('/'),
                clientKey = "key",
                context = mapOf("user_id" to "u1"),
                onChange = { _, _ -> },
                onSnapshot = { _, _ -> },
                initialBackoffMs = 10,
            )
            ds.start()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (server.requestCount < 3 && System.nanoTime() < deadline) Thread.sleep(10)
            ds.stop()

            assertThat(server.requestCount).isGreaterThanOrEqualTo(3)
            assertThat(Regex("stream rejected: HTTP 401").findAll(captured.toString()).count()).isEqualTo(1)
        }
    }

    private fun count(text: String) = Regex(Regex.escape(text)).findAll(captured.toString()).count()

    private fun runStream(server: MockWebServer, requests: Int) {
        val ds = StreamingDataSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            clientKey = "key",
            context = mapOf("user_id" to "u1"),
            onChange = { _, _ -> },
            onSnapshot = { _, _ -> },
            initialBackoffMs = 10,
        )
        ds.start()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (server.requestCount < requests && System.nanoTime() < deadline) Thread.sleep(10)
        ds.stop()
        assertThat(server.requestCount).isGreaterThanOrEqualTo(requests)
    }

    @Test
    fun `a stream failure of a different kind logs again within the same outage`() {
        MockWebServer().use { server ->
            server.start()
            // Unreachable-host style (a thrown IOException), then a revoked key.
            repeat(2) {
                server.enqueue(MockResponse.Builder().onRequestStart(mockwebserver3.SocketEffect.CloseSocket()).build())
            }
            repeat(2) { server.enqueue(MockResponse.Builder().code(401).build()) }
            runStream(server, 4)

            assertThat(count("stream connection failed")).isEqualTo(1)
            assertThat(count("stream rejected: HTTP 401")).isEqualTo(1)
        }
    }

    @Test
    fun `a stream failure after a delivered config logs again`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).build())
            server.enqueue(MockResponse.Builder().code(401).build())
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("event: flags-updated\ndata: {\"full\":true,\"flags\":{}}\n\n")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(401).build())
            server.enqueue(MockResponse.Builder().code(401).build())
            runStream(server, 5)

            assertThat(count("stream rejected: HTTP 401"))
                .`as`("once before the recovery, once after it")
                .isEqualTo(2)
        }
    }

    @Test
    fun `a poll failure of a different kind logs again`() {
        MockWebServer().use { server ->
            server.start()
            val poller = PollingDataSource(
                httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key"),
                context = mapOf("user_id" to "u1"),
                intervalMs = 60_000,
                onChange = { _, _ -> },
            )
            server.enqueue(MockResponse.Builder().body("{not json").build())
            poller.pollOnce()
            server.enqueue(MockResponse.Builder().code(401).build())
            poller.pollOnce()
            server.enqueue(MockResponse.Builder().code(401).build())
            poller.pollOnce()

            assertThat(count("poll failed")).isEqualTo(2)
            assertThat(count("HTTP 401")).isEqualTo(1)
        }
    }
}
