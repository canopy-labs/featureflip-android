package dev.featureflip.android

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for evaluation API requests.
 */
internal class HttpClient(
    private val baseUrl: String,
    private val clientKey: String,
    callFactory: Call.Factory? = null,
    /**
     * Whether this SDK reports the flags its app reads (`sendEvaluationEvents`). When
     * true, evaluate and identify tell the server so, and it stops recording every flag
     * it serves on this client's behalf. Defaults to false: a construction site that
     * forgets it over-counts reads, which is the safe direction. Under-counting is what
     * would let the archive guard pass a flag that live code still reads.
     */
    reportsEvaluations: Boolean = false,
) {
    internal companion object {
        const val REPORTS_EVALUATIONS_HEADER = "X-Featureflip-Reports-Evaluations"
    }

    private val json: ObjectMapper = jacksonObjectMapper()
    private val mediaType = "application/json".toMediaType()

    // Only on evaluate and identify, the two calls the server records served flags for.
    // Never on events (those ARE the reads), and never on the SSE stream, which
    // StreamingDataSource opens without this class. Built once, not per request.
    private val readReportingHeaders: Map<String, String> =
        if (reportsEvaluations) mapOf(REPORTS_EVALUATIONS_HEADER to "1") else emptyMap()

    private val defaultClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val callFactory: Call.Factory = callFactory ?: defaultClient

    fun evaluate(context: Map<String, Any?>, timeoutMs: Long? = null): EvaluateResponse {
        return post("/v1/client/evaluate", mapOf("context" to context), timeoutMs, extraHeaders = readReportingHeaders)
    }

    fun identify(context: Map<String, Any?>, connectionId: String? = null): EvaluateResponse {
        // Merged, not replaced: identify already carries X-Connection-Id so the server
        // can re-target this client's SSE stream.
        val headers = if (connectionId != null) {
            readReportingHeaders + ("X-Connection-Id" to connectionId)
        } else {
            readReportingHeaders
        }
        return post("/v1/client/identify", mapOf("context" to context), extraHeaders = headers)
    }

    fun postEvents(events: List<SdkEvent>) {
        val body = RecordEventsRequest(events)
        val requestBody = json.writeValueAsString(body).toRequestBody(mediaType)
        val request = Request.Builder()
            // The CLIENT surface, like every other call this SDK makes. /v1/sdk/events accepts
            // server keys only, so it answered this one with a 401 — which the event processor
            // classifies as permanent, discarding every batch (#3069).
            .url("$baseUrl/v1/client/events")
            .header("Content-Type", "application/json")
            .header("Authorization", clientKey)
            .post(requestBody)
            .build()

        callFactory.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Typed rather than a bare IOException: the flush needs the status
                // to decide whether keeping the batch could ever help (#2456).
                throw EventSendException(response.code)
            }
        }
    }

    private inline fun <reified T> post(
        path: String,
        body: Any,
        timeoutMs: Long? = null,
        extraHeaders: Map<String, String>? = null,
    ): T {
        val requestBody = json.writeValueAsString(body).toRequestBody(mediaType)
        val requestBuilder = Request.Builder()
            .url("$baseUrl$path")
            .header("Content-Type", "application/json")
            .header("Authorization", clientKey)
            .post(requestBody)

        extraHeaders?.forEach { (key, value) -> requestBuilder.header(key, value) }

        val request = requestBuilder.build()

        val client = if (timeoutMs != null) {
            (callFactory as? OkHttpClient)?.newBuilder()
                ?.callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                ?.build()
                ?: callFactory
        } else {
            callFactory
        }

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val responseBody = response.body.string()
            return json.readValue(responseBody)
        }
    }
}
