package com.moveo.segment.sample

import android.util.Log
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.GroupEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.ScreenEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Moveo One Destination Plugin for Segment Analytics (Kotlin)
 *
 * This plugin intercepts all Segment events and forwards them to the Moveo One API,
 * so you can use a single Segment instrumentation to feed both Segment and Moveo One.
 *
 * ──────────────────────────────────────────────────
 * INTEGRATION — add this to your Analytics setup:
 *
 *   analytics.add(plugin = MoveoOneDestination(apiKey = "YOUR_MOVEO_API_KEY"))
 *
 * That's it. Every track / screen / identify / group call you already make
 * through Segment will automatically be forwarded to Moveo One.
 * ──────────────────────────────────────────────────
 *
 * @param apiKey          Your Moveo One API key.
 * @param endpoint        Override the default API endpoint (optional).
 * @param debug           When true, request and response details are printed to Logcat.
 * @param batchSize       Number of events that trigger an immediate flush (default 20).
 * @param flushIntervalMs How often the batch is flushed automatically in ms (default 30s).
 * @param maxQueueSize    Max events held in the retry queue while offline (default 50).
 * @param filter          Optional property filter. When provided, only events whose
 *                        properties/traits contain ALL specified keys with a matching
 *                        value are forwarded. Null (default) forwards every event.
 *
 *                        Example — forward only events where screen_name is one of the listed values:
 *                          filter = mapOf("screen_name" to listOf("Home", "Checkout"))
 */
