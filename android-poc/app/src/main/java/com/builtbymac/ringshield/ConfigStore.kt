package com.builtbymac.ringshield

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

enum class TriggerMode {
    COLD_START,
    MOTION_PREWARM,
}

data class PocConfig(
    val relayUrl: String = "http://192.168.1.100:8787",
    val lanToken: String? = null,
    val deviceId: String = "",
    val mode: TriggerMode = TriggerMode.MOTION_PREWARM,
    val overlayDurationSec: Int = 20,
    val overlayWidthPx: Int = 640,
    val overlayHeightPx: Int = 360,
    val softwareDecoder: Boolean = false,
    val stripTwcc: Boolean = false,
    val includeAudioTransceiver: Boolean = false,
    val iceGatherBudgetMs: Long = 1500L,
    val prewarmTtlMs: Long = 25_000L,
    val minPlayoutDelayMs: Int = 350,
)

object ConfigStore {
    private const val TAG = "ConfigStore"
    private const val FILE_NAME = "config.json"

    fun configFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun load(context: Context): PocConfig {
        val file = configFile(context)
        if (!file.exists()) {
            Log.i(TAG, "No config.json at ${file.absolutePath}; using defaults")
            return PocConfig()
        }
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val modeStr = json.optString("mode", "MOTION_PREWARM").uppercase()
            val mode = runCatching { TriggerMode.valueOf(modeStr) }.getOrDefault(TriggerMode.MOTION_PREWARM)
            PocConfig(
                relayUrl = json.optString("relayUrl", "http://192.168.1.100:8787").trimEnd('/'),
                lanToken = json.optString("lanToken", "").takeIf { it.isNotBlank() },
                deviceId = json.optString("deviceId", ""),
                mode = mode,
                overlayDurationSec = json.optInt("overlayDurationSec", 20).coerceIn(5, 180),
                overlayWidthPx = json.optInt("overlayWidthPx", 640).coerceIn(240, 1920),
                overlayHeightPx = json.optInt("overlayHeightPx", 360).coerceIn(135, 1080),
                softwareDecoder = json.optBoolean("softwareDecoder", false),
                stripTwcc = json.optBoolean("stripTwcc", false),
                includeAudioTransceiver = json.optBoolean("includeAudioTransceiver", false),
                iceGatherBudgetMs = json.optLong("iceGatherBudgetMs", 1500L).coerceIn(50L, 5000L),
                prewarmTtlMs = json.optLong("prewarmTtlMs", 25_000L).coerceIn(5_000L, 60_000L),
                minPlayoutDelayMs = json.optInt("minPlayoutDelayMs", 350).coerceIn(0, 1500),
            )
        } catch (ex: Throwable) {
            Log.e(TAG, "Failed to parse ${file.absolutePath}: ${ex.message}")
            PocConfig()
        }
    }
}
