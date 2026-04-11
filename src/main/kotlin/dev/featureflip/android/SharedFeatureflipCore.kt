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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Internal shared core owning all expensive resources of a FeatureflipClient:
 * the HTTP client, disk cache, streaming/polling data sources, event processor,
 * lifecycle observer, and the in-memory flag snapshot.
 *
 * Refcounted: multiple [FeatureflipClient] handles can share one core. The real
 * shutdown runs only when the last handle is closed. Refcount uses
 * [AtomicInteger] with a CAS loop so concurrent [tryAcquire]/[release] calls are
 * race-safe.
 *
 * Constructed either by the static factory in [FeatureflipClient.get] or
 * directly by [createForTesting]. Not intended as a public API.
 */
internal class SharedFeatureflipCore private constructor(
    internal val config: FeatureflipConfig,
    private val httpClient: HttpClient,
    private val cache: FlagCache,
    private val isTestClient: Boolean,
    initialFlags: Map<String, FlagValue>,
) {
    private val snapshotLock = ReentrantReadWriteLock()
    private var flagSnapshot: MutableMap<String, FlagValue> = initialFlags.toMutableMap()

    private val lock = ReentrantReadWriteLock()
    private var currentContext: Map<String, String> = config.context
    private var _initialized = false
    private var streamingDataSource: StreamingDataSource? = null
    private var pollingDataSource: PollingDataSource? = null
    internal var lifecycleObserver: LifecycleObserver? = null

    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val eventProcessor = EventProcessor(
        httpClient = httpClient,
        flushIntervalMs = config.flushIntervalMs,
        batchSize = config.flushBatchSize,
    )

    private val refCount = AtomicInteger(1)
    private val isShutDown = AtomicBoolean(false)
    private var owningMap: ConcurrentHashMap<String, SharedFeatureflipCore>? = null
    private var owningKey: String? = null

    /**
     * Exactly-once guard for [initialize]. The first thread to CAS this flag
     * from false to true runs the initialization body; every other thread
     * waits on [initializationDone] until the first thread finishes.
     */
    private val initializationStarted = AtomicBoolean(false)
    private val initializationDone = CountDownLatch(1)

    val isInitialized: Boolean
        get() = lock.read { _initialized }

    val debugRefCount: Int
        get() = refCount.get()

    val debugIsShutDown: Boolean
        get() = isShutDown.get()

    /**
     * Atomically increments the refcount if the core is still alive.
     * Returns false if the core has already shut down (caller must construct a new one).
     */
    fun tryAcquire(): Boolean {
        while (true) {
            val current = refCount.get()
            if (current <= 0) return false
            if (refCount.compareAndSet(current, current + 1)) return true
        }
    }

    /**
     * Decrements the refcount. When it reaches zero, runs the real shutdown
     * exactly once. Over-release is a no-op — the CAS loop prevents the
     * counter from going below zero and the shutdown guard fires exactly once.
     */
    fun release() {
        while (true) {
            val current = refCount.get()
            if (current <= 0) return
            if (refCount.compareAndSet(current, current - 1)) {
                if (current - 1 == 0 && isShutDown.compareAndSet(false, true)) {
                    shutdown()
                }
                return
            }
        }
    }

    /** Called by the factory after successfully inserting this core into the owning map. */
    fun setOwningMap(map: ConcurrentHashMap<String, SharedFeatureflipCore>, key: String) {
        owningMap = map
        owningKey = key
    }

    /**
     * Initializes the core: loads disk cache, fetches flags, starts
     * streaming/polling and the lifecycle observer.
     *
     * Exactly-once regardless of how many handles concurrently call
     * [initialize] on the same shared core. The first caller to win the
     * [initializationStarted] CAS runs the body; every other caller blocks
     * on [initializationDone] until the body finishes, then returns. This
     * prevents duplicate HTTP fetches, duplicate SSE connections, and
     * duplicate background workers when two handles race to initialize the
     * same shared core (which is the whole point of the refcounted factory).
     */
    fun initialize() {
        if (isTestClient) return

        if (!initializationStarted.compareAndSet(false, true)) {
            // Another thread has already started (or finished) initialization.
            // Wait for it to complete, then return without re-running the body.
            initializationDone.await()
            return
        }

        try {
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
        } finally {
            // Release any threads waiting in the "else" branch above, even if
            // the initialization body threw. Init failures are already absorbed
            // by the inner try/catch on the HTTP fetch; any failure that
            // escapes here (e.g. from startDataSource() or LifecycleObserver
            // construction) is a programmer error — we still want to release
            // waiters so they don't hang forever.
            initializationDone.countDown()
        }
    }

    /**
     * Real shutdown — runs exactly once when the last handle calls [release].
     * Stops streaming/polling, lifecycle observer, and event processor, and
     * removes this core from the owning factory map.
     */
    private fun shutdown() {
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

        val map = owningMap
        val key = owningKey
        if (map != null && key != null) {
            // Only remove if we're still the mapped instance — defensive against
            // a racing factory call that already replaced us with a new core.
            map.remove(key, this)
        }
    }

    // -- Variation methods --

    fun boolVariation(key: String, defaultValue: Boolean): Boolean {
        val flag = getFlag(key) ?: return defaultValue
        return flag.value as? Boolean ?: defaultValue
    }

    fun stringVariation(key: String, defaultValue: String): String {
        val flag = getFlag(key) ?: return defaultValue
        return flag.value as? String ?: defaultValue
    }

    fun numberVariation(key: String, defaultValue: Double): Double {
        val flag = getFlag(key) ?: return defaultValue
        return when (val v = flag.value) {
            is Number -> v.toDouble()
            else -> defaultValue
        }
    }

    fun jsonVariation(key: String, defaultValue: Any?): Any? {
        val flag = getFlag(key) ?: return defaultValue
        return flag.value
    }

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

    fun track(eventName: String, metadata: Map<String, Any?>?) {
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

    fun flush() {
        if (isTestClient) return
        eventProcessor.flush()
    }

    // -- Internal test helpers --

    internal fun allFlags(): Map<String, FlagValue> = snapshotLock.read { flagSnapshot.toMap() }

    // -- Private --

    private fun isoFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

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

    companion object {
        /** Real constructor used by the factory. */
        internal fun create(config: FeatureflipConfig, callFactory: Call.Factory? = null): SharedFeatureflipCore {
            val httpClient = if (callFactory != null) {
                HttpClient(config.baseUrl, config.clientKey, callFactory)
            } else {
                HttpClient(config.baseUrl, config.clientKey)
            }
            val cache = FlagCache(config.clientKey)
            return SharedFeatureflipCore(
                config = config,
                httpClient = httpClient,
                cache = cache,
                isTestClient = false,
                initialFlags = emptyMap(),
            )
        }

        /**
         * Test-only core: no network calls, snapshot pre-populated, marked
         * initialized immediately.
         */
        internal fun createForTesting(overrides: Map<String, Any?>): SharedFeatureflipCore {
            val dummyConfig = FeatureflipConfig(clientKey = "test-key", baseUrl = "https://localhost")
            val httpClient = HttpClient(dummyConfig.baseUrl, dummyConfig.clientKey)
            val cache = FlagCache(dummyConfig.clientKey)

            val flags = overrides.mapValues { (_, value) ->
                FlagValue(value = value, variation = "override", reason = "TEST")
            }

            return SharedFeatureflipCore(
                config = dummyConfig,
                httpClient = httpClient,
                cache = cache,
                isTestClient = true,
                initialFlags = flags,
            ).also {
                it.snapshotLock.write { it.flagSnapshot = flags.toMutableMap() }
                it.lock.write { it._initialized = true }
            }
        }
    }
}

/**
 * Structural comparison of configs for the "options differ on repeat get()"
 * warning. `clientKey` is excluded (it is the cache key itself); `context` is
 * excluded because different callers naturally supply different contexts and
 * the first one wins via the initial core construction.
 */
internal fun configsEqual(a: FeatureflipConfig, b: FeatureflipConfig): Boolean {
    return a.baseUrl == b.baseUrl &&
        a.streaming == b.streaming &&
        a.pollIntervalMs == b.pollIntervalMs &&
        a.flushIntervalMs == b.flushIntervalMs &&
        a.flushBatchSize == b.flushBatchSize &&
        a.initTimeoutMs == b.initTimeoutMs
}
