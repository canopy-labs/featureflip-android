package dev.featureflip.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

class LifecycleStreamTest {

    @AfterEach
    fun tearDown() {
        FeatureflipClient.resetForTesting()
    }

    @Test
    fun `the foreground signal Android sends on registration does not reconnect the stream`() {
        // The observer registers while the app is already in the foreground, and Android
        // answers with onStart at once. That restarted the stream initialize() had just
        // opened, and the first connection stayed open, in the background too, for as
        // long as the server kept it.
        HeldOpenStreamServer("""{"flags":{}}""").use { server ->
            val client = FeatureflipClient.get(
                FeatureflipConfig(
                    clientKey = "lifecycle-${UUID.randomUUID()}",
                    baseUrl = server.baseUrl,
                    context = mapOf("user_id" to "u1"),
                ),
            )
            client.initialize()
            assertThat(server.awaitUntil { server.streams.size == 1 }).isTrue()
            val observer = requireNotNull(client.lifecycleObserver) { "initialize() registers the observer" }

            observer.simulateForeground()
            // Proving a reconnect did NOT happen needs a wait; the foreground handler
            // reconnects on an IO coroutine within milliseconds when it does.
            Thread.sleep(300)
            assertThat(server.streams)
                .`as`("a foreground with no background before it must not reconnect")
                .hasSize(1)

            observer.simulateBackground()
            assertThat(server.awaitUntil { server.openStreams() == 0 })
                .`as`("going to the background closes the stream")
                .isTrue()

            observer.simulateForeground()
            assertThat(server.awaitUntil { server.streams.size == 2 && server.openStreams() == 1 })
                .`as`("coming back from the background reconnects, once")
                .isTrue()

            client.close()
            assertThat(server.awaitUntil { server.openStreams() == 0 }).isTrue()
        }
    }
}
