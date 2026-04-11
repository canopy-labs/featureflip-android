package dev.featureflip.android

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main public API for the Featureflip Android SDK.
 *
 * This is a client-side SDK — flags are evaluated server-side and only the
 * values are returned. Obtain a client via the static factory [get]; direct
 * instantiation is not supported. Multiple [get] calls with the same SDK key
 * return handles sharing one underlying [SharedFeatureflipCore] (refcounted);
 * the shared core shuts down when the last handle is closed.
 *
 * This makes the SDK safe under any lifetime pattern — DI containers, scoped
 * or transient registration, per-Activity instantiation, etc. all resolve to
 * one SSE streaming connection and one flag store per client key.
 *
 * Call [initialize] to load initial flags and start background workers, then
 * read values via [boolVariation], [stringVariation], etc. When done, call
 * [close] to decrement the refcount.
 */
class FeatureflipClient private constructor(
    private val core: SharedFeatureflipCore,
) {
    private val disposed = AtomicBoolean(false)

    /** Whether the underlying shared core has been initialized. */
    val isInitialized: Boolean
        get() = core.isInitialized

    /**
     * Initializes the underlying shared core: loads disk cache, fetches flags,
     * starts streaming/polling, and registers the lifecycle observer. Safe to
     * call more than once — the first invocation does the work and subsequent
     * calls are no-ops. Also safe to call from a handle acquired on an
     * already-initialized shared core.
     */
    fun initialize() {
        core.initialize()
    }

    /**
     * Decrements the refcount on the shared core. When the last handle for a
     * given client key is closed, the shared core stops streaming/polling,
     * removes the lifecycle observer, flushes events, and removes itself from
     * the factory cache. Double-close on the same handle is a no-op.
     */
    fun close() {
        if (disposed.compareAndSet(false, true)) {
            core.release()
        }
    }

    // -- Variation methods --

    fun boolVariation(key: String, defaultValue: Boolean): Boolean =
        core.boolVariation(key, defaultValue)

    fun stringVariation(key: String, defaultValue: String): String =
        core.stringVariation(key, defaultValue)

    fun numberVariation(key: String, defaultValue: Double): Double =
        core.numberVariation(key, defaultValue)

    fun jsonVariation(key: String, defaultValue: Any?): Any? =
        core.jsonVariation(key, defaultValue)

    // -- Identify / track / flush --

    fun identify(context: Map<String, String>) = core.identify(context)

    fun track(eventName: String, metadata: Map<String, Any?>? = null) =
        core.track(eventName, metadata)

    fun flush() = core.flush()

    // -- Internal test helpers (package-private access via Kotlin internal) --

    internal fun allFlags(): Map<String, FlagValue> = core.allFlags()

    internal fun mergeSnapshot(delta: Map<String, FlagValue>): Map<String, FlagValue> =
        core.mergeSnapshot(delta)

    internal fun startDataSource() = core.startDataSource()

    internal fun startPolling() = core.startPolling()

    /** Exposed for tests that simulate lifecycle transitions. */
    internal val lifecycleObserver: LifecycleObserver?
        get() = core.lifecycleObserver

    companion object {
        const val VERSION = "2.0.0"

        /**
         * Process-wide cache of shared cores keyed by client SDK key. Uses
         * [ConcurrentHashMap] so concurrent `get()` calls from multiple
         * threads share one core.
         */
        private val liveCores = ConcurrentHashMap<String, SharedFeatureflipCore>()

        /**
         * Returns a client for the given config. The first call with a given
         * client key constructs and registers a shared core; subsequent calls
         * with the same key return a new handle pointing at the cached core.
         * When the last handle for a key is closed, the core shuts down and
         * is removed from the cache.
         *
         * The [config] is honored only on the first call for a given client
         * key. Subsequent callers that pass meaningfully different options
         * (baseUrl, streaming, intervals, timeouts) will see a warning logged
         * via [System.err]; the cached core's config is preserved.
         *
         * Concurrent calls to `get(sameKey)` from multiple threads result in
         * exactly one core construction — the losing thread's speculative core
         * is released before being returned.
         */
        @JvmStatic
        @JvmOverloads
        fun get(config: FeatureflipConfig, callFactory: Call.Factory? = null): FeatureflipClient {
            require(config.clientKey.isNotBlank()) { "clientKey is required" }

            // Retry loop handles the race where a cached core is found but has
            // already begun shutting down (refcount hit 0 between lookup and
            // tryAcquire). Each iteration makes progress: acquire & return,
            // drop stale entry and retry, or successfully add a new core.
            while (true) {
                val existing = liveCores[config.clientKey]
                if (existing != null) {
                    if (existing.tryAcquire()) {
                        if (!configsEqual(existing.config, config)) {
                            System.err.println(
                                "[featureflip] FeatureflipClient.get called with different " +
                                    "options for client key already in use. The cached instance's " +
                                    "options are preserved; the passed options are ignored.",
                            )
                        }
                        return FeatureflipClient(existing)
                    }
                    // Stale entry — core shut down between lookup and acquire.
                    liveCores.remove(config.clientKey, existing)
                    continue
                }

                val newCore = SharedFeatureflipCore.create(config, callFactory)
                val previous = liveCores.putIfAbsent(config.clientKey, newCore)
                if (previous == null) {
                    newCore.setOwningMap(liveCores, config.clientKey)
                    return FeatureflipClient(newCore)
                }
                // Another thread won the race — release our speculative core and retry.
                newCore.release()
            }
        }

        /**
         * Creates a no-network test client with static flag overrides. Not
         * registered in the factory cache — each call returns an independent
         * instance with its own snapshot and no background workers.
         */
        @JvmStatic
        fun forTesting(overrides: Map<String, Any?>): FeatureflipClient {
            return FeatureflipClient(SharedFeatureflipCore.createForTesting(overrides))
        }

        /**
         * Current number of live shared cores in the factory cache.
         *
         * **Diagnostic only** — not part of the stable API surface. Exposed
         * as `public` so cross-module integration tests can observe factory
         * state, but may change or be removed in a minor version.
         */
        @JvmStatic
        fun debugLiveCoreCount(): Int = liveCores.size

        /**
         * Returns the shared core's current refcount for the given client
         * key, or 0 if no core is cached for that key.
         *
         * **Diagnostic only** — not part of the stable API surface. Exposed
         * as `public` so cross-module integration tests can observe factory
         * state, but may change or be removed in a minor version.
         */
        @JvmStatic
        fun debugRefCount(clientKey: String): Int =
            liveCores[clientKey]?.debugRefCount ?: 0

        /**
         * Resets the factory cache. For test isolation only — forces shutdown
         * of each currently-cached core. Any handles callers still hold will
         * become no-ops on [close] (their refcount decrement is absorbed by
         * the already-shut-down core).
         *
         * **Test-only** — not part of the stable API surface. Exposed as
         * `public` so cross-module integration tests can reset state, but
         * may change or be removed in a minor version.
         */
        @JvmStatic
        fun resetForTesting() {
            val cores = liveCores.values.toList()
            liveCores.clear()
            for (core in cores) {
                while (core.debugRefCount > 0 && !core.debugIsShutDown) {
                    core.release()
                }
            }
        }
    }
}
