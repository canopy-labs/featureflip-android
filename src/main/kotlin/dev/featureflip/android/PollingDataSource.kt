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
    private val onChange: (Map<String, FlagValue>) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val lock = ReentrantLock()
    private var context: Map<String, Any?> = context
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

    fun updateContext(newContext: Map<String, Any?>) {
        lock.withLock { context = newContext }
    }

    internal fun pollOnce() {
        val currentContext = lock.withLock { context.toMap() }
        try {
            val result = httpClient.evaluate(currentContext)
            if (stopped) return
            onChange(result.flags)
        } catch (_: Exception) {
            // Silent — don't crash on network errors
        }
    }
}
