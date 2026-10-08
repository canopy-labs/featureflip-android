# Featureflip Android SDK

Android/Kotlin SDK for [Featureflip](https://featureflip.io) — evaluate feature flags in Android apps.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.featureflip:featureflip-android:3.3.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'io.featureflip:featureflip-android:3.3.0'
}
```

## Quick Start

```kotlin
import dev.featureflip.android.FeatureflipClient
import dev.featureflip.android.FeatureflipConfig

val config = FeatureflipConfig(clientKey = "your-client-sdk-key")
val client = FeatureflipClient.get(config)

client.initialize()

val enabled = client.boolVariation("my-feature", false)

if (enabled) {
    println("Feature is enabled!")
}

client.close()
```

> **Singleton by construction.** `FeatureflipClient.get()` is the only way to obtain a client — the public constructor was removed in v2.0. Calling `get()` more than once with the same `clientKey` returns handles pointing at one shared underlying client (refcounted). This makes the SDK safe to call from per-Activity or per-ViewModel constructors and from DI containers without leaking SSE connections.

## Configuration

```kotlin
val config = FeatureflipConfig(
    clientKey = "your-client-sdk-key",
    baseUrl = "https://eval.featureflip.io",      // Evaluation API URL (default)
    context = mapOf("user_id" to "123"),            // Initial evaluation context
    streaming = true,                               // SSE for real-time updates (default)
    pollIntervalMs = 30_000,                        // Polling interval in ms
    flushIntervalMs = 30_000,                       // Event flush interval in ms
    flushBatchSize = 100,                           // Events per batch
    initTimeoutMs = 10_000,                         // Max ms to wait for initialization
    sendEvaluationEvents = true,                    // Report which flags the app reads (default)
)
```

## Singleton Pattern

The factory `FeatureflipClient.get(config)` **is** the singleton pattern — it dedupes by client key across the whole process. Call it anywhere:

```kotlin
// Same underlying shared client, two handles.
val a = FeatureflipClient.get(config)
val b = FeatureflipClient.get(config)

// Access from anywhere
val enabled = FeatureflipClient.get(config).boolVariation("my-feature", false)
```

## Evaluation

```kotlin
// Boolean flag
val enabled = client.boolVariation("feature-key", false)

// String flag
val tier = client.stringVariation("pricing-tier", "free")

// Number flag
val limit = client.numberVariation("rate-limit", 100.0)

// JSON flag
val config = client.jsonVariation("ui-config", null)
```

## Identify

Re-evaluate all flags with a new context (e.g., after login):

```kotlin
client.identify(mapOf("user_id" to "123", "plan" to "pro"))
```

## Event Tracking

```kotlin
// Track custom events
client.track("checkout-completed", mapOf("total" to 99.99))

// Force flush pending events
client.flush()
```

Featureflip counts each event name, per environment. The metadata you pass is transmitted with the event but is not stored, and event counts are not surfaced in the app or API.

`flush()` and `close()` are safe to call from the main thread: the request they
trigger is a blocking network round-trip, so both hand it to a background
dispatcher and return before it completes. From a coroutine, use the suspending
variants when you want the events actually sent before moving on, for example
before signing a user out, or from an application-scoped coroutine. The scope has
to still be alive: `viewModelScope` is already cancelled by the time
`ViewModel.onCleared()` runs, so call plain `close()` there.

```kotlin
// Suspends until the flush attempt has completed
client.flushAndAwait()

// Suspends until the final flush has been attempted, then releases the handle
client.closeAndAwait()
```

## Read reporting

The SDK tells Featureflip which flags your app actually reads. Without reads, a client-side flag looks used whenever it is sent to a device. With them, a flag nothing reads any more can be archived without forcing it, and staleness detection treats client-side flags like server-side ones.

- A read is a call to `boolVariation`, `stringVariation`, `numberVariation`, `jsonVariation` or `flagDetail`. Reading a key the SDK doesn't have counts too.
- Loading flags, streaming updates, `identify()` and inspectors aren't reads.
- A flag is reported at most once an hour per variation and user, however often you read it, and again each time the app comes back to the foreground. A repeat read costs no allocation and no network. Reports go out in the same batches as `track()` events, including the flush when the app goes to the background.

To turn it off:

```kotlin
val config = FeatureflipConfig(
    clientKey = "your-client-sdk-key",
    sendEvaluationEvents = false,
)
```

With it off, Featureflip counts every flag it sends to the app as evaluated, as it did before 3.3.0, so to Featureflip a flag this app is sent never looks unused.

## Android Lifecycle

The SDK automatically pauses streaming and flushes events when the app moves to the background, and resumes streaming when the app returns to the foreground. This requires the `androidx.lifecycle:lifecycle-process` dependency on your classpath (included by default in most Android projects).

## Testing

Use `forTesting()` to create a client with predetermined flag values — no network calls.

```kotlin
val client = FeatureflipClient.forTesting(
    mapOf(
        "my-feature" to true,
        "pricing-tier" to "pro",
    )
)

client.boolVariation("my-feature", false)     // true
client.stringVariation("pricing-tier", "free") // "pro"
client.boolVariation("unknown", false)         // false (default)
```

## Features

- **Client-side evaluation** — Flags evaluated server-side, only values returned
- **Real-time updates** — SSE streaming with automatic polling fallback
- **Event tracking** — Automatic batching and background flushing
- **Test support** — `forTesting()` factory for deterministic unit tests
- **Lifecycle-aware** — Automatic pause/resume on background/foreground
- **Singleton or instance** — `configure`/`shared()` pattern or manual instantiation

## Requirements

- Kotlin 1.9+ / Java 17+
- Android API 21+ (or any JVM environment)

## License

Apache-2.0
