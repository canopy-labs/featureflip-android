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
import java.util.concurrent.ThreadLocalRandom

/**
 * Connects to the evaluation API SSE stream for real-time flag updates.
 */
internal class StreamingDataSource(
    private val baseUrl: String,
    private val clientKey: String,
    context: Map<String, Any?>,
    // Both delivery callbacks receive the context generation the delivering connection
    // was opened for. A frame read off the previous user's connection after identify()
    // carries the old generation, and the core drops it (#4886).
    private val onChange: (Map<String, FlagValue>, Long) -> Unit,
    // Full snapshot the server sends first on every (re)connect -> apply as a REPLACE.
    // Required, not defaulting to onChange: an omitted snapshot handler would silently
    // merge the connect snapshot and resurrect flags deleted during the outage (#1873),
    // and no dispatch test can see that -- they all pass it explicitly.
    private val onSnapshot: (Map<String, FlagValue>, Long) -> Unit,
    // Invoked ONCE per outage, when the stream has failed MAX_RETRIES times, so the
    // core can start polling ALONGSIDE this still-retrying stream. Never a terminal
    // give-up: the connect loop keeps going at the capped backoff (#3075).
    private val onFallbackToPolling: (() -> Unit)? = null,
    // Invoked when a stream that had fallen back delivers a frame again, so the core
    // can retire the fallback poller.
    private val onStreamRecovered: (() -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val initialBackoffMs: Long = INITIAL_BACKOFF_MS,
    // Sent on the stream connect (#4141). The stream uses its own OkHttpClient, so it
    // shares nothing with HttpClient; the core passes the same value to both.
    private val userAgent: String = SdkIdentity.DEFAULT,
    generation: Long = 0L,
) {
    internal data class SSEEvent(val eventType: String, val data: String)

    companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_RETRIES = 5

        /**
         * Returns a value in [d/2, d] to de-correlate reconnects across many SDK
         * instances (thundering-herd avoidance after a shared outage).
         *
         * Applied to EVERY reconnect, including the first. The drops this absorbs
         * are fleet-wide — one edge event severs every stream at once (#2457) — so
         * every client re-enters the backoff together. Delaying by the raw ladder
         * value there republished the drop's own synchronisation as a reconnect
         * spike one backoff later (#2508). The band stays strictly positive, so a
         * stream that fails immediately still cannot busy-loop.
         */
        internal fun withJitter(delayMs: Long): Long {
            if (delayMs <= 0) return delayMs
            val half = delayMs / 2
            return half + ThreadLocalRandom.current().nextLong(half + 1)
        }

        internal fun buildStreamUrl(
            baseUrl: String,
            clientKey: String,
            context: Map<String, Any?>,
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
    private var context: Map<String, Any?> = context
    private var generation: Long = generation
    private var job: Job? = null
    private var activeCall: Call? = null
    private var backoffMs = initialBackoffMs
    private var retryCount = 0

    // Bumped under [lock] by every start() and stop(), and captured by the connect loop
    // that start() launches. A connection whose epoch is no longer current belongs to a
    // run that was stopped or replaced, which the context generation cannot tell: a
    // foreground restart replaces the connection without changing it (#4946). Within one
    // run, connections are sequential on the loop's coroutine, so a backoff reconnect
    // needs no bump: the previous connection has finished reading before it opens.
    private var epoch = 0L

    // True between arming the polling fallback and the next delivered frame. Gates
    // both callbacks so each fires once per outage rather than once per retry.
    //
    // Deliberately NOT cleared by start(): that is reachable from handleForeground()
    // and updateContext() while a fallback poller is live, and clearing it there
    // would lose the only record that a poller is waiting to be retired -- leaving it
    // running beside a recovered stream forever, which is the defect this fixes.
    private var fallbackActive = false

    // Written under [lock], together with the epoch check, so stop() cannot null it
    // between a late connection-ready's check and its assignment (#4946).
    @Volatile
    var connectionId: String? = null
        private set

    /** The epoch of the current run; see [epoch]. */
    internal val connectionEpoch: Long get() = lock.withLock { epoch }

    internal var sseClient: OkHttpClient? = null
        private set

    /** Test seam: runs at the top of every connect attempt, before it opens anything. */
    internal var beforeConnectForTest: (() -> Unit)? = null

    /** Test seam: runs in start() after the epoch is bumped, before the loop launches. */
    internal var beforeLaunchForTest: (() -> Unit)? = null

    /** Whether the connect loop is live: started and not since stopped. */
    internal val isRunning: Boolean get() = job?.isActive == true

    private fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No read timeout for SSE
        .build()

    fun start() {
        job?.cancel()
        val runEpoch = lock.withLock {
            // Cancelling the job does not end a connection that is already reading: the
            // SSE read blocks in I/O, which coroutine cancellation cannot interrupt. Left
            // open, the old connection outlives its replacement and stop(), which only
            // knows the newest call, never closes it.
            activeCall?.cancel()
            activeCall = null
            retryCount = 0
            backoffMs = initialBackoffMs
            sseClient?.dispatcher?.executorService?.shutdown()
            sseClient?.connectionPool?.evictAll()
            sseClient = createClient()
            // The ID named the connection just cancelled; the new one sends its own.
            connectionId = null
            ++epoch
        }
        beforeLaunchForTest?.invoke()
        job = scope.launch { connectLoop(runEpoch) }
    }

    fun stop() {
        job?.cancel()
        job = null
        lock.withLock {
            epoch++
            activeCall?.cancel()
            sseClient?.dispatcher?.executorService?.shutdown()
            sseClient?.connectionPool?.evictAll()
            connectionId = null
        }
    }

    /**
     * Reconnects for [newContext]. Ignored when [generation] is older than the one
     * already held, for the reason given on [PollingDataSource.updateContext], and
     * when it is EQUAL: a source is built with the context and generation current at
     * that moment, so an equal generation means identify()'s swap happened before this
     * source started and it already carries [newContext]. Reconnecting would only drop
     * a healthy connection. An ignored call does not reconnect either.
     */
    fun updateContext(newContext: Map<String, Any?>, generation: Long) {
        lock.withLock {
            if (generation <= this.generation) return
            context = newContext
            this.generation = generation
        }
        stop()
        start()
    }

    private suspend fun connectLoop(runEpoch: Long) {
        // The epoch as well as the job: start() launches the loop after leaving the lock,
        // and the core can run stop() or another start() in between, so the job this
        // loop runs in may be one nothing will cancel. Without the exit it would retry
        // forever, opening nothing, and arm the polling fallback beside a stopped stream.
        fun stale() = lock.withLock { epoch != runEpoch }
        while (currentCoroutineContext().isActive && !stale()) {
            try {
                connect(runEpoch)
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive || stale()) return
                logStreamFailure(e.javaClass.name, "stream connection failed, will retry: $e")
            }
            if (!currentCoroutineContext().isActive || stale()) return

            // Read the ladder AFTER connect(), not before: a healthy connection can
            // last hours and resets the ladder from inside, so a value captured up
            // front would make the first reconnect after it wait the pre-outage delay
            // — up to the 30s cap — instead of the base.
            val (armFallback, currentBackoff) = lock.withLock {
                retryCount++
                val arm = retryCount >= MAX_RETRIES && !fallbackActive
                if (arm) fallbackActive = true
                // The ladder state (backoffMs) stays un-jittered so the doubling is
                // exact; only the scheduled wait is scattered.
                val current = backoffMs
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                arm to current
            }

            // The fallback is ADDITIVE, never terminal (#3075). Polling covers the
            // outage while this loop keeps retrying the stream underneath at the
            // capped backoff, and the next config frame retires the poller. Returning
            // here instead left the app polling — and blind to real-time updates, kill
            // switches included — until it was restarted, after only ~31s of
            // unreachability.
            //
            // Armed on the failure itself rather than at the top of the next
            // iteration, so the poller starts covering the outage ~15s in rather than
            // after the fifth backoff has also elapsed (~31s) — matching flutter and
            // the js core.
            if (armFallback) onFallbackToPolling?.invoke()

            delay(withJitter(currentBackoff))
        }
    }

    // Set once an outage has been logged, cleared when config is next delivered, so an
    // offline device logs the transition to failing, not every retry.
    @Volatile
    private var lastLoggedFailure: String? = null

    /**
     * A failed connect is expected during an outage and retried, so it gets one line per
     * outage and kind of failure (HTTP status or exception class) rather than an alarm per
     * retry. It is what tells a revoked key (HTTP 401/403) from an unreachable host.
     */
    private fun logStreamFailure(kind: String, message: String) {
        if (kind == lastLoggedFailure) return
        lastLoggedFailure = kind
        System.err.println("[featureflip] $message")
    }

    private fun connect(runEpoch: Long) {
        beforeConnectForTest?.invoke()
        // Read together: every frame this connection delivers is tagged with the
        // generation its context belongs to.
        val (currentContext, connectedFor) = lock.withLock { context.toMap() to generation }
        val url = buildStreamUrl(baseUrl, clientKey, currentContext)

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("User-Agent", userAgent)
            .build()

        // Checked and registered in one critical section (#4947). Cancelling the job
        // does not stop a connect already past its isActive checks, so a stop() or
        // restart that ran since this run began is caught here. One that runs after
        // the call is registered cancels it, and execute() then throws instead of
        // opening the socket.
        val call = lock.withLock {
            if (epoch != runEpoch) return
            val client = sseClient ?: return
            client.newCall(request).also { activeCall = it }
        }
        call.execute().use { response ->
            if (response.code != 200) {
                logStreamFailure("HTTP ${response.code}", "stream rejected: HTTP ${response.code}, will retry")
                return
            }

            val reader = response.body.source().inputStream().bufferedReader()
            readSseStream(reader, connectedFor, runEpoch)
        }
    }

    private fun readSseStream(reader: BufferedReader, connectedFor: Long, runEpoch: Long) {
        val lineBuffer = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                parseSSEEvent(lineBuffer)?.let {
                    handleEvent(it, connectedFor, runEpoch)
                    // AFTER the store has been updated, never before: retiring the
                    // fallback poller is what this signals, and a poller retired one
                    // frame early can still land an older whole-store replace on top
                    // of the snapshot just applied.
                    if (it.eventType == "flags-updated") onConfigDelivered()
                }
                lineBuffer.clear()
            } else {
                lineBuffer.add(line)
            }
        }
    }

    /**
     * DELIVERED CONFIG — not merely an accepted socket — is what proves the stream
     * healthy, and it is the condition the rest of the fleet resets on (js and java on
     * `sync`, go on its first complete frame, which for the server stream *is* `sync`).
     * Resetting on the 200 instead let an accept-then-close server clear the counter
     * every cycle, so the retry budget was never exhausted, the polling fallback could
     * never arm, and the app saw nothing at all for the duration of such an outage
     * (#3074).
     *
     * Keyed on `flags-updated` rather than on any frame because the client stream's
     * FIRST frame is `connection-ready`, a ~40-byte handshake carrying no config: a
     * server that accepts, greets and dies would otherwise reset the budget forever
     * and re-open exactly the hole above. Deliberately still counted when the payload
     * fails to parse — the stream itself is demonstrably up, the store keeps its
     * last-known-good, and the parse failure is reported on its own path.
     *
     * Recovery is signalled from HERE rather than from [connectLoop]: [connect]
     * blocks in [readSseStream] for the whole lifetime of a healthy stream, so a reap
     * on its return would leave the poller alive that entire time, its periodic
     * whole-store replaces reverting the deltas this stream applies.
     */
    private fun onConfigDelivered() {
        lastLoggedFailure = null
        val recovered = lock.withLock {
            retryCount = 0
            backoffMs = initialBackoffMs
            val wasFallenBack = fallbackActive
            fallbackActive = false
            wasFallenBack
        }
        if (recovered) onStreamRecovered?.invoke()
    }

    internal fun handleEvent(
        event: SSEEvent,
        connectedFor: Long = lock.withLock { generation },
        runEpoch: Long = connectionEpoch,
    ) {
        if (event.eventType == "connection-ready") {
            try {
                val data: Map<String, String> = json.readValue(event.data)
                // A late connection-ready on a connection that a stop(), a restart or
                // identify()'s reconnect has since replaced must not overwrite the ID of
                // the current one, or put back the one stop() cleared. identify() always
                // reconnects through stop() and start(), so the epoch covers it too.
                lock.withLock {
                    if (runEpoch == epoch) connectionId = data["connectionId"]
                }
            } catch (e: Exception) {
                System.err.println("[featureflip] ignoring unparseable connection-ready event: $e")
            }
            return
        }
        if (event.eventType != "flags-updated") return
        try {
            val response: EvaluateResponse = json.readValue(event.data)
            // The connect-time snapshot is marked `full: true` (#1873) -> REPLACE the
            // store (drops flags deleted during the outage). Deltas omit it -> MERGE.
            // Keyed off the explicit marker, not event order, so a delta racing ahead
            // of the snapshot can't be mistaken for a full replace.
            if (response.full) {
                onSnapshot(response.flags, connectedFor)
            } else {
                onChange(response.flags, connectedFor)
            }
        } catch (e: Exception) {
            // The store keeps its last-known-good flags; say so rather than drop it.
            System.err.println("[featureflip] ignoring unparseable flags-updated event: $e")
        }
    }
}
