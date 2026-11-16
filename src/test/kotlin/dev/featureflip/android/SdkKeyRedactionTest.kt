package dev.featureflip.android

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream

/**
 * The SDK key must never reach a log line or an exception message (#5167, #5166).
 * The redactor sits in [SdkLogger]; the transport here quotes the Authorization header in
 * its exception, the way a third-party HTTP stack can, so the test proves the redactor and
 * not just clean call sites.
 */
class SdkKeyRedactionTest {

    private val sentinel = "sdk_client_SentinelKeyMustNeverAppearInLogs-0123456789"

    private val server = MockWebServer()
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
        FeatureflipClient.resetForTesting()
        server.close()
    }

    private fun config(streaming: Boolean = false) = FeatureflipConfig(
        clientKey = sentinel,
        baseUrl = server.url("/").toString().trimEnd('/'),
        streaming = streaming,
        pollIntervalMs = 60_000,
    )

    private val keyQuotingTransport = OkHttpClient.Builder()
        .addInterceptor { chain ->
            throw IOException("unexpected end of stream, request headers: Authorization: $sentinel")
        }
        .build()

    private fun driveEverything(client: FeatureflipClient, failures: MutableList<Throwable>) {
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                failures.add(e)
            }
        }
        attempt { client.initialize() }
        attempt { client.boolVariation("no-such-flag", false) }
        attempt { client.stringVariation("no-such-flag", "d") }
        attempt { client.boolVariation("a-string-flag", false) }
        attempt { client.identify(mapOf("user_id" to "u2")) }
        attempt { client.track("purchase", mapOf("x" to 1)) }
        attempt { runBlocking { client.flushAndAwait() } }
        attempt {
            // Same key, different options: the "options ignored" warning path.
            FeatureflipClient.get(config(streaming = false).copy(pollIntervalMs = 5_000)).close()
        }
        attempt { runBlocking { client.closeAndAwait() } }
    }

    private fun assertNoLeak(failures: List<Throwable>) {
        val logs = captured.toString()
        assertThat(logs).doesNotContain(sentinel)
        for (f in failures) {
            var t: Throwable? = f
            while (t != null) {
                assertThat(t.message ?: "").doesNotContain(sentinel)
                t = t.cause
            }
        }
    }

    @Test
    fun `a transport error that quotes the key is redacted in every log line`() {
        val failures = mutableListOf<Throwable>()
        driveEverything(FeatureflipClient.get(config(), keyQuotingTransport), failures)

        val logs = captured.toString()
        assertThat(logs).contains("[featureflip]").contains("[redacted]")
        assertNoLeak(failures)
    }

    @Test
    fun `a stream that fails with an error quoting the key logs it redacted`() {
        val failures = mutableListOf<Throwable>()
        val client = FeatureflipClient.get(config(streaming = true), keyQuotingTransport)
        attemptAll(client, failures)
        Thread.sleep(500) // let the stream's connect attempt fail and log
        assertNoLeak(failures)
    }

    @Test
    fun `a stream connect failing with an error quoting the key logs it redacted`() {
        val ds = StreamingDataSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            clientKey = sentinel,
            context = mapOf("user_id" to "u1"),
            onChange = { _, _ -> },
            onSnapshot = { _, _ -> },
            log = SdkLogger(sentinel),
        )
        ds.beforeConnectForTest = { throw IOException("connect failed, Authorization: $sentinel") }
        ds.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (!captured.toString().contains("[featureflip]") && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        ds.stop()
        assertThat(captured.toString()).contains("[redacted]").doesNotContain(sentinel)
    }

    private fun attemptAll(client: FeatureflipClient, failures: MutableList<Throwable>) {
        try {
            client.initialize()
        } catch (e: Throwable) {
            failures.add(e)
        }
        try {
            runBlocking { client.closeAndAwait() }
        } catch (e: Throwable) {
            failures.add(e)
        }
    }

    @Test
    fun `401 then 500 responses leave no key in the logs`() {
        val failures = mutableListOf<Throwable>()
        repeat(3) { server.enqueue(MockResponse(code = 401, body = """{"error":"invalid client key"}""")) }
        repeat(20) { server.enqueue(MockResponse(code = 500, body = "boom")) }
        driveEverything(FeatureflipClient.get(config()), failures)
        assertNoLeak(failures)
    }

    @Test
    fun `a healthy start and shutdown log nothing`() {
        server.enqueue(MockResponse(code = 200, body = """{"flags":{}}"""))
        val client = FeatureflipClient.get(config())
        client.initialize()
        runBlocking { client.closeAndAwait() }
        assertThat(captured.toString()).doesNotContain(sentinel)
    }

    @Test
    fun `a key in an exception message is replaced with the marker`() {
        val log = SdkLogger(sentinel)
        log.error("flush failed: ${IOException("Authorization: $sentinel")} and again $sentinel")
        val out = captured.toString()
        assertThat(out).doesNotContain(sentinel)
        assertThat(out).contains("flush failed: java.io.IOException: Authorization: [redacted] and again [redacted]")
    }

    @Test
    fun `a key shorter than 20 characters is not redacted`() {
        SdkLogger("short-key").error("failed for short-key")
        assertThat(captured.toString()).contains("failed for short-key")
    }

    @Test
    fun `a null secret leaves text alone`() {
        SdkLogger().error("plain")
        assertThat(captured.toString()).contains("plain")
    }
}

