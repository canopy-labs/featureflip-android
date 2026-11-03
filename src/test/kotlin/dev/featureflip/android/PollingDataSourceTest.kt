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
            onChange = { flags, _ -> received.add(flags) },
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
            onChange = { flags, _ -> received.add(flags) },
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
            onChange = { flags, _ -> received.add(flags) },
        )

        poller.updateContext(mapOf("user_id" to "user-2"), generation = 1)
        poller.pollOnce()

        val request = server.takeRequest()
        val body = request.body!!.utf8()
        assertThat(body).contains("user-2")
    }

    @Test
    fun `a poll already on the wire does not deliver after stop`() {
        // pollOnce() is not a suspend function, so cancelling the job cannot interrupt
        // it: without an explicit guard, httpClient.evaluate() completes and onChange()
        // fires regardless. That is only harmless while a poller is stopped alongside
        // everything else — #3075 retires the fallback poller while the recovered
        // stream is live, so a late response would REPLACE the store on top of the
        // stream's fresher snapshot and stay wrong until the flag next changed.
        val flags = mapOf("feature" to FlagValue(value = true, variation = "v1", reason = "RULE"))
        server.enqueue(MockResponse.Builder().body(makeResponseBody(flags)).build())

        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val received = CopyOnWriteArrayList<Map<String, FlagValue>>()

        val poller = PollingDataSource(
            httpClient = httpClient,
            context = mapOf("user_id" to "user-1"),
            intervalMs = 60_000,
            onChange = { flags, _ -> received.add(flags) },
        )

        poller.stop()
        poller.pollOnce()

        assertThat(received)
            .`as`("a response that arrives after stop() must not reach the store")
            .isEmpty()
        assertThat(server.requestCount)
            .`as`("the request itself is not recallable — only its delivery is suppressed")
            .isEqualTo(1)
    }

    @Test
    fun `a poll's delivery carries the generation it was requested for`() {
        // The core drops a response requested for a user identify() has since replaced
        // (#4886), so the tag must be the generation read WITH the context the request
        // carried, not whatever is current when the response lands.
        server.enqueue(MockResponse.Builder().body(makeResponseBody(emptyMap())).build())
        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val tags = CopyOnWriteArrayList<Long>()
        val poller = PollingDataSource(
            httpClient = httpClient,
            context = mapOf("user_id" to "user-1"),
            intervalMs = 60_000,
            onChange = { _, generation -> tags.add(generation) },
            generation = 3,
        )

        poller.pollOnce()

        assertThat(tags).containsExactly(3L)
    }

    @Test
    fun `an updateContext older than the current generation is ignored`() {
        // Two identify() calls retarget the sources under a shared lock, so their
        // updates can arrive out of order. The older one must not win, or every later
        // poll asks for a user the core has moved past and is dropped as stale.
        server.enqueue(MockResponse.Builder().body(makeResponseBody(emptyMap())).build())
        val httpClient = HttpClient(server.url("/").toString().trimEnd('/'), "test-key")
        val tags = CopyOnWriteArrayList<Long>()
        val poller = PollingDataSource(
            httpClient = httpClient,
            context = mapOf("user_id" to "user-1"),
            intervalMs = 60_000,
            onChange = { _, generation -> tags.add(generation) },
        )

        poller.updateContext(mapOf("user_id" to "user-3"), generation = 2)
        poller.updateContext(mapOf("user_id" to "user-2"), generation = 1)
        poller.pollOnce()

        assertThat(server.takeRequest().body!!.utf8()).contains("user-3")
        assertThat(tags).containsExactly(2L)
    }
}
