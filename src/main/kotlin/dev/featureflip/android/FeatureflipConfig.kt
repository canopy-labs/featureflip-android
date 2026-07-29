package dev.featureflip.android

/**
 * Configuration for the Featureflip client.
 *
 * [applicationContext] is the Android `Context` used to persist the generated
 * anonymous `user_id` (for sticky percentage-rollout bucketing of anonymous
 * users) across app restarts. It is typed `Any?` rather than
 * `android.content.Context` because this module has no compile-time Android
 * dependency — it runs on both Android and pure JVM. When omitted, the anonymous
 * id is sticky within the process but not persisted across restarts.
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
    val applicationContext: Any? = null,
    /**
     * In-process observers fired on every variation call. Honored on the first
     * `get()` per client key, like every other option. Deliberately excluded
     * from `configsEqual` — functions are not structurally comparable.
     */
    val inspectors: List<EvaluationInspector> = emptyList(),
)
