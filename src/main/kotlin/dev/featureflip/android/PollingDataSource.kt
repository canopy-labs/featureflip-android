package dev.featureflip.android

import kotlinx.coroutines.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Periodically fetches evaluated flags via HTTP polling.
 */
internal class PollingDataSource(
    private val httpClient: HttpClient,
    context: Map<String, Any?>,
    private val intervalMs: Long,
    // Receives each poll's flags with the context generation the poll was REQUESTED
    // for, so the core can drop a response that identify() has since superseded (#4886).
    private val onChange: (Map<String, FlagValue>, Long) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    generation: Long = 0L,
) {
    private val lock = ReentrantLock()
    private var context: Map<String, Any?> = context
    private var generation: Long = generation
    private var job: Job? = null

    // Cancelling the job cannot interrupt an in-flight pollOnce: it is not a suspend
    // function, so httpClient.evaluate() runs to completion and onChange() fires
    // regardless of cancellation. That used to be harmless, because a poller was only
    // ever stopped alongside everything else — but #3075 retires the fallback poller
    // while the recovered stream is live, so a response still on the wire would
    // REPLACE the store on top of the stream's fresher connect snapshot. Any flag that
    // changed between the two server-side evaluations would revert, and because the
    // stream already delivered that change IN the snapshot, later deltas would merge
    // on top of the stale value and never correct it.
    @Volatile
    private var stopped = false

    fun start() {
        job?.cancel()
        stopped = false
        job = scope.launch {
            pollOnce()
            while (isActive) {
                delay(intervalMs)
                pollOnce()
            }
        }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
    }

    /**
     * Points later polls at [newContext]. Ignored when [generation] is older than the
     * one already held: identify() retargets the sources after leaving the snapshot
     * lock, under a lock two identify() calls can hold together, so the older one can
     * arrive second. Applied, it would leave every later poll asking for a user the
     * core has moved past, all of them dropped as stale.
     */
    fun updateContext(newContext: Map<String, Any?>, generation: Long) {
        lock.withLock {
            if (generation < this.generation) return
            context = newContext
            this.generation = generation
        }
    }

    internal fun pollOnce() {
        // Read together, so the generation a response is tagged with is the one its
        // request was made for.
        val (currentContext, requestedFor) = lock.withLock { context.toMap() to generation }
        try {
            val result = httpClient.evaluate(currentContext)
            if (stopped) return
            onChange(result.flags, requestedFor)
        } catch (_: Exception) {
            // Silent — don't crash on network errors
        }
    }
}
