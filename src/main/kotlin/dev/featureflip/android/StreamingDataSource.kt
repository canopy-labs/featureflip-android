package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import java.io.BufferedReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Connects to the evaluation API SSE stream for real-time flag updates.
 */
internal class StreamingDataSource(
    private val baseUrl: String,
    private val clientKey: String,
    context: Map<String, String>,
    private val onChange: (Map<String, FlagValue>) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    internal data class SSEEvent(val eventType: String, val data: String)

    companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_RETRIES = 5

        internal fun buildStreamUrl(
            baseUrl: String,
            clientKey: String,
            context: Map<String, String>,
        ): String {
            val contextJson = jacksonObjectMapper().writeValueAsBytes(context)
            val encodedContext = contextJson.toByteString().base64()
            val encodedKey = URLEncoder.encode(clientKey, "UTF-8")
            val encodedCtx = URLEncoder.encode(encodedContext, "UTF-8")
            return "$baseUrl/v1/client/stream?authorization=$encodedKey&context=$encodedCtx"
        }

        internal fun parseSSEEvent(lines: List<String>): SSEEvent? {
            var eventType: String? = null
            var data: String? = null
            for (line in lines) {
                when {
                    line.startsWith("event:") -> {
                        val value = line.removePrefix("event:").trimStart()
                        eventType = value
                    }
                    line.startsWith("data:") -> {
                        val value = line.removePrefix("data:").trimStart()
                        data = if (data != null) "$data\n$value" else value
                    }
                }
            }
            return eventType?.let { SSEEvent(it, data ?: "") }
        }
    }

    private val json = jacksonObjectMapper()
    private val lock = ReentrantLock()
    private var context: Map<String, String> = context
    private var job: Job? = null
    private var activeCall: Call? = null
    private var backoffMs = INITIAL_BACKOFF_MS
    private var retryCount = 0

    @Volatile
    var connectionId: String? = null
        private set

    internal var sseClient: OkHttpClient? = null
        private set

    private fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No read timeout for SSE
        .build()

    fun start() {
        job?.cancel()
        lock.withLock {
            retryCount = 0
            backoffMs = INITIAL_BACKOFF_MS
            sseClient?.dispatcher?.executorService?.shutdown()
            sseClient?.connectionPool?.evictAll()
            sseClient = createClient()
        }
        job = scope.launch { connectLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        lock.withLock {
            activeCall?.cancel()
            sseClient?.dispatcher?.executorService?.shutdown()
            sseClient?.connectionPool?.evictAll()
        }
        connectionId = null
    }

    fun updateContext(newContext: Map<String, String>) {
        lock.withLock { context = newContext }
        stop()
        start()
    }

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            val (currentRetryCount, currentBackoff) = lock.withLock { retryCount to backoffMs }
            if (currentRetryCount >= MAX_RETRIES) return

            try {
                connect()
            } catch (_: Exception) {
                if (!currentCoroutineContext().isActive) return
            }

            lock.withLock { retryCount++ }
            delay(currentBackoff)
            lock.withLock { backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS) }
        }
    }

    private fun connect() {
        val currentContext = lock.withLock { context.toMap() }
        val url = buildStreamUrl(baseUrl, clientKey, currentContext)

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .build()

        val client = lock.withLock { sseClient } ?: return
        val call = client.newCall(request)
        lock.withLock { activeCall = call }
        call.execute().use { response ->
            if (response.code != 200) return

            // Reset backoff on successful connection
            lock.withLock {
                backoffMs = INITIAL_BACKOFF_MS
                retryCount = 0
            }

            val reader = response.body?.source()?.inputStream()?.bufferedReader() ?: return
            readSseStream(reader)
        }
    }

    private fun readSseStream(reader: BufferedReader) {
        val lineBuffer = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                parseSSEEvent(lineBuffer)?.let { handleEvent(it) }
                lineBuffer.clear()
            } else {
                lineBuffer.add(line)
            }
        }
    }

    private fun handleEvent(event: SSEEvent) {
        if (event.eventType == "connection-ready") {
            try {
                val data: Map<String, String> = json.readValue(event.data)
                connectionId = data["connectionId"]
            } catch (_: Exception) {}
            return
        }
        if (event.eventType != "flags-updated") return
        try {
            val response: EvaluateResponse = json.readValue(event.data)
            onChange(response.flags)
        } catch (_: Exception) {
            // Ignore parse errors
        }
    }
}
