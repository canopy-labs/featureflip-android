package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PrerequisiteKeyTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `decodes prerequisiteKey when present`() {
        val json = """
            {
              "value": false,
              "variation": "off",
              "reason": "prerequisite-failed",
              "prerequisiteKey": "billing-enabled"
            }
        """.trimIndent()

        val flag = mapper.readValue<FlagValue>(json)

        assertThat(flag.variation).isEqualTo("off")
        assertThat(flag.reason).isEqualTo("prerequisite-failed")
        assertThat(flag.prerequisiteKey).isEqualTo("billing-enabled")
    }

    @Test
    fun `prerequisiteKey is null when absent`() {
        val json = """
            {
              "value": true,
              "variation": "on",
              "reason": "fallthrough"
            }
        """.trimIndent()

        val flag = mapper.readValue<FlagValue>(json)

        assertThat(flag.prerequisiteKey).isNull()
    }

    @Test
    fun `prerequisiteKey is null when server sends null`() {
        val json = """
            {
              "value": true,
              "variation": "on",
              "reason": "fallthrough",
              "prerequisiteKey": null
            }
        """.trimIndent()

        val flag = mapper.readValue<FlagValue>(json)

        assertThat(flag.prerequisiteKey).isNull()
    }

    @Test
    fun `decodes prerequisiteKey from client evaluate envelope`() {
        val json = """
            {
              "flags": {
                "premium-feature": {
                  "value": false,
                  "variation": "off",
                  "reason": "prerequisite-failed",
                  "prerequisiteKey": "subscription-active"
                },
                "dark-mode": {
                  "value": true,
                  "variation": "on",
                  "reason": "fallthrough"
                }
              }
            }
        """.trimIndent()

        val response = mapper.readValue<EvaluateResponse>(json)

        assertThat(response.flags["premium-feature"]?.prerequisiteKey).isEqualTo("subscription-active")
        assertThat(response.flags["dark-mode"]?.prerequisiteKey).isNull()
    }

    @Test
    fun `encode roundtrip preserves prerequisiteKey`() {
        val original = FlagValue(
            value = false,
            variation = "off",
            reason = "prerequisite-failed",
            prerequisiteKey = "parent-flag",
        )

        val data = mapper.writeValueAsString(original)
        val decoded = mapper.readValue<FlagValue>(data)

        assertThat(decoded).isEqualTo(original)
        assertThat(decoded.prerequisiteKey).isEqualTo("parent-flag")
    }

    @Test
    fun `encode roundtrip without prerequisiteKey`() {
        val original = FlagValue(
            value = true,
            variation = "on",
            reason = "fallthrough",
        )

        val data = mapper.writeValueAsString(original)
        val decoded = mapper.readValue<FlagValue>(data)

        assertThat(decoded).isEqualTo(original)
        assertThat(decoded.prerequisiteKey).isNull()
    }

    @Test
    fun `encode omits prerequisiteKey when null`() {
        // Aligns the cache write with the server's wire format, which omits the field
        // when not applicable, and matches the Swift SDK's Codable behavior.
        val flag = FlagValue(value = true, variation = "on", reason = "fallthrough")

        val data = mapper.writeValueAsString(flag)

        assertThat(data).doesNotContain("prerequisiteKey")
    }

    @Test
    fun `flag values differing only in prerequisiteKey are not equal`() {
        val a = FlagValue(value = false, variation = "off", reason = "prerequisite-failed", prerequisiteKey = "alpha")
        val b = FlagValue(value = false, variation = "off", reason = "prerequisite-failed", prerequisiteKey = "beta")
        val c = FlagValue(value = false, variation = "off", reason = "prerequisite-failed")

        assertThat(a).isNotEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun `flag cache persists prerequisiteKey across reload`(@TempDir tempDir: File) {
        val cache = FlagCache("test-key", tempDir)
        cache.setAll(
            mapOf(
                "premium" to FlagValue(
                    value = false,
                    variation = "off",
                    reason = "prerequisite-failed",
                    prerequisiteKey = "subscription",
                ),
                "vanilla" to FlagValue(value = true, variation = "on", reason = "fallthrough"),
            ),
        )

        val reloaded = FlagCache("test-key", tempDir)
        reloaded.loadFromDisk()

        assertThat(reloaded.get("premium")?.prerequisiteKey).isEqualTo("subscription")
        assertThat(reloaded.get("vanilla")?.prerequisiteKey).isNull()
    }

    @Test
    fun `flag cache decodes pre-existing file without prerequisiteKey`(@TempDir tempDir: File) {
        // Simulate a cache file written by an older SDK version that didn't know about prerequisiteKey.
        val safeKey = "test-key".replace("/", "_")
        File(tempDir, "${safeKey}_flags.json").writeText(
            """{"feature":{"value":true,"variation":"v1","reason":"RULE"}}""",
        )

        val cache = FlagCache("test-key", tempDir)
        cache.loadFromDisk()

        val loaded = cache.get("feature")
        assertThat(loaded).isNotNull
        assertThat(loaded?.value).isEqualTo(true)
        assertThat(loaded?.prerequisiteKey).isNull()
    }
}
