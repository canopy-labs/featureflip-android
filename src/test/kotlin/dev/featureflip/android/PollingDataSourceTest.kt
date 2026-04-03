package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class PollingDataSourceTest {

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

    private fun makeResponseBody(flags: Map<String, FlagValue>): String {
        return json.writeValueAsString(mapOf("flags" to flags))
    }

    @Test
    fun `pollOnce fetches and calls onChange`() {
        val flags = mapOf("feature" to FlagValue(value = true, variation = "v1", reason = "RULE"))
        server.enqueue(MockResponse.Builder().body(makeResponseBody(flags)).build())

        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val received = CopyOnWriteArrayList<Map<String, FlagValue>>()

        val poller = PollingDataSource(
            httpClient = httpClient,
            context = mapOf("user_id" to "user-1"),
            intervalMs = 60_000,
            onChange = { received.add(it) },
        )

        poller.pollOnce()

        assertThat(received).hasSize(1)
        assertThat(received[0]).containsKey("feature")
    }

    @Test
    fun `pollOnce silently handles network errors`() {
        server.enqueue(MockResponse.Builder().code(500).build())

        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val received = CopyOnWriteArrayList<Map<String, FlagValue>>()

        val poller = PollingDataSource(
            httpClient = httpClient,
            context = mapOf("user_id" to "user-1"),
            intervalMs = 60_000,
            onChange = { received.add(it) },
        )

        poller.pollOnce() // Should not throw

        assertThat(received).isEmpty()
    }

    @Test
    fun `updateContext changes the polling context`() {
        val flags = mapOf("feature" to FlagValue(value = true, variation = "v1", reason = "RULE"))
        server.enqueue(MockResponse.Builder().body(makeResponseBody(flags)).build())

        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val received = CopyOnWriteArrayList<Map<String, FlagValue>>()

        val poller = PollingDataSource(
            httpClient = httpClient,
            context = mapOf("user_id" to "user-1"),
            intervalMs = 60_000,
            onChange = { received.add(it) },
        )

        poller.updateContext(mapOf("user_id" to "user-2"))
        poller.pollOnce()

        val request = server.takeRequest()
        val body = request.body!!.utf8()
        assertThat(body).contains("user-2")
    }
}
