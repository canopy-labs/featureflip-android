# Changelog

## 3.1.0 — 2026-08-24

### Fixed

- A permanently rejected batch of analytics events is no longer retried forever. The flush restored the batch on *any* exception, so a 401/403 (rejected SDK key) or 400 (malformed body) would be re-sent indefinitely, starving every later event. Only a retryable failure — 5xx, 429, or a transport fault — is kept now; anything else is dropped and the flush moves on, so one rejected batch cannot block the backlog behind it. ([#2456](https://github.com/canopy-labs/featureflip/issues/2456))
- A failing events endpoint no longer receives one request per recorded event. A restored batch leaves the buffer at or above the batch size, so every subsequent event re-fired the size trigger. That trigger now backs off for one flush interval after a retryable failure and will not start a second flush while one is running; the periodic job remains the retry vehicle. ([#2456](https://github.com/canopy-labs/featureflip/issues/2456))
- A flush failure is now reported. It was swallowed by a bare `catch (_: Exception)`, so events could be retried or discarded with nothing written to the log. ([#2456](https://github.com/canopy-labs/featureflip/issues/2456))
- `stop()` makes a single final attempt and discards the remainder, rather than restoring a batch into a buffer nothing will ever drain again. ([#2456](https://github.com/canopy-labs/featureflip/issues/2456))

### Added

- The event buffer is now bounded, at 1000 events, shedding the oldest first. It previously had no bound at all: because a failed batch was always put back, a sustained outage grew it without limit. The bound is lower than the server SDKs' 10,000 because this is a mobile client. ([#2456](https://github.com/canopy-labs/featureflip/issues/2456))

### Changed

- A flush sends one request per batch instead of one for the whole buffer. A backlog can now reach the buffer bound, and a body that size invites a 413 — which is not retryable, so the path meant to preserve the backlog would have been the one that discarded it. ([#2456](https://github.com/canopy-labs/featureflip/issues/2456))


## 3.0.0 — 2026-08-20

### Fixed

- A closed handle serves the caller's default from every accessor and reports not-initialized. `close()` releases the shared core — stopping streaming and polling, shutting down the event processor — but the in-memory cache stayed readable, so a closed client kept evaluating against a frozen snapshot that could never update again while still reporting itself initialized. ([#2295](https://github.com/canopy-labs/featureflip/issues/2295))

- A failed initial flag fetch is now diagnosable rather than swallowed by a bare `catch (_: Exception)`. ([#2294](https://github.com/canopy-labs/featureflip/issues/2294))
### Changed

- **BREAKING:** the evaluation context now accepts any JSON value, not just strings. It was typed `Map<String, String>`, so this SDK could not send a JSON number — and the engine's equality coercion only engages for numbers, so a rule like `age Equals ["25.0"]` matched on web and Flutter and silently no-opped here ([#2293](https://github.com/canopy-labs/featureflip/issues/2293)).

  `FeatureflipConfig.context` and `identify()` now take `Map<String, Any?>`, as do `EvaluationEvent.context` and the data sources. Source-compatible for callers passing a string map; breaking for code **reading** `event.context[k]` as a `String`.

## 2.4.1 — 2026-08-05

### Fixed

- `LICENSE` is now the verbatim Apache-2.0 text. Three phrases in the operative sections had been reworded and the appendix dropped, which left automated license scanners unable to identify it. The license itself is unchanged; the file now says what it always claimed to.
- The README's License section said MIT. `LICENSE`, the published POM and the Maven Central listing have always said Apache-2.0, which is the actual license.
- The README's Gradle snippets pinned `1.0.0`, four minor versions behind. It is the copy mirrored to `canopy-labs/featureflip-android`, so that was the install line anyone reading the public repo got.

## 2.4.0 — 2026-07-29

### Added

- **`onEvaluation` inspector callback.** `inspectors` config option registering in-process observers fired on every evaluation. Notified from the four variation accessors after type coercion — `flagDetail()` and all-flags accessors stay silent so one decision is never double-counted. `reason` is the engine's kebab-case string forwarded verbatim; a flag absent from the snapshot synthesizes `flag-not-found` (#1914).

## 2.3.0 — 2026-07-13

### Fixed

- Outage-recovery hardening: reconnect-forever fallback and replace-on-reconnect (#1882).
- The connect-snapshot store replacement is keyed off the explicit `full: true` marker rather than event order, which was ambiguous when a delta arrived first (#1886).
- The SSE stream is stopped when falling back to polling, instead of being left open alongside it (#1902).

## 2.2.0 — 2026-06-19

### Added

- A generated anonymous `user_id` is persisted in `SharedPreferences` (via an optional `applicationContext` on config, with an in-memory fallback when absent) and injected at every evaluate/identify/SSE call, so anonymous users bucket consistently (#1467).

## 2.1.1 — 2026-06-03

### Changed

- Bumped `com.fasterxml.jackson.core:jackson-databind` and `com.fasterxml.jackson.module:jackson-module-kotlin` from 2.21.3 to 2.22.0.

## 2.1.0 — 2026-05-27

### Added

- **`FlagValue.prerequisiteKey`** — optional `String?` field that surfaces the prerequisite flag that caused this flag to serve its off variation. Populated by the server on `/v1/client/evaluate` and `/v1/client/identify` responses when `reason == "prerequisite-failed"`; `null` otherwise. Decoded from the JSON `prerequisiteKey` field. Serialization omits the field when null (matches the server's wire format and the Swift SDK), so cache files only carry it when meaningful. Pre-existing cache files written by older SDK versions decode cleanly with `prerequisiteKey == null`.

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
