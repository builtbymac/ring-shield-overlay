package com.builtbymac.ringshield

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Context-aware background media controller for Nvidia Shield Pro when Doorbell Audio is toggled:
 *
 *  1. Live TV Context (e.g. TiviMate `ar.tvplayer.tv`, Live Channels):
 *     - Mutes `STREAM_MUSIC` internally (`adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE, 0)`)
 *       WITHOUT requesting transient audio focus or sending `KEYCODE_MEDIA_PAUSE`, so Live TV
 *       video keeps playing smoothly in the background while doorbell audio plays on
 *       `USAGE_ASSISTANCE_ACCESSIBILITY` (`STREAM_ACCESSIBILITY`, exempt from `STREAM_MUSIC` mute).
 *     - On audio toggle OFF or overlay dismiss: unmutes `STREAM_MUSIC` (`ADJUST_UNMUTE`).
 *
 *  2. Not-Live / VOD Context (Films, YouTube, Netflix, Stremio, Prime Video, Nuvio, iPlayer, etc.):
 *     - Requests `AUDIOFOCUS_GAIN_TRANSIENT` AND dispatches `KEYCODE_MEDIA_PAUSE` so the watching
 *       film/video pauses while doorbell audio plays (also muting `STREAM_MUSIC` while active so
 *       no residual audio bleeds through).
 *     - On audio toggle OFF or overlay dismiss: unmutes `STREAM_MUSIC`, abandons audio focus, and
 *       dispatches `KEYCODE_MEDIA_PLAY` if media was actively playing before we paused it.
 */
class BackgroundMediaController(private val context: Context) {

    enum class MediaMode {
        NONE,
        LIVE_TV_MUTED,
        VOD_PAUSED,
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeMode: MediaMode = MediaMode.NONE
    private var wasStreamMusicMutedBefore: Boolean = false
    private var wasMediaPlayingBefore: Boolean = false
    private var activeFocusRequest: AudioFocusRequest? = null

    fun onDoorbellAudioEnabled() {
        if (activeMode != MediaMode.NONE) return

        val fgPkg = detectForegroundPackage()
        val isLiveTv = fgPkg != null && LIVE_TV_PACKAGES.contains(fgPkg)
        wasStreamMusicMutedBefore = runCatching {
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        }.getOrDefault(false)
        wasMediaPlayingBefore = runCatching { audioManager.isMusicActive }.getOrDefault(false)

        Log.i(
            TAG,
            "Enabling doorbell audio: foregroundPkg=${fgPkg ?: "unknown"}, isLiveTv=$isLiveTv, " +
                "wasMusicActive=$wasMediaPlayingBefore, wasMuted=$wasStreamMusicMutedBefore"
        )

        if (isLiveTv) {
            activeMode = MediaMode.LIVE_TV_MUTED
            // Mute STREAM_MUSIC internally (flags=0 -> no UI toast, no pausing Live TV video)
            if (!wasStreamMusicMutedBefore) {
                runCatching {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        0,
                    )
                }.onFailure { Log.w(TAG, "adjustStreamVolume(ADJUST_MUTE) failed: ${it.message}") }
            }
        } else {
            activeMode = MediaMode.VOD_PAUSED
            // 1. Request transient audio focus so standard Android TV VOD players pause
            runCatching {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
                activeFocusRequest = req
                audioManager.requestAudioFocus(req)
            }.onFailure { Log.w(TAG, "requestAudioFocus failed: ${it.message}") }

            // 2. Dispatch explicit idempotent KEYCODE_MEDIA_PAUSE for players that ignore focus loss
            if (wasMediaPlayingBefore) {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }

            // 3. Also silence STREAM_MUSIC while doorbell audio is active so no transition bleed occurs
            if (!wasStreamMusicMutedBefore) {
                runCatching {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        0,
                    )
                }
            }
        }
    }

    fun onDoorbellAudioDisabled() {
        val mode = activeMode
        if (mode == MediaMode.NONE) return
        activeMode = MediaMode.NONE

        Log.i(TAG, "Disabling doorbell audio (restoring mode=$mode)")

        // 1. Unmute STREAM_MUSIC if it wasn't muted before we started
        if (!wasStreamMusicMutedBefore) {
            runCatching {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0,
                )
            }.onFailure { Log.w(TAG, "adjustStreamVolume(ADJUST_UNMUTE) failed: ${it.message}") }
        }

        // 2. If we paused VOD media, abandon audio focus and resume playback
        if (mode == MediaMode.VOD_PAUSED) {
            activeFocusRequest?.let { req ->
                runCatching { audioManager.abandonAudioFocusRequest(req) }
            }
            activeFocusRequest = null

            if (wasMediaPlayingBefore) {
                val shouldResume = wasMediaPlayingBefore
                wasMediaPlayingBefore = false
                mainHandler.postDelayed({
                    if (activeMode == MediaMode.NONE && shouldResume) {
                        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                    }
                }, 160L)
            }
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        runCatching {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
            Log.i(TAG, "Dispatched media keyEvent=$keyCode")
        }.onFailure { Log.w(TAG, "dispatchMediaKey($keyCode) failed: ${it.message}") }
    }

    private fun detectForegroundPackage(): String? {
        return runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 6 * 3600 * 1000L, now) ?: return null
            val ev = UsageEvents.Event()
            var lastPkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val pkg = ev.packageName
                    if (!pkg.isNullOrBlank() &&
                        pkg != context.packageName &&
                        pkg != "com.android.systemui"
                    ) {
                        lastPkg = pkg
                    }
                }
            }
            lastPkg
        }.getOrNull()
    }

    companion object {
        private const val TAG = "BackgroundMediaCtrl"

        // Dedicated Live TV packages on Android TV / Shield Pro (mute audio only, never pause video)
        private val LIVE_TV_PACKAGES = setOf(
            "ar.tvplayer.tv",              // TiviMate IPTV Player
            "ar.tvplayer.companion",
            "com.google.android.tv",       // Android TV Live Channels
            "com.silicondust.view",        // HDHomeRun Live TV
            "com.hdhomerun.view",
            "tv.pluto.android",            // Pluto TV Live
            "com.ottnavigator.tv",         // OTT Navigator IPTV
            "com.sparkle.tv",              // Sparkle TV IPTV
            "com.instaip.iptv",
            "com.nst.iptvsmarterstvbox",
        )
    }
}
