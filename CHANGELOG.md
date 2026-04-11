# Changelog

## 2.0.0 — 2026-04-09

### BREAKING

- **Public `FeatureflipClient.create()` / `configure()` / `shared()` entry points removed.** The only way to obtain a client is now the static factory `FeatureflipClient.get(config)`. The factory dedupes by client key: repeated calls with the same key return handles pointing at a single shared underlying client, making per-Activity / per-ViewModel / DI-scoped registration harmless instead of leaking SSE connections and background workers.

  **Migration:**

  Before (instance-based):
  ```kotlin
  val client = FeatureflipClient.create(config)
  client.initialize()
  ```

  Before (singleton):
  ```kotlin
  FeatureflipClient.configure(config)
  FeatureflipClient.shared().initialize()
  ```

  After:
  ```kotlin
  val client = FeatureflipClient.get(config)
  client.initialize()
  ```

  The factory IS the singleton — calling `get()` multiple times with the same `clientKey` always returns handles sharing one underlying shared client. No more `configure()` / `shared()` ceremony.

- **`close()` is now refcounted.** When multiple handles share one cached core, calling `close()` on one handle does not shut down the core — the SSE connection, event processor, and lifecycle observer stay alive until the last handle is closed. Double-closing the same handle is idempotent and does not double-decrement the refcount. `FeatureflipClient.forTesting(...)` clients are not cached by the factory and are always independent.

- **`config` is ignored on repeat calls for the same client key.** The first `get()` for a given key owns the config used by the shared core; subsequent `get()` calls with meaningfully different `baseUrl` / `streaming` / `pollIntervalMs` / `flushIntervalMs` / `flushBatchSize` / `initTimeoutMs` will log a warning to `System.err` and reuse the cached core's config.

### Added

- `FeatureflipClient.get(config)` — static factory, the new primary entry point. Also `get(config, callFactory)` for tests that need a custom OkHttp `Call.Factory`.
- Internal `SharedFeatureflipCore` class separating expensive resources (HTTP client, disk cache, streaming/polling data sources, event processor, lifecycle observer) from the public handle. Refcounted via `AtomicInteger` with a CAS loop.
- `FeatureflipClient.debugLiveCoreCount()` and `FeatureflipClient.debugRefCount(clientKey)` public diagnostics (marked "not part of the stable API surface") for tests and lifetime debugging.
- `FeatureflipClient.resetForTesting()` public test helper for clean slate between tests. Also marked "not part of the stable API surface".

### Changed

- `FeatureflipClient` is now a thin handle over `SharedFeatureflipCore`. All evaluation, identify, track, flush, and close operations delegate to the core.
- Concurrent `get(sameKey)` calls from multiple threads resolve to exactly one core construction via `ConcurrentHashMap.putIfAbsent` + refcounted `tryAcquire` retry loop.

### Removed

- `FeatureflipClient.create(config)` and `create(config, callFactory)`.
- `FeatureflipClient.configure(config)`.
- `FeatureflipClient.shared()`.
- `FeatureflipClient.resetShared()` (replaced by `resetForTesting()`).

## 0.1.0

Initial release.
