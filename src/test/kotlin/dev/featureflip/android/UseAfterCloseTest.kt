package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * `close()` releases the core — stopping streaming/polling, removing the lifecycle
 * observer, flushing events — but the in-memory cache stays readable, so a closed
 * handle kept serving a frozen snapshot that can never update again, and reported
 * `isInitialized == true` while doing it (#2295).
 *
 * The contract settled in #2313 for the server SDKs: a closed handle returns the
 * caller's default and reports not-initialized. This applies it to android.
 *
 * The fixture deliberately seeds real values via `forTesting`. A client whose fetch
 * failed has an empty cache and would return defaults either way, so the stale value
 * has to exist for these assertions to mean anything.
 */
class UseAfterCloseTest {

    @AfterEach
    fun tearDown() {
        FeatureflipClient.resetForTesting()
    }

    private fun seededClient() = FeatureflipClient.forTesting(
        mapOf(
            "bool-flag" to true,
            "string-flag" to "served",
            "number-flag" to 42.0,
        ),
    )

    @Test
    fun `serves real values while open`() {
        val client = seededClient()

        assertThat(client.boolVariation("bool-flag", false)).isTrue()
        assertThat(client.stringVariation("string-flag", "fallback")).isEqualTo("served")
        assertThat(client.numberVariation("number-flag", 0.0)).isEqualTo(42.0)
        assertThat(client.isInitialized).isTrue()

        client.close()
    }

    @Test
    fun `a closed handle serves the caller default, not the stale value`() {
        val client = seededClient()
        client.close()

        // Each default is deliberately the opposite of the cached value, so a stale
        // read is distinguishable from a correct default.
        assertThat(client.boolVariation("bool-flag", false)).isFalse()
        assertThat(client.stringVariation("string-flag", "fallback")).isEqualTo("fallback")
        assertThat(client.numberVariation("number-flag", 0.0)).isEqualTo(0.0)
        assertThat(client.jsonVariation("bool-flag", null)).isNull()
    }

    @Test
    fun `a closed handle reports not-initialized`() {
        val client = seededClient()
        assertThat(client.isInitialized).isTrue()

        client.close()

        assertThat(client.isInitialized).isFalse()
    }

    @Test
    fun `a closed handle exposes no flag detail`() {
        val client = seededClient()
        assertThat(client.flagDetail("bool-flag")).isNotNull()

        client.close()

        assertThat(client.flagDetail("bool-flag")).isNull()
    }

    @Test
    fun `close stays idempotent`() {
        val client = seededClient()

        client.close()
        client.close()

        assertThat(client.boolVariation("bool-flag", false)).isFalse()
    }
}
