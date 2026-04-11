package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FeatureflipClientFactoryTest {

    private val json = jacksonObjectMapper()
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

    private fun uniqueKey(name: String): String = "factory-$name-${UUID.randomUUID()}"

    private fun enqueueFlags(count: Int = 4) {
        val body = json.writeValueAsString(
            mapOf("flags" to mapOf("test-flag" to FlagValue(value = true, variation = "on", reason = "RULE"))),
        )
        repeat(count) { server.enqueue(MockResponse.Builder().body(body).build()) }
    }

    private fun makeConfig(clientKey: String): FeatureflipConfig =
        FeatureflipConfig(
            clientKey = clientKey,
            baseUrl = server.url("/").toString().trimEnd('/'),
            streaming = false,
            pollIntervalMs = 60_000,
        )

    // -- Basic factory behavior --

    @Test
    fun `get returns a working client for a fresh client key`() {
        enqueueFlags()
        val key = uniqueKey("fresh")

        val client = FeatureflipClient.get(makeConfig(key))
        client.initialize()

        assertThat(client.boolVariation("test-flag", false)).isTrue()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(1)

        client.close()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)
    }

    @Test
    fun `second get with same key shares one shared core`() {
        enqueueFlags(8)
        val key = uniqueKey("dedupe")

        val h1 = FeatureflipClient.get(makeConfig(key))
        val h2 = FeatureflipClient.get(makeConfig(key))
        h1.initialize()
        h2.initialize() // no-op on the already-initialized core

        // Handles are distinct but share state.
        assertThat(h1).isNotSameAs(h2)
        assertThat(h1.boolVariation("test-flag", false)).isTrue()
        assertThat(h2.boolVariation("test-flag", false)).isTrue()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(2)

        h1.close()
        h2.close()
    }

    @Test
    fun `get with different keys constructs independent cores`() {
        enqueueFlags(8)
        val keyA = uniqueKey("a")
        val keyB = uniqueKey("b")

        val hA = FeatureflipClient.get(makeConfig(keyA))
        val hB = FeatureflipClient.get(makeConfig(keyB))
        hA.initialize()
        hB.initialize()

        assertThat(FeatureflipClient.debugRefCount(keyA)).isEqualTo(1)
        assertThat(FeatureflipClient.debugRefCount(keyB)).isEqualTo(1)
        assertThat(FeatureflipClient.debugLiveCoreCount()).isGreaterThanOrEqualTo(2)

        hA.close()
        hB.close()
    }

    @Test
    fun `after closing the only handle next get constructs a fresh core`() {
        enqueueFlags(8)
        val key = uniqueKey("recreate")

        val h1 = FeatureflipClient.get(makeConfig(key))
        h1.initialize()
        h1.close()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)

        val h2 = FeatureflipClient.get(makeConfig(key))
        h2.initialize()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(1)

        h2.close()
    }

    @Test
    fun `closing one of two handles leaves the other functional`() {
        enqueueFlags(8)
        val key = uniqueKey("shared")

        val h1 = FeatureflipClient.get(makeConfig(key))
        val h2 = FeatureflipClient.get(makeConfig(key))
        h1.initialize()

        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(2)
        h1.close()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(1)

        // h2 still works.
        assertThat(h2.boolVariation("test-flag", false)).isTrue()

        h2.close()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)
    }

    @Test
    fun `double-close on the same handle is idempotent`() {
        enqueueFlags(8)
        val key = uniqueKey("double")

        val h1 = FeatureflipClient.get(makeConfig(key))
        val h2 = FeatureflipClient.get(makeConfig(key))
        h1.initialize()

        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(2)

        h1.close()
        h1.close() // no-op
        h1.close() // no-op

        // Only one decrement — h2 is still alive.
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(1)

        h2.close()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)
    }

    @Test
    fun `32 concurrent get calls share one shared core`() {
        // 32 threads all call get() with the same key concurrently. Exactly one
        // core must be constructed (verified via initial flag fetch count);
        // all 32 handles must point at it (refcount == 32).
        val key = uniqueKey("concurrent")
        // Enqueue plenty of mock responses — the shared core only calls fetch
        // once during initialize(), but enqueuing extras is safe.
        enqueueFlags(64)

        val threadCount = 32
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val handles = mutableListOf<FeatureflipClient>()
        val handlesLock = Any()
        val failures = AtomicInteger(0)

        repeat(threadCount) {
            executor.submit {
                try {
                    val handle = FeatureflipClient.get(makeConfig(key))
                    synchronized(handlesLock) { handles.add(handle) }
                } catch (_: Throwable) {
                    failures.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertThat(failures.get()).isEqualTo(0)
        assertThat(handles).hasSize(threadCount)
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(threadCount)

        handles.forEach { it.close() }
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)
    }

    @Test
    fun `get with different options on an existing key reuses cache`() {
        enqueueFlags(8)
        val key = uniqueKey("warn")

        val h1 = FeatureflipClient.get(
            FeatureflipConfig(
                clientKey = key,
                baseUrl = server.url("/").toString().trimEnd('/'),
                streaming = false,
                pollIntervalMs = 60_000,
            ),
        )
        h1.initialize()

        // Different baseUrl — should reuse the cached core (and log a warning).
        val h2 = FeatureflipClient.get(
            FeatureflipConfig(
                clientKey = key,
                baseUrl = "https://different.example",
                streaming = false,
                pollIntervalMs = 60_000,
            ),
        )

        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(2)
        // Both handles still refer to the original server's flags.
        assertThat(h2.boolVariation("test-flag", false)).isTrue()

        h1.close()
        h2.close()
    }

    @Test
    fun `concurrent initialize calls on shared core run the init body exactly once`() {
        // Two handles on the same shared core both call initialize() concurrently.
        // The second caller must wait for the first to finish and return without
        // running the init body a second time. Otherwise the core would leak
        // duplicate data sources, event processors, and SSE connections.
        //
        // We verify this by observing the request count DURING the 800ms delay
        // of the initial evaluate response — while the first thread is blocked
        // on its HTTP call, any racing second thread would have also made its
        // own evaluate request. The PollingDataSource's initial poll cannot
        // fire until initialize() returns (and thus not until after the 800ms
        // delay elapses), so measuring at T~400ms isolates the init-body
        // request count from background polling.
        val key = uniqueKey("concurrent-init")

        val responseBody = json.writeValueAsString(
            mapOf("flags" to mapOf("test-flag" to FlagValue(value = true, variation = "on", reason = "RULE"))),
        )
        server.enqueue(
            MockResponse.Builder()
                .body(responseBody)
                .headersDelay(800, TimeUnit.MILLISECONDS)
                .build(),
        )
        // Extra responses for any racing duplicate fetches and for the
        // legitimate polling data source's initial poll.
        repeat(4) { server.enqueue(MockResponse.Builder().body(responseBody).build()) }

        val config = makeConfig(key)
        val h1 = FeatureflipClient.get(config)
        val h2 = FeatureflipClient.get(config)

        try {
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val latch = CountDownLatch(2)
            val executor = Executors.newFixedThreadPool(2)
            val failures = AtomicInteger(0)

            for (h in listOf(h1, h2)) {
                executor.submit {
                    try {
                        barrier.await()
                        h.initialize()
                    } catch (_: Throwable) {
                        failures.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }
            }

            // Sample the request count while both threads are still inside
            // initialize() (first thread blocked on the 800ms server delay,
            // second thread blocked on initializationDone latch). If the
            // TOCTOU bug is present, both threads fire their own evaluate
            // request here and requestCount is 2.
            Thread.sleep(400)
            val duringInit = server.requestCount
            assertThat(duringInit)
                .withFailMessage("Expected exactly 1 evaluate request during init (got %d) — TOCTOU bug?", duringInit)
                .isEqualTo(1)

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
            executor.shutdown()
            assertThat(failures.get()).isEqualTo(0)
        } finally {
            h1.close()
            h2.close()
        }
    }

    @Test
    fun `get without clientKey throws`() {
        assertThrows<IllegalArgumentException> {
            FeatureflipClient.get(FeatureflipConfig(clientKey = ""))
        }
    }

    @Test
    fun `forTesting creates an independent client not registered in the factory cache`() {
        val before = FeatureflipClient.debugLiveCoreCount()
        val client = FeatureflipClient.forTesting(mapOf("my-flag" to true))
        assertThat(client.boolVariation("my-flag", false)).isTrue()
        // forTesting core is not registered in the factory map.
        assertThat(FeatureflipClient.debugLiveCoreCount()).isEqualTo(before)
    }
}
