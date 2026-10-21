package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Every request names the SDK and its version so the Evaluation API can count usage
 * (#4141, contract in #4138). Before this the SDK sent OkHttp's default `okhttp/<x>`,
 * which the Java SDK sends too, so the two were indistinguishable.
 *
 * Asserted on the wire. HttpClient sets the header per request (a caller-supplied
 * Call.Factory would bypass any client-level interceptor), and the stream is opened by
 * StreamingDataSource on its own client, so each path is exercised rather than assumed.
 */
class SdkIdentityTest {

    private val json = jacksonObjectMapper()
    private val server = MockWebServer()
    private val sdkToken = "featureflip-android/$SDK_VERSION"

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        FeatureflipClient.resetForTesting()
        server.close()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun flagsBody() = json.writeValueAsString(
        mapOf("flags" to mapOf("f" to FlagValue(value = true, variation = "on", reason = "RULE"))),
    )

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    @Test
    fun `version is the one the publish workflow writes to the build file`() {
        // publish-android-sdk.yml seds `version = "..."` in build.gradle.kts from the tag,
        // and the build generates SDK_VERSION from it. Pinning the two together is what
        // stops the UA advertising a stale version across a release line (#2141).
        val version = Regex("""(?m)^version = "([^"]+)"""")
            .find(File("build.gradle.kts").readText())!!.groupValues[1]

        assertThat(SDK_VERSION).isEqualTo(version)
    }

    @Test
    fun `http client sends the sdk token on evaluate, identify and events by default`() {
        repeat(2) { server.enqueue(MockResponse.Builder().body(flagsBody()).build()) }
        server.enqueue(MockResponse.Builder().code(202).build())
        val client = HttpClient(baseUrl(), "client-key")

        client.evaluate(mapOf("user_id" to "u"))
        client.identify(mapOf("user_id" to "u"))
        client.postEvents(listOf(SdkEvent(SdkEventType.Custom, "f", "2026-10-20T00:00:00Z")))

        assertThat(listOf(take(), take(), take()).map { it.headers["User-Agent"] })
            .containsExactly(sdkToken, sdkToken, sdkToken)
    }

    @Test
    fun `stream connect sends the user agent it was given`() {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "text/event-stream")
                .body("event: ping\ndata: {}\n\n")
                .build(),
        )
        val source = StreamingDataSource(
            baseUrl = baseUrl(),
            clientKey = "client-key",
            context = emptyMap(),
            onChange = {},
            onSnapshot = {},
            userAgent = "$sdkToken my-wrapper/1.0",
        )

        source.start()
        val request = take()
        source.stop()

        assertThat(request.url.encodedPath).isEqualTo("/v1/client/stream")
        assertThat(request.headers["User-Agent"]).isEqualTo("$sdkToken my-wrapper/1.0")
    }

    @Test
    fun `configured wrapper reaches both the evaluate call and the stream`() {
        server.enqueue(MockResponse.Builder().body(flagsBody()).build())
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "text/event-stream")
                .body("event: ping\ndata: {}\n\n")
                .build(),
        )
        val config = FeatureflipConfig(
            clientKey = "identity-${UUID.randomUUID()}",
            baseUrl = baseUrl(),
            streaming = true,
            wrapperName = "featureflip-react-native",
            wrapperVersion = "0.1.0",
        )

        val client = FeatureflipClient.get(config)
        client.initialize()
        val agents = listOf(take(), take()).associate { it.url.encodedPath to it.headers["User-Agent"] }
        client.close()

        val expected = "$sdkToken featureflip-react-native/0.1.0"
        assertThat(agents["/v1/client/evaluate"]).isEqualTo(expected)
        assertThat(agents["/v1/client/stream"]).isEqualTo(expected)
    }

    @Test
    fun `wrapper without a version is sent as a bare name`() {
        assertThat(SdkIdentity.userAgent("my-wrapper", null)).isEqualTo("$sdkToken my-wrapper")
    }

    @Test
    fun `invalid wrapper is dropped with a warning`() {
        // A space is not a legal product-token character, and OkHttp would send it inside
        // the header value regardless. Identity metadata must never stop the client
        // starting, so it is dropped and said so.
        val err = captureStderr { assertThat(SdkIdentity.userAgent("bad name", "1.0")).isEqualTo(sdkToken) }

        assertThat(err).contains("bad name")
    }

    @Test
    fun `wrapper does not count as a different config`() {
        // It identifies the caller rather than changing behavior, so a wrapper sharing a
        // client key with the host app must not trip the different-options warning.
        val base = FeatureflipConfig(clientKey = "k")

        assertThat(configsEqual(base, base.copy(wrapperName = "w", wrapperVersion = "1"))).isTrue()
    }

    private fun captureStderr(body: () -> Unit): String {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            body()
        } finally {
            System.setErr(original)
        }
        return captured.toString(Charsets.UTF_8)
    }
}
