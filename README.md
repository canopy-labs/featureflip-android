# Featureflip Android SDK

Android/Kotlin SDK for [Featureflip](https://featureflip.io) — evaluate feature flags in Android apps.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.featureflip:featureflip-android:1.0.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'io.featureflip:featureflip-android:1.0.0'
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

MIT
