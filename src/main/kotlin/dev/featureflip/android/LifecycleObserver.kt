package dev.featureflip.android

import java.lang.reflect.Proxy

/**
 * Observes app lifecycle events and calls handlers for foreground/background transitions.
 *
 * On Android with the `androidx.lifecycle:lifecycle-process` dependency available,
 * this hooks into ProcessLifecycleOwner. Otherwise, callers can use [simulateForeground]
 * and [simulateBackground] directly (useful for testing or non-Android JVM usage).
 */
internal class LifecycleObserver(
    onForeground: () -> Unit,
    onBackground: () -> Unit,
) {
    // These run on the host app's main thread, where nothing catches an exception.
    private val onForeground = contained("foreground", onForeground)
    private val onBackground = contained("background", onBackground)

    // Written by the constructing thread and by remove(), read by a registration posted
    // to the main thread.
    @Volatile
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
            val lifecycle = processLifecycle()
            val observer = lifecycleProxy(
                Class.forName("androidx.lifecycle.DefaultLifecycleObserver"),
                onForeground,
                onBackground,
            )
            val addMethod = lifecycle.javaClass.getMethod("addObserver", Class.forName("androidx.lifecycle.LifecycleObserver"))
            // Set before the add is dispatched, so a remove() queued behind a posted add
            // still finds it. A remove() that runs first clears it, and the add is skipped.
            androidObserver = observer
            onMainThread {
                if (androidObserver === observer) addMethod.invoke(lifecycle, observer)
            }
        } catch (_: Exception) {
            // androidx.lifecycle not available — lifecycle must be managed manually
        }
    }

    private fun tryUnregisterAndroidLifecycle() {
        val observer = androidObserver ?: return
        androidObserver = null
        try {
            val lifecycle = processLifecycle()
            val removeMethod = lifecycle.javaClass.getMethod("removeObserver", Class.forName("androidx.lifecycle.LifecycleObserver"))
            onMainThread { removeMethod.invoke(lifecycle, observer) }
        } catch (_: Exception) {
            // Best-effort
        }
    }

    private fun processLifecycle(): Any {
        val owner = Class.forName("androidx.lifecycle.ProcessLifecycleOwner").getMethod("get").invoke(null)
        return owner.javaClass.getMethod("getLifecycle").invoke(owner)
    }

    /**
     * Runs [block] on Android's main thread, which ProcessLifecycleOwner's registry
     * requires for addObserver/removeObserver; the core is initialized off it. Runs it
     * directly when already on the main thread, or when `android.os` is absent (a plain
     * JVM). Reflection only: this module has no compile-time Android dependency.
     */
    private fun onMainThread(block: () -> Unit) {
        // A posted block that threw would throw on the main thread, where nothing catches it.
        val safe = Runnable {
            try {
                block()
            } catch (_: Exception) {
                // Best-effort, like the callers' own catch
            }
        }
        val looperClass = try {
            Class.forName("android.os.Looper")
        } catch (_: Exception) {
            null
        }
        val mainLooper = looperClass?.getMethod("getMainLooper")?.invoke(null)
        if (looperClass == null || mainLooper == null || looperClass.getMethod("myLooper").invoke(null) === mainLooper) {
            safe.run()
            return
        }
        val handler = Class.forName("android.os.Handler").getConstructor(looperClass).newInstance(mainLooper)
        handler.javaClass.getMethod("post", Runnable::class.java).invoke(handler, safe)
    }
}

private fun contained(name: String, handler: () -> Unit): () -> Unit = {
    try {
        handler()
    } catch (e: Exception) {
        System.err.println("[featureflip] $name lifecycle handler threw: $e")
    }
}

/**
 * A [Proxy] implementing [observerInterface] (`DefaultLifecycleObserver` in production)
 * that calls [onForeground] on `onStart` and [onBackground] on `onStop`.
 *
 * The `Object` methods must be answered: LifecycleRegistry keeps observers in a hashed
 * map, so a proxy whose `hashCode` returned null would throw on registration. Every other
 * observer method returns void, so null is the right answer for them.
 */
internal fun lifecycleProxy(
    observerInterface: Class<*>,
    onForeground: () -> Unit,
    onBackground: () -> Unit,
): Any = Proxy.newProxyInstance(observerInterface.classLoader, arrayOf(observerInterface)) { proxy, method, args ->
    when (method.name) {
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.getOrNull(0)
        "toString" -> "FeatureflipLifecycleObserver"
        "onStart" -> {
            onForeground()
            null
        }
        "onStop" -> {
            onBackground()
            null
        }
        else -> null
    }
}
