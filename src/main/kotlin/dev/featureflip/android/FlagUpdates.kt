package dev.featureflip.android

/**
 * Keys whose [FlagValue] differs between two snapshots: added, removed, or not `==`.
 * Returned sorted ascending; empty when nothing changed.
 *
 * Client SDKs hold server-evaluated values, so there is no segment or prerequisite
 * graph to walk (contrast the Java SDK's `FlagUpdates.diffSnapshot`). The whole
 * [FlagValue] is compared, not just `value`, because a reason or variation change
 * matters to a caller reading flag details. JSON-object values compare structurally,
 * so a re-delivered identical payload is not a change.
 */
internal fun diffKeys(before: Map<String, FlagValue>, after: Map<String, FlagValue>): List<String> {
    val changed = after.filter { (key, value) -> before[key] != value }.keys
    val removed = before.keys - after.keys
    return (changed + removed).sorted()
}
