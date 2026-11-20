package com.builtbymac.ringshield

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

enum class TriggerMode {
    COLD_START,
    MOTION_PREWARM,
}

enum class OverlayCorner(val label: String) {
    TOP_RIGHT("Top-Right"),
    TOP_LEFT("Top-Left"),
    BOTTOM_RIGHT("Bottom-Right"),
    BOTTOM_LEFT("Bottom-Left"),
}

data class PocConfig(
    val relayUrl: String = "http://192.168.1.100:8787",
    val lanToken: String? = null,
    val deviceId: String = "",
    val mode: TriggerMode = TriggerMode.MOTION_PREWARM,
    val corner: OverlayCorner = OverlayCorner.TOP_RIGHT,
    val overlayDurationSec: Int = 20,
    val overlayWidthPx: Int = 640,
    val overlayHeightPx: Int = 360,
    val softwareDecoder: Boolean = false,
    val stripTwcc: Boolean = false,
    val includeAudioTransceiver: Boolean = false,
    val iceGatherBudgetMs: Long = 1500L,
    val prewarmTtlMs: Long = 45_000L,
    val minPlayoutDelayMs: Int = 200,
    val cropRingWatermark: Boolean = false,
    val showLiveDot: Boolean = false,
    val showCountdownBar: Boolean = false,
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
            val cornerStr = json.optString("corner", "TOP_RIGHT").uppercase()
            val corner = runCatching { OverlayCorner.valueOf(cornerStr) }.getOrDefault(OverlayCorner.TOP_RIGHT)
            PocConfig(
                relayUrl = json.optString("relayUrl", "http://192.168.1.100:8787").trimEnd('/'),
                lanToken = if (json.isNull("lanToken")) null else json.optString("lanToken", "").takeIf { it.isNotBlank() && it != "null" },
                deviceId = if (json.isNull("deviceId")) "" else json.optString("deviceId", "").takeUnless { it == "null" }.orEmpty(),
                mode = mode,
                corner = corner,
                overlayDurationSec = json.optInt("overlayDurationSec", 20).coerceIn(5, 180),
                overlayWidthPx = json.optInt("overlayWidthPx", 640).coerceIn(240, 1920),
                overlayHeightPx = json.optInt("overlayHeightPx", 360).coerceIn(135, 1080),
                softwareDecoder = json.optBoolean("softwareDecoder", false),
                stripTwcc = json.optBoolean("stripTwcc", false),
                includeAudioTransceiver = json.optBoolean("includeAudioTransceiver", false),
                iceGatherBudgetMs = json.optLong("iceGatherBudgetMs", 1500L).coerceIn(50L, 5000L),
                prewarmTtlMs = json.optLong("prewarmTtlMs", 45_000L).coerceIn(5_000L, 90_000L),
                minPlayoutDelayMs = json.optInt("minPlayoutDelayMs", 200).coerceIn(0, 1500),
                cropRingWatermark = json.optBoolean("cropRingWatermark", false),
                showLiveDot = json.optBoolean("showLiveDot", false),
                showCountdownBar = json.optBoolean("showCountdownBar", false),
            )
        } catch (ex: Throwable) {
            Log.e(TAG, "Failed to parse ${file.absolutePath}: ${ex.message}")
            PocConfig()
        }
    }

    fun save(context: Context, cfg: PocConfig) {
        val file = configFile(context)
        runCatching {
            file.parentFile?.mkdirs()
            val json = JSONObject().apply {
                put("relayUrl", cfg.relayUrl)
                cfg.lanToken?.let { put("lanToken", it) }
                put("deviceId", cfg.deviceId)
                put("mode", cfg.mode.name)
                put("corner", cfg.corner.name)
                put("overlayDurationSec", cfg.overlayDurationSec)
                put("overlayWidthPx", cfg.overlayWidthPx)
                put("overlayHeightPx", cfg.overlayHeightPx)
                put("softwareDecoder", cfg.softwareDecoder)
                put("stripTwcc", cfg.stripTwcc)
                put("includeAudioTransceiver", cfg.includeAudioTransceiver)
                put("iceGatherBudgetMs", cfg.iceGatherBudgetMs)
                put("prewarmTtlMs", cfg.prewarmTtlMs)
                put("minPlayoutDelayMs", cfg.minPlayoutDelayMs)
                put("cropRingWatermark", cfg.cropRingWatermark)
                put("showLiveDot", cfg.showLiveDot)
                put("showCountdownBar", cfg.showCountdownBar)
            }
            file.writeText(json.toString(2) + "\n", Charsets.UTF_8)
        }.onFailure { Log.e(TAG, "Failed to save ${file.absolutePath}: ${it.message}") }
    }
}
