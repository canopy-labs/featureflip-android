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
 * The initial evaluate was wrapped in a bare `catch (_: Exception)` with no
 * diagnostic, so a revoked SDK key, a 4xx/5xx, a timeout and a JSON parse failure
 * were all indistinguishable to the caller — `isInitialized` reported success in
 * every case and every flag quietly served its default (#2294).
 *
 * Serving cached-or-default values is CORRECT and stays: it matches the browser and
 * flutter SDKs' documented contract, the data source started right after retries
 * forever, and throwing here would take an app down at startup over a transient
 * blip. What was wrong is that the failure left no trace at all.
 */
class InitFailureDiagnosticsTest {

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

    private fun makeConfig(clientKey: String): FeatureflipConfig =
        FeatureflipConfig(
            clientKey = clientKey,
            baseUrl = server.url("/").toString().trimEnd('/'),
            streaming = false,
            pollIntervalMs = 60_000,
        )

    @Test
    fun `a failing initial evaluate logs a diagnostic`() {
        server.enqueue(MockResponse(code = 401, body = """{"error":"invalid client key"}"""))

        val client = FeatureflipClient.get(makeConfig("bad-key"))
        client.initialize()

        val logs = captured.toString()
        assertThat(logs)
            .withFailMessage("a failed initial fetch must leave a trace; stderr was: <%s>", logs)
            .contains("[featureflip]")
            .contains("initial flag fetch failed")
    }

    @Test
    fun `the diagnostic carries the underlying error`() {
        server.enqueue(MockResponse(code = 500, body = "boom"))

        val client = FeatureflipClient.get(makeConfig("key-500"))
        client.initialize()

        val line = captured.toString().lines().first { it.contains("initial flag fetch failed") }
        // The point of logging is to tell a bad key apart from an unreachable host,
        // so the cause has to survive into the message.
        assertThat(line.length)
            .withFailMessage("the diagnostic must carry the underlying error, not just a label: <%s>", line)
            .isGreaterThan("[featureflip] initial flag fetch failed".length + 10)
    }

    @Test
    fun `a successful initial evaluate logs nothing`() {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"flags":{}}""",
            ).newBuilder().addHeader("Content-Type", "application/json").build(),
        )

        val client = FeatureflipClient.get(makeConfig("good-key"))
        client.initialize()

        assertThat(captured.toString())
            .withFailMessage("a healthy start must stay quiet")
            .doesNotContain("initial flag fetch failed")
    }

    @Test
    fun `still reports initialized and serves defaults (contract unchanged)`() {
        server.enqueue(MockResponse(code = 401, body = """{"error":"invalid client key"}"""))

        val client = FeatureflipClient.get(makeConfig("bad-key-2"))
        client.initialize()

        // Deliberate, and matching browser + flutter. A failed initial fetch is
        // non-terminal: the data source keeps retrying and re-snapshots on connect,
        // and any flags already persisted to disk keep serving. Changing this to
        // throw, or to leave isInitialized false, would diverge from every other
        // client SDK and can break app startup on a transient blip.
        assertThat(client.isInitialized).isTrue()
        assertThat(client.boolVariation("anything", false)).isFalse()
        assertThat(client.stringVariation("anything", "fallback")).isEqualTo("fallback")
    }
}
