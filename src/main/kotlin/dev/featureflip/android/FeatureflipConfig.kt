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
    val context: Map<String, Any?> = emptyMap(),
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
    /**
     * Report the flags this app reads, so Featureflip can tell a flag that is still in
     * use from one whose code is gone. Each read through a typed accessor or
     * [FeatureflipClient.flagDetail] queues at most one `Evaluation` event per
     * (flag, variation, user) per hour, sent in the same batches as `track()` events.
     * The first read after each return to the foreground is reported again. A repeat
     * read costs no allocation and no I/O.
     *
     * When false, no reads are reported, and evaluate and identify stop telling the
     * server that this client reports them. The server then counts every flag it sends
     * as evaluated, as it does for SDK versions before 3.3.0.
     *
     * Compared by `configsEqual`: a later `get()` for the same client key with a
     * different value logs a warning and keeps the first value, like every other option.
     */
    val sendEvaluationEvents: Boolean = true,
)