class HttpClientRedactionTest {
    private val sentinel = "sdk_client_SentinelKeyMustNeverAppearInLogs-0123456789"
    private val request = okhttp3.Request.Builder().url("https://localhost/x").build()

    private fun thrownBy(t: Throwable): Throwable {
        val client = HttpClient("https://localhost", sentinel)
        return try {
            client.execute(okhttp3.Call.Factory { throw t }, request)
            error("expected a throw")
        } catch (e: Throwable) {
            e
        }
    }

    @Test
    fun `common IOException subtypes survive with the key redacted and the cause dropped`() {
        val cases: List<Throwable> = listOf(
            java.net.SocketTimeoutException("timeout, Authorization: $sentinel"),
            java.net.UnknownHostException("host, Authorization: $sentinel"),
            java.net.ConnectException("conn, Authorization: $sentinel"),
            java.io.InterruptedIOException("intr, Authorization: $sentinel"),
            javax.net.ssl.SSLException("ssl, Authorization: $sentinel"),
            IOException("io, Authorization: $sentinel", RuntimeException("cause $sentinel")),
        )
        for (original in cases) {
            val e = thrownBy(original)
            assertThat(e.javaClass).isEqualTo(original.javaClass)
            assertThat(e.message).contains("[redacted]").doesNotContain(sentinel)
            assertThat(e.cause).isNull()
            assertThat(e.stackTrace).isEqualTo(original.stackTrace)
        }
    }

    @Test
    fun `a RuntimeException quoting the key is redacted`() {
        val e = thrownBy(IllegalStateException("bad: $sentinel"))
        assertThat(e).isInstanceOf(IllegalStateException::class.java)
        assertThat(e.message).doesNotContain(sentinel).contains("[redacted]")
        val other = thrownBy(UnsupportedOperationException("odd: $sentinel"))
        assertThat(other.message).doesNotContain(sentinel).contains("UnsupportedOperationException")
    }

    @Test
    fun `a key only in a suppressed exception is caught`() {
        val original = IOException("clean")
        original.addSuppressed(RuntimeException("Authorization: $sentinel"))
        val e = thrownBy(original)
        assertThat(e).isNotSameAs(original)
        assertThat(e.suppressed).isEmpty()
    }

    @Test
    fun `a clean exception passes through by identity`() {
        val original = IOException("connection reset")
        assertThat(thrownBy(original)).isSameAs(original)
        val rt = IllegalStateException("fine")
        assertThat(thrownBy(rt)).isSameAs(rt)
    }
}
