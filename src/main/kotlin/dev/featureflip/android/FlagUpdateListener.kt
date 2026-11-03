package dev.featureflip.android

/**
 * Receives the keys of flags whose value or evaluation details changed after the initial load.
 *
 * The listener runs on the thread that delivered the change: the streaming
 * connection, the polling worker, or the caller of `identify`. It must not
 * block. [flagKeys] is sorted ascending, never empty, and is not modified after
 * delivery. An exception thrown by a listener is logged and does not affect
 * delivery to other listeners or the data source.
 *
 * Delivery iterates a snapshot of the listeners, so a delivery already in progress
 * can still call a listener once after its unsubscribe function or `close()` returns.
 *
 * Inside the callback, reading flags through the client returns the new values.
 */
fun interface FlagUpdateListener {
    fun onUpdate(flagKeys: List<String>)
}
