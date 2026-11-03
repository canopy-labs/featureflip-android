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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    // Test seam: where the read recorder sends its events. Null means the event processor.
    enqueueRead: ((SdkEvent) -> Unit)? = null,
) {
    // Lock order: snapshotLock before lock, never the reverse. identify() takes both to
    // swap the snapshot and the context together.
    private val snapshotLock = ReentrantReadWriteLock()
    private val updateListeners = CopyOnWriteArrayList<FlagUpdateListener>()
    private var flagSnapshot: MutableMap<String, FlagValue> = initialFlags.toMutableMap()

    // Bumped on every change to flagSnapshot, under snapshotLock (write), and handed to
    // the disk write that change makes. Those writes run after the lock is released, so
    // they can reach the cache out of order; it keeps the newest (#4937, #4945).
    private var snapshotVersion = 0L

    private val lock = ReentrantReadWriteLock()

    // Which user the snapshot belongs to (#4886). Every applied identify() bumps it, and
    // every poll, stream frame and initial fetch is tagged with the value current when
    // it was requested. A delivery tagged with an older one was evaluated for the
    // previous user and is dropped. Written only while holding BOTH snapshotLock and
    // lock (write), so a reader holding either one sees a value that matches what it
    // guards: the snapshot under snapshotLock, currentContext under lock.
    private var contextGeneration = 0L

    // Orders overlapping identify() calls. A response is applied only if no later
    // identify() has already been applied; otherwise it would swap the earlier user
    // back in. Guarded by snapshotLock.
    private val identifySequence = AtomicLong()
    private var appliedIdentifySequence = 0L

    // For real clients, resolve a persisted anonymous user_id into the working
    // context up front so evaluate, SSE, polling, and track() all carry it.
    private var currentContext: Map<String, Any?> =
        if (isTestClient) config.context else resolveAnonymousContext(config.context, anonymousKeyStore)

    // The user reads and track() events are attributed to, cached so that a flag read
    // neither takes [lock] nor stringifies a context value. It is rewritten wherever
    // currentContext is (here and in identify), inside the same write lock. Volatile, so
    // readers need no lock.
    @Volatile
    private var currentUserId: String? = userIdOf(currentContext)

    private var _initialized = false
    private var streamingDataSource: StreamingDataSource? = null
    private var pollingDataSource: PollingDataSource? = null
    internal var lifecycleObserver: LifecycleObserver? = null

    /** Set by [handleBackground], consumed by [handleForeground]. */
    private val pausedInBackground = AtomicBoolean(false)

    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val eventProcessor = EventProcessor(
        httpClient = httpClient,
        flushIntervalMs = config.flushIntervalMs,
        batchSize = config.flushBatchSize,
    )

    // One per core, so every handle on a client key shares one dedupe window. Null for
    // a test client, which never sends anything (like track()), and when the caller
    // turned reporting off. create() builds the HttpClient from the same
    // config.sendEvaluationEvents, so the header and the reporting cannot disagree.
    // The window is ReadRecorder's fixed hour, not config.flushIntervalMs.
    private val readRecorder: ReadRecorder? =
        if (isTestClient || !config.sendEvaluationEvents) {
            null
        } else {
            ReadRecorder(
                enqueue = enqueueRead ?: eventProcessor::enqueue,
                timestamp = { isoFormat().format(Date()) },
            )
        }

    private val refCount = AtomicInteger(1)
    private val isShutDown = AtomicBoolean(false)

    // Test seam: runs in identify() after the new user's flags are applied and before
    // the data sources are pointed at that user, the window a close() must not be able
    // to exploit (#4901).
    @Volatile
    internal var afterIdentifyAppliedForTest: (() -> Unit)? = null

    // Test seam: runs just before a cache write, after the snapshot lock is released,
    // with the snapshot version the write is tagged with. It holds a write in the
    // window where a later snapshot change can overtake it (#4937, #4945).
    @Volatile
    internal var beforeCacheWriteForTest: ((Long) -> Unit)? = null

    // Test seam: runs inside handleForeground()'s restart, under the read lock, after the
    // sources have been read into locals and before they are started. Without the lock,
    // a detachSources() running here would leave the restart starting a source that
    // close() already stopped.
    @Volatile
    internal var duringForegroundRestartForTest: (() -> Unit)? = null

    // Test seam: runs when handleForeground()'s restart has finished.
    @Volatile
    internal var afterForegroundRestartForTest: (() -> Unit)? = null

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
            // Use currentContext (not config.context) so the persisted anonymous user_id
            // resolved at construction is sent. An identify() that completes while the
            // fetch is on the wire supersedes it (#4886): its response is the one to
            // keep, so both loads below apply only if the generation is unchanged.
            val (initialContext, requestedFor) = lock.read { currentContext to contextGeneration }

            // Load persisted cache
            cache.loadFromDisk()
            val cached = cache.all()
            if (cached.isNotEmpty()) {
                loadSnapshotIfCurrent(cached, requestedFor)
            }

            // Fetch initial flags.
            try {
                val response = httpClient.evaluate(initialContext, config.initTimeoutMs)
                loadSnapshotIfCurrent(response.flags, requestedFor)?.let { persist(response.flags, it) }
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

            // The last handle may have been closed while the fetch was on the wire. That
            // released the core and unregistered it from the factory, so nothing can
            // ever stop what is started on it from here: the stream would retry forever
            // (#4901). Logged because the caller sees a client that never streamed.
            if (isShutDown.get()) {
                System.err.println(
                    "[featureflip] client closed during initialize(); not starting the data source",
                )
                return
            }

            // A close() landing from here on is covered by each start below refusing,
            // atomically, once the core is shut down: startDataSource() and the observer
            // registration check under the write lock detachSources() needs, and the
            // event processor checks under its own lock against markClosed(). Either
            // the start wins and shutdown then stops it, or it sees the shutdown and
            // starts nothing.
            startDataSource()
            eventProcessor.start()
            lock.write {
                if (!isShutDown.get()) {
                    lifecycleObserver = LifecycleObserver(
                        onForeground = { handleForeground() },
                        onBackground = { handleBackground() },
                    )
                    _initialized = true
                }
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
        recordRead(key, flag)
        return value
    }

    fun stringVariation(key: String, defaultValue: String): String {
        val flag = getFlag(key)
        val value = flag?.value as? String ?: defaultValue
        notifyInspectors(key, flag, value)
        recordRead(key, flag)
        return value
    }

    fun numberVariation(key: String, defaultValue: Double): Double {
        val flag = getFlag(key)
        val value = (flag?.value as? Number)?.toDouble() ?: defaultValue
        notifyInspectors(key, flag, value)
        recordRead(key, flag)
        return value
    }

    fun jsonVariation(key: String, defaultValue: Any?): Any? {
        val flag = getFlag(key)
        val value = if (flag == null) defaultValue else flag.value
        notifyInspectors(key, flag, value)
        recordRead(key, flag)
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

    /**
     * The cached [FlagValue] for [key], or null. Counts as a read, like the typed
     * accessors, but notifies no inspectors: they fire once per typed decision, and
     * this is not one.
     */
    fun flagDetail(key: String): FlagValue? {
        val flag = getFlag(key)
        recordRead(key, flag)
        return flag
    }

    /**
     * Reports a read of [key] (#3545). Called beside [notifyInspectors] in every typed
     * accessor, and from [flagDetail]. Never from [allFlags]: it hands back every flag
     * at once, so counting it would mark every served flag as read, which is the bug
     * read reporting exists to fix. [flag] is null when the key is absent from the
     * snapshot, and that read is still reported, with no variation.
     *
     * Hot path: two field reads, then the recorder's lock-free lookups. No lock, no
     * allocation and no context access on a repeat read.
     */
    private fun recordRead(key: String, flag: FlagValue?) {
        val recorder = readRecorder ?: return
        if (isShutDown.get()) return
        // Isolated like an inspector: reporting a read must never break the read itself.
        try {
            recorder.record(key, flag?.variation, currentUserId)
        } catch (e: Exception) {
            System.err.println("[featureflip] read recording threw: $e")
        }
    }

    fun identify(context: Map<String, Any?>) {
        if (isTestClient) {
            lock.write {
                currentContext = context
                currentUserId = userIdOf(context)
            }
            return
        }
        val resolved = resolveAnonymousContext(context, anonymousKeyStore)
        val sequence = identifySequence.incrementAndGet()
        val connectionId = lock.read { streamingDataSource }?.connectionId
        val response = httpClient.identify(resolved, connectionId)

        // The last handle was closed while the request was on the wire. Nobody can
        // observe this core again (close() dropped every listener, and a closed handle
        // serves defaults), so there is nothing to apply and nothing to report.
        if (isShutDown.get()) return

        // Swap the flags and the context together, bumping the generation, so a poll
        // or stream frame still on the wire for the previous user is recognisably
        // stale (#4886). Diffed under the same lock as the swap.
        val (changed, generation, version) = snapshotLock.write {
            // Overtaken by a later identify() that has already been applied: its
            // response is the newer one, and applying this one would swap the earlier
            // user back in. Silent: the caller's last identify() is being served.
            if (sequence < appliedIdentifySequence) return
            appliedIdentifySequence = sequence
            val before = flagSnapshot
            flagSnapshot = response.flags.toMutableMap()
            val generation = lock.write {
                currentContext = resolved
                currentUserId = userIdOf(resolved)
                ++contextGeneration
            }
            Triple(diffKeys(before, flagSnapshot), generation, ++snapshotVersion)
        }
        persist(response.flags, version)
        afterIdentifyAppliedForTest?.invoke()

        // Read and retargeted under the lock detachSources() writes under, so a close()
        // that has released the core finds nothing here to restart (#4901): restarting
        // the stream of a released core would leave it reconnecting forever.
        lock.read {
            streamingDataSource?.updateContext(resolved, generation)
            pollingDataSource?.updateContext(resolved, generation)
        }
        // Last, so a listener sees the new user's context and flags together.
        notifyUpdate(changed)
    }

    fun track(eventName: String, metadata: Map<String, Any?>?) {
        if (isTestClient) return
        val userId = currentUserId
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

    // Never reports a read; see recordRead.
    internal fun allFlags(): Map<String, FlagValue> = snapshotLock.read { flagSnapshot.toMap() }

    internal fun debugBufferedEventCount(): Int = eventProcessor.bufferedEventCount()

    internal fun hasStreamingSource(): Boolean = lock.read { streamingDataSource != null }

    /** Test-only: the live streaming source, to observe whether it is running. */
    internal fun streamingSourceForTest(): StreamingDataSource? = lock.read { streamingDataSource }

    internal fun hasPollingSource(): Boolean = lock.read { pollingDataSource != null }

    /** Test-only: replace the snapshot with hand-built [FlagValue]s. */
    internal fun applyFlagUpdateForTest(flags: Map<String, FlagValue>) {
        snapshotLock.write {
            flagSnapshot = flags.toMutableMap()
            ++snapshotVersion
        }
    }

    // -- Private --

    private fun isoFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun getFlag(key: String): FlagValue? = snapshotLock.read { flagSnapshot[key] }

    /**
     * Starts the configured data source. Runs entirely under the write lock, which
     * [detachSources] also takes, and refuses once the core is shut down (#4901): a
     * source started on a released core has no owner left to stop it. Holding the lock
     * across the context read and the registration also means an identify() either
     * finds the new source to retarget or has already set the context it starts with.
     */
    internal fun startDataSource() {
        lock.write {
            // Idempotent, and that is load-bearing rather than defensive (#3075). An
            // orphaned streaming source used to stop itself at the retry cap; it now
            // retries forever AND keeps calling back into this core, so one left running
            // by a second startDataSource() could retire the live source's fallback
            // poller mid-outage and leave the app uncovered by either.
            if (isShutDown.get() || streamingDataSource != null) return
            if (!config.streaming) {
                startPollingLocked()
                return
            }
            val source = StreamingDataSource(
                baseUrl = config.baseUrl,
                clientKey = config.clientKey,
                context = currentContext,
                onChange = { flags, requestedFor -> handleStreamingUpdate(flags, requestedFor) },
                // First flags-updated after (re)connect is the full snapshot -> REPLACE.
                onSnapshot = { flags, requestedFor -> applyFullSnapshot(flags, requestedFor) },
                // Stream exhausted its retries -> poll ALONGSIDE it until it recovers.
                onFallbackToPolling = { handleStreamingFallback() },
                onStreamRecovered = { stopFallbackPolling() },
                userAgent = httpClient.userAgent,
                generation = contextGeneration,
            )
            source.start()
            streamingDataSource = source
        }
    }

    internal fun startPolling() {
        lock.write { startPollingLocked() }
    }

    /** Caller holds the write lock; see [startDataSource] for why. */
    private fun startPollingLocked() {
        // Idempotent: a stream->polling fallback must start at most one poller.
        if (isShutDown.get() || pollingDataSource != null) return
        val source = PollingDataSource(
            httpClient = httpClient,
            context = currentContext,
            intervalMs = config.pollIntervalMs,
            onChange = { flags, requestedFor -> applyFullSnapshot(flags, requestedFor) },
            generation = contextGeneration,
        )
        source.start()
        pollingDataSource = source
    }

    /**
     * Streaming exhausted its retries: start polling to cover the outage. Called
     * from the [StreamingDataSource] `onFallbackToPolling` callback.
     *
     * The streaming source is deliberately NOT stopped or nulled (#3075). Nulling it
     * is what used to make the fallback permanent — nothing would ever have restarted
     * streaming, so the app lost real-time updates until it was killed. It kept
     * [handleForeground]/[identify] from resurrecting a *dormant* stream beside the
     * poller (#1902), but the stream is no longer dormant: it keeps retrying
     * underneath, so those two call sites act on the one live source that already
     * exists and cannot create a second. [startDataSource] is the only construction
     * site and runs once.
     */
    internal fun handleStreamingFallback() {
        if (!isLiveStreamingSource()) return
        startPolling()
    }

    /**
     * Retires a polling fallback once the stream is carrying configuration again.
     * Clears the reference as well as stopping the poller, so a later outage falls
     * back again — [startPolling] refuses to start a second poller while one is
     * referenced, and a dead one parked there would leave the next outage uncovered.
     *
     * A poller the caller configured (`streaming = false`) is never reached: this
     * only runs off `onStreamRecovered`, which only fires after `onFallbackToPolling`
     * did, and neither exists unless a streaming source was built.
     */
    internal fun stopFallbackPolling() {
        if (!isLiveStreamingSource()) return
        val poller = lock.write {
            val p = pollingDataSource
            pollingDataSource = null
            p
        }
        poller?.stop()
    }

    /**
     * Whether this core still owns a streaming source, i.e. whether a callback
     * arriving from one is still relevant.
     *
     * Both fallback callbacks come from a coroutine that keeps running until it
     * observes cancellation, so one can arrive after [detachSources] has nulled the
     * reference. Acting on it then would start a poller nothing holds a reference to —
     * it would poll for the rest of the process's life — or retire a poller this core
     * no longer has any stream to replace.
     */
    private fun isLiveStreamingSource(): Boolean = lock.read { streamingDataSource } != null

    private fun handleStreamingUpdate(delta: Map<String, FlagValue>, requestedFor: Long) {
        val (merged, version) = mergeIfCurrent(delta, requestedFor) ?: return
        persist(merged, version)
    }

    /**
     * Writes [flags] to the disk cache, tagged with the [snapshotVersion] they were
     * applied at. The write happens after [snapshotLock] is released (no disk I/O under
     * it), so a later change to the snapshot can overtake it: an identify() (#4937), or
     * another delivery for the same user on a different thread (#4945). [FlagCache]
     * drops a write older than the newest it has applied, so the disk ends up holding
     * the snapshot memory last moved to.
     */
    private fun persist(flags: Map<String, FlagValue>, version: Long) {
        // A released core must not write the per-key file a new core may have taken over.
        if (isShutDown.get()) return
        beforeCacheWriteForTest?.invoke(version)
        cache.setAll(flags, version)
    }

    /**
     * Replaces the snapshot with [flags] and notifies the changed keys. [requestedFor]
     * is the context generation a data source asked for these flags under; null (tests)
     * applies unconditionally.
     */
    internal fun applyFullSnapshot(flags: Map<String, FlagValue>, requestedFor: Long? = null) {
        val (changed, version) = replaceSnapshotAndDiff(flags, requestedFor) ?: return
        notifyUpdate(changed)
        persist(flags, version)
    }

    /**
     * Whether a delivery requested under [requestedFor] is for a user identify() has
     * since replaced. Caller holds [snapshotLock], under which [contextGeneration] is
     * stable.
     *
     * A stale delivery is dropped SILENTLY, on purpose. It is the expected outcome
     * whenever identify() overlaps a poll or a stream frame, which is routine on a
     * user switch, and the new user's flags are already being served; a diagnostic
     * would fire on healthy apps and tell an operator nothing actionable.
     */
    private fun isStale(requestedFor: Long?): Boolean =
        requestedFor != null && requestedFor != contextGeneration

    /**
     * Swaps the snapshot under the write lock and returns the keys that changed with
     * the new [snapshotVersion], or null when the delivery is stale (see [isStale]) and
     * nothing was swapped.
     */
    private fun replaceSnapshotAndDiff(
        flags: Map<String, FlagValue>,
        requestedFor: Long?,
    ): Pair<List<String>, Long>? =
        snapshotLock.write {
            if (isStale(requestedFor)) return null
            val before = flagSnapshot
            flagSnapshot = flags.toMutableMap()
            diffKeys(before, flagSnapshot) to ++snapshotVersion
        }

    /**
     * initialize()'s load, from disk and from the initial fetch. Returns the new
     * [snapshotVersion], or null when an identify() has superseded it. Silent: the
     * initial load is reported by readiness, not as an update.
     */
    private fun loadSnapshotIfCurrent(flags: Map<String, FlagValue>, requestedFor: Long): Long? =
        snapshotLock.write {
            if (isStale(requestedFor)) return null
            flagSnapshot = flags.toMutableMap()
            ++snapshotVersion
        }

    internal fun addUpdateListener(listener: FlagUpdateListener): () -> Unit {
        updateListeners.add(listener)
        return { updateListeners.remove(listener) }
    }

    /** Called outside [snapshotLock] so a listener can read flags without deadlocking. */
    internal fun notifyUpdate(keys: List<String>) {
        if (keys.isEmpty()) return
        val delivered = java.util.Collections.unmodifiableList(keys)
        for (listener in updateListeners) {
            try {
                listener.onUpdate(delivered)
            } catch (e: Exception) {
                System.err.println("[featureflip] flag update listener threw: $e")
            }
        }
    }

    internal fun mergeSnapshot(delta: Map<String, FlagValue>): Map<String, FlagValue> =
        checkNotNull(mergeIfCurrent(delta, requestedFor = null)).first

    /**
     * Merges a stream delta and notifies. Returns the merged snapshot with its
     * [snapshotVersion], or null when the delta is stale (see [isStale]).
     */
    private fun mergeIfCurrent(delta: Map<String, FlagValue>, requestedFor: Long?): Pair<Map<String, FlagValue>, Long>? {
        val keys: List<String>
        val merged = snapshotLock.write {
            if (isStale(requestedFor)) return null
            val before = flagSnapshot.toMap()
            for ((key, value) in delta) {
                if (value.reason == "FLAG_REMOVED" && value.value == null) {
                    flagSnapshot.remove(key)
                } else {
                    flagSnapshot[key] = value
                }
            }
            keys = diffKeys(before, flagSnapshot)
            flagSnapshot.toMap() to ++snapshotVersion
        }
        notifyUpdate(keys)
        return merged
    }

    private fun handleForeground() {
        // The spec requires a new read window on every return to the foreground. The
        // window runs on the wall clock, which advances through deep sleep, so a long
        // sleep already ends it; this reset is a second defence that does not depend on
        // the clock, so the first read after coming back is always reported again.
        readRecorder?.resetWindow()
        // Only resume what handleBackground() paused. Android delivers onStart the
        // moment the observer registers when the app is already in the foreground, and
        // restarting the stream there would reconnect a stream that just connected.
        if (!pausedInBackground.getAndSet(false)) return
        backgroundScope.launch {
            // Started while holding the lock, not merely read under it: detachSources()
            // and stopFallbackPolling() null these under the write lock, so a source
            // close() released (#4901) or a retired fallback poller cannot be restarted
            // here, which would leave it running with nothing holding a reference.
            lock.read {
                val stream = streamingDataSource
                val poller = pollingDataSource
                duringForegroundRestartForTest?.invoke()
                stream?.start()
                poller?.start()
            }
            afterForegroundRestartForTest?.invoke()
        }
    }

    private fun handleBackground() {
        pausedInBackground.set(true)
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
            enqueueRead: ((SdkEvent) -> Unit)? = null,
        ): SharedFeatureflipCore {
            val httpClient = HttpClient(
                config.baseUrl,
                config.clientKey,
                callFactory,
                reportsEvaluations = config.sendEvaluationEvents,
                userAgent = SdkIdentity.userAgent(config.wrapperName, config.wrapperVersion),
            )
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
                enqueueRead = enqueueRead,
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
 * The user id events and evaluate carry for [context]: `user_id` if present and non-null,
 * else the `userId` alias. Context values are Any? since #2293, and
 * SdkEvent.userId is String?. `?.toString()` keeps an absent id null rather than the
 * literal "null", and carries a numeric id through as its decimal form.
 */
private fun userIdOf(context: Map<String, Any?>): String? = (context["user_id"] ?: context["userId"])?.toString()

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
        a.initTimeoutMs == b.initTimeoutMs &&
        a.sendEvaluationEvents == b.sendEvaluationEvents
    // wrapperName/wrapperVersion are deliberately excluded: they identify the caller
    // rather than change behavior (see FeatureflipConfig.wrapperName).
}
