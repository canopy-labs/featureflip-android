package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FlagCacheTest {

    @Test
    fun `setAll and get returns flags`() {
        val cache = FlagCache("test-key")
        val flags = mapOf("feature" to FlagValue(value = true, variation = "v1", reason = "RULE"))
        cache.setAll(flags)

        assertThat(cache.get("feature")?.value).isEqualTo(true)
        assertThat(cache.get("missing")).isNull()
    }

    @Test
    fun `all returns all flags`() {
        val cache = FlagCache("test-key")
        cache.setAll(
            mapOf(
                "a" to FlagValue(value = true, variation = "v1", reason = "RULE"),
                "b" to FlagValue(value = "hello", variation = "v1", reason = "RULE"),
            ),
        )

        assertThat(cache.all()).hasSize(2)
    }

    @Test
    fun `persists to and loads from disk`(@TempDir tempDir: File) {
        val cache1 = FlagCache("test-key", tempDir)
        cache1.setAll(mapOf("feature" to FlagValue(value = true, variation = "v1", reason = "RULE")))

        val cache2 = FlagCache("test-key", tempDir)
        cache2.loadFromDisk()

        assertThat(cache2.get("feature")?.value).isEqualTo(true)
    }

    @Test
    fun `loadFromDisk handles missing file`(@TempDir tempDir: File) {
        val cache = FlagCache("test-key", tempDir)
        cache.loadFromDisk() // Should not throw
        assertThat(cache.all()).isEmpty()
    }

    @Test
    fun `loadFromDisk handles corrupt file`(@TempDir tempDir: File) {
        val safeKey = "test-key".replace("/", "_")
        File(tempDir, "${safeKey}_flags.json").writeText("not json")

        val cache = FlagCache("test-key", tempDir)
        cache.loadFromDisk() // Should not throw
        assertThat(cache.all()).isEmpty()
    }
}
