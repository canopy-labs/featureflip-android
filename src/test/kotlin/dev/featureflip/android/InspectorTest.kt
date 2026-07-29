package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InspectorTest {

    @Test
    fun `fires once per accessor with served value and verbatim reason`() {
        val events = mutableListOf<EvaluationEvent>()
        val client = FeatureflipClient.forTesting(
            mapOf("stub-flag" to true),
            listOf { e -> events.add(e) },
        )

        assertThat(client.boolVariation("stub-flag", false)).isTrue()
        assertThat(events).hasSize(1)
        assertThat(events[0].flagKey).isEqualTo("stub-flag")
        assertThat(events[0].value).isEqualTo(true)
        assertThat(events[0].variationKey).isEqualTo("override")
        assertThat(events[0].reason).isEqualTo("TEST")
        assertThat(events[0].ruleId).isNull()
    }

    @Test
    fun `reports flag-not-found for an absent flag`() {
        val events = mutableListOf<EvaluationEvent>()
        val client = FeatureflipClient.forTesting(emptyMap(), listOf { e -> events.add(e) })

        assertThat(client.boolVariation("nope", true)).isTrue()
        assertThat(events[0].reason).isEqualTo("flag-not-found")
        assertThat(events[0].value).isEqualTo(true)
        assertThat(events[0].variationKey).isNull()
    }

    @Test
    fun `on type mismatch reports the default but keeps the server reason`() {
        val events = mutableListOf<EvaluationEvent>()
        val client = FeatureflipClient.forTesting(
            mapOf("stub-flag" to "a string"),
            listOf { e -> events.add(e) },
        )

        assertThat(client.boolVariation("stub-flag", false)).isFalse()
        assertThat(events[0].value).isEqualTo(false)
        assertThat(events[0].reason).isEqualTo("TEST")
        assertThat(events[0].variationKey).isEqualTo("override")
    }

    @Test
    fun `fires exactly once per accessor across all four`() {
        val events = mutableListOf<EvaluationEvent>()
        val client = FeatureflipClient.forTesting(
            mapOf("stub-flag" to true),
            listOf { e -> events.add(e) },
        )

        client.boolVariation("stub-flag", false)
        assertThat(events).hasSize(1)
        client.stringVariation("stub-flag", "")
        assertThat(events).hasSize(2)
        client.numberVariation("stub-flag", 0.0)
        assertThat(events).hasSize(3)
        client.jsonVariation("stub-flag", null)
        assertThat(events).hasSize(4)
    }

    @Test
    fun `does not fire for flagDetail`() {
        val events = mutableListOf<EvaluationEvent>()
        val client = FeatureflipClient.forTesting(
            mapOf("stub-flag" to true),
            listOf { e -> events.add(e) },
        )

        client.flagDetail("stub-flag")
        assertThat(events).isEmpty()
    }

    @Test
    fun `isolates a throwing inspector from the value and from siblings`() {
        val seen = mutableListOf<String>()
        val client = FeatureflipClient.forTesting(
            mapOf("stub-flag" to true),
            listOf(
                { _: EvaluationEvent -> throw IllegalStateException("boom") },
                { e: EvaluationEvent -> seen.add(e.flagKey) },
            ),
        )

        assertThat(client.boolVariation("stub-flag", false)).isTrue()
        assertThat(seen).isEqualTo(listOf("stub-flag"))
    }

    @Test
    fun `hands over a context copy, not the core's live map`() {
        val events = mutableListOf<EvaluationEvent>()
        val core = SharedFeatureflipCore.createForTesting(
            mapOf("stub-flag" to true),
            listOf { e -> events.add(e) },
        )

        core.boolVariation("stub-flag", false)
        core.identify(mapOf("user_id" to "bob"))
        core.boolVariation("stub-flag", false)

        // The first event captured the context as it was at that call; the
        // identify must not have mutated it retroactively.
        assertThat(events[0].context).isEqualTo(emptyMap<String, String>())
        assertThat(events[1].context).isEqualTo(mapOf("user_id" to "bob"))
    }

    @Test
    fun `parses ruleId out of a rule-match reason leaving reason verbatim`() {
        val events = mutableListOf<EvaluationEvent>()
        val core = SharedFeatureflipCore.createForTesting(
            emptyMap(),
            listOf { e -> events.add(e) },
        )
        core.applyFlagUpdateForTest(
            mapOf(
                "ruled" to FlagValue(
                    value = true,
                    variation = "on",
                    reason = "rule-match:rule-abc-123",
                ),
            ),
        )

        core.boolVariation("ruled", false)

        assertThat(events[0].reason).isEqualTo("rule-match:rule-abc-123")
        assertThat(events[0].ruleId).isEqualTo("rule-abc-123")
    }

    @Test
    fun `is a no-op with no inspectors configured`() {
        val client = FeatureflipClient.forTesting(mapOf("stub-flag" to true))
        assertThat(client.boolVariation("stub-flag", false)).isTrue()
    }
}
