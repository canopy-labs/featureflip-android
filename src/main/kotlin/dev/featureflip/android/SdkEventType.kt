package dev.featureflip.android

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * SDK event type. Wire format is PascalCase to match the server's
 * `JsonStringEnumConverter` deserialization (no naming policy).
 */
internal enum class SdkEventType {
    @JsonProperty("Evaluation") Evaluation,
    @JsonProperty("Impression") Impression,
    @JsonProperty("Identify") Identify,
    @JsonProperty("Custom") Custom,
}
