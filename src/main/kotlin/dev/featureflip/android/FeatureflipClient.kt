package dev.featureflip.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Main public API for the Featureflip Android SDK.
 *
 * This is a client-side SDK — flags are evaluated server-side and only the
 * values are returned. Use [initialize] to fetch initial flags, then read
 * values via [boolVariation], [stringVariation], etc.
 */
class FeatureflipClient private constructor(
    private val config: FeatureflipConfig,
    private val httpClient: HttpClient,
    private val cache: FlagCache,
    private val isTestClient: Boolean,
    initialFlags: Map<String, FlagValue>,
) {
    companion object {
        const val VERSION = "0.1.0"

        @Volatile
        private var instance: FeatureflipClient? = null

        /**
         * Returns the shared singleton client. Must call [configure] first.
         */
        @JvmStatic
        fun shared(): FeatureflipClient =
            instance ?: throw IllegalStateException("FeatureflipClient.configure() must be called before shared()")

        /**
         * Configures the shared singleton client.
         */
        @JvmStatic
        fun configure(config: FeatureflipConfig) {
            instance = create(config)
        }

        /**
         * Creates a new client instance.
         */
        @JvmStatic
        fun create(config: FeatureflipConfig): FeatureflipClient {
            val httpClient = HttpClient(config.baseUrl, config.clientKey)
            val cache = FlagCache(config.clientKey)
            return FeatureflipClient(config, httpClient, cache, isTestClient = false, initialFlags = emptyMap())
        }

        /**
         * Creates a new client instance with a custom [Call.Factory] (for testing).
         */
        internal fun create(config: FeatureflipConfig, callFactory: Call.Factory): FeatureflipClient {
            val httpClient = HttpClient(config.baseUrl, config.clientKey, callFactory)
            val cache = FlagCache(config.clientKey)
            return FeatureflipClient(config, httpClient, cache, isTestClient = false, initialFlags = emptyMap())
        }

        /**
         * Creates a no-network test client with static flag overrides.
         */
        @JvmStatic
        fun forTesting(overrides: Map<String, Any?>): FeatureflipClient {
            val dummyConfig = FeatureflipConfig(clientKey = "test-key", baseUrl = "https://localhost")
            val httpClient = HttpClient(dummyConfig.baseUrl, dummyConfig.clientKey)
            val cache = FlagCache(dummyConfig.clientKey)

            val flags = overrides.mapValues { (_, value) ->
                FlagValue(value = value, variation = "override", reason = "TEST")
            }

            return FeatureflipClient(dummyConfig, httpClient, cache, isTestClient = true, initialFlags = flags).also {
                it.snapshotLock.write { it.flagSnapshot = flags.toMutableMap() }
                it.lock.write { it._initialized = true }
            }
        }

        /**
         * Resets the singleton (for testing only).
         */
        internal fun resetShared() {
            instance = null
        }
    }

    private val snapshotLock = ReentrantReadWriteLock()
    private var flagSnapshot: MutableMap<String, FlagValue> = initialFlags.toMutableMap()

    private val lock = ReentrantReadWriteLock()
    private var currentContext: Map<String, String> = config.context
    private var _initialized = false
    private var streamingDataSource: StreamingDataSource? = null
    private var pollingDataSource: PollingDataSource? = null
    private var lifecycleObserver: LifecycleObserver? = null

    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val eventProcessor = EventProcessor(
        httpClient = httpClient,
        flushIntervalMs = config.flushIntervalMs,
        batchSize = config.flushBatchSize,
    )

    /**
     * Whether the client has been initialized.
     */
    val isInitialized: Boolean
        get() = lock.read { _initialized }

    /**
     * Initializes the client: loads disk cache, fetches flags, starts streaming/polling
     * and lifecycle observer.
     */
    fun initialize() {
        if (isTestClient) return

        // Load persisted cache
        cache.loadFromDisk()
        val cached = cache.all()
        if (cached.isNotEmpty()) {
            updateSnapshot(cached)
        }

        // Fetch initial flags
        try {
            val response = httpClient.evaluate(config.context, config.initTimeoutMs)
            cache.setAll(response.flags)
            updateSnapshot(response.flags)
        } catch (_: Exception) {
            // Use cached flags if available
        }

        // Start data source
        startDataSource()

        // Start event processor
        eventProcessor.start()

        // Start lifecycle observer
        val observer = LifecycleObserver(
            onForeground = { handleForeground() },
            onBackground = { handleBackground() },
        )
        lock.write {
            lifecycleObserver = observer
            _initialized = true
        }
    }

    /**
     * Stops streaming/polling and flushes pending events.
     */
    fun close() {
        val (stream, poller, observer) = lock.write {
            val s = streamingDataSource
            val p = pollingDataSource
            val o = lifecycleObserver
            streamingDataSource = null
            pollingDataSource = null
            lifecycleObserver = null
            Triple(s, p, o)
        }
        stream?.stop()
        poller?.stop()
        observer?.remove()
        eventProcessor.stop()
    }

    // -- Variation methods --

    /**
     * Returns a boolean flag value, or [defaultValue] if the flag is missing or not a boolean.
     */
    fun boolVariation(key: String, defaultValue: Boolean): Boolean {
        val flag = getFlag(key) ?: return defaultValue
        return flag.value as? Boolean ?: defaultValue
    }

    /**
     * Returns a string flag value, or [defaultValue] if the flag is missing or not a string.
     */
    fun stringVariation(key: String, defaultValue: String): String {
        val flag = getFlag(key) ?: return defaultValue
        return flag.value as? String ?: defaultValue
    }

    /**
     * Returns a numeric flag value as Double, or [defaultValue] if the flag is missing or not numeric.
     */
    fun numberVariation(key: String, defaultValue: Double): Double {
        val flag = getFlag(key) ?: return defaultValue
        return when (val v = flag.value) {
            is Number -> v.toDouble()
            else -> defaultValue
        }
    }

    /**
     * Returns the raw flag value, or [defaultValue] if the flag is missing.
     */
    fun jsonVariation(key: String, defaultValue: Any?): Any? {
        val flag = getFlag(key) ?: return defaultValue
        return flag.value
    }

    // -- Identify --

    /**
     * Re-evaluates flags for a new user context.
     */
    fun identify(context: Map<String, String>) {
        if (isTestClient) {
            lock.write { currentContext = context }
            return
        }
        val connectionId = lock.read { streamingDataSource }?.connectionId
        val response = httpClient.identify(context, connectionId)
        cache.setAll(response.flags)
        updateSnapshot(response.flags)

        val (stream, poller) = lock.write {
            currentContext = context
            streamingDataSource to pollingDataSource
        }
        stream?.updateContext(context)
        poller?.updateContext(context)
    }

    // -- Track --

    /**
     * Enqueues a custom analytics event.
     */
    fun track(eventName: String, metadata: Map<String, Any?>? = null) {
        if (isTestClient) return
        val userId = lock.read { currentContext["user_id"] }
        val event = SdkEvent(
            type = "Custom",
            flagKey = eventName,
            userId = userId,
            timestamp = isoFormat().format(Date()),
            metadata = metadata,
        )
        eventProcessor.enqueue(event)
    }

    private fun isoFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    // -- Flush --

    /**
     * Force-flushes pending analytics events.
     */
    fun flush() {
        if (isTestClient) return
        eventProcessor.flush()
    }

    // -- Internal --

    internal fun allFlags(): Map<String, FlagValue> = snapshotLock.read { flagSnapshot.toMap() }

    // -- Private --

    private fun getFlag(key: String): FlagValue? = snapshotLock.read { flagSnapshot[key] }

    private fun updateSnapshot(flags: Map<String, FlagValue>) {
        snapshotLock.write { flagSnapshot = flags.toMutableMap() }
    }

    internal fun startDataSource() {
        val ctx = lock.read { currentContext }
        if (config.streaming) {
            val source = StreamingDataSource(
                baseUrl = config.baseUrl,
                clientKey = config.clientKey,
                context = ctx,
                onChange = { flags -> handleStreamingUpdate(flags) },
            )
            source.start()
            lock.write { streamingDataSource = source }
        } else {
            startPolling()
        }
    }

    internal fun startPolling() {
        val ctx = lock.read { currentContext }
        val source = PollingDataSource(
            httpClient = httpClient,
            context = ctx,
            intervalMs = config.pollIntervalMs,
            onChange = { flags -> handlePollingUpdate(flags) },
        )
        source.start()
        lock.write { pollingDataSource = source }
    }

    private fun handleStreamingUpdate(delta: Map<String, FlagValue>) {
        val merged = mergeSnapshot(delta)
        cache.setAll(merged)
    }

    private fun handlePollingUpdate(flags: Map<String, FlagValue>) {
        updateSnapshot(flags)
        cache.setAll(flags)
    }

    internal fun mergeSnapshot(delta: Map<String, FlagValue>): Map<String, FlagValue> {
        return snapshotLock.write {
            for ((key, value) in delta) {
                if (value.reason == "FLAG_REMOVED" && value.value == null) {
                    flagSnapshot.remove(key)
                } else {
                    flagSnapshot[key] = value
                }
            }
            flagSnapshot.toMap()
        }
    }

    private fun handleForeground() {
        backgroundScope.launch {
            val (stream, poller) = lock.read { streamingDataSource to pollingDataSource }
            stream?.start()
            poller?.start()
        }
    }

    private fun handleBackground() {
        val (stream, poller) = lock.read { streamingDataSource to pollingDataSource }
        stream?.stop()
        poller?.stop()
        backgroundScope.launch { eventProcessor.flush() }
    }
}
