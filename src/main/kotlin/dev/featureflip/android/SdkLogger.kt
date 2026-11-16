package dev.featureflip.android

/**
 * The single sink for everything the SDK writes to [System.err], with the SDK key stripped.
 *
 * Failure paths report the underlying exception verbatim (an error boundary must not be
 * silent), and that exception can come from a caller-supplied `Call.Factory` whose message
 * quotes the request, including the key. Logs end up in aggregators, so the key is replaced
 * with `[redacted]` here rather than at each of the call sites. Built once per core.
 *
 * Only the exact configured key is replaced, and only when it is at least
 * [MIN_REDACTABLE_LENGTH] characters: a real key is always 54, and replacing a short value
 * would corrupt every line it appears in.
 */
internal class SdkLogger(private val secret: String? = null) {
    companion object {
        const val REPLACEMENT = "[redacted]"
        const val MIN_REDACTABLE_LENGTH = 20
    }

    /** Looks up [System.err] on every call, so a test (or host) that swaps it is honoured. */
    fun error(message: String) {
        System.err.println(redact(message))
    }

    internal fun redact(text: String): String =
        if (secret != null && secret.length >= MIN_REDACTABLE_LENGTH) {
            text.replace(secret, REPLACEMENT)
        } else {
            text
        }
}
