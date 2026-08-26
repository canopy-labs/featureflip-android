package dev.featureflip.android

import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Batches analytics events and flushes them to the evaluation API.
 */
internal class EventProcessor(
    private val httpClient: HttpClient,
    private val flushIntervalMs: Long,
    batchSize: Int,
    private val maxBufferSize: Int = DEFAULT_MAX_BUFFER_SIZE,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    internal companion object {
        /**
         * Upper bound on buffered events.
         *
         * Deliberately lower than the 10,000 the server SDKs use. This is a mobile
         * client: memory is tighter and event volume is far lower. What matters for
         * cross-SDK parity is the rule — shed the OLDEST first — not the number.
         * Until #2456 there was no bound here at all, so a sustained outage grew the
         * buffer without limit, because a failed batch was always put back.
         */
        const val DEFAULT_MAX_BUFFER_SIZE = 1000
    }

    // Clamped: flush() loops on this, so a non-positive value would take nothing
    // per pass and spin on a non-empty buffer.
    private val batchSize: Int = if (batchSize >= 1) batchSize else 1

    private val lock = ReentrantLock()

    // A deque, not a list: a failed batch goes back at the FRONT, and the bound is
    // enforced from the front too.
    private val buffer = ArrayDeque<SdkEvent>()
    // Written by start() on the caller's thread, read from an IO thread once the
    // core moved shutdown off the caller — no happens-before edge without this.
    @Volatile
    private var flushJob: Job? = null

    /**
     * Wall-clock ms before which the batch-size trigger must not start a flush.
     *
     * A restored batch leaves the buffer at or above [batchSize], so without this
     * gate every subsequent [enqueue] would start another flush — turning a failing
     * endpoint into one request per recorded event, which is worse for the server
     * than losing the events. The periodic job is the retry vehicle; this only
     * suppresses the size trigger between its ticks.
     */
    private var nextAutoFlushAtMs = 0L

    /**
     * True while a size-triggered flush is running.
     *
     * The gate above is only armed once a flush has already FAILED, and the trigger
     * fires again long before the first round-trip returns — so without this latch
     * a burst of events still starts a flush each.
     */
    private var autoFlushInFlight = false

    /**
     * Coalescing state for the drain loop, all guarded by [lock].
     *
     * [autoFlushInFlight] above only ever guarded the SIZE trigger. Nothing stopped
     * the periodic job, an explicit `FeatureflipClient.flush()` and a size-triggered
     * flush from entering the loop together — two request streams against the
     * endpoint the backoff gate exists to protect, and a success in one clearing the
     * gate a failure in the other had just armed, which re-opens the
     * one-request-per-event behaviour outright (#2477).
     *
     * Generation counters rather than a bare flag: a waiter has to be able to tell
     * "the drain I was waiting for has finished" from "a later drain is running", or
     * it would sleep through its own completion.
     */
    private val drainDone = lock.newCondition()
    private var drainInFlight = false
    private var drainStarted = 0L
    private var drainFinished = 0L

    private var closed = false

    fun start() {
        flushJob = scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                flush()
            }
        }
    }

    fun enqueue(event: SdkEvent) {
        var dropped = 0
        val startFlush = lock.withLock {
            if (closed) return
            buffer.addLast(event)
            dropped = trimToBoundLocked()
            if (buffer.size >= batchSize &&
                !autoFlushInFlight &&
                System.currentTimeMillis() >= nextAutoFlushAtMs
            ) {
                autoFlushInFlight = true
                true
            } else {
                false
            }
        }
        if (dropped > 0) reportOverflow(dropped)
        if (startFlush) {
            scope.launch {
                try {
                    flush()
                } finally {
                    lock.withLock { autoFlushInFlight = false }
                }
            }
        }
    }

    /**
     * Flushes buffered events, one request per batch.
     *
     * Posting the whole buffer at once only became a risk once failures started
     * being kept: a backlog can now reach [maxBufferSize], and a body that size
     * invites a 413 — which is not retryable, so the path meant to preserve the
     * backlog would be the one that discarded it.
     *
     * At most one drain runs at a time. A caller arriving while one is already going
     * waits for it and returns — it does NOT start its own, and it does NOT return
     * early, because a caller that asked for a flush is asking for its events to be
     * sent. Matches the js/node SDKs, whose `flush()` has always returned the
     * in-flight promise.
     */
    fun flush() {
        val mine = lock.withLock {
            if (drainInFlight) {
                val waitingFor = drainStarted
                // await() can return spuriously, and a LATER drain must not be
                // mistaken for the one this caller is owed.
                while (drainFinished < waitingFor) drainDone.await()
                return
            }
            drainInFlight = true
            ++drainStarted
        }

        try {
            drain()
        } finally {
            lock.withLock {
                drainInFlight = false
                drainFinished = mine
                drainDone.signalAll()
            }
        }
    }

    /** The drain loop itself, callable when coalescing must be bypassed. */
    private fun drain() {
        while (true) {
            val batch = lock.withLock {
                if (buffer.isEmpty()) return
                List(minOf(batchSize, buffer.size)) { buffer.removeFirst() }
            }

            try {
                httpClient.postEvents(batch)
                lock.withLock { nextAutoFlushAtMs = 0L }
            } catch (e: Exception) {
                if (isRetryableSendFailure(e)) {
                    requeue(batch, e)
                    // Stop here. The batch is back at the head of the buffer this
                    // loop is draining, so continuing would re-send it at once and
                    // spin for as long as the endpoint stayed down.
                    return
                }
                // A 401/403 means the key was rejected and a 400 means the body is
                // malformed; both fail identically next time. Dropping shrinks the
                // buffer, so the loop still ends, and moving on means one poison
                // batch cannot block the backlog queued behind it.
                System.err.println(
                    "[featureflip] dropped ${batch.size} analytics event(s) the events endpoint rejected permanently: $e",
                )
                if (lock.withLock { closed }) return
            }
        }
    }

    /**
     * Whether the same batch could succeed if sent again.
     *
     * 5xx and 429 are the server asking for another attempt, and any other
     * [IOException] is a transport fault a later flush may get past. Anything else
     * — a rejected key, a malformed body, a serialization bug — would fail
     * identically forever, and keeping it would pin the buffer at its bound.
     */
    private fun isRetryableSendFailure(e: Exception): Boolean = when (e) {
        is EventSendException -> e.statusCode >= 500 || e.statusCode == 429
        is IOException -> true
        else -> false
    }

    /**
     * Returns a batch that failed to send to the FRONT of the buffer, so the next
     * flush retries it ahead of newer events and rough chronological order survives.
     */
    private fun requeue(batch: List<SdkEvent>, cause: Exception) {
        val dropped = lock.withLock {
            // Nothing will flush again after close, so buffering here would only
            // lose them later and less visibly.
            if (closed) return@withLock -1
            nextAutoFlushAtMs = System.currentTimeMillis() + flushIntervalMs
            for (i in batch.indices.reversed()) buffer.addFirst(batch[i])
            trimToBoundLocked()
        }

        if (dropped < 0) {
            System.err.println(
                "[featureflip] dropped ${batch.size} analytics event(s): shutting down and will not flush again: $cause",
            )
        } else {
            System.err.println(
                "[featureflip] failed to flush ${batch.size} analytics event(s); re-queued for the next flush " +
                    "($dropped dropped to stay within the buffer bound): $cause",
            )
        }
    }

    /** Sheds oldest-first until the buffer fits the bound. Caller holds [lock]. */
    private fun trimToBoundLocked(): Int {
        var dropped = 0
        while (buffer.size > maxBufferSize) {
            buffer.removeFirst()
            dropped++
        }
        return dropped
    }

    private fun reportOverflow(dropped: Int) {
        System.err.println(
            "[featureflip] event buffer is full; dropped $dropped of the oldest analytics event(s)",
        )
    }

    /**
     * Stops accepting new events. Cheap and non-blocking, unlike [stop].
     *
     * [stop] posts the final batch inline, so the core runs it on a background
     * scope rather than on whichever thread called `close()` (#2478). This is
     * the half that must stay on the caller's thread: without it `close()` would
     * return while the processor still accepted events and the periodic job was
     * still ticking, and an event arriving in that window could re-fire the size
     * trigger and post after shutdown.
     */
    fun markClosed() {
        // Cancelled here, not in stop(), so the periodic job cannot tick in the
        // window after the caller returned: such a tick flushes with closed
        // already true, and a transient failure there discards the batch.
        flushJob?.cancel()
        flushJob = null
        lock.withLock { closed = true }
    }

    fun stop() {
        // Set before the final flush so a failure inside it discards rather than
        // re-queueing into a buffer nothing will ever drain.
        markClosed()
        // drain(), not flush(): shutdown must never be the call that gets coalesced
        // away. If a periodic drain happens to be in flight, flush() would wait for
        // it and return, and anything enqueued after that loop's last look at the
        // buffer would be discarded unsent. Two drains overlapping is safe here
        // precisely because [closed] is already set, so neither can re-queue and
        // there is no backoff left to disarm.
        drain()
        lock.withLock { buffer.clear() }
    }

    // Test-only inspection. The buffer is private and its bound is only observable
    // from outside by what it holds, so the bound tests would otherwise have to go
    // through reflection.
    internal fun bufferedEventCount(): Int = lock.withLock { buffer.size }

    internal fun bufferedFlagKeys(): List<String> =
        lock.withLock { buffer.mapNotNull { it.flagKey } }
}
