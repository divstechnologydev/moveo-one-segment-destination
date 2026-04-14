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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Moveo One Destination Plugin for Segment Analytics (Kotlin)
 *
 * This plugin intercepts all Segment events and forwards them to the Moveo One API,
 * so you can use a single Segment instrumentation to feed both Segment and Moveo One.
 *
 * ──────────────────────────────────────────────────
 * INTEGRATION — add this to your Analytics setup:
 *
 *   analytics.add(plugin = MoveoDestination(apiKey = "YOUR_MOVEO_API_KEY"))
 *
 * That's it. Every track / screen / identify / group call you already make
 * through Segment will automatically be forwarded to Moveo One.
 * ──────────────────────────────────────────────────
 *
 * @param apiKey   Your Moveo One API key.
 * @param endpoint Override the default API endpoint (optional).
 * @param debug    When true, request and response details are printed to Logcat.
 */
class MoveoDestination(
    private val apiKey: String,
    private val endpoint: String = "https://api.moveo.one/api/analytic/external/segment-destination",
    private val debug: Boolean = false
) : EventPlugin {

    override val type = Plugin.Type.Enrichment
    override lateinit var analytics: Analytics

    companion object {
        private const val TAG = "MoveoDestination"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
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
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun ship(payload: BaseEvent) {
        try {
            val body = buildPayload(payload).toString()
            sendRequest(body)
        } catch (e: Exception) {
            if (debug) Log.e(TAG, "Failed to send [${payload.type}]: ${e.message}", e)
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
        } finally {
            conn.disconnect()
        }
    }
}
