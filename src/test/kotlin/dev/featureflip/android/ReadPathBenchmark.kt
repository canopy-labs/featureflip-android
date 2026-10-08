package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Cost of a REPEAT flag read with read reporting on vs off (#3545). Budget: at most
 * 100 ns added per read on a dev JVM. Excluded from `test`; run `./gradlew benchmark`
 * and paste the printed line into the PR description.
 *
 * The cores are never initialized: no network, no data source, no flush timer. The
 * "on" core's single first read is buffered during warm-up and dropped at release.
 */
@Tag("benchmark")
class ReadPathBenchmark {

    private val reads = 1_000_000

    private fun core(sendEvaluationEvents: Boolean): SharedFeatureflipCore {
        val core = SharedFeatureflipCore.create(
            FeatureflipConfig(
                clientKey = "bench-${UUID.randomUUID()}",
                // Never contacted: nothing here initializes or flushes.
                baseUrl = "http://127.0.0.1:9",
                context = mapOf("user_id" to "user-1"),
                streaming = false,
                sendEvaluationEvents = sendEvaluationEvents,
            ),
            anonymousKeyStore = InMemoryAnonymousKeyStore(),
        )
        core.applyFlagUpdateForTest(mapOf("flag-a" to FlagValue(value = true, variation = "on", reason = "RULE")))
        return core
    }

    /** ns per repeat boolVariation over [reads] calls. [sink] keeps the JIT from dropping the loop. */
    private fun nsPerRead(core: SharedFeatureflipCore): Double {
        var sink = 0
        val start = System.nanoTime()
        for (i in 0 until reads) {
            if (core.boolVariation("flag-a", false)) sink++
        }
        val elapsed = System.nanoTime() - start
        check(sink == reads)
        return elapsed.toDouble() / reads
    }

    private fun List<Double>.median(): Double = sorted()[size / 2]

    @Test
    fun `repeat boolVariation, read reporting on vs off`() {
        val off = core(sendEvaluationEvents = false)
        val on = core(sendEvaluationEvents = true)
        try {
            // Warm-up: JIT both paths. The "on" core's one first-in-window read happens here.
            repeat(5) {
                nsPerRead(off)
                nsPerRead(on)
            }
            // Interleaved, so drift (thermal, GC, other load) hits both sides alike.
            val offRuns = mutableListOf<Double>()
            val onRuns = mutableListOf<Double>()
            repeat(7) {
                offRuns.add(nsPerRead(off))
                onRuns.add(nsPerRead(on))
            }
            val offNs = offRuns.median()
            val onNs = onRuns.median()
            val added = onNs - offNs

            println(
                "[read-path benchmark] repeat boolVariation, median of 7 x %,d: off %.1f ns/op, on %.1f ns/op, added %.1f ns/op (budget 100) on %s %s"
                    .format(reads, offNs, onNs, added, System.getProperty("java.vm.name"), System.getProperty("java.version")),
            )
            assertThat(added).isLessThanOrEqualTo(100.0)
        } finally {
            off.release()
            on.release()
        }
    }
}
