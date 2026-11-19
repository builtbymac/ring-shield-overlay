package com.builtbymac.ringshield

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TrialTimestamps(
    val eventId: String,
    val eventType: String,
    val mode: TriggerMode,
    val softwareDecoder: Boolean,
    val usedPrewarm: Boolean,
    val synthetic: Boolean,
    val ringEventTsMs: Long?,
    val relayRxTsMs: Long?,
    val shieldRxWallMs: Long = System.currentTimeMillis(),
    val shieldRxMonoMs: Long = SystemClock.elapsedRealtime(),
    var iceReadyMonoMs: Long? = null,
    var whepSentMonoMs: Long? = null,
    var whepAnswerMonoMs: Long? = null,
    var ringServerRttMs: Double? = null,
    var iceConnectedMonoMs: Long? = null,
    var firstFrameMonoMs: Long? = null,
    var overlayVisibleMonoMs: Long? = null,
    var success: Boolean = false,
    var error: String? = null,
)

class LatencyLogger(context: Context) {
    private val file: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks.jsonl")
    private val lock = Any()

    fun record(trial: TrialTimestamps) {
        val relayToShieldMs = trial.relayRxTsMs?.let { trial.shieldRxWallMs - it }
        val iceGatherMs = trial.iceReadyMonoMs?.let { it - trial.shieldRxMonoMs }
        val whepRttMs = if (trial.whepSentMonoMs != null && trial.whepAnswerMonoMs != null) {
            trial.whepAnswerMonoMs!! - trial.whepSentMonoMs!!
        } else null
        val iceConnectMs = if (trial.whepAnswerMonoMs != null && trial.iceConnectedMonoMs != null) {
            trial.iceConnectedMonoMs!! - trial.whepAnswerMonoMs!!
        } else null
        val firstFrameAfterConnectMs = if (trial.iceConnectedMonoMs != null && trial.firstFrameMonoMs != null) {
            trial.firstFrameMonoMs!! - trial.iceConnectedMonoMs!!
        } else null
        val totalShieldMs = trial.overlayVisibleMonoMs?.let { it - trial.shieldRxMonoMs }

        val isoNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(trial.shieldRxWallMs))

        val obj = JSONObject().apply {
            put("ts", isoNow)
            put("eventId", trial.eventId)
            put("eventType", trial.eventType)
            put("mode", trial.mode.name)
            put("softwareDecoder", trial.softwareDecoder)
            put("usedPrewarm", trial.usedPrewarm)
            put("synthetic", trial.synthetic)
            put("success", trial.success)
            if (trial.error != null) put("error", trial.error)
            if (trial.ringEventTsMs != null) put("ringEventTsMs", trial.ringEventTsMs)
            if (trial.relayRxTsMs != null) put("relayRxTsMs", trial.relayRxTsMs)
            put("shieldRxWallMs", trial.shieldRxWallMs)
            if (relayToShieldMs != null) put("relayToShieldMs", relayToShieldMs)
            if (iceGatherMs != null) put("iceGatherMs", iceGatherMs)
            if (whepRttMs != null) put("whepRttMs", whepRttMs)
            if (trial.ringServerRttMs != null) put("ringServerRttMs", trial.ringServerRttMs)
            if (iceConnectMs != null) put("iceConnectMs", iceConnectMs)
            if (firstFrameAfterConnectMs != null) put("firstFrameAfterConnectMs", firstFrameAfterConnectMs)
            if (totalShieldMs != null) put("totalShieldMs", totalShieldMs)
        }

        val line = obj.toString()
        Log.i(
            TAG,
            "BENCH mode=${trial.mode} prewarm=${trial.usedPrewarm} ok=${trial.success} " +
                "ice=${iceGatherMs}ms whep=${whepRttMs}ms(ring=${trial.ringServerRttMs}ms) " +
                "conn=${iceConnectMs}ms firstFrame=+${firstFrameAfterConnectMs}ms totalShield=${totalShieldMs}ms"
        )
        synchronized(lock) {
            runCatching { file.appendText("$line\n", Charsets.UTF_8) }
                .onFailure { Log.e(TAG, "Failed writing benchmarks.jsonl: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "LatencyLogger"
    }
}
