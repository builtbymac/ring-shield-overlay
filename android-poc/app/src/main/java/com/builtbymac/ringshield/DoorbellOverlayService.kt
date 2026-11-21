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
    private lateinit var backgroundMediaCtrl: BackgroundMediaController

    private var reconnectJob: Job? = null
    private var prewarmExpiryJob: Job? = null
    private var activeSessionJob: Job? = null
    private var rolloverJob: Job? = null

    private var prewarmedOffer: PrewarmedOffer? = null
    private var activeOffer: PrewarmedOffer? = null
    private var activeVideoTrack: org.webrtc.VideoTrack? = null
    private var activeAudioTrack: org.webrtc.AudioTrack? = null
    private var isDoorbellAudioEnabled: Boolean = false
    private var activeRenderer: TextureViewRenderer? = null
    private var activeSessionUrl: String? = null
    private var activeDeviceId: String? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        enterForeground()

        cfg = ConfigStore.load(this)
        latencyLogger = LatencyLogger(this)
        overlayManager = OverlayManager(this)
        webrtc = WebRtcEngine(applicationContext, cfg.softwareDecoder, cfg.minPlayoutDelayMs)
        relay = RelayClient(
            cfg = cfg,
            onEvent = { ev -> scope.launch { handleRelayEvent(ev) } },
            onCommand = { cmd -> scope.launch { handleRemoteCommand(cmd) } },
            onConnectionChanged = { connected ->
                isRelayConnected = connected
                if (connected) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    relay.sendOverlayState(overlayManager.isShowing, overlayManager.isFullscreen)
                } else if (!relay.isStopped) {
                    scheduleReconnect()
                }
            }
        )
        backgroundMediaCtrl = BackgroundMediaController(this) { pkg, muted ->
            scope.launch { relay.setAppAudioMute(pkg, muted) }
        }

        registerNetworkWatcher()
        relay.connect()
        scope.launch { prewarmPipeline() }
        Log.i(TAG, "Service started (mode=${cfg.mode}, relay=${cfg.relayUrl}, swDec=${cfg.softwareDecoder})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always answer startForegroundService() idempotently to avoid RemoteServiceException
        enterForeground()

        when (intent?.action) {
            ACTION_TEST_TRIGGER, ACTION_SHOW_DOORBELL -> {
                scope.launch { handleRemoteCommand("show") }
            }
            ACTION_SHOW_FULLSCREEN -> {
                scope.launch { handleRemoteCommand("fullscreen") }
            }
            ACTION_TOGGLE_FULLSCREEN -> {
                scope.launch { handleRemoteCommand("toggle_fullscreen") }
            }
            ACTION_DISMISS -> {
                scope.launch { handleRemoteCommand("dismiss") }
            }
            ACTION_RELOAD_CONFIG -> {
                reloadConfig()
            }
            else -> {
                if (activeOffer == null && prewarmedOffer == null) {
                    scope.launch { prewarmPipeline() }
                }
            }
        }
        return START_STICKY
    }

    private fun reloadConfig() {
        val prev = cfg
        cfg = ConfigStore.load(this)
        Log.i(TAG, "Reloaded config.json (corner=${cfg.corner}, size=${cfg.overlayWidthPx}x${cfg.overlayHeightPx}, dur=${cfg.overlayDurationSec}s, crop=${cfg.cropRingWatermark}, dot=${cfg.showLiveDot}, bar=${cfg.showCountdownBar}, delay=${cfg.minPlayoutDelayMs}ms)")
        if (prev.softwareDecoder != cfg.softwareDecoder || prev.minPlayoutDelayMs != cfg.minPlayoutDelayMs) {
            stopActiveStream()
            clearPrewarm()
            webrtc.release()
            webrtc = WebRtcEngine(applicationContext, cfg.softwareDecoder, cfg.minPlayoutDelayMs)
        }
    }

    private fun handleRemoteCommand(action: String) {
        Log.i(TAG, "Handling overlay command: $action (showing=${overlayManager.isShowing}, fullscreen=${overlayManager.isFullscreen})")
        when (action.lowercase()) {
            "toggle_fullscreen" -> {
                if (overlayManager.isShowing) {
                    overlayManager.toggleFullscreen()
                } else {
                    triggerOnDemand(startFullscreen = false)
                }
            }
            "show" -> {
                if (!overlayManager.isShowing) {
                    triggerOnDemand(startFullscreen = false)
                } else if (overlayManager.isFullscreen) {
                    overlayManager.setFullscreen(false)
                }
            }
            "fullscreen" -> {
                if (overlayManager.isShowing) {
                    overlayManager.setFullscreen(true)
                } else {
                    triggerOnDemand(startFullscreen = true)
                }
            }
            "dismiss" -> {
                if (overlayManager.isShowing) {
                    overlayManager.dismissAnimated()
                } else {
                    stopActiveStream()
                }
            }
        }
    }

    private fun triggerOnDemand(startFullscreen: Boolean) {
        startDoorbellOverlay(
            event = RelayRingEvent(
                eventId = "ondemand_${System.currentTimeMillis()}",
                eventType = "button_press",
                deviceId = cfg.deviceId.takeIf { it.isNotBlank() },
                ringEventTsMs = System.currentTimeMillis(),
                relayRxTsMs = System.currentTimeMillis(),
                synthetic = true,
            ),
            startFullscreen = startFullscreen,
            keepAliveUntilDismissed = startFullscreen,
        )
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
                event.thumbnailUrl?.let { thumbUrl ->
                    scope.launch {
                        val bytes = relay.downloadThumbnailBytes(thumbUrl) ?: return@launch
                        val frosted = SnapshotStore.saveBytes(applicationContext, bytes)
                        frosted?.recycle()
                    }
                }
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

    private fun startDoorbellOverlay(
        event: RelayRingEvent,
        startFullscreen: Boolean = false,
        keepAliveUntilDismissed: Boolean = false,
    ) {
        activeSessionJob?.cancel()
        stopActiveStream()

        val deviceId = event.deviceId ?: cfg.deviceId
        if (deviceId.isBlank()) {
            Log.e(TAG, "No deviceId in event or config.json; cannot start WHEP stream")
            return
        }
        activeDeviceId = deviceId

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
                    startFullscreen = startFullscreen,
                    keepAliveUntilDismissed = keepAliveUntilDismissed,
                    onFirstFrameVisible = {
                        val now = SystemClock.elapsedRealtime()
                        if (trial.firstFrameMonoMs == null) {
                            trial.firstFrameMonoMs = now
                            trial.overlayVisibleMonoMs = now
                            trial.success = true
                            latencyLogger.record(trial)
                        }
                        // Capture a clean exposed live frame at t=2.2s for the next frosted Liquid Glass connecting screen
                        scope.launch {
                            delay(2_200L)
                            val bmp = overlayManager.captureSnapshotBitmap() ?: return@launch
                            try {
                                SnapshotStore.saveBitmap(applicationContext, bmp)
                            } finally {
                                bmp.recycle()
                            }
                        }
                    },
                    onKeepAliveActivated = {
                        armSeamlessRollover(deviceId)
                    },
                    onToggleAudio = {
                        toggleDoorbellAudio()
                    },
                    onOverlayStateChanged = { showing, fullscreen ->
                        relay.sendOverlayState(showing, fullscreen)
                    },
                    onAutoDismiss = {
                        if (!trial.success && trial.error == null) {
                            trial.error = "overlay_timeout_no_video_frame"
                            latencyLogger.record(trial)
                        }
                        stopActiveStream()
                    }
                ) ?: error("Overlay permission missing (grant SYSTEM_ALERT_WINDOW)")

                // If Ring webhook carried a fresh activity thumbnail URL, upgrade the frosted preview while connecting
                event.thumbnailUrl?.let { thumbUrl ->
                    scope.launch {
                        val bytes = relay.downloadThumbnailBytes(thumbUrl) ?: return@launch
                        val frosted = SnapshotStore.saveBytes(applicationContext, bytes) ?: return@launch
                        overlayManager.updateFrostedPreview(frosted)
                    }
                }

                val offer = freshPrewarm ?: webrtc.prepareOffer(cfg)
                activeOffer = offer
                trial.iceReadyMonoMs = SystemClock.elapsedRealtime()

                trial.whepSentMonoMs = SystemClock.elapsedRealtime()
                val answer = relay.startWhep(deviceId, offer.sdpOffer)
                trial.whepAnswerMonoMs = SystemClock.elapsedRealtime()
                trial.ringServerRttMs = answer.ringRttMs
                activeSessionUrl = answer.sessionUrl

                webrtc.applyAnswer(offer, answer.sdpAnswer, cfg.stripTwcc)

                // Bind remote AudioTrack as soon as it arrives (stays muted unless user toggled audio ON)
                if (cfg.includeAudioTransceiver) {
                    scope.launch {
                        runCatching {
                            val audioTrack = withTimeout(10_000L) { offer.audioTrackDeferred.await() }
                            withContext(Dispatchers.Main.immediate) {
                                if (activeOffer === offer) {
                                    activeAudioTrack = audioTrack
                                    webrtc.setDoorbellAudioEnabled(audioTrack, isDoorbellAudioEnabled)
                                }
                            }
                        }
                    }
                }

                // Wait for remote VideoTrack & attach directly to GPU TextureViewRenderer
                val videoTrack = withTimeout(10_000L) { offer.videoTrackDeferred.await() }
                withContext(Dispatchers.Main.immediate) {
                    activeVideoTrack = videoTrack
                    activeRenderer = renderer
                    webrtc.attachVideoSink(videoTrack, renderer)
                }

                // Track ICE connection time for telemetry
                trial.iceConnectedMonoMs = withTimeout(10_000L) { offer.iceConnectedDeferred.await() }

                if (overlayManager.isKeepAlive) {
                    armSeamlessRollover(deviceId)
                }

                // Log detailed inbound-rtp jitter/freeze/loss stats every 4 s for diagnostics
                repeat(5) { sampleIdx ->
                    delay(4_000L)
                    if (activeOffer !== offer) return@repeat
                    offer.peerConnection.getStats { report ->
                        report.statsMap.values
                            .filter { it.type == "inbound-rtp" }
                            .forEach { s ->
                                val kind = s.members["kind"]
                                val jbDelay = (s.members["jitterBufferDelay"] as? Number)?.toDouble()
                                val jbEmitted = (s.members["jitterBufferEmittedCount"] as? Number)?.toDouble()
                                val avgJbMs = if (jbDelay != null && jbEmitted != null && jbEmitted > 0) {
                                    "%.1f".format((jbDelay / jbEmitted) * 1000.0)
                                } else "n/a"
                                val jitterMs = (s.members["jitter"] as? Number)?.toDouble()?.let { "%.1f".format(it * 1000.0) } ?: "n/a"
                                if (kind == "video") {
                                    Log.i(
                                        TAG,
                                        "RTC stats[#${sampleIdx + 1}]: " +
                                            "${s.members["frameWidth"]}x${s.members["frameHeight"]}@${s.members["framesPerSecond"]}fps " +
                                            "dec=${s.members["framesDecoded"]} drop=${s.members["framesDropped"]} " +
                                            "freezes=${s.members["freezeCount"]}(${s.members["totalFreezesDuration"]}s) " +
                                            "pkts=${s.members["packetsReceived"]} lost=${s.members["packetsLost"]} " +
                                            "nack=${s.members["nackCount"]} pli=${s.members["pliCount"]} " +
                                            "jitter=${jitterMs}ms jb=${avgJbMs}ms impl=${s.members["decoderImplementation"]}"
                                    )
                                } else if (kind == "audio") {
                                    Log.i(
                                        TAG,
                                        "RTC AUDIO stats[#${sampleIdx + 1}]: " +
                                            "pkts=${s.members["packetsReceived"]} bytes=${s.members["bytesReceived"]} " +
                                            "lost=${s.members["packetsLost"]} samples=${s.members["totalSamplesReceived"]} " +
                                            "level=${s.members["audioLevel"]} energy=${s.members["totalAudioEnergy"]} " +
                                            "jitter=${jitterMs}ms jb=${avgJbMs}ms"
                                    )
                                }
                            }
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

    /**
     * Ring battery doorbells cap individual WHEP sessions at 30 s.
     * When the user has chosen to keep watching (on-demand or expanded to fullscreen),
     * silently open a replacement WHEP session every 24 s in the background and hot-swap
     * the VideoSink on the first decoded frame so playback continues seamlessly until Back!
     */
    private fun armSeamlessRollover(deviceId: String) {
        if (rolloverJob?.isActive == true) return
        rolloverJob = scope.launch {
            while (overlayManager.isShowing && overlayManager.isKeepAlive) {
                delay(24_000L)
                if (!overlayManager.isShowing || !overlayManager.isKeepAlive) break
                val renderer = activeRenderer ?: break

                Log.i(TAG, "Keep-alive active: preparing seamless background WHEP rollover...")
                var nextOffer: PrewarmedOffer? = null
                var nextSessionUrl: String? = null
                try {
                    nextOffer = webrtc.prepareOffer(cfg)
                    val answer = relay.startWhep(deviceId, nextOffer.sdpOffer)
                    nextSessionUrl = answer.sessionUrl
                    webrtc.applyAnswer(nextOffer, answer.sdpAnswer, cfg.stripTwcc)

                    val nextTrack = withTimeout(8_000L) { nextOffer.videoTrackDeferred.await() }
                    val nextAudioTrack = if (cfg.includeAudioTransceiver) {
                        kotlinx.coroutines.withTimeoutOrNull(1_500L) { nextOffer.audioTrackDeferred.await() }
                    } else null
                    val firstFrameReady = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val probeSink = org.webrtc.VideoSink { _ -> firstFrameReady.complete(Unit) }
                    withContext(Dispatchers.Main.immediate) {
                        nextTrack.addSink(probeSink)
                    }
                    withTimeout(8_000L) { firstFrameReady.await() }

                    // Atomic hot-swap onto the existing TextureViewRenderer with zero black screen!
                    val oldTrack = activeVideoTrack
                    val oldAudio = activeAudioTrack
                    val oldOffer = activeOffer
                    val oldSessionUrl = activeSessionUrl

                    withContext(Dispatchers.Main.immediate) {
                        if (!overlayManager.isShowing) {
                            nextTrack.removeSink(probeSink)
                            webrtc.closeOffer(nextOffer)
                            nextSessionUrl?.let { scope.launch { relay.stopWhep(it) } }
                            return@withContext
                        }
                        oldTrack?.let { runCatching { it.removeSink(renderer) } }
                        oldAudio?.let { runCatching { it.setEnabled(false) } }
                        nextTrack.removeSink(probeSink)
                        nextTrack.addSink(renderer)
                        activeVideoTrack = nextTrack
                        activeAudioTrack = nextAudioTrack
                        webrtc.setDoorbellAudioEnabled(nextAudioTrack, isDoorbellAudioEnabled)
                        activeOffer = nextOffer
                        activeSessionUrl = nextSessionUrl
                    }

                    webrtc.closeOffer(oldOffer)
                    if (oldSessionUrl != null) {
                        scope.launch { relay.stopWhep(oldSessionUrl) }
                    }
                    Log.i(TAG, "Seamless WHEP rollover complete — continuous live viewing active")
                } catch (ex: Throwable) {
                    Log.w(TAG, "Background WHEP rollover failed: ${ex.message}")
                    webrtc.closeOffer(nextOffer)
                    nextSessionUrl?.let { scope.launch { relay.stopWhep(it) } }
                    delay(2_000L)
                }
            }
        }
    }

    private fun toggleDoorbellAudio(): Boolean {
        val nextState = !isDoorbellAudioEnabled
        isDoorbellAudioEnabled = nextState
        if (nextState) {
            backgroundMediaCtrl.onDoorbellAudioEnabled()
            webrtc.setDoorbellAudioEnabled(activeAudioTrack, true)
        } else {
            webrtc.setDoorbellAudioEnabled(activeAudioTrack, false)
            backgroundMediaCtrl.onDoorbellAudioDisabled()
        }
        return nextState
    }

    private fun stopActiveStream() {
        rolloverJob?.cancel()
        rolloverJob = null
        activeDeviceId = null

        val wasAudioEnabled = isDoorbellAudioEnabled
        isDoorbellAudioEnabled = false
        val aTrack = activeAudioTrack
        activeAudioTrack = null
        webrtc.setDoorbellAudioEnabled(aTrack, false)
        if (wasAudioEnabled) {
            backgroundMediaCtrl.onDoorbellAudioDisabled()
        } else {
            backgroundMediaCtrl.onDoorbellAudioDisabled()
        }

        val track = activeVideoTrack
        val rend = activeRenderer
        activeVideoTrack = null
        activeRenderer = null
        // Grab one final live snapshot right before closing so the next connecting preview is maximally fresh
        overlayManager.captureSnapshotBitmap()?.let { finalBmp ->
            scope.launch {
                try {
                    SnapshotStore.saveBitmap(applicationContext, finalBmp)
                } finally {
                    finalBmp.recycle()
                }
            }
        }
        if (track != null && rend != null) {
            runCatching { track.removeSink(rend) }
        }

        overlayManager.hideImmediate()

        val offer = activeOffer
        activeOffer = null
        webrtc.closeOffer(offer)

        val sessionUrl = activeSessionUrl
        activeSessionUrl = null
        if (sessionUrl != null) {
            scope.launch { relay.stopWhep(sessionUrl) }
        }
        if (offer != null && prewarmedOffer == null) {
            scope.launch {
                delay(400L)
                if (activeOffer == null && prewarmedOffer == null) {
                    prewarmPipeline()
                }
            }
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
        const val ACTION_SHOW_DOORBELL = "com.builtbymac.ringshield.ACTION_SHOW_DOORBELL"
        const val ACTION_SHOW_FULLSCREEN = "com.builtbymac.ringshield.ACTION_SHOW_FULLSCREEN"
        const val ACTION_TOGGLE_FULLSCREEN = "com.builtbymac.ringshield.ACTION_TOGGLE_FULLSCREEN"
        const val ACTION_DISMISS = "com.builtbymac.ringshield.ACTION_DISMISS"
        const val ACTION_RELOAD_CONFIG = "com.builtbymac.ringshield.ACTION_RELOAD_CONFIG"

        @Volatile var isRelayConnected: Boolean = false
    }
}
