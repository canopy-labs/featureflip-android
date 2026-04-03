package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
}
