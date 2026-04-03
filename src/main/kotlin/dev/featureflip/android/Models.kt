package dev.featureflip.android

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A pre-evaluated flag value returned by the server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FlagValue(
    @JsonProperty("value") val value: Any?,
    @JsonProperty("variation") val variation: String,
    @JsonProperty("reason") val reason: String,
)

/**
 * Server response from /v1/client/evaluate and /v1/client/identify.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class EvaluateResponse(
    @JsonProperty("flags") val flags: Map<String, FlagValue>,
)

/**
 * An analytics event sent to /v1/sdk/events.
 */
internal data class SdkEvent(
    val type: String,
    val flagKey: String? = null,
    val userId: String? = null,
    val variation: String? = null,
    val timestamp: String,
    val metadata: Map<String, Any?>? = null,
)

/**
 * Wrapper for event batch POST body.
 */
internal data class RecordEventsRequest(
    val events: List<SdkEvent>,
)
