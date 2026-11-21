package com.builtbymac.ringshield

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RelayRingEvent(
    val eventId: String,
    val eventType: String,
    val deviceId: String?,
    val ringEventTsMs: Long?,
    val relayRxTsMs: Long?,
    val synthetic: Boolean,
    val thumbnailUrl: String? = null,
)

data class WhepResponse(
    val sdpAnswer: String,
    val sessionUrl: String?,
    val ringRttMs: Double?,
)

class RelayClient(
    private val cfg: PocConfig,
    private val onEvent: (RelayRingEvent) -> Unit,
    private val onCommand: (String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    private val connSeq = java.util.concurrent.atomic.AtomicLong(0)

    fun connect() {
        stopped = false
        val wsBase = cfg.relayUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val url = if (cfg.lanToken != null) "$wsBase/ws?token=${cfg.lanToken}" else "$wsBase/ws"

        val reqBuilder = Request.Builder().url(url)
        cfg.lanToken?.let { reqBuilder.header("Authorization", "Bearer $it") }

        val mySeq = connSeq.incrementAndGet()
        val oldWs = ws
        ws = null
        oldWs?.close(1000, "reconnecting")

        ws = http.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (mySeq != connSeq.get()) return
                Log.i(TAG, "Connected to relay WebSocket: $wsBase/ws")
                onConnectionChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (mySeq != connSeq.get()) return
                runCatching {
                    val root = JSONObject(text)
                    when (root.optString("kind")) {
                        "overlay_command" -> {
                            val action = root.optString("action", "").trim()
                            if (action.isNotBlank()) onCommand(action)
                        }
                        "ring_event" -> {
                            val ev = root.getJSONObject("event")
                            onEvent(
                                RelayRingEvent(
                                    eventId = ev.optString("event_id", "unknown"),
                                    eventType = ev.optString("event_type", "unknown"),
                                    deviceId = if (ev.isNull("device_id")) null else ev.optString("device_id", "").takeIf { it.isNotBlank() && it != "null" },
                                    ringEventTsMs = if (ev.isNull("ring_event_ts_ms")) null else ev.optLong("ring_event_ts_ms"),
                                    relayRxTsMs = if (ev.isNull("relay_rx_ts_ms")) null else ev.optLong("relay_rx_ts_ms"),
                                    synthetic = ev.optBoolean("synthetic", false),
                                    thumbnailUrl = if (ev.isNull("thumbnail_url")) null else ev.optString("thumbnail_url", "").takeIf { it.isNotBlank() && it != "null" },
                                )
                            )
                        }
                    }
                }.onFailure { Log.e(TAG, "Malformed relay message: ${it.message}") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (mySeq != connSeq.get()) return
                Log.w(TAG, "Relay WS closed ($code): $reason")
                onConnectionChanged(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (mySeq != connSeq.get()) return
                Log.e(TAG, "Relay WS error: ${t.message}")
                onConnectionChanged(false)
            }
        })
    }

    suspend fun startWhep(deviceId: String, sdpOffer: String): WhepResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("sdpOffer", sdpOffer)
        }.toString()

        val reqBuilder = Request.Builder()
            .url("${cfg.relayUrl}/api/whep/start")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        cfg.lanToken?.let { reqBuilder.header("Authorization", "Bearer $it") }

        http.newCall(reqBuilder.build()).execute().use { res ->
            val bodyStr = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                error("Relay WHEP start HTTP ${res.code}: $bodyStr")
            }
            val json = JSONObject(bodyStr)
            WhepResponse(
                sdpAnswer = json.getString("sdpAnswer"),
                sessionUrl = if (json.isNull("sessionUrl")) null else json.optString("sessionUrl", "").takeIf { it.isNotBlank() && it != "null" },
                ringRttMs = if (json.has("ringRttMs") && !json.isNull("ringRttMs")) json.optDouble("ringRttMs") else null,
            )
        }
    }

    suspend fun stopWhep(sessionUrl: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("sessionUrl", sessionUrl) }.toString()
        val reqBuilder = Request.Builder()
            .url("${cfg.relayUrl}/api/whep/stop")
            .delete(payload.toRequestBody(JSON_MEDIA_TYPE))
        cfg.lanToken?.let { reqBuilder.header("Authorization", "Bearer $it") }

        runCatching {
            http.newCall(reqBuilder.build()).execute().use { /* fire-and-forget cleanup */ }
        }.onFailure { Log.w(TAG, "WHEP stop failed: ${it.message}") }
    }

    suspend fun setAppAudioMute(packageName: String?, muted: Boolean) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            if (!packageName.isNullOrBlank()) put("packageName", packageName)
            put("muted", muted)
        }.toString()
        val reqBuilder = Request.Builder()
            .url("${cfg.relayUrl}/api/audio/app-mute")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        cfg.lanToken?.let { reqBuilder.header("Authorization", "Bearer $it") }

        runCatching {
            http.newCall(reqBuilder.build()).execute().use { /* per-package PLAY_AUDIO mute */ }
        }.onFailure { Log.w(TAG, "setAppAudioMute failed: ${it.message}") }
    }

    suspend fun downloadThumbnailBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                res.body?.bytes()
            }
        }.onFailure { Log.d(TAG, "Thumbnail fetch skipped: ${it.message}") }.getOrNull()
    }

    fun close() {
        stopped = true
        ws?.close(1000, "service_stopped")
        ws = null
    }

    val isStopped: Boolean get() = stopped

    companion object {
        private const val TAG = "RelayClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
