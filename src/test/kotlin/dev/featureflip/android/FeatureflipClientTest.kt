package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FeatureflipClientTest {

    private val json = jacksonObjectMapper()
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        FeatureflipClient.resetShared()
        server.close()
    }

    private fun makeEvaluateResponseBody(flags: Map<String, FlagValue>): String {
        return json.writeValueAsString(mapOf("flags" to flags))
    }

    private fun flagValue(value: Any?) =
        FlagValue(value = value, variation = "v1", reason = "RULE")

    // -- forTesting --

    @Test
    fun `forTesting returns static overrides`() {
        val client = FeatureflipClient.forTesting(
            mapOf(
                "bool-flag" to true,
                "string-flag" to "value",
                "int-flag" to 99,
                "double-flag" to 3.14,
            ),
        )

        assertThat(client.boolVariation("bool-flag", false)).isTrue()
        assertThat(client.stringVariation("string-flag", "default")).isEqualTo("value")
        assertThat(client.numberVariation("int-flag", 0.0)).isEqualTo(99.0)
        assertThat(client.numberVariation("double-flag", 0.0)).isEqualTo(3.14)
        assertThat(client.boolVariation("missing", false)).isFalse()
        assertThat(client.isInitialized).isTrue()
    }

    // -- Variation defaults --

    @Test
    fun `boolVariation returns default when flag missing`() {
        val client = FeatureflipClient.forTesting(emptyMap())
        assertThat(client.boolVariation("nonexistent", false)).isFalse()
        assertThat(client.boolVariation("nonexistent", true)).isTrue()
    }

    @Test
    fun `stringVariation returns default when flag missing`() {
        val client = FeatureflipClient.forTesting(emptyMap())
        assertThat(client.stringVariation("nonexistent", "fallback")).isEqualTo("fallback")
    }

    @Test
    fun `numberVariation returns default when flag missing`() {
        val client = FeatureflipClient.forTesting(emptyMap())
        assertThat(client.numberVariation("nonexistent", 5.0)).isEqualTo(5.0)
    }

    @Test
    fun `jsonVariation returns default when flag missing`() {
        val client = FeatureflipClient.forTesting(emptyMap())
        assertThat(client.jsonVariation("nonexistent", "default")).isEqualTo("default")
    }

    @Test
    fun `jsonVariation returns value when flag present`() {
        val client = FeatureflipClient.forTesting(mapOf("key" to "value"))
        assertThat(client.jsonVariation("key", null)).isEqualTo("value")
    }

    // -- forTesting no-network guarantees --

    @Test
    fun `forTesting identify updates context without network`() {
        val client = FeatureflipClient.forTesting(mapOf("feature" to true))
        // Should not throw — no network call made
        client.identify(mapOf("user_id" to "new-user"))
        assertThat(client.boolVariation("feature", false)).isTrue()
    }

    @Test
    fun `forTesting track does not throw`() {
        val client = FeatureflipClient.forTesting(mapOf("feature" to true))
        // Should not throw — no network call made
        client.track("event-name", mapOf("key" to "value"))
    }

    @Test
    fun `forTesting flush does not throw`() {
        val client = FeatureflipClient.forTesting(emptyMap())
        // Should not throw — no network call made
        client.flush()
    }

    // -- allFlags --

    @Test
    fun `allFlags returns all flag values`() {
        val client = FeatureflipClient.forTesting(mapOf("a" to true, "b" to "hello"))
        val flags = client.allFlags()
        assertThat(flags).hasSize(2)
        assertThat(flags).containsKey("a")
        assertThat(flags).containsKey("b")
    }

    // -- Initialize with MockWebServer --

    @Test
    fun `initialize fetches flags from server`() {
        val flags = mapOf("dark-mode" to flagValue(true))
        // For initialize evaluate call
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(flags)).build())
        // For polling data source initial poll
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(flags)).build())

        val config = FeatureflipConfig(
            clientKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            streaming = false,
        )
        val client = FeatureflipClient.create(config)

        client.initialize()

        assertThat(client.boolVariation("dark-mode", false)).isTrue()

        client.close()
    }

    // -- Identify --

    @Test
    fun `identify refetches flags`() {
        val initialFlags = mapOf("feature" to flagValue(false))
        // For initialize evaluate call + poller initial poll (may or may not fire)
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(initialFlags)).build())
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(initialFlags)).build())

        val config = FeatureflipConfig(
            clientKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            streaming = false,
            pollIntervalMs = 60_000,
        )
        val client = FeatureflipClient.create(config)
        client.initialize()

        assertThat(client.boolVariation("feature", true)).isFalse()

        // Drain requests: initialize evaluate + possibly the poller's initial poll
        server.takeRequest(2, TimeUnit.SECONDS)
        server.takeRequest(2, TimeUnit.SECONDS)

        client.close() // Stop poller

        // Identify with new context
        val updatedFlags = mapOf("feature" to flagValue(true))
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(updatedFlags)).build())

        client.identify(mapOf("user_id" to "new-user"))

        assertThat(client.boolVariation("feature", false)).isTrue()
    }

    // -- SSE delta merge --

    @Test
    fun `mergeSnapshot adds new flags without removing existing`() {
        val client = FeatureflipClient.forTesting(mapOf("existing" to true))

        // Simulate an SSE delta with a new flag
        client.mergeSnapshot(mapOf("new-flag" to flagValue("hello")))

        assertThat(client.boolVariation("existing", false)).isTrue()
        assertThat(client.stringVariation("new-flag", "default")).isEqualTo("hello")
    }

    @Test
    fun `mergeSnapshot updates existing flags`() {
        val client = FeatureflipClient.forTesting(mapOf("feature" to false))

        client.mergeSnapshot(mapOf("feature" to flagValue(true)))

        assertThat(client.boolVariation("feature", false)).isTrue()
    }

    @Test
    fun `mergeSnapshot removes flags with FLAG_REMOVED reason`() {
        val client = FeatureflipClient.forTesting(mapOf("feature" to true, "other" to "keep"))

        client.mergeSnapshot(mapOf("feature" to FlagValue(value = null, variation = "", reason = "FLAG_REMOVED")))

        assertThat(client.boolVariation("feature", false)).isFalse() // returns default
        assertThat(client.stringVariation("other", "default")).isEqualTo("keep") // preserved
    }

    // -- Concurrent merge atomicity --

    @Test
    fun `concurrent mergeSnapshot calls do not lose updates`() {
        val client = FeatureflipClient.forTesting(emptyMap())
        val threadCount = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        // Each thread merges a unique flag
        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    client.mergeSnapshot(mapOf("flag-$i" to flagValue(i)))
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val allFlags = client.allFlags()
        // Every flag from every thread must be present
        for (i in 0 until threadCount) {
            assertThat(allFlags).containsKey("flag-$i")
        }
        assertThat(allFlags).hasSize(threadCount)
    }

    // -- Singleton --

    // -- Lifecycle foreground dispatches off calling thread --

    @Test
    fun `handleForeground does not block calling thread`() {
        // Use a slow server to prove foreground returns immediately
        val flags = mapOf("feat" to flagValue(true))
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(flags)).build())
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(flags)).build())

        val config = FeatureflipConfig(
            clientKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            streaming = false,
            pollIntervalMs = 60_000,
        )
        val client = FeatureflipClient.create(config)
        client.initialize()

        // Enqueue a slow response for the foreground resume poll
        server.enqueue(
            MockResponse.Builder()
                .body(makeEvaluateResponseBody(flags))
                .headersDelay(500, TimeUnit.MILLISECONDS)
                .build(),
        )

        // Simulate lifecycle: the observer callbacks call handleForeground/handleBackground
        // Since handleForeground dispatches via backgroundScope.launch,
        // it should return well before the 500ms server delay
        val start = System.nanoTime()
        // Access the lifecycle observer via reflection to test the real path
        val observerField = FeatureflipClient::class.java.getDeclaredField("lifecycleObserver")
        observerField.isAccessible = true
        val observer = observerField.get(client) as? LifecycleObserver

        // Simulate background then foreground
        observer?.simulateBackground()
        observer?.simulateForeground()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // If handleForeground blocked on the slow server, this would take >500ms
        assertThat(elapsedMs).isLessThan(200)

        client.close()
    }

    @Test
    fun `startDataSource uses currentContext after identify`() {
        val flags = mapOf("feature" to flagValue(true))
        // For initialize evaluate call
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(flags)).build())
        // For poller initial poll during initialize
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(flags)).build())

        val config = FeatureflipConfig(
            clientKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            context = mapOf("user_id" to "user-a"),
            streaming = false,
            pollIntervalMs = 60_000,
        )
        val client = FeatureflipClient.create(config)
        client.initialize()
        client.close()

        // Drain all requests from initialize
        server.takeRequest(2, TimeUnit.SECONDS)
        server.takeRequest(2, TimeUnit.SECONDS)

        // Identify with new context
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(flags)).build())
        client.identify(mapOf("user_id" to "user-b"))
        server.takeRequest(2, TimeUnit.SECONDS) // drain identify request

        // Enqueue response for the new poller
        server.enqueue(MockResponse.Builder().body(makeEvaluateResponseBody(flags)).build())
        client.startDataSource()

        // Take the next request — should use "user-b" context
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertThat(request).isNotNull()
        val body = request!!.body!!.utf8()
        assertThat(body).contains("user-b")
        assertThat(body.contains("user-a")).isFalse()

        client.close()
    }

    @Test
    fun `shared throws before configure`() {
        assertThrows<IllegalStateException> {
            FeatureflipClient.shared()
        }
    }

    @Test
    fun `configure sets shared instance`() {
        FeatureflipClient.configure(FeatureflipConfig(clientKey = "key"))
        assertThat(FeatureflipClient.shared()).isNotNull
    }
}
