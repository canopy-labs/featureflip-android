package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AnonymousKeyTest {

    // --- Pure resolver ---

    @Test
    fun `injects and persists an anonymous user_id`() {
        val store = InMemoryAnonymousKeyStore()
        val first = resolveAnonymousContext(mapOf("plan" to "pro"), store)
        assertThat(first["user_id"]).isNotNull()
        assertThat(first["user_id"]).isNotBlank()
        assertThat(first["plan"]).isEqualTo("pro")

        // Second call reads the SAME persisted key.
        val second = resolveAnonymousContext(mapOf("plan" to "pro"), store)
        assertThat(second["user_id"]).isEqualTo(first["user_id"])
    }

    @Test
    fun `real user_id wins`() {
        val store = InMemoryAnonymousKeyStore()
        val out = resolveAnonymousContext(mapOf("user_id" to "real-1"), store)
        assertThat(out["user_id"]).isEqualTo("real-1")
        assertThat(store.read()).isNull()
    }

    @Test
    fun `camelCase userId alias wins`() {
        val store = InMemoryAnonymousKeyStore()
        val out = resolveAnonymousContext(mapOf("userId" to "alice"), store)
        assertThat(out).isEqualTo(mapOf("userId" to "alice"))
        assertThat(store.read()).isNull()
    }

    @Test
    fun `blank user_id treated as anonymous`() {
        val store = InMemoryAnonymousKeyStore()
        val out = resolveAnonymousContext(mapOf("user_id" to "   "), store)
        assertThat(out["user_id"]?.isNotBlank()).isTrue()
        assertThat(out["user_id"]).isNotEqualTo("   ")
    }

    // --- Core wiring ---

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
    fun `evaluate sends the persisted anonymous user_id`() {
        val body = json.writeValueAsString(mapOf("flags" to emptyMap<String, Any>()))
        server.enqueue(MockResponse.Builder().body(body).build()) // initial evaluate
        server.enqueue(MockResponse.Builder().body(body).build()) // poller's immediate poll

        val store = InMemoryAnonymousKeyStore()
        val config = FeatureflipConfig(
            clientKey = "anon-wiring-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            streaming = false,
        )
        val core = SharedFeatureflipCore.create(config, anonymousKeyStore = store)
        core.initialize()
        core.release()

        val request = server.takeRequest()
        assertThat(request.requestLine).contains("/v1/client/evaluate")
        val parsed: Map<String, Any> = json.readValue(request.body!!.utf8())
        @Suppress("UNCHECKED_CAST")
        val ctx = parsed["context"] as Map<String, Any?>
        assertThat(ctx["user_id"]).isNotNull()
        assertThat(ctx["user_id"] as String).isNotBlank()
        assertThat(ctx["user_id"]).isEqualTo(store.read())
    }
}
