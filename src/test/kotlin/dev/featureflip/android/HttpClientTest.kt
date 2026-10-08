package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
            SdkEvent(type = SdkEventType.Custom, flagKey = "test-event", userId = "user-1", timestamp = "2025-01-01T00:00:00Z"),
        )
        client.postEvents(events)

        val request = server.takeRequest()
        assertThat(request.requestLine).contains("/v1/client/events")
        assertThat(request.headers["Authorization"]).isEqualTo("test-key")
    }

    @Test
    fun `postEvents omits null optional fields and serializes type as PascalCase`() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val events = listOf(
            SdkEvent(type = SdkEventType.Custom, flagKey = "test-event", timestamp = "2025-01-01T00:00:00Z"),
        )
        client.postEvents(events)

        val request = server.takeRequest()
        val rawBody = request.body!!.utf8()

        // PascalCase enum on the wire (matches server JsonStringEnumConverter)
        assertThat(rawBody).contains("\"type\":\"Custom\"")
        assertThat(rawBody).contains("\"flagKey\":\"test-event\"")

        // Null optional fields must be omitted from the wire payload
        assertThat(rawBody).doesNotContain("\"userId\"")
        assertThat(rawBody).doesNotContain("\"variation\"")
        assertThat(rawBody).doesNotContain("\"metadata\"")
    }

    @Test
    fun `evaluate throws on non-2xx response`() {
        server.enqueue(MockResponse.Builder().code(500).build())

        val client = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")

        assertThatThrownBy { client.evaluate(mapOf("user_id" to "user-1")) }
            .isInstanceOf(IOException::class.java)
    }

    private fun flagsBody(): String =
        json.writeValueAsString(mapOf("flags" to mapOf("feature" to FlagValue(value = true, variation = "v1", reason = "RULE"))))

    private fun client(reportsEvaluations: Boolean) =
        HttpClient(server.url("/").toString().trimEnd('/'), "test-key", reportsEvaluations = reportsEvaluations)

    @Test
    fun `evaluate and identify declare read reporting when it is on`() {
        server.enqueue(MockResponse.Builder().body(flagsBody()).build())
        server.enqueue(MockResponse.Builder().body(flagsBody()).build())
        val c = client(reportsEvaluations = true)

        c.evaluate(mapOf("user_id" to "user-1"))
        c.identify(mapOf("user_id" to "user-1"))

        val evaluate = server.takeRequest()
        assertThat(evaluate.requestLine).contains("/v1/client/evaluate")
        assertThat(evaluate.headers["X-Featureflip-Reports-Evaluations"]).isEqualTo("1")
        val identify = server.takeRequest()
        assertThat(identify.requestLine).contains("/v1/client/identify")
        assertThat(identify.headers["X-Featureflip-Reports-Evaluations"]).isEqualTo("1")
    }

    @Test
    fun `evaluate and identify omit the read-reporting header when it is off`() {
        server.enqueue(MockResponse.Builder().body(flagsBody()).build())
        server.enqueue(MockResponse.Builder().body(flagsBody()).build())
        val c = client(reportsEvaluations = false)

        c.evaluate(mapOf("user_id" to "user-1"))
        c.identify(mapOf("user_id" to "user-1"), connectionId = "conn-1")

        assertThat(server.takeRequest().headers["X-Featureflip-Reports-Evaluations"]).isNull()
        assertThat(server.takeRequest().headers["X-Featureflip-Reports-Evaluations"]).isNull()
    }

    @Test
    fun `read reporting defaults to off when the flag is not passed`() {
        server.enqueue(MockResponse.Builder().body(flagsBody()).build())

        HttpClient(server.url("/").toString().trimEnd('/'), "test-key").evaluate(mapOf("user_id" to "user-1"))

        assertThat(server.takeRequest().headers["X-Featureflip-Reports-Evaluations"]).isNull()
    }

    @Test
    fun `identify keeps X-Connection-Id alongside the read-reporting header`() {
        server.enqueue(MockResponse.Builder().body(flagsBody()).build())

        client(reportsEvaluations = true).identify(mapOf("user_id" to "user-1"), connectionId = "conn-abc-123")

        val request = server.takeRequest()
        assertThat(request.headers["X-Connection-Id"]).isEqualTo("conn-abc-123")
        assertThat(request.headers["X-Featureflip-Reports-Evaluations"]).isEqualTo("1")
    }

    @Test
    fun `events are never sent with the read-reporting header`() {
        server.enqueue(MockResponse.Builder().code(202).build())

        client(reportsEvaluations = true).postEvents(
            listOf(SdkEvent(type = SdkEventType.Evaluation, flagKey = "flag-a", timestamp = "2026-10-07T00:00:00.000Z")),
        )

        val request = server.takeRequest()
        assertThat(request.requestLine).contains("/v1/client/events")
        assertThat(request.headers["X-Featureflip-Reports-Evaluations"]).isNull()
    }

    @Test
    fun `an Evaluation event serializes with its wire type, variation and user`() {
        server.enqueue(MockResponse.Builder().code(202).build())

        client(reportsEvaluations = true).postEvents(
            listOf(
                SdkEvent(
                    type = SdkEventType.Evaluation,
                    flagKey = "flag-a",
                    timestamp = "2026-10-07T00:00:00.000Z",
                    userId = "user-1",
                    variation = "on",
                ),
                SdkEvent(
                    type = SdkEventType.Evaluation,
                    flagKey = "missing-flag",
                    timestamp = "2026-10-07T00:00:00.000Z",
                    userId = "user-1",
                ),
            ),
        )

        val body: Map<String, Any?> = json.readValue(server.takeRequest().body!!.utf8())
        @Suppress("UNCHECKED_CAST")
        val events = body["events"] as List<Map<String, Any?>>
        assertThat(events[0]).containsEntry("type", "Evaluation")
        assertThat(events[0]).containsEntry("flagKey", "flag-a")
        assertThat(events[0]).containsEntry("variation", "on")
        assertThat(events[0]).containsEntry("userId", "user-1")
        assertThat(events[0]).doesNotContainKey("metadata")
        // A read of a missing key has no variation, and the field is omitted rather than null.
        assertThat(events[1]).containsEntry("type", "Evaluation")
        assertThat(events[1]).doesNotContainKey("variation")
    }
}
