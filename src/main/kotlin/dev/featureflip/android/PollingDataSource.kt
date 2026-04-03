package dev.featureflip.android

import kotlinx.coroutines.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Periodically fetches evaluated flags via HTTP polling.
 */
internal class PollingDataSource(
    private val httpClient: HttpClient,
    context: Map<String, String>,
    private val intervalMs: Long,
    private val onChange: (Map<String, FlagValue>) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val lock = ReentrantLock()
    private var context: Map<String, String> = context
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            pollOnce()
            while (isActive) {
                delay(intervalMs)
                pollOnce()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun updateContext(newContext: Map<String, String>) {
        lock.withLock { context = newContext }
    }

    internal fun pollOnce() {
        val currentContext = lock.withLock { context.toMap() }
        try {
            val result = httpClient.evaluate(currentContext)
            onChange(result.flags)
        } catch (_: Exception) {
            // Silent — don't crash on network errors
        }
    }
}
