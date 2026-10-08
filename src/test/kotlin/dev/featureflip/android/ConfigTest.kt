package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

class ConfigTest {

    @Test
    fun `default values are correct`() {
        val config = FeatureflipConfig(clientKey = "test-key")

        assertThat(config.clientKey).isEqualTo("test-key")
        assertThat(config.baseUrl).isEqualTo("https://eval.featureflip.io")
        assertThat(config.context).isEmpty()
        assertThat(config.streaming).isTrue()
        assertThat(config.pollIntervalMs).isEqualTo(30_000)
        assertThat(config.flushIntervalMs).isEqualTo(30_000)
        assertThat(config.flushBatchSize).isEqualTo(100)
        assertThat(config.initTimeoutMs).isEqualTo(10_000)
        assertThat(config.sendEvaluationEvents).isTrue()
    }

    @Test
    fun `custom values are applied`() {
        val config = FeatureflipConfig(
            clientKey = "my-key",
            baseUrl = "https://custom.example.com",
            context = mapOf("user_id" to "123"),
            streaming = false,
            pollIntervalMs = 60_000,
            flushIntervalMs = 15_000,
            flushBatchSize = 50,
            initTimeoutMs = 5_000,
        )

        assertThat(config.clientKey).isEqualTo("my-key")
        assertThat(config.baseUrl).isEqualTo("https://custom.example.com")
        assertThat(config.context).containsEntry("user_id", "123")
        assertThat(config.streaming).isFalse()
        assertThat(config.pollIntervalMs).isEqualTo(60_000)
        assertThat(config.flushIntervalMs).isEqualTo(15_000)
        assertThat(config.flushBatchSize).isEqualTo(50)
        assertThat(config.initTimeoutMs).isEqualTo(5_000)
    }

    @Test
    fun `read reporting is on by default`() {
        assertThat(FeatureflipConfig(clientKey = "test-key").sendEvaluationEvents).isTrue()
    }

    @Test
    fun `configsEqual treats a different sendEvaluationEvents as a mismatch`() {
        val on = FeatureflipConfig(clientKey = "k")
        val off = on.copy(sendEvaluationEvents = false)

        assertThat(configsEqual(on, on.copy())).isTrue()
        assertThat(configsEqual(on, off)).isFalse()
        assertThat(configsEqual(off, on)).isFalse()
    }

    @Test
    fun `a second get with a different sendEvaluationEvents logs the options warning`() {
        // The cached core keeps the first config, so a caller turning reporting off on a
        // later get() would otherwise be ignored silently. baseUrl is never contacted:
        // there is no initialize(), and close() flushes an empty buffer.
        val first = FeatureflipConfig(
            clientKey = "config-warn-${UUID.randomUUID()}",
            baseUrl = "https://localhost",
            streaming = false,
        )
        val original = System.err
        val captured = ByteArrayOutputStream()
        val h1 = FeatureflipClient.get(first)
        try {
            System.setErr(PrintStream(captured, true))
            FeatureflipClient.get(first.copy(sendEvaluationEvents = false)).close()
        } finally {
            System.setErr(original)
            h1.close()
            FeatureflipClient.resetForTesting()
        }

        assertThat(captured.toString()).contains("called with different options")
    }
}
