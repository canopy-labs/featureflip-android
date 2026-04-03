package dev.featureflip.android

import kotlinx.coroutines.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Batches analytics events and flushes them to the evaluation API.
 */
internal class EventProcessor(
    private val httpClient: HttpClient,
    private val flushIntervalMs: Long,
    private val batchSize: Int,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val lock = ReentrantLock()
    private val buffer = mutableListOf<SdkEvent>()
    private var flushJob: Job? = null

    fun start() {
        flushJob = scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                flush()
            }
        }
    }

    fun enqueue(event: SdkEvent) {
        val eventsToFlush: List<SdkEvent>?
        lock.withLock {
            buffer.add(event)
            eventsToFlush = if (buffer.size >= batchSize) {
                val batch = buffer.toList()
                buffer.clear()
                batch
            } else {
                null
            }
        }
        eventsToFlush?.let { events ->
            scope.launch {
                try {
                    httpClient.postEvents(events)
                } catch (_: Exception) {
                    lock.withLock { buffer.addAll(0, events) }
                }
            }
        }
    }

    fun flush() {
        val events = lock.withLock {
            if (buffer.isEmpty()) return
            val batch = buffer.toList()
            buffer.clear()
            batch
        }
        try {
            httpClient.postEvents(events)
        } catch (_: Exception) {
            lock.withLock { buffer.addAll(0, events) }
        }
    }

    fun stop() {
        flushJob?.cancel()
        flushJob = null
        flush()
    }
}
