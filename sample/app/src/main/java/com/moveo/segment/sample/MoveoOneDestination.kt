package com.moveo.segment.sample

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
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
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone
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
 *
 * The plugin auto-detects the Android Context from your Analytics instance, so it
 * can persist events to disk. You do NOT need to pass a Context.
 * ──────────────────────────────────────────────────
 *
 * PRODUCTION BEHAVIOUR
 *  • Durability — events are written to disk before the network is touched, so they
 *    survive app kills, crashes and reboots. They are deleted only after the server
 *    confirms receipt (HTTP 2xx). If no Android Context is available (e.g. unit
 *    tests), the plugin transparently falls back to an in-memory store.
 *  • Batching — events accumulate until [batchSize] is reached, a size limit is hit,
 *    the [flushIntervalMs] timer fires, or the app goes to the background.
 *  • Retries — failed uploads are retried with exponential backoff + jitter. HTTP 429
 *    and a `Retry-After` header are honoured. 5xx/network errors retry; non-429 4xx
 *    are treated as permanent and dropped. A batch is dropped after [maxRetries]
 *    attempts so one poison batch can't block the queue forever.
 *  • Bounded — the on-disk queue is capped by [maxQueueBytes] and [maxQueueAgeMs];
 *    when over budget the OLDEST batches are evicted (logged in debug — never silent).
 *
 * Delivery is at-least-once: after a crash a batch may be sent twice. The backend
 * de-duplicates on `messageId`, which is included on every event.
 *
 * @param apiKey          Your Moveo One API key.
 * @param endpoint        Override the default API endpoint (optional).
 * @param debug           When true, request and response details are printed to Logcat.
 * @param batchSize       Number of events that trigger an immediate flush (default 20).
 * @param flushIntervalMs How often the batch is flushed automatically in ms (default 30s).
 * @param maxQueueBytes   Max bytes of queued events held on disk (default 5 MB). When
 *                        exceeded, the oldest batches are evicted.
 * @param maxQueueAgeMs   Max age of a queued batch before it is evicted (default 7 days).
 * @param maxRetries      Max upload attempts for a batch before it is dropped (default 10).
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
    private val maxQueueBytes: Long = 5_000_000,
    private val maxQueueAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val maxRetries: Int = 10,
    private val filter: Map<String, List<String>>? = null
) : EventPlugin {

    override val type = Plugin.Type.Enrichment
    override lateinit var analytics: Analytics

    // Durable (or in-memory fallback) event queue. Initialised in setup().
    private lateinit var store: EventStore

    // All disk + network I/O runs on this single thread, so the store is only ever
    // touched serially and Segment's calling thread is never blocked on the network.
    private val io: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // Retry/backoff state (in-memory — on restart we simply try again, which is safe).
    private val random = Random()
    private var consecutiveFailures = 0
    private var nextAllowedSendAtMs = 0L

    // Foreground activity count, used to flush when the app goes to the background.
    private var startedActivities = 0

    companion object {
        private const val TAG = "MoveoOneDestination"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000

        // Segment-style payload limits — keep a single event and a single request
        // comfortably under the Tracking API's 32 KB / 500 KB caps.
        private const val MAX_EVENT_BYTES = 32_000
        private const val MAX_REQUEST_BYTES = 475_000L

        // Exponential backoff parameters.
        private const val BACKOFF_BASE_SEC = 1.0
        private const val BACKOFF_MAX_SEC = 300.0
        private const val BACKOFF_JITTER = 0.2
    }

    override fun setup(analytics: Analytics) {
        super.setup(analytics)
        this.analytics = analytics

        val ctx = (analytics.configuration.application as? Context)?.applicationContext
        store = buildStore(ctx)
        registerLifecycle(ctx)

        // Seal any leftover partial file from a previous run and try to flush.
        io.execute { runCatching { doFlush() } }
        io.scheduleWithFixedDelay(
            { runCatching { doFlush() } },
            flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS
        )
    }

    private fun buildStore(ctx: Context?): EventStore {
        if (ctx == null) {
            if (debug) Log.w(TAG, "No Android Context — using in-memory store (events are NOT durable)")
            return InMemoryEventStore()
        }
        return try {
            val dir = File(ctx.filesDir, "moveo-one-segment/${Integer.toHexString(apiKey.hashCode())}")
            val prefs = ctx.getSharedPreferences("moveo-one-segment", Context.MODE_PRIVATE)
            FileEventStore(dir, prefs).also { if (debug) Log.d(TAG, "Durable store at $dir") }
        } catch (e: Exception) {
            if (debug) Log.w(TAG, "Disk store init failed — using in-memory store", e)
            InMemoryEventStore()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Segment plugin overrides — called automatically on Segment's background
    // thread. We only serialize + hand off to our I/O thread here.
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
     * Immediately flushes the pending batch and any queued events.
     * Called automatically on the interval timer and when the app backgrounds.
     */
    override fun flush() {
        io.execute { runCatching { doFlush() } }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun ship(payload: BaseEvent) {
        if (!passesFilter(payload)) return
        val body = buildPayload(payload).toString()

        if (body.toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES) {
            if (debug) Log.w(TAG, "Event ${payload.messageId} is too large (${body.length} chars) — skipped")
            return
        }

        io.execute {
            try {
                store.append(body)
                if (store.currentCount() >= batchSize || store.currentBytes() >= MAX_REQUEST_BYTES) {
                    doFlush()
                }
            } catch (e: Exception) {
                if (debug) Log.w(TAG, "Failed to enqueue event", e)
            }
        }
    }

    /**
     * Seals the pending events into a batch and uploads everything that is due.
     * Always runs on the [io] thread.
     */
    private fun doFlush() {
        try {
            store.sealCurrent()
        } catch (e: Exception) {
            if (debug) Log.w(TAG, "Seal failed", e)
        }

        val now = System.currentTimeMillis()
        if (now < nextAllowedSendAtMs) {
            if (debug) Log.d(TAG, "Backing off — skipping uploads for ${(nextAllowedSendAtMs - now)}ms")
            return
        }

        store.enforceLimits(maxQueueBytes, maxQueueAgeMs, debug)

        for (batch in store.batches()) {
            if (batch.events.isEmpty()) {
                store.delete(batch)
                continue
            }
            when (val result = postBatch(batch.events)) {
                is SendResult.Success -> {
                    store.delete(batch)
                    onSendSuccess()
                    if (debug) Log.d(TAG, "Sent batch ${batch.id} (${batch.events.size} events)")
                }
                is SendResult.Permanent -> {
                    store.delete(batch)
                    if (debug) Log.w(TAG, "Dropped batch ${batch.id} — HTTP ${result.code} (non-retryable)")
                }
                is SendResult.Retryable -> {
                    val nextAttempt = batch.attempts + 1
                    if (nextAttempt > maxRetries) {
                        store.delete(batch)
                        if (debug) Log.w(TAG, "Dropped batch ${batch.id} after $maxRetries failed attempts")
                        continue
                    }
                    store.bumpAttempt(batch)
                    onSendFailure(result.retryAfterSec)
                    if (debug) Log.w(TAG, "Batch ${batch.id} failed (attempt $nextAttempt) — backing off")
                    return // respect the backoff window before trying anything else
                }
            }
        }
    }

    private fun onSendSuccess() {
        consecutiveFailures = 0
        nextAllowedSendAtMs = 0L
    }

    private fun onSendFailure(retryAfterSec: Int?) {
        consecutiveFailures++
        val delaySec = if (retryAfterSec != null && retryAfterSec > 0) {
            retryAfterSec.toDouble()
        } else {
            val expo = BACKOFF_BASE_SEC * Math.pow(2.0, (consecutiveFailures - 1).toDouble())
            val capped = minOf(expo, BACKOFF_MAX_SEC)
            capped + capped * BACKOFF_JITTER * random.nextDouble()
        }
        nextAllowedSendAtMs = System.currentTimeMillis() + (delaySec * 1000).toLong()
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

    private fun postBatch(events: List<String>): SendResult {
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
        return sendRequest(body)
    }

    private fun sendRequest(body: String): SendResult {
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
            val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
            if (debug) {
                val responseBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: "(empty)"
                Log.d(TAG, "← HTTP $code")
                Log.d(TAG, "← Response: $responseBody")
                Log.d(TAG, "──────────────────────────────────────")
            }
            return when {
                code in 200..299 -> SendResult.Success
                code == 429      -> SendResult.Retryable(retryAfter)        // rate limited
                code in 500..599 -> SendResult.Retryable(retryAfter)        // server error
                code in 400..499 -> SendResult.Permanent(code)             // client error — drop
                else             -> SendResult.Retryable(retryAfter)
            }
        } catch (e: Exception) {
            if (debug) Log.w(TAG, "Network error while uploading", e)
            return SendResult.Retryable(null)
        } finally {
            conn.disconnect()
        }
    }

    private fun currentIso8601Utc(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private fun registerLifecycle(ctx: Context?) {
        val app = ctx as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) flush() // app went to background — drain the queue
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Result of a single upload attempt.
    // ──────────────────────────────────────────────────────────────────────────
    private sealed class SendResult {
        object Success : SendResult()
        data class Permanent(val code: Int) : SendResult()
        data class Retryable(val retryAfterSec: Int?) : SendResult()
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Event store — a durable, file-backed queue with an in-memory fallback.
// A "batch" is a sealed group of events that maps to exactly one HTTP request.
// ──────────────────────────────────────────────────────────────────────────────

internal data class BatchFile(
    val id: String,            // identifier / filename
    val seq: Long,             // monotonic ordering key
    val attempts: Int,         // failed upload attempts so far
    val createdAtMs: Long,
    val events: List<String>
)

internal interface EventStore {
    /** Append one serialized event to the pending (not yet sealed) batch. */
    fun append(eventJson: String)
    fun currentCount(): Int
    fun currentBytes(): Long
    /** Seal the pending events into an uploadable batch. No-op if empty. */
    fun sealCurrent()
    /** Sealed batches, oldest first. */
    fun batches(): List<BatchFile>
    fun delete(batch: BatchFile)
    fun bumpAttempt(batch: BatchFile)
    /** Evict oldest batches that exceed the size/age budget. */
    fun enforceLimits(maxBytes: Long, maxAgeMs: Long, debug: Boolean)
}

/**
 * Disk-backed store. Pending events are appended to `current.ndjson`; on seal it is
 * renamed to `b-<seq>-a<attempts>.ndjson`. Files are uploaded oldest-first and
 * deleted only after a confirmed send. Survives process death.
 */
internal class FileEventStore(
    private val dir: File,
    private val prefs: SharedPreferences
) : EventStore {

    private val currentFile = File(dir, "current.ndjson")
    private var currentCount = 0

    private val nameRegex = Regex("""b-(\d+)-a(\d+)\.ndjson""")

    companion object {
        private const val TAG = "MoveoOneDestination"
        private const val SEQ_KEY = "moveo.seq"
    }

    init {
        dir.mkdirs()
    }

    override fun append(eventJson: String) {
        currentFile.appendText(eventJson + "\n")
        currentCount++
    }

    override fun currentCount(): Int = currentCount

    override fun currentBytes(): Long = if (currentFile.exists()) currentFile.length() else 0L

    override fun sealCurrent() {
        if (!currentFile.exists() || currentFile.length() == 0L) {
            currentCount = 0
            return
        }
        val seq = prefs.getLong(SEQ_KEY, 0L)
        prefs.edit().putLong(SEQ_KEY, seq + 1).apply()
        val dst = File(dir, "b-%012d-a0.ndjson".format(seq))
        if (!currentFile.renameTo(dst)) {
            currentFile.copyTo(dst, overwrite = true)
            currentFile.delete()
        }
        currentCount = 0
    }

    override fun batches(): List<BatchFile> =
        (dir.listFiles() ?: emptyArray())
            .mapNotNull { f ->
                val m = nameRegex.matchEntire(f.name) ?: return@mapNotNull null
                val events = try {
                    f.readLines().filter { it.isNotBlank() }
                } catch (e: Exception) {
                    emptyList()
                }
                BatchFile(f.name, m.groupValues[1].toLong(), m.groupValues[2].toInt(), f.lastModified(), events)
            }
            .sortedBy { it.seq }

    override fun delete(batch: BatchFile) {
        File(dir, batch.id).delete()
    }

    override fun bumpAttempt(batch: BatchFile) {
        val src = File(dir, batch.id)
        if (!src.exists()) return
        val dst = File(dir, "b-%012d-a%d.ndjson".format(batch.seq, batch.attempts + 1))
        src.renameTo(dst)
    }

    override fun enforceLimits(maxBytes: Long, maxAgeMs: Long, debug: Boolean) {
        val now = System.currentTimeMillis()
        val sealed = (dir.listFiles() ?: emptyArray())
            .filter { nameRegex.matches(it.name) }
            .sortedBy { it.name }

        // Age-based eviction.
        for (f in sealed) {
            if (now - f.lastModified() > maxAgeMs) {
                if (debug) Log.w(TAG, "Evicting aged batch ${f.name}")
                f.delete()
            }
        }

        // Size-based eviction — drop oldest until within budget.
        val remaining = (dir.listFiles() ?: emptyArray())
            .filter { nameRegex.matches(it.name) }
            .sortedBy { it.name }
            .toMutableList()
        var total = remaining.sumOf { it.length() }
        var i = 0
        while (total > maxBytes && i < remaining.size) {
            val f = remaining[i]
            total -= f.length()
            if (debug) Log.w(TAG, "Evicting over-budget batch ${f.name}")
            f.delete()
            i++
        }
    }
}

/** In-memory fallback used when no Android Context is available (e.g. unit tests). */
internal class InMemoryEventStore : EventStore {
    private val current = mutableListOf<String>()
    private val sealed = mutableListOf<BatchFile>()
    private var seq = 0L
    private var currentBytes = 0L

    override fun append(eventJson: String) {
        current.add(eventJson)
        currentBytes += eventJson.toByteArray(Charsets.UTF_8).size
    }

    override fun currentCount(): Int = current.size

    override fun currentBytes(): Long = currentBytes

    override fun sealCurrent() {
        if (current.isEmpty()) return
        val s = seq++
        sealed.add(BatchFile("mem-$s", s, 0, System.currentTimeMillis(), current.toList()))
        current.clear()
        currentBytes = 0L
    }

    override fun batches(): List<BatchFile> = sealed.sortedBy { it.seq }

    override fun delete(batch: BatchFile) {
        sealed.removeAll { it.id == batch.id }
    }

    override fun bumpAttempt(batch: BatchFile) {
        val idx = sealed.indexOfFirst { it.id == batch.id }
        if (idx >= 0) sealed[idx] = sealed[idx].copy(attempts = sealed[idx].attempts + 1)
    }

    override fun enforceLimits(maxBytes: Long, maxAgeMs: Long, debug: Boolean) {
        val now = System.currentTimeMillis()
        sealed.removeAll { now - it.createdAtMs > maxAgeMs }
        var total = sealed.sumOf { b -> b.events.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } }
        while (total > maxBytes && sealed.isNotEmpty()) {
            val removed = sealed.removeAt(0)
            total -= removed.events.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        }
    }
}
