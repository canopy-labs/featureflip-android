package dev.featureflip.android

import java.io.IOException

/**
 * Thrown when the events endpoint answers a flush with a non-success status.
 *
 * Carries the status so the flush can tell a retryable failure (5xx, 429) from a
 * permanent one (any other 4xx). Extends [IOException], the type `postEvents`
 * already threw for a non-2xx, so no existing catch site changes behaviour.
 */
internal class EventSendException(val statusCode: Int) :
    IOException("Events endpoint responded $statusCode.")
