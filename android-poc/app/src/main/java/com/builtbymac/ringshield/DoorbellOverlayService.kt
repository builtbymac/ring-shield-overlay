package com.builtbymac.ringshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DoorbellOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var cfg: PocConfig
    private lateinit var latencyLogger: LatencyLogger
    private lateinit var overlayManager: OverlayManager
    private lateinit var webrtc: WebRtcEngine
    private lateinit var relay: RelayClient

    private var reconnectJob: Job? = null
    private var prewarmExpiryJob: Job? = null
    private var activeSessionJob: Job? = null

    private var prewarmedOffer: PrewarmedOffer? = null
    private var activeOffer: PrewarmedOffer? = null
    private var activeSessionUrl: String? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        enterForeground()

        cfg = ConfigStore.load(this)
        latencyLogger = LatencyLogger(this)
        overlayManager = OverlayManager(this)
        webrtc = WebRtcEngine(applicationContext, cfg.softwareDecoder)
        relay = RelayClient(
            cfg = cfg,
            onEvent = { ev -> scope.launch { handleRelayEvent(ev) } },
            onConnectionChanged = { connected ->
                isRelayConnected = connected
                if (connected) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                } else if (!relay.isStopped) {
                    scheduleReconnect()
                }
            }
        )

        registerNetworkWatcher()
        relay.connect()
        Log.i(TAG, "Service started (mode=${cfg.mode}, relay=${cfg.relayUrl}, swDec=${cfg.softwareDecoder})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always answer startForegroundService() idempotently to avoid RemoteServiceException
        enterForeground()

        if (intent?.action == ACTION_TEST_TRIGGER) {
            scope.launch {
                handleRelayEvent(
                    RelayRingEvent(
                        eventId = "local_${System.currentTimeMillis()}",
                        eventType = "button_press",
                        deviceId = cfg.deviceId.takeIf { it.isNotBlank() },
                        ringEventTsMs = System.currentTimeMillis(),
                        relayRxTsMs = System.currentTimeMillis(),
                        synthetic = true,
                    )
                )
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterNetworkWatcher()
        stopActiveStream()
        clearPrewarm()
        relay.close()
        webrtc.release()
        scope.cancel()
        isRelayConnected = false
        super.onDestroy()
    }

    private suspend fun handleRelayEvent(event: RelayRingEvent) {
        val type = event.eventType.lowercase()
        Log.i(TAG, "Received event type=$type device=${event.deviceId} synthetic=${event.synthetic}")

        when {
            type.contains("motion") || type.contains("person") -> {
                if (cfg.mode == TriggerMode.MOTION_PREWARM && activeOffer == null) {
                    prewarmPipeline()
                }
            }
            type.contains("button") || type.contains("ding") || type.contains("press") -> {
                startDoorbellOverlay(event)
            }
            else -> Log.d(TAG, "Ignoring non-doorbell event type=$type")
        }
    }

    /**
     * Zero-side-effect pre-warming: creates the PeerConnection and finishes local STUN ICE
     * gathering BEFORE the visitor presses the button — no Ring API call, no blue LED,
     * no battery drain, and zero risk of suppressing the physical doorbell chime.
     */
    private suspend fun prewarmPipeline() {
        clearPrewarm()
        runCatching {
            val t0 = SystemClock.elapsedRealtime()
            val offer = webrtc.prepareOffer(cfg)
            prewarmedOffer = offer
            Log.i(TAG, "Pipeline pre-warmed in ${SystemClock.elapsedRealtime() - t0} ms (TTL=${cfg.prewarmTtlMs} ms)")
            prewarmExpiryJob = scope.launch {
                delay(cfg.prewarmTtlMs)
                if (prewarmedOffer === offer) {
                    Log.d(TAG, "Pre-warmed PeerConnection expired unused; closing")
                    clearPrewarm()
                }
            }
        }.onFailure { Log.w(TAG, "Pre-warm failed: ${it.message}") }
    }

    private fun clearPrewarm() {
        prewarmExpiryJob?.cancel()
        prewarmExpiryJob = null
        webrtc.closeOffer(prewarmedOffer)
        prewarmedOffer = null
    }

    private fun startDoorbellOverlay(event: RelayRingEvent) {
        activeSessionJob?.cancel()
        stopActiveStream()

        val deviceId = event.deviceId ?: cfg.deviceId
        if (deviceId.isBlank()) {
            Log.e(TAG, "No deviceId in event or config.json; cannot start WHEP stream")
            return
        }

        val freshPrewarm = prewarmedOffer?.takeIf {
            SystemClock.elapsedRealtime() - it.createdAtMonoMs <= cfg.prewarmTtlMs
        }
        prewarmExpiryJob?.cancel()
        prewarmExpiryJob = null
        if (freshPrewarm != null) {
            prewarmedOffer = null
        } else {
            clearPrewarm()
        }

        val trial = TrialTimestamps(
            eventId = event.eventId,
            eventType = event.eventType,
            mode = cfg.mode,
            softwareDecoder = cfg.softwareDecoder,
            usedPrewarm = freshPrewarm != null,
            synthetic = event.synthetic,
            ringEventTsMs = event.ringEventTsMs,
            relayRxTsMs = event.relayRxTsMs,
        )

        activeSessionJob = scope.launch {
            try {
                val renderer = overlayManager.showOverlay(
                    cfg = cfg,
                    eglContext = webrtc.eglBase.eglBaseContext,
                    onFirstFrameVisible = {
                        val now = SystemClock.elapsedRealtime()
                        if (trial.firstFrameMonoMs == null) {
                            trial.firstFrameMonoMs = now
                            trial.overlayVisibleMonoMs = now
                            trial.success = true
                            latencyLogger.record(trial)
                        }
                    },
                    onAutoDismiss = {
                        if (!trial.success && trial.error == null) {
                            trial.error = "overlay_timeout_no_video_frame"
                            latencyLogger.record(trial)
                        }
                        stopActiveStream()
                    }
                ) ?: error("Overlay permission missing (grant SYSTEM_ALERT_WINDOW)")

                overlayManager.setStatusText("GATHERING ICE…")
                val offer = freshPrewarm ?: webrtc.prepareOffer(cfg)
                activeOffer = offer
                trial.iceReadyMonoMs = SystemClock.elapsedRealtime()

                overlayManager.setStatusText("CALLING RING WHEP…")
                trial.whepSentMonoMs = SystemClock.elapsedRealtime()
                val answer = relay.startWhep(deviceId, offer.sdpOffer)
                trial.whepAnswerMonoMs = SystemClock.elapsedRealtime()
                trial.ringServerRttMs = answer.ringRttMs
                activeSessionUrl = answer.sessionUrl

                overlayManager.setStatusText("CONNECTING WEBRTC…")
                webrtc.applyAnswer(offer, answer.sdpAnswer, cfg.stripTwcc)

                // Wait for remote VideoTrack & attach directly to GPU TextureViewRenderer
                val videoTrack = withTimeout(10_000L) { offer.videoTrackDeferred.await() }
                withContext(Dispatchers.Main.immediate) {
                    webrtc.attachVideoSink(videoTrack, renderer)
                }

                // Track ICE connection time for telemetry
                trial.iceConnectedMonoMs = withTimeout(10_000L) { offer.iceConnectedDeferred.await() }
                if (!trial.success) {
                    overlayManager.setStatusText("ICE OK • DECODING…")
                }

                // Log inbound RTP/decoder stats after 3 s for PoC diagnostics
                delay(3_000L)
                offer.peerConnection.getStats { report ->
                    report.statsMap.values
                        .filter { it.type == "inbound-rtp" && it.members["kind"] == "video" }
                        .forEach { s ->
                            Log.i(
                                TAG,
                                "RTC video stats: pkts=${s.members["packetsReceived"]} " +
                                    "bytes=${s.members["bytesReceived"]} " +
                                    "decoded=${s.members["framesDecoded"]} " +
                                    "dropped=${s.members["framesDropped"]} " +
                                    "impl=${s.members["decoderImplementation"]}"
                            )
                        }
                }
            } catch (ex: Throwable) {
                Log.e(TAG, "Doorbell overlay session failed: ${ex.message}", ex)
                trial.error = ex.message ?: ex.javaClass.simpleName
                latencyLogger.record(trial)
                stopActiveStream()
            }
        }
    }

    private fun stopActiveStream() {
        overlayManager.hideImmediate()

        val offer = activeOffer
        activeOffer = null
        webrtc.closeOffer(offer)

        val sessionUrl = activeSessionUrl
        activeSessionUrl = null
        if (sessionUrl != null) {
            scope.launch { relay.stopWhep(sessionUrl) }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var waitMs = 1_500L
            while (!isRelayConnected && !relay.isStopped) {
                delay(waitMs)
                Log.i(TAG, "Reconnecting to relay...")
                relay.connect()
                waitMs = (waitMs * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun registerNetworkWatcher() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!isRelayConnected && !relay.isStopped) {
                        scope.launch { relay.connect() }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }
    }

    private fun unregisterNetworkWatcher() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        }
        networkCallback = null
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }

    private fun enterForeground() {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(intent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "DoorbellOverlayService"
        private const val CHANNEL_ID = "ring_shield_listener"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TEST_TRIGGER = "com.builtbymac.ringshield.ACTION_TEST_TRIGGER"

        @Volatile var isRelayConnected: Boolean = false
    }
}
