package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class HttpClientTest {

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
    fun `evaluate posts context and returns flags`() {
        val flags = mapOf("feature" to FlagValue(value = true, variation = "v1", reason = "RULE"))
        val body = json.writeValueAsString(mapOf("flags" to flags))
        server.enqueue(MockResponse.Builder().body(body).build())

        val client = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val result = client.evaluate(mapOf("user_id" to "user-1"))

        assertThat(result.flags).containsKey("feature")
        assertThat(result.flags["feature"]?.value).isEqualTo(true)

        val request = server.takeRequest()
        assertThat(request.requestLine).contains("/v1/client/evaluate")
        assertThat(request.headers["Authorization"]).isEqualTo("test-key")
        assertThat(request.headers["Content-Type"]).contains("application/json")
    }

    @Test
    fun `identify posts context and returns flags`() {
        val flags = mapOf("feature" to FlagValue(value = "on", variation = "v1", reason = "RULE"))
        val body = json.writeValueAsString(mapOf("flags" to flags))
        server.enqueue(MockResponse.Builder().body(body).build())

        val client = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val result = client.identify(mapOf("user_id" to "user-2"))

        assertThat(result.flags).containsKey("feature")
        val request = server.takeRequest()
        assertThat(request.requestLine).contains("/v1/client/identify")
    }

    @Test
    fun `identify sends X-Connection-Id header when connectionId provided`() {
        val flags = mapOf("feature" to FlagValue(value = "on", variation = "v1", reason = "RULE"))
        val body = json.writeValueAsString(mapOf("flags" to flags))
        server.enqueue(MockResponse.Builder().body(body).build())

        val client = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        client.identify(mapOf("user_id" to "user-1"), connectionId = "conn-abc-123")

        val request = server.takeRequest()
        assertThat(request.headers["X-Connection-Id"]).isEqualTo("conn-abc-123")
    }

    @Test
    fun `identify omits X-Connection-Id header when connectionId is null`() {
        val flags = mapOf("feature" to FlagValue(value = "on", variation = "v1", reason = "RULE"))
        val body = json.writeValueAsString(mapOf("flags" to flags))
        server.enqueue(MockResponse.Builder().body(body).build())

        val client = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        client.identify(mapOf("user_id" to "user-1"))

        val request = server.takeRequest()
        assertThat(request.headers["X-Connection-Id"]).isNull()
    }

    @Test
    fun `postEvents sends event batch`() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val events = listOf(
            SdkEvent(type = "Custom", flagKey = "test-event", userId = "user-1", timestamp = "2025-01-01T00:00:00Z"),
        )
        client.postEvents(events)

        val request = server.takeRequest()
        assertThat(request.requestLine).contains("/v1/sdk/events")
        assertThat(request.headers["Authorization"]).isEqualTo("test-key")
    }

    @Test
    fun `evaluate throws on non-2xx response`() {
        server.enqueue(MockResponse.Builder().code(500).build())

        val client = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")

        assertThatThrownBy { client.evaluate(mapOf("user_id" to "user-1")) }
            .isInstanceOf(IOException::class.java)
    }
}
