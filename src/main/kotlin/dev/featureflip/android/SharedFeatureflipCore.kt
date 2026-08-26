package dev.featureflip.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** The engine embeds the matched rule id in the reason as `rule-match:{id}`. */
private const val RULE_MATCH_PREFIX = "rule-match:"

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
    private val anonymousKeyStore: AnonymousKeyStore,
) {
    private val snapshotLock = ReentrantReadWriteLock()
    private var flagSnapshot: MutableMap<String, FlagValue> = initialFlags.toMutableMap()

    private val lock = ReentrantReadWriteLock()
    // For real clients, resolve a persisted anonymous user_id into the working
    // context up front so evaluate, SSE, polling, and track() all carry it.
    private var currentContext: Map<String, Any?> =
        if (isTestClient) config.context else resolveAnonymousContext(config.context, anonymousKeyStore)
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
     *
     * Returns without waiting for the final event flush, which is handed to
     * [backgroundScope]. Use [releaseAndAwait] to wait for it (#2478).
     */
    fun release() {
        if (releaseIsFinal()) shutdown()
    }

    /**
     * Suspending [release]: returns only once the final event flush has been
     * attempted. Prefer this where the caller can suspend and wants the
     * buffered events actually sent before the core goes away.
     */
    suspend fun releaseAndAwait() {
        if (releaseIsFinal()) shutdownAndAwaitFlush()
    }

    /**
     * Decrements the refcount, returning true for the single caller that both
     * took it to zero and won the shutdown guard.
     */
    private fun releaseIsFinal(): Boolean {
        while (true) {
            val current = refCount.get()
            if (current <= 0) return false
            if (refCount.compareAndSet(current, current - 1)) {
                return current - 1 == 0 && isShutDown.compareAndSet(false, true)
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

            // Fetch initial flags. Use currentContext (not config.context) so the
            // persisted anonymous user_id resolved at construction is sent.
            try {
                val initialContext = lock.read { currentContext }
                val response = httpClient.evaluate(initialContext, config.initTimeoutMs)
                cache.setAll(response.flags)
                updateSnapshot(response.flags)
            } catch (e: Exception) {
                // NON-TERMINAL BY DESIGN — do not rethrow, and do not leave
                // _initialized false. Any flags already loaded from disk above keep
                // serving, the data source started below retries forever and
                // re-snapshots on connect, and anything still unknown falls back to
                // the caller's default. This matches the browser and flutter SDKs;
                // throwing here would take an app down at startup over a transient
                // blip.
                //
                // But it must not be SILENT. A revoked key, a 4xx/5xx, a timeout and
                // a JSON parse failure otherwise all present exactly like a healthy
                // start, and the caller cannot tell a working client from a
                // completely misconfigured one (#2294).
                System.err.println(
                    "[featureflip] initial flag fetch failed, serving cached or default " +
                        "values until the data source recovers: $e",
                )
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
     * Stops streaming/polling and the lifecycle observer, flushes remaining
     * events, and removes this core from the owning factory map.
     *
     * The final flush is handed to [backgroundScope].
     *
     * [EventProcessor.stop] posts the remaining batch inline, so running it here
     * would put a network round-trip on whichever thread called `close()` — on
     * Android typically the main thread, from `onPause`/`onDestroy` (#2478).
     */
    private fun shutdown() {
        detachSources()
        // Inline, so release() returns with the core already refusing events —
        // stop() used to guarantee that before it moved off this thread.
        eventProcessor.markClosed()
        backgroundScope.launch { eventProcessor.stop() }
        unregisterFromFactory()
    }

    /**
     * [shutdown], but waits for the final event flush instead of detaching it.
     *
     * Deliberately [NonCancellable]. This is cleanup, and the caller's scope is
     * routinely dying at exactly this moment — a plain `withContext` throws on
     * resume when that happens, stranding the core in the factory map with a
     * refcount already at zero, and skipping the unregister entirely.
     */
    private suspend fun shutdownAndAwaitFlush() {
        detachSources()
        eventProcessor.markClosed()
        try {
            withContext(NonCancellable + Dispatchers.IO) { eventProcessor.stop() }
        } finally {
            unregisterFromFactory()
        }
    }

    /** Stops streaming/polling and unhooks the lifecycle observer. Never blocks on network. */
    private fun detachSources() {
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
    }

    private fun unregisterFromFactory() {
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
        val flag = getFlag(key)
        val value = flag?.value as? Boolean ?: defaultValue
        notifyInspectors(key, flag, value)
        return value
    }

    fun stringVariation(key: String, defaultValue: String): String {
        val flag = getFlag(key)
        val value = flag?.value as? String ?: defaultValue
        notifyInspectors(key, flag, value)
        return value
    }

    fun numberVariation(key: String, defaultValue: Double): Double {
        val flag = getFlag(key)
        val value = (flag?.value as? Number)?.toDouble() ?: defaultValue
        notifyInspectors(key, flag, value)
        return value
    }

    fun jsonVariation(key: String, defaultValue: Any?): Any? {
        val flag = getFlag(key)
        val value = if (flag == null) defaultValue else flag.value
        notifyInspectors(key, flag, value)
        return value
    }

    /**
     * Fire the registered inspectors. Called once per variation call, after type
     * coercion, so [value] is exactly what the accessor returns. A throwing
     * inspector is isolated: it neither breaks the returned value nor stops the
     * remaining inspectors.
     */
    private fun notifyInspectors(key: String, flag: FlagValue?, value: Any?) {
        val inspectors = config.inspectors
        if (inspectors.isEmpty() || isShutDown.get()) return

        // The flag is absent from the snapshot (unknown key, not yet
        // initialized, or not clientSideVisible). The server never sent a reason
        // for it, so synthesize one in the same kebab-case as the rest.
        val reason = flag?.reason ?: "flag-not-found"
        val ruleId = if (reason.startsWith(RULE_MATCH_PREFIX)) {
            reason.removePrefix(RULE_MATCH_PREFIX).ifEmpty { null }
        } else {
            null
        }

        val event = EvaluationEvent(
            flagKey = key,
            // Copy, so a buggy inspector cannot mutate core state.
            context = lock.read { currentContext.toMap() },
            value = value,
            variationKey = flag?.variation,
            reason = reason,
            ruleId = ruleId,
            prerequisiteKey = flag?.prerequisiteKey,
            timestamp = isoFormat().format(Date()),
        )

        for (inspector in inspectors) {
            try {
                inspector(event)
            } catch (e: Exception) {
                System.err.println("[featureflip] evaluation inspector threw: $e")
            }
        }
    }

    fun identify(context: Map<String, Any?>) {
        if (isTestClient) {
            lock.write { currentContext = context }
            return
        }
        val resolved = resolveAnonymousContext(context, anonymousKeyStore)
        val connectionId = lock.read { streamingDataSource }?.connectionId
        val response = httpClient.identify(resolved, connectionId)
        cache.setAll(response.flags)
        updateSnapshot(response.flags)

        val (stream, poller) = lock.write {
            currentContext = resolved
            streamingDataSource to pollingDataSource
        }
        stream?.updateContext(resolved)
        poller?.updateContext(resolved)
    }

    fun track(eventName: String, metadata: Map<String, Any?>?) {
        if (isTestClient) return
        // Context values are Any? since #2293; SdkEvent.userId is String?. `?.toString()`
        // keeps an absent id null rather than the literal "null", and carries a numeric
        // id through as its decimal form.
        val userId = lock.read { currentContext["user_id"]?.toString() }
        val event = SdkEvent(
            type = SdkEventType.Custom,
            flagKey = eventName,
            userId = userId,
            timestamp = isoFormat().format(Date()),
            metadata = metadata,
        )
        eventProcessor.enqueue(event)
    }

    /**
     * Starts a flush of buffered events and returns immediately.
     *
     * [EventProcessor.flush] posts inline, so calling it here would run the
     * request on the caller's thread — a `NetworkOnMainThreadException` when
     * that thread is Android's main one (#2478). Use [flushAndAwait] to wait
     * for the result.
     */
    fun flush() {
        if (isTestClient) return
        backgroundScope.launch { eventProcessor.flush() }
    }

    /** Suspending [flush]: returns once the flush attempt has completed. */
    suspend fun flushAndAwait() {
        if (isTestClient) return
        withContext(Dispatchers.IO) { eventProcessor.flush() }
    }

    // -- Internal test helpers --

    internal fun allFlags(): Map<String, FlagValue> = snapshotLock.read { flagSnapshot.toMap() }

    internal fun debugBufferedEventCount(): Int = eventProcessor.bufferedEventCount()

    internal fun hasStreamingSource(): Boolean = lock.read { streamingDataSource != null }

    internal fun hasPollingSource(): Boolean = lock.read { pollingDataSource != null }

    /** Test-only: replace the snapshot with hand-built [FlagValue]s. */
    internal fun applyFlagUpdateForTest(flags: Map<String, FlagValue>) {
        updateSnapshot(flags)
    }

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
                // First flags-updated after (re)connect is the full snapshot -> REPLACE.
                onSnapshot = { flags -> handleFullUpdate(flags) },
                // Stream exhausted its retries -> fall back to polling (retries forever).
                onMaxRetriesReached = { handleStreamingFallback() },
            )
            source.start()
            lock.write { streamingDataSource = source }
        } else {
            startPolling()
        }
    }

    internal fun startPolling() {
        // Idempotent: a stream->polling fallback must start at most one poller.
        if (lock.read { pollingDataSource } != null) return
        val ctx = lock.read { currentContext }
        val source = PollingDataSource(
            httpClient = httpClient,
            context = ctx,
            intervalMs = config.pollIntervalMs,
            onChange = { flags -> handleFullUpdate(flags) },
        )
        source.start()
        lock.write { pollingDataSource = source }
    }

    /**
     * Streaming exhausted its retries: tear down the dormant streaming source
     * before falling back to polling (which retries forever). Stopping and
     * nulling the stream is what keeps a later [handleForeground]/[identify]
     * from resurrecting it alongside the poller — two live sources racing
     * stale deltas over fresh poll snapshots. Mirrors Flutter's
     * `_handleStreamingFallback`. Called from the [StreamingDataSource]
     * `onMaxRetriesReached` callback (safe to call from within it: `stop()`
     * only cancels the coroutine, it does not join).
     */
    internal fun handleStreamingFallback() {
        val stream = lock.write {
            val s = streamingDataSource
            streamingDataSource = null
            s
        }
        stream?.stop()
        startPolling()
    }

    private fun handleStreamingUpdate(delta: Map<String, FlagValue>) {
        val merged = mergeSnapshot(delta)
        cache.setAll(merged)
    }

    private fun handleFullUpdate(flags: Map<String, FlagValue>) {
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
        internal fun create(
            config: FeatureflipConfig,
            callFactory: Call.Factory? = null,
            anonymousKeyStore: AnonymousKeyStore? = null,
        ): SharedFeatureflipCore {
            val httpClient = if (callFactory != null) {
                HttpClient(config.baseUrl, config.clientKey, callFactory)
            } else {
                HttpClient(config.baseUrl, config.clientKey)
            }
            val cache = FlagCache(config.clientKey)
            val store = anonymousKeyStore
                ?: config.applicationContext?.let { SharedPreferencesAnonymousKeyStore.fromContext(it) }
                ?: InMemoryAnonymousKeyStore()
            return SharedFeatureflipCore(
                config = config,
                httpClient = httpClient,
                cache = cache,
                isTestClient = false,
                initialFlags = emptyMap(),
                anonymousKeyStore = store,
            )
        }

        /**
         * Test-only core: no network calls, snapshot pre-populated, marked
         * initialized immediately.
         */
        internal fun createForTesting(
            overrides: Map<String, Any?>,
            inspectors: List<EvaluationInspector> = emptyList(),
        ): SharedFeatureflipCore {
            val dummyConfig = FeatureflipConfig(
                clientKey = "test-key",
                baseUrl = "https://localhost",
                inspectors = inspectors,
            )
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
                anonymousKeyStore = InMemoryAnonymousKeyStore(),
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
