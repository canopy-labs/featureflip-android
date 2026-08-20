package dev.featureflip.android

import java.util.UUID

private const val PREFS_NAME = "dev.featureflip.android"
private const val STORAGE_KEY = "featureflip.anonymous_id"

// android.content.Context.MODE_PRIVATE — hardcoded to avoid a compile-time
// Android dependency (this module is a pure-JVM library; Android types are
// reached only via reflection, mirroring LifecycleObserver).
private const val MODE_PRIVATE = 0

/** Persistence seam for the generated anonymous user id. Injectable for tests. */
interface AnonymousKeyStore {
    fun read(): String?
    fun write(value: String)
}

/**
 * In-memory store: sticky within the process but not across restarts. Used in
 * unit tests and as the fallback when no Android `Context` is supplied (or when
 * one is supplied but reflection into `SharedPreferences` fails).
 */
class InMemoryAnonymousKeyStore : AnonymousKeyStore {
    @Volatile private var value: String? = null
    override fun read(): String? = value
    override fun write(value: String) { this.value = value }
}

/**
 * `SharedPreferences`-backed store, built reflectively from an Android `Context`.
 *
 * The SDK is a pure-JVM library with **no compile-time Android dependency**
 * (see build.gradle.kts / [LifecycleObserver]), so it cannot name
 * `android.content.Context` directly — the caller passes their Context as `Any`
 * and we reflect the stable public `getSharedPreferences` / `getString` /
 * `edit().putString().apply()` surface. The reflected object is only used to
 * obtain the `SharedPreferences` instance, which is then retained directly — no
 * `Context` reference is held, so no Activity can leak.
 */
class SharedPreferencesAnonymousKeyStore private constructor(
    private val getString: () -> String?,
    private val putString: (String) -> Unit,
) : AnonymousKeyStore {

    override fun read(): String? = getString()
    override fun write(value: String) = putString(value)

    companion object {
        /**
         * Builds a store from an Android `Context` (typed `Any`). Returns null
         * if [context] is not a usable Context or the Android persistence API is
         * unavailable — callers fall back to [InMemoryAnonymousKeyStore].
         */
        fun fromContext(context: Any): SharedPreferencesAnonymousKeyStore? {
            return try {
                val contextClass = Class.forName("android.content.Context")
                if (!contextClass.isInstance(context)) return null

                val appContext = contextClass.getMethod("getApplicationContext").invoke(context) ?: context
                val getPrefs = contextClass.getMethod(
                    "getSharedPreferences",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
                val prefs = getPrefs.invoke(appContext, PREFS_NAME, MODE_PRIVATE) ?: return null

                val prefsClass = Class.forName("android.content.SharedPreferences")
                val editorClass = Class.forName("android.content.SharedPreferences\$Editor")
                val getStringMethod = prefsClass.getMethod("getString", String::class.java, String::class.java)
                val editMethod = prefsClass.getMethod("edit")
                val putStringMethod = editorClass.getMethod("putString", String::class.java, String::class.java)
                val applyMethod = editorClass.getMethod("apply")

                SharedPreferencesAnonymousKeyStore(
                    // Guard the invokes themselves, not just method resolution: a
                    // SharedPreferences IO error (disk full / corrupt prefs) surfaces
                    // as InvocationTargetException at read/write time. Swallowing here
                    // degrades to non-persisted behaviour instead of crashing the
                    // SharedFeatureflipCore constructor / FeatureflipClient.get().
                    getString = {
                        try {
                            getStringMethod.invoke(prefs, STORAGE_KEY, null) as String?
                        } catch (_: Throwable) {
                            null
                        }
                    },
                    putString = { value ->
                        try {
                            val editor = editMethod.invoke(prefs)
                            putStringMethod.invoke(editor, STORAGE_KEY, value)
                            applyMethod.invoke(editor)
                        } catch (_: Throwable) {
                            // best-effort persistence
                        }
                    },
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/**
 * True when a context value is a usable caller id. Values are `Any?` since #2293,
 * so a numeric `user_id` is a real id too — stringify before testing blankness
 * rather than demanding a String and silently treating `user_id = 42` as absent.
 */
private fun Any?.isPresentId(): Boolean = this != null && this.toString().isNotBlank()

/**
 * Returns a context guaranteed to carry a non-blank `user_id`. A real caller id
 * (either the canonical `user_id` or its accepted `userId` alias, mirroring the
 * engine's ClientContextMapper) is returned unchanged so a real user always
 * wins. Otherwise a persisted anonymous id is read — or generated and persisted
 * once — and injected under `user_id`, giving anonymous users sticky
 * percentage-rollout bucketing.
 */
fun resolveAnonymousContext(context: Map<String, Any?>, store: AnonymousKeyStore): Map<String, Any?> {
    if (context["user_id"].isPresentId() || context["userId"].isPresentId()) {
        return context
    }
    val key = store.read()?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString().also { store.write(it) }
    return context + ("user_id" to key)
}
