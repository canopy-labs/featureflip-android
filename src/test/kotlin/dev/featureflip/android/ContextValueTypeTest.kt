package dev.featureflip.android

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The context was typed `Map<String, String>`, so this SDK could not send a JSON
 * number — and the #1458 equality contract ("`Equals`/`In` coerce by attribute
 * *type*, not stringification") only engages when the attribute arrives as a JSON
 * number. A rule like `age Equals ["25.0"]` therefore matched on browser and
 * flutter and silently no-opped on android (#2293).
 *
 * These assert on the REQUEST BODY rather than an evaluation result: the coercion
 * itself lives in the engine, so the only thing this SDK can get wrong — and the
 * only thing worth pinning here — is whether the type survives serialization.
 */
class ContextValueTypeTest {

    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        FeatureflipClient.resetForTesting()
        server.close()
    }

    private fun config(context: Map<String, Any?>) = FeatureflipConfig(
        clientKey = "test-key",
        baseUrl = server.url("/").toString().trimEnd('/'),
        streaming = false,
        pollIntervalMs = 60_000,
        context = context,
    )

    @Test
    fun `numeric context values serialize as JSON numbers, not strings`() {
        server.enqueue(MockResponse.Builder().body("""{"flags":{}}""").build())

        val client = FeatureflipClient.get(
            config(mapOf("age" to 25, "score" to 1.5, "premium" to true, "plan" to "pro")),
        )
        client.initialize()

        val body = server.takeRequest().body?.utf8() ?: ""

        // The whole point: unquoted on the wire. `"age":"25"` is the bug.
        assertThat(body).contains("\"age\":25")
        assertThat(body).contains("\"score\":1.5")
        assertThat(body).contains("\"premium\":true")
        // Strings must still be quoted — widening the type must not stringify or
        // unquote anything that was already correct.
        assertThat(body).contains("\"plan\":\"pro\"")

        client.close()
    }

    @Test
    fun `identify preserves value types too`() {
        // Three responses, not two: PollingDataSource.start() fires an immediate
        // poll, so initialize() consumes the evaluate AND that first poll before
        // identify() gets its turn.
        repeat(3) { server.enqueue(MockResponse.Builder().body("""{"flags":{}}""").build()) }

        val client = FeatureflipClient.get(config(mapOf("plan" to "free")))
        client.initialize()
        server.takeRequest() // initialize evaluate
        server.takeRequest() // poller's immediate first poll

        client.identify(mapOf("age" to 30, "plan" to "pro"))

        val body = server.takeRequest().body?.utf8() ?: ""
        assertThat(body).contains("\"age\":30")
        assertThat(body).contains("\"plan\":\"pro\"")

        client.close()
    }

    @Test
    fun `a numeric user_id is treated as a real caller id, not anonymous`() {
        // resolveAnonymousContext used isNullOrBlank(), which no longer applies to
        // Any?. A numeric id must not be mistaken for an absent one and overwritten
        // with a generated anonymous key.
        val store = InMemoryAnonymousKeyStore()

        val resolved = resolveAnonymousContext(mapOf("user_id" to 4242), store)

        assertThat(resolved["user_id"]).isEqualTo(4242)
        assertThat(store.read()).isNull()
    }
}
