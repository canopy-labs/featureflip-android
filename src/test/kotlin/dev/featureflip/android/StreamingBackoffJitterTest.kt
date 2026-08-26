package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The SSE drops this backoff absorbs are fleet-wide: one edge event severs every
 * stream at once (#2457 — measured at a 2.5-3.0ms spread across both eval-api pods),
 * so every client re-enters the backoff together. A constant delay there republishes
 * the drop's own synchronisation as a reconnect spike one backoff later (#2508).
 */
class StreamingBackoffJitterTest {

    @Test
    @DisplayName("first reconnect is scattered, not a constant every client shares")
    fun firstReconnectIsJittered() {
        val samples = (1..200)
            .map { StreamingDataSource.withJitter(StreamingDataSource.INITIAL_BACKOFF_MS) }
            .toSet()

        assertThat(samples)
            .`as`("reconnect delay is deterministic — a fleet-wide drop reconnects in lockstep")
            .hasSizeGreaterThan(1)
    }

    @Test
    @DisplayName("every delay stays inside [d/2, d] and strictly positive")
    fun delaysAreBoundedAndPositive() {
        for (base in listOf(
            StreamingDataSource.INITIAL_BACKOFF_MS,
            2_000L,
            StreamingDataSource.MAX_BACKOFF_MS,
        )) {
            repeat(100) {
                val delay = StreamingDataSource.withJitter(base)
                assertThat(delay)
                    .`as`("base=%d", base)
                    .isGreaterThan(0) // anti-busy-loop on an immediate failure
                    .isBetween(base / 2, base)
            }
        }
    }

    @Test
    @DisplayName("a non-positive delay is passed through unchanged")
    fun nonPositiveIsPassedThrough() {
        assertThat(StreamingDataSource.withJitter(0L)).isZero()
    }
}
