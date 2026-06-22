# Moveo One — Segment Destination Plugin (Android / Kotlin)

A Segment destination plugin that forwards every Segment event to Moveo One, so both platforms receive identical event data from a single instrumentation.

---

## Requirements

- Android API 24+
- [Segment Analytics Kotlin](https://github.com/segmentio/analytics-kotlin) `1.14.0+`

---

## Installation

Copy [`MoveoOneDestination.kt`](MoveoOneDestination.kt) into your project and adjust the package declaration at the top to match your own package.

Add the Segment SDK dependency to your `build.gradle` if you haven't already:

```groovy
implementation 'com.segment.analytics.kotlin:android:1.24.1'
```

---

## Usage

Initialise Segment as you normally would, then add the plugin. That's it — every `track`, `screen`, `identify`, and `group` call will be forwarded to Moveo One automatically.

```kotlin
val analytics = Analytics("YOUR_SEGMENT_WRITE_KEY", applicationContext) {
    trackApplicationLifecycleEvents = true
}

analytics.add(plugin = MoveoOneDestination(apiKey = "YOUR_MOVEO_API_KEY"))
```

---

## Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `apiKey` | `String` | — | **Required.** Your Moveo One API key. |
| `endpoint` | `String` | Production URL | Override the ingestion endpoint. |
| `debug` | `Boolean` | `false` | Print request and response details to Logcat. |
| `batchSize` | `Int` | `20` | Number of events that trigger an immediate flush. |
| `flushIntervalMs` | `Long` | `30000` | How often the batch is flushed automatically (ms). |
| `maxQueueBytes` | `Long` | `5000000` | Max bytes of queued events held on disk. When exceeded, the oldest batches are evicted. |
| `maxQueueAgeMs` | `Long` | `604800000` | Max age (ms) of a queued batch before it is evicted (default 7 days). |
| `maxRetries` | `Int` | `10` | Max upload attempts for a batch before it is dropped. |
| `filter` | `Map<String, List<String>>?` | `null` | Property filter — see [Filtering events](#filtering-events) below. |

> Most apps never set any of these. The one knob you may want is `flushIntervalMs` (push interval) or `batchSize`. Everything else has production defaults.

```kotlin
MoveoOneDestination(
    apiKey   = "YOUR_MOVEO_API_KEY",
    debug    = true   // enable Logcat output during development
)
```

---

## Reliability & delivery

The plugin is built to not lose events on a flaky mobile connection — clients just add it as a destination, nothing else.

- **Durable queue** — events are written to disk (under the app's private files dir) *before* any network call, so they survive app kills, crashes, and reboots. They are deleted only after the server confirms receipt (HTTP `2xx`). The Android `Context` is auto-detected from your `Analytics` instance — you do **not** pass it. (If no Context is available, e.g. in unit tests, it transparently falls back to an in-memory queue.)
- **Batching & flushing** — events are sent when `batchSize` is reached, a request-size limit is hit, the `flushIntervalMs` timer fires, or the app goes to the background.
- **Smart retries** — failed uploads retry with exponential backoff + jitter. HTTP `429` and the `Retry-After` header are honoured; `5xx`/network errors retry; non-`429` `4xx` responses are treated as permanent and dropped. A batch is dropped after `maxRetries` attempts so one bad batch can't block the queue.
- **Bounded** — the on-disk queue is capped by `maxQueueBytes` and `maxQueueAgeMs`; when over budget the **oldest** batches are evicted (logged when `debug = true`).

> **Delivery is at-least-once.** After a crash a batch may be sent twice. Each event carries a stable `messageId`; the Moveo One backend de-duplicates on it.

---

## Filtering events

By default every event is forwarded. Pass a `filter` map to forward only events whose properties or traits match your criteria.

Each map entry is a condition: `propertyName to listOf(allowedValue1, allowedValue2, ...)`.
When multiple entries are provided **all conditions must match** (AND logic).
Events that do not match are dropped immediately and never queued.

**Forward all events (default)**
```kotlin
analytics.add(plugin = MoveoOneDestination(apiKey = "YOUR_MOVEO_API_KEY"))
```

**Forward only events with a specific property value**
```kotlin
analytics.add(plugin = MoveoOneDestination(
    apiKey = "YOUR_MOVEO_API_KEY",
    filter = mapOf("category" to listOf("purchase"))
))
```

**Forward events where a property matches any of several values**
```kotlin
analytics.add(plugin = MoveoOneDestination(
    apiKey = "YOUR_MOVEO_API_KEY",
    filter = mapOf("status" to listOf("active", "trial", "premium"))
))
```

**Combine multiple conditions — all must match**
```kotlin
analytics.add(plugin = MoveoOneDestination(
    apiKey = "YOUR_MOVEO_API_KEY",
    filter = mapOf(
        "category" to listOf("purchase", "subscription"),
        "currency" to listOf("USD", "EUR")
    )
))
```

> **Note:** The filter checks `properties` for `track` and `screen` events, and `traits` for `identify` and `group` events. Events where a required property is missing or is not a primitive value (string / number / boolean) are dropped.

---

## Event types

| Segment call | Forwarded |
|---|---|
| `analytics.track(...)` | ✅ |
| `analytics.screen(...)` | ✅ |
| `analytics.identify(...)` | ✅ |
| `analytics.group(...)` | ✅ |
| `analytics.alias(...)` | ✅ common fields only |

> `alias` is an advanced call used to merge two user identities (e.g. anonymous → known user after sign-up). Only the common Segment fields are forwarded — if your Moveo One integration requires alias-specific fields, override the `alias()` method in `MoveoOneDestination`.

---

## Sample app

The [`sample/`](sample/) directory contains a minimal Android app that demonstrates the integration. To run it:

1. Open `sample/` in Android Studio.
2. In `SampleApp.kt` replace `YOUR_SEGMENT_WRITE_KEY` and `YOUR_MOVEO_API_KEY` with your actual keys.
3. Run on a device or emulator.

Tap any button — the event fires through Segment and is simultaneously forwarded to Moveo One. Enable `debug = true` in `SampleApp.kt` and filter Logcat by `MoveoOneDestination` to inspect the raw requests and responses.
