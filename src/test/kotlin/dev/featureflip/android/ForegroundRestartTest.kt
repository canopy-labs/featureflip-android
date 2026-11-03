package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * handleForeground() restarts the data sources on a background coroutine. A close() that
 * releases the core meanwhile must win: restarting the source detachSources() just
 * stopped leaves it reconnecting forever with nothing holding a reference (#4901). The
 * restart therefore runs under the read lock detachSources() needs for writing.
 */
class ForegroundRestartTest {

    private val gate = CountDownLatch(1)

    @AfterEach
    fun tearDown() {
        gate.countDown()
        FeatureflipClient.resetForTesting()
    }

    @Test
    fun `a close racing the foreground restart leaves no stream running`() {
        HeldOpenStreamServer("""{"flags":{}}""").use { server ->
            val core = SharedFeatureflipCore.create(
                FeatureflipConfig(
                    clientKey = "fg-${UUID.randomUUID()}",
                    baseUrl = server.baseUrl,
                    context = mapOf("user_id" to "u1"),
                    sendEvaluationEvents = false,
                ),
            )
            var closerStarted = false
            try {
                core.initialize()
                assertThat(server.awaitUntil { server.streams.size == 1 }).isTrue()
                val source = requireNotNull(core.streamingSourceForTest())
                val observer = requireNotNull(core.lifecycleObserver)
                observer.simulateBackground()
                assertThat(server.awaitUntil { server.openStreams() == 0 }).isTrue()

                // Hold the restart after it has read the sources into locals, under the read
                // lock, and before it starts them.
                val inRestart = CountDownLatch(1)
                core.duringForegroundRestartForTest = {
                    inRestart.countDown()
                    gate.await(10, TimeUnit.SECONDS)
                }
                val restarted = CountDownLatch(1)
                core.afterForegroundRestartForTest = { restarted.countDown() }
                observer.simulateForeground()
                assertThat(inRestart.await(5, TimeUnit.SECONDS)).isTrue()

                closerStarted = true
                val closer = thread { core.release() }
                // With the restart under the read lock, release() waits behind it. Without the
                // lock it completes here, and the restart then starts the source from the
                // locals it already holds. Either way the restart is released next and the
                // source must end up stopped.
                closer.join(1_000)
                gate.countDown()
                closer.join(10_000)
                assertThat(closer.isAlive).isFalse()
                assertThat(restarted.await(5, TimeUnit.SECONDS)).isTrue()

                assertThat(source.isRunning)
                    .`as`("the foreground restart must not revive a source close() detached")
                    .isFalse()
                // Nor leave a socket behind: a connect the restart began before close()
                // stopped the source must not open afterwards (#4947).
                assertThat(server.awaitUntil { server.openStreams() == 0 })
                    .`as`("a released core must leave no stream connection open")
                    .isTrue()
            } finally {
                gate.countDown()
                if (!closerStarted) core.release()
            }
        }
    }
}
