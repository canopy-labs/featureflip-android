package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LifecycleObserverTest {

    @Test
    fun `simulateForeground calls onForeground`() {
        var called = false
        val observer = LifecycleObserver(
            onForeground = { called = true },
            onBackground = {},
        )

        observer.simulateForeground()
        assertThat(called).isTrue()
    }

    @Test
    fun `simulateBackground calls onBackground`() {
        var called = false
        val observer = LifecycleObserver(
            onForeground = {},
            onBackground = { called = true },
        )

        observer.simulateBackground()
        assertThat(called).isTrue()
    }

    @Test
    fun `a throwing handler does not propagate out of the callback`() {
        var foreground = 0
        var background = 0
        val observer = LifecycleObserver(
            onForeground = { foreground++; error("foreground boom") },
            onBackground = { background++; error("background boom") },
        )

        observer.simulateForeground()
        observer.simulateBackground()
        // A later callback still reaches the handler.
        observer.simulateForeground()

        assertThat(foreground).isEqualTo(2)
        assertThat(background).isEqualTo(1)
    }

    @Test
    fun `remove does not throw without android lifecycle`() {
        val observer = LifecycleObserver(
            onForeground = {},
            onBackground = {},
        )
        observer.remove() // Should not throw
    }
}

/**
 * Stands in for `androidx.lifecycle.DefaultLifecycleObserver`, which is absent on a plain
 * JVM. Only the method names matter to the proxy.
 */
interface FakeLifecycleObserver {
    fun onStart(owner: Any?)
    fun onStop(owner: Any?)
}

class LifecycleProxyTest {

    private fun proxy(onForeground: () -> Unit = {}, onBackground: () -> Unit = {}) =
        lifecycleProxy(FakeLifecycleObserver::class.java, onForeground, onBackground)

    // LifecycleRegistry keeps observers in a hashed map, so registration calls hashCode().
    // A proxy answering null for it throws, and that failure used to be swallowed.
    @Test
    fun `the proxy can be stored in and found in hashed collections`() {
        val observer = proxy()

        val map = HashMap<Any, String>()
        map[observer] = "registered"
        assertThat(map[observer]).isEqualTo("registered")

        val set = HashSet<Any>()
        set.add(observer)
        assertThat(set.contains(observer)).isTrue()
    }

    @Test
    fun `equals is identity`() {
        val observer = proxy()

        assertThat(observer == observer).isTrue()
        assertThat(observer == proxy()).isFalse()
    }

    @Test
    fun `toString does not throw`() {
        assertThat(proxy().toString()).isNotEmpty()
    }

    @Test
    fun `onStart and onStop call the callbacks`() {
        var foreground = 0
        var background = 0
        val observer = proxy(onForeground = { foreground++ }, onBackground = { background++ }) as FakeLifecycleObserver

        observer.onStart(null)
        observer.onStop(null)

        assertThat(foreground).isEqualTo(1)
        assertThat(background).isEqualTo(1)
    }
}
