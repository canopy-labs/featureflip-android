package dev.featureflip.android

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The disk cache must end up holding the snapshot memory holds. Cache writes run after
 * the snapshot lock is released, so a write computed for the previous user can arrive
 * after the next user's (#4937), and an older delivery for the same user after a newer
 * one (#4945). Each write is tagged with the snapshot version it was taken at and the
 * cache keeps the newest; the disk copy is what the next cold start serves, and offline
 * it is served indefinitely.
 */
class CacheGenerationTest {

    private val server = MockWebServer()
    private val gate = CountDownLatch(1)

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        gate.countDown()
        server.close()
    }

    private fun flag(v: String) = FlagValue(value = v, variation = "v-$v", reason = "RULE")

    @Test
    fun `a write for an older version does not replace a newer one on disk`(@TempDir dir: File) {
        val cache = FlagCache("k", dir)
        cache.setAll(mapOf("x" to flag("user-b")), 2)
        cache.setAll(mapOf("x" to flag("user-a")), 1)

        assertThat(cache.get("x")?.value).isEqualTo("user-b")
        val reloaded = FlagCache("k", dir).apply { loadFromDisk() }
        assertThat(reloaded.get("x")?.value).isEqualTo("user-b")
    }

    @Test
    fun `loadFromDisk does not overwrite a write that has already been applied`(@TempDir dir: File) {
        val cache = FlagCache("k", dir)
        cache.setAll(mapOf("x" to flag("user-b")), 2)
        // A file read that finished after the write above: older content.
        FlagCache("k", dir).setAll(mapOf("x" to flag("user-a")), 1)

        cache.loadFromDisk()

        assertThat(cache.get("x")?.value).isEqualTo("user-b")
    }

    @Test
    fun `a write for the same or a newer version is applied`(@TempDir dir: File) {
        val cache = FlagCache("k", dir)
        cache.setAll(mapOf("x" to flag("one")), 1)
        cache.setAll(mapOf("x" to flag("two")), 1)
        assertThat(cache.get("x")?.value).isEqualTo("two")
        cache.setAll(mapOf("x" to flag("three")), 2)
        assertThat(FlagCache("k", dir).apply { loadFromDisk() }.get("x")?.value).isEqualTo("three")
    }

    @Test
    fun `a poll applied just before identify cannot leave the previous user's flags on disk`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .body("""{"flags":{"x":{"value":"user-b","variation":"v-b","reason":"RULE"}}}""")
                .build()
        }
        val key = "cache-gen-${UUID.randomUUID()}"
        val core = SharedFeatureflipCore.create(
            FeatureflipConfig(
                clientKey = key,
                baseUrl = server.url("/").toString().trimEnd('/'),
                context = mapOf("user_id" to "user-a"),
                streaming = false,
                sendEvaluationEvents = false,
            ),
        )
        try {
            // Hold the poll's write (the snapshot is already applied for user-a) in the
            // window after the snapshot lock is released.
            val held = CountDownLatch(1)
            val writes = AtomicInteger()
            core.beforeCacheWriteForTest = {
                if (writes.getAndIncrement() == 0) {
                    held.countDown()
                    gate.await(10, TimeUnit.SECONDS)
                }
            }
            val poll = thread { core.applyFullSnapshot(mapOf("x" to flag("user-a")), requestedFor = 0) }
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue()

            core.identify(mapOf("user_id" to "user-b"))
            gate.countDown()
            poll.join(10_000)
            assertThat(poll.isAlive).isFalse()

            assertThat(core.allFlags()["x"]?.value).isEqualTo("user-b")
            val onDisk = FlagCache(key).apply { loadFromDisk() }
            assertThat(onDisk.get("x")?.value)
                .`as`("the disk cache must hold the flags of the user identify() switched to")
                .isEqualTo("user-b")
        } finally {
            core.release()
            File(File(System.getProperty("java.io.tmpdir"), "featureflip"), "${key}_flags.json").delete()
        }
    }

    @Test
    fun `two deliveries for the same user reach the disk in the order they were applied`() {
        // #4945: a stream delta and a fallback poll, or two stream deltas on different
        // threads, both for the current user. The older one's cache write is held until
        // the newer one has been applied and written.
        val key = "cache-gen-${UUID.randomUUID()}"
        val core = SharedFeatureflipCore.create(
            FeatureflipConfig(
                clientKey = key,
                baseUrl = server.url("/").toString().trimEnd('/'),
                context = mapOf("user_id" to "user-a"),
                streaming = false,
                sendEvaluationEvents = false,
            ),
        )
        try {
            val held = CountDownLatch(1)
            val writes = AtomicInteger()
            core.beforeCacheWriteForTest = {
                if (writes.getAndIncrement() == 0) {
                    held.countDown()
                    gate.await(10, TimeUnit.SECONDS)
                }
            }
            val older = thread { core.applyFullSnapshot(mapOf("x" to flag("older")), requestedFor = 0) }
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue()

            core.applyFullSnapshot(mapOf("x" to flag("newer")), requestedFor = 0)
            gate.countDown()
            older.join(10_000)
            assertThat(older.isAlive).isFalse()

            assertThat(core.allFlags()["x"]?.value).isEqualTo("newer")
            val onDisk = FlagCache(key).apply { loadFromDisk() }
            assertThat(onDisk.get("x")?.value)
                .`as`("the disk cache must hold the snapshot memory holds, not an older one")
                .isEqualTo("newer")
        } finally {
            core.release()
            File(File(System.getProperty("java.io.tmpdir"), "featureflip"), "${key}_flags.json").delete()
        }
    }
}
