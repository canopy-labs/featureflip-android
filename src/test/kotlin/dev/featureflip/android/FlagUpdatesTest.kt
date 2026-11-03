package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FlagUpdatesTest {
    private fun fv(
        value: Any?,
        variation: String = "on",
        reason: String = "fallthrough",
        prereq: String? = null,
    ) = FlagValue(value, variation, reason, prereq)

    @Test
    fun `added key is a change`() {
        assertThat(diffKeys(emptyMap(), mapOf("a" to fv(true)))).isEqualTo(listOf("a"))
    }

    @Test
    fun `removed key is a change`() {
        assertThat(diffKeys(mapOf("a" to fv(true)), emptyMap())).isEqualTo(listOf("a"))
    }

    @Test
    fun `identical snapshots have no changes`() {
        val m = mapOf("a" to fv(true), "b" to fv("x"))
        assertThat(diffKeys(m, m.toMap())).isEmpty()
    }

    @Test
    fun `value change is a change`() {
        assertThat(diffKeys(mapOf("a" to fv(true)), mapOf("a" to fv(false)))).isEqualTo(listOf("a"))
    }

    @Test
    fun `variation-only change is a change`() {
        assertThat(diffKeys(mapOf("a" to fv(true, "on")), mapOf("a" to fv(true, "alt"))))
            .isEqualTo(listOf("a"))
    }

    @Test
    fun `reason-only change is a change`() {
        assertThat(
            diffKeys(
                mapOf("a" to fv(true, reason = "fallthrough")),
                mapOf("a" to fv(true, reason = "rule-match:r1")),
            ),
        ).isEqualTo(listOf("a"))
    }

    @Test
    fun `prerequisiteKey-only change is a change`() {
        assertThat(
            diffKeys(mapOf("a" to fv(true, prereq = null)), mapOf("a" to fv(true, prereq = "p"))),
        ).isEqualTo(listOf("a"))
    }

    @Test
    fun `structurally equal json values are unchanged`() {
        val before = mapOf("a" to fv(linkedMapOf("a" to listOf(1, 2))))
        val after = mapOf("a" to fv(hashMapOf("a" to mutableListOf(1, 2))))
        assertThat(diffKeys(before, after)).isEmpty()
    }

    @Test
    fun `keys are sorted`() {
        val before = mapOf("b" to fv(1), "a" to fv(1), "c" to fv(1))
        val after = mapOf("c" to fv(2), "b" to fv(2), "a" to fv(2))
        assertThat(diffKeys(before, after)).isEqualTo(listOf("a", "b", "c"))
    }

    @Test
    fun `removing an absent key is not a change`() {
        assertThat(diffKeys(mapOf("a" to fv(true)), mapOf("a" to fv(true)))).isEmpty()
    }
}
