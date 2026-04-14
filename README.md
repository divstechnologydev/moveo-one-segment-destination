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

### 1. Mirror Segment events to Moveo One

Initialise Segment as you normally would, then add the plugin. That's it — every `track`, `screen`, `identify`, and `group` call will be forwarded to Moveo One automatically.

```kotlin
val analytics = Analytics("YOUR_SEGMENT_WRITE_KEY", applicationContext) {
    trackApplicationLifecycleEvents = true
}

analytics.add(plugin = MoveoOneDestination(apiKey = "YOUR_MOVEO_API_KEY"))
```

### 2. Send events only to Moveo One (bypass Segment)

Set `autoAddSegmentDestination = false` to stop the SDK from forwarding events to Segment's own pipeline. The `MoveoOneDestination` plugin still receives everything.

```kotlin
val analytics = Analytics("YOUR_SEGMENT_WRITE_KEY", applicationContext) {
    autoAddSegmentDestination = false
}

analytics.add(plugin = MoveoOneDestination(apiKey = "YOUR_MOVEO_API_KEY"))
```

Your existing `analytics.track(...)` calls remain unchanged in both cases.

---

## Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `apiKey` | `String` | — | **Required.** Your Moveo One API key. |
| `endpoint` | `String` | Production URL | Override the ingestion endpoint. |
| `debug` | `Boolean` | `false` | Print request and response details to Logcat. |

```kotlin
MoveoOneDestination(
    apiKey   = "YOUR_MOVEO_API_KEY",
    debug    = true   // enable Logcat output during development
)
```

---

## Event types

| Segment call | Forwarded |
|---|---|
| `analytics.track(...)` | ✅ |
| `analytics.screen(...)` | ✅ |
| `analytics.identify(...)` | ✅ |
| `analytics.group(...)` | ✅ |

---

## Sample app

The [`sample/`](sample/) directory contains a minimal Android app that demonstrates both integration modes. To run it:

1. Open `sample/` in Android Studio.
2. In `SampleApp.kt` replace `YOUR_SEGMENT_WRITE_KEY` and `YOUR_MOVEO_API_KEY` with your actual keys.
3. Run on a device or emulator.

Tap any button — the event fires through Segment and is simultaneously forwarded to Moveo One. Enable `debug = true` in `SampleApp.kt` and filter Logcat by `MoveoOneDestination` to inspect the raw requests and responses.
