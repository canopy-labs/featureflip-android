package dev.featureflip.android

/**
 * Observes app lifecycle events and calls handlers for foreground/background transitions.
 *
 * On Android with the `androidx.lifecycle:lifecycle-process` dependency available,
 * this hooks into ProcessLifecycleOwner. Otherwise, callers can use [simulateForeground]
 * and [simulateBackground] directly (useful for testing or non-Android JVM usage).
 */
internal class LifecycleObserver(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) {
    private var androidObserver: Any? = null

    init {
        tryRegisterAndroidLifecycle()
    }

    fun simulateForeground() = onForeground()
    fun simulateBackground() = onBackground()

    fun remove() {
        tryUnregisterAndroidLifecycle()
    }

    private fun tryRegisterAndroidLifecycle() {
        try {
            val lifecycleClass = Class.forName("androidx.lifecycle.ProcessLifecycleOwner")
            val getMethod = lifecycleClass.getMethod("get")
            val owner = getMethod.invoke(null)
            val lifecycleMethod = owner.javaClass.getMethod("getLifecycle")
            val lifecycle = lifecycleMethod.invoke(owner)

            val observerClass = Class.forName("androidx.lifecycle.DefaultLifecycleObserver")
            val observer = java.lang.reflect.Proxy.newProxyInstance(
                observerClass.classLoader,
                arrayOf(observerClass),
            ) { _, method, _ ->
                when (method.name) {
                    "onStart" -> onForeground()
                    "onStop" -> onBackground()
                }
                null
            }

            val addMethod = lifecycle.javaClass.getMethod("addObserver", Class.forName("androidx.lifecycle.LifecycleObserver"))
            addMethod.invoke(lifecycle, observer)
            androidObserver = observer
        } catch (_: Exception) {
            // androidx.lifecycle not available — lifecycle must be managed manually
        }
    }

    private fun tryUnregisterAndroidLifecycle() {
        val observer = androidObserver ?: return
        try {
            val lifecycleClass = Class.forName("androidx.lifecycle.ProcessLifecycleOwner")
            val getMethod = lifecycleClass.getMethod("get")
            val owner = getMethod.invoke(null)
            val lifecycleMethod = owner.javaClass.getMethod("getLifecycle")
            val lifecycle = lifecycleMethod.invoke(owner)
            val removeMethod = lifecycle.javaClass.getMethod("removeObserver", Class.forName("androidx.lifecycle.LifecycleObserver"))
            removeMethod.invoke(lifecycle, observer)
        } catch (_: Exception) {
            // Best-effort
        }
        androidObserver = null
    }
}
