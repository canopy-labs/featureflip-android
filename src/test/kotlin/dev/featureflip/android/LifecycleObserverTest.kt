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
    fun `remove does not throw without android lifecycle`() {
        val observer = LifecycleObserver(
            onForeground = {},
            onBackground = {},
        )
        observer.remove() // Should not throw
    }
}
