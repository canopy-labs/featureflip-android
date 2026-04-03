package dev.featureflip.android

/**
 * Configuration for the Featureflip client.
 */
data class FeatureflipConfig(
    val clientKey: String,
    val baseUrl: String = "https://eval.featureflip.io",
    val context: Map<String, String> = emptyMap(),
    val streaming: Boolean = true,
    val pollIntervalMs: Long = 30_000,
    val flushIntervalMs: Long = 30_000,
    val flushBatchSize: Int = 100,
    val initTimeoutMs: Long = 10_000,
)