class MoveoOneDestination(
    private val apiKey: String,
    private val endpoint: String = "https://api.moveo.one/api/analytic/external/segment-destination",
    private val debug: Boolean = false,
    private val batchSize: Int = 20,
    private val flushIntervalMs: Long = 30_000,
    private val maxQueueSize: Int = 50,
    private val filter: Map<String, List<String>>? = null
) : EventPlugin {

    override val type = Plugin.Type.Enrichment
    override lateinit var analytics: Analytics

    // Pending events waiting to be sent in the next batch
    private val batch = mutableListOf<String>()
    private val batchLock = Any()

    // Failed events waiting to be retried.
    // Replace with a DB-backed store (e.g. Room) here for persistence across app restarts.
    private val retryQueue = ConcurrentLinkedQueue<String>()

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    companion object {
        private const val TAG = "MoveoOneDestination"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    override fun setup(analytics: Analytics) {
        super.setup(analytics)
        this.analytics = analytics
        scheduler.scheduleWithFixedDelay(::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Segment plugin overrides — called automatically on Segment's background
    // thread, so network I/O here is safe and will not block the UI thread.
    // ──────────────────────────────────────────────────────────────────────────

    override fun track(payload: TrackEvent): BaseEvent {
        ship(payload)
        return payload
    }

    override fun screen(payload: ScreenEvent): BaseEvent {
        ship(payload)
        return payload
    }

    override fun identify(payload: IdentifyEvent): BaseEvent {
        ship(payload)
        return payload
    }

    override fun group(payload: GroupEvent): BaseEvent {
        ship(payload)
        return payload
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Immediately flushes the pending batch and any queued retry events.
     * Call this when connectivity is restored or before the app goes to background.
     */
    override fun flush() {
        val toSend = synchronized(batchLock) {
            val copy = batch.toList()
            batch.clear()
            copy
        }

        if (toSend.isEmpty() && retryQueue.isEmpty()) return

        try {
            drainRetryQueue()
        } catch (e: Exception) {
            toSend.forEach { enqueue(it) }
            if (debug) Log.w(TAG, "Network unavailable — queued ${toSend.size} events (retry queue: ${retryQueue.size}/$maxQueueSize)")
            return
        }

        if (toSend.isEmpty()) return

        try {
            sendBatch(toSend)
            if (debug) Log.d(TAG, "Sent batch of ${toSend.size} events")
        } catch (e: Exception) {
            toSend.forEach { enqueue(it) }
            if (debug) Log.w(TAG, "Batch failed — queued ${toSend.size} events (retry queue: ${retryQueue.size}/$maxQueueSize)")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun ship(payload: BaseEvent) {
        if (!passesFilter(payload)) return
        val body = buildPayload(payload).toString()
        val shouldFlush = synchronized(batchLock) {
            batch.add(body)
            batch.size >= batchSize
        }
        if (shouldFlush) flush()
    }

    /**
     * Returns true if the event should be forwarded to Moveo One.
     * When no filter is set all events pass. When a filter is set, all specified
     * key/value conditions must match against the event's properties or traits.
     */
    private fun passesFilter(payload: BaseEvent): Boolean {
        val f = filter?.takeIf { it.isNotEmpty() } ?: return true

        val properties: JsonObject = when (payload) {
            is TrackEvent    -> payload.properties
            is ScreenEvent   -> payload.properties
            is IdentifyEvent -> payload.traits
            is GroupEvent    -> payload.traits
            else             -> return true
        }

        return f.all { (key, allowedValues) ->
            val value = (properties[key] as? JsonPrimitive)?.content
            value != null && value in allowedValues
        }
    }

    private fun drainRetryQueue() {
        if (retryQueue.isEmpty()) return
        val toRetry = mutableListOf<String>()
        repeat(batchSize) { toRetry.add(retryQueue.poll() ?: return@repeat) }
        if (toRetry.isEmpty()) return
        try {
            sendBatch(toRetry)
            if (debug) Log.d(TAG, "Retried ${toRetry.size} events (${retryQueue.size} remaining in queue)")
        } catch (e: Exception) {
            toRetry.forEach { enqueue(it) }
            throw e
        }
    }

    private fun enqueue(body: String) {
        if (retryQueue.size >= maxQueueSize) {
            retryQueue.poll()
            if (debug) Log.w(TAG, "Retry queue full — dropped oldest event")
        }
        retryQueue.offer(body)
    }

    /**
     * Builds the JSON body sent to the Moveo One API.
     * Follows the Segment event spec so the same fields are available on both sides.
     */
    private fun buildPayload(payload: BaseEvent) = buildJsonObject {
        put("type", payload.type.name.lowercase())
        put("messageId", payload.messageId)
        put("anonymousId", payload.anonymousId)
        put("timestamp", payload.timestamp)
        put("originalTimestamp", payload.timestamp)

        payload.userId
            .takeIf { it.isNotBlank() }
            ?.let { put("userId", it) }

        put("context", payload.context)
        put("integrations", payload.integrations)

        when (payload) {
            is TrackEvent -> {
                put("event", payload.event)
                put("properties", payload.properties)
            }
            is ScreenEvent -> {
                put("name", payload.name)
                put("properties", payload.properties)
            }
            is IdentifyEvent -> {
                put("traits", payload.traits)
            }
            is GroupEvent -> {
                put("groupId", payload.groupId)
                put("traits", payload.traits)
            }
            else -> { /* AliasEvent / PageEvent — common fields above are sufficient */ }
        }
    }

    private fun sendBatch(events: List<String>) {
        val sentAt = currentIso8601Utc()
        val body = buildJsonObject {
            put("events", buildJsonArray {
                events.forEach { raw ->
                    val obj = Json.parseToJsonElement(raw).jsonObject
                    if (obj.containsKey("sentAt")) {
                        add(obj)                                   // already set — don't overwrite
                    } else {
                        add(buildJsonObject {
                            obj.forEach { (k, v) -> put(k, v) }    // preserve existing fields
                            put("sentAt", sentAt)
                        })
                    }
                }
            })
        }.toString()
        sendRequest(body)
    }

    private fun currentIso8601Utc(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private fun sendRequest(body: String) {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", apiKey)
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
        }

        if (debug) {
            Log.d(TAG, "──────────────────────────────────────")
            Log.d(TAG, "→ POST $endpoint")
            Log.d(TAG, "→ Authorization: ${apiKey.take(8)}…")
            Log.d(TAG, "→ Body: $body")
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            if (debug) {
                val responseBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: "(empty)"
                Log.d(TAG, "← HTTP $code")
                Log.d(TAG, "← Response: $responseBody")
                Log.d(TAG, "──────────────────────────────────────")
            }
            if (code in 400..499) return            // client error — do not retry
            if (code !in 200..299) throw IOException("HTTP $code") // server error — will be retried
        } finally {
            conn.disconnect()
        }
    }
}
