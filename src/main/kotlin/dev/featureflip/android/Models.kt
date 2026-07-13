package dev.featureflip.android

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A pre-evaluated flag value returned by the server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FlagValue(
    @param:JsonProperty("value") val value: Any?,
    @param:JsonProperty("variation") val variation: String,
    @param:JsonProperty("reason") val reason: String,
    @param:JsonProperty("prerequisiteKey")
    @param:JsonInclude(JsonInclude.Include.NON_NULL)
    val prerequisiteKey: String? = null,
)

/**
 * Server response from /v1/client/evaluate and /v1/client/identify.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class EvaluateResponse(
    @param:JsonProperty("flags") val flags: Map<String, FlagValue>,
    // The client SSE connect-time snapshot carries `full: true` (#1873); deltas omit it.
    // Absent on /v1/client/evaluate + polling responses (they are always full replaces).
    @param:JsonProperty("full") val full: Boolean = false,
)

/**
 * An analytics event sent to /v1/sdk/events.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SdkEvent(
    val type: SdkEventType,
    val flagKey: String,
    val timestamp: String,
    val userId: String? = null,
    val variation: String? = null,
    val metadata: Map<String, Any?>? = null,
)

/**
 * Wrapper for event batch POST body.
 */
internal data class RecordEventsRequest(
    val events: List<SdkEvent>,
)
