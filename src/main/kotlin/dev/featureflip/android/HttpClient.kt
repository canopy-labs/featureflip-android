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
) {
    private val json: ObjectMapper = jacksonObjectMapper()
    private val mediaType = "application/json".toMediaType()

    private val defaultClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val callFactory: Call.Factory = callFactory ?: defaultClient

    fun evaluate(context: Map<String, String>, timeoutMs: Long? = null): EvaluateResponse {
        return post("/v1/client/evaluate", mapOf("context" to context), timeoutMs)
    }

    fun identify(context: Map<String, String>, connectionId: String? = null): EvaluateResponse {
        return post("/v1/client/identify", mapOf("context" to context), extraHeaders = connectionId?.let { mapOf("X-Connection-Id" to it) })
    }

    fun postEvents(events: List<SdkEvent>) {
        val body = RecordEventsRequest(events)
        val requestBody = json.writeValueAsString(body).toRequestBody(mediaType)
        val request = Request.Builder()
            .url("$baseUrl/v1/sdk/events")
            .header("Content-Type", "application/json")
            .header("Authorization", clientKey)
            .post(requestBody)
            .build()

        callFactory.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
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
            val responseBody = response.body?.string()
                ?: throw IOException("Empty response body")
            return json.readValue(responseBody)
        }
    }
}
