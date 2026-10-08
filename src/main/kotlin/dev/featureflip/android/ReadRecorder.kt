package dev.featureflip.android

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The clock [ReadRecorder] times its window on, in milliseconds. The default is the wall
 * clock, which is not monotonic: a user can set it back or forward.
 *
 * A `fun interface` rather than `() -> Long`: `Function0<Long>.invoke()` returns a boxed
 * Long, which is a heap allocation on every flag read.
 */
internal fun interface WindowClock {
    fun nowMs(): Long
}

/**
 * Turns flag reads into `Evaluation` events, at most one per (flag, variation, user)
 * per window.
 *
 * A client SDK is sent every client-visible flag at startup, so "the server sent it"
 * says nothing about whether any code still reads it. These events are the signal the
 * archive guard and staleness detection use instead. That is why a read of a key the
 * snapshot lacks is recorded too, with no variation: an old build still asking for an
 * archived flag has to stay visible.
 *
 * This runs inside every typed accessor, so a REPEAT read (one already recorded this
 * window) must cost next to nothing. It is a clock read, a volatile read and three
 * concurrent-map lookups: no allocation, no lock, no I/O, no logging. Only the first
 * read of a (flag, variation, user) in a window allocates anything or calls [enqueue].
 */
internal class ReadRecorder(
    private val enqueue: (SdkEvent) -> Unit,
    private val timestamp: () -> String,
    private val windowMs: Long = WINDOW_MS,
    // Wall clock, because it keeps advancing through deep sleep. A read made after a long
    // sleep starts a new window even if the app never came back to the foreground, which
    // covers background work such as WorkManager jobs and FCM handlers. (System.nanoTime
    // is CLOCK_MONOTONIC on Android and stops in deep sleep.) A user changing the clock
    // can only cost extra events: set back, it starts a new window (see currentWindow);
    // set forward, it ends the current one early.
    private val clock: WindowClock = WindowClock { System.currentTimeMillis() },
) {
    internal companion object {
        /**
         * One hour, fixed, independent of the flush interval. Rollups are hourly buckets
         * and the archive guard looks back 24 hours, so re-reporting hourly loses no
         * resolution, and a long session sends at most one event per
         * (flag, variation, user) per hour. The flush interval still sends first reads
         * within seconds.
         */
        const val WINDOW_MS = 3_600_000L

        /** Stands in for a null variation or user, so lookups need no wrapper object. */
        private const val NONE = ""
    }

    /** One window's reads, user -> flag -> variations. Replaced whole, never cleared. */
    private class Window(val startedAtMs: Long) {
        val reads = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableSet<String>>>()
    }

    private val window = AtomicReference(Window(clock.nowMs()))

    /** When the live window opened, by [clock]. For tests. */
    internal val windowStartedAtMs: Long get() = window.get().startedAtMs

    fun record(flagKey: String, variation: String?, userId: String?) {
        val w = currentWindow()
        val userKey = userId ?: NONE
        val variationKey = variation ?: NONE

        // Repeat path: plain lookups only. Not computeIfAbsent, which locks the bin even
        // when the key is present on some JDKs and Android runtimes.
        var byFlag = w.reads[userKey]
        var variations = byFlag?.get(flagKey)
        if (variations != null && variations.contains(variationKey)) return

        // First read of this (flag, variation, user) in the window, or a race to be it.
        if (byFlag == null) {
            val created = ConcurrentHashMap<String, MutableSet<String>>()
            byFlag = w.reads.putIfAbsent(userKey, created) ?: created
        }
        if (variations == null) {
            val created: MutableSet<String> = ConcurrentHashMap.newKeySet()
            variations = byFlag.putIfAbsent(flagKey, created) ?: created
        }
        // add() is the arbiter: of several threads racing here, exactly one sees true.
        if (!variations.add(variationKey)) return

        enqueue(
            SdkEvent(
                type = SdkEventType.Evaluation,
                flagKey = flagKey,
                timestamp = timestamp(),
                userId = userId,
                variation = variation,
            ),
        )
    }

    /**
     * Starts a new window now, whatever the clock says. Called by the core on every
     * return to the foreground, as the spec requires. The wall clock already ends a
     * window that a long sleep has outlasted; this is a second defence that does not
     * depend on the clock at all, so the first read after the app comes back is always
     * reported again. Off the read path.
     */
    fun resetWindow() {
        window.set(Window(clock.nowMs()))
    }

    /**
     * The live window, rolling it over once [windowMs] has passed since it opened.
     *
     * A rollover swaps in a fresh root instead of clearing the old one, so a reader
     * still holding the old window sees a whole structure. At worst, its read counts
     * in the old window and is reported again in the new one. Of several threads that
     * see an expired window, one wins the CAS and the rest adopt its window.
     */
    private fun currentWindow(): Window {
        val w = window.get()
        val now = clock.nowMs()
        val elapsed = now - w.startedAtMs
        // elapsed < 0 means the clock was set back. A new window is the safe direction:
        // it over-counts (one extra event), never under-counts.
        if (elapsed >= 0 && elapsed < windowMs) return w
        val fresh = Window(now)
        return if (window.compareAndSet(w, fresh)) fresh else window.get()
    }
}
