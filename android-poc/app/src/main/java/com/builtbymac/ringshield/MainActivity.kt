package com.builtbymac.ringshield

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * D-pad friendly Android TV Control & Settings screen for Nvidia Shield Pro.
 * All settings persist immediately to config.json and hot-reload into DoorbellOverlayService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var cornerBtn: Button
    private lateinit var sizeBtn: Button
    private lateinit var durationBtn: Button
    private lateinit var dotBtn: Button
    private lateinit var smoothBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(
            this,
            Intent(this, DoorbellOverlayService::class.java)
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FF0B0E14"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
        }

        val title = TextView(this).apply {
            text = "Ring Shield Overlay"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val subtitle = TextView(this).apply {
            text = "Ultra-low-latency live doorbell PiP & fullscreen monitor"
            textSize = 14f
            setTextColor(Color.parseColor("#FF8A9AB0"))
            setPadding(0, dp(4), 0, dp(20))
        }

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#FFC8D6E5"))
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FF131924"))
                setStroke(1, Color.parseColor("#2AFFFFFF"))
            }
        }

        val actionsHeader = sectionLabel("Quick Actions")
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val testPipBtn = tvButton("Test Corner PiP", accent = true) {
            sendServiceAction(DoorbellOverlayService.ACTION_SHOW_DOORBELL)
        }
        val testFsBtn = tvButton("Test Fullscreen", accent = true) {
            sendServiceAction(DoorbellOverlayService.ACTION_SHOW_FULLSCREEN)
        }
        val toggleFsBtn = tvButton("Toggle PiP / Fullscreen") {
            sendServiceAction(DoorbellOverlayService.ACTION_TOGGLE_FULLSCREEN)
        }
        val dismissBtn = tvButton("Dismiss Overlay") {
            sendServiceAction(DoorbellOverlayService.ACTION_DISMISS)
        }

        listOf(testPipBtn, testFsBtn, toggleFsBtn, dismissBtn).forEach { btn ->
            actionsRow.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(12) }
            )
        }

        val settingsHeader = sectionLabel("Overlay Appearance & Playback (D-pad click to cycle)")

        cornerBtn = tvButton("") {
            updateConfig {
                val values = OverlayCorner.values()
                it.copy(corner = values[(it.corner.ordinal + 1) % values.size])
            }
        }
        sizeBtn = tvButton("") {
            updateConfig {
                when (it.overlayWidthPx) {
                    in 0..550 -> it.copy(overlayWidthPx = 640, overlayHeightPx = 360)
                    in 551..720 -> it.copy(overlayWidthPx = 800, overlayHeightPx = 450)
                    else -> it.copy(overlayWidthPx = 480, overlayHeightPx = 270)
                }
            }
        }
        durationBtn = tvButton("") {
            updateConfig {
                val next = when (it.overlayDurationSec) {
                    in 0..19 -> 20
                    20 -> 25
                    in 21..27 -> 30
                    else -> 15
                }
                it.copy(overlayDurationSec = next)
            }
        }
        dotBtn = tvButton("") {
            updateConfig { it.copy(showLiveDot = !it.showLiveDot) }
        }
        smoothBtn = tvButton("") {
            updateConfig {
                val next = when (it.minPlayoutDelayMs) {
                    0 -> 200
                    in 1..250 -> 350
                    else -> 0
                }
                it.copy(minPlayoutDelayMs = next)
            }
        }

        val permBtn = tvButton("Open Android Overlay Permission Settings") {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusView)
        root.addView(actionsHeader)
        root.addView(actionsRow)
        root.addView(settingsHeader)

        val settingsGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(cornerBtn, sizeBtn, durationBtn, dotBtn, smoothBtn, permBtn).forEach { btn ->
            settingsGrid.addView(
                btn,
                LinearLayout.LayoutParams(dp(440), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
            )
        }
        root.addView(settingsGrid)

        scroll.addView(root)
        setContentView(scroll)
        testPipBtn.requestFocus()
    }

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.startForegroundService(
            this,
            Intent(this, DoorbellOverlayService::class.java)
        )
        uiHandler.removeCallbacks(statusTicker)
        statusTicker.run()
    }

    override fun onPause() {
        uiHandler.removeCallbacks(statusTicker)
        super.onPause()
    }

    private fun updateConfig(transform: (PocConfig) -> PocConfig) {
        val updated = transform(ConfigStore.load(this))
        ConfigStore.save(this, updated)
        sendServiceAction(DoorbellOverlayService.ACTION_RELOAD_CONFIG)
        refreshUi()
    }

    private fun sendServiceAction(action: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, DoorbellOverlayService::class.java).apply { this.action = action }
        )
    }

    private fun refreshUi() {
        val cfg = ConfigStore.load(this)
        val canOverlay = Settings.canDrawOverlays(this)
        val devShort = if (cfg.deviceId.length > 22) "…${cfg.deviceId.takeLast(12)}" else cfg.deviceId.ifBlank { "(auto)" }

        statusView.text = buildString {
            appendLine("Relay WebSocket    : ${if (DoorbellOverlayService.isRelayConnected) "CONNECTED" else "CONNECTING…"}  (${cfg.relayUrl})")
            appendLine("Overlay Permission : ${if (canOverlay) "GRANTED" else "MISSING"}  |  Device: $devShort")
            append("Remote Shortcut    : Map a Shield remote button to \"Doorbell PiP / Fullscreen\" (QuickToggleActivity)")
        }

        cornerBtn.text = "Corner Position : ${cfg.corner.label}"
        sizeBtn.text = "PiP Overlay Size : ${cfg.overlayWidthPx}×${cfg.overlayHeightPx}"
        durationBtn.text = "Auto-Dismiss Duration : ${cfg.overlayDurationSec}s"
        dotBtn.text = "Subtle Red Live Dot : ${if (cfg.showLiveDot) "ON" else "OFF (Pure Video)"}"
        val smoothLabel = when (cfg.minPlayoutDelayMs) {
            0 -> "0 ms (Raw / Lowest Latency)"
            350 -> "350 ms (Smooth — Recommended)"
            else -> "${cfg.minPlayoutDelayMs} ms (Extra Smooth)"
        }
        smoothBtn.text = "Jitter Smoothing Buffer : $smoothLabel"
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setTextColor(Color.parseColor("#FF64B5F6"))
        setPadding(0, dp(22), 0, dp(10))
    }

    private fun tvButton(label: String, accent: Boolean = false, onClick: () -> Unit): Button {
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(if (accent) "#FF1976D2" else "#FF1A2230"))
            setStroke(1, Color.parseColor("#2AFFFFFF"))
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(if (accent) "#FF2196F3" else "#FF263347"))
            setStroke(dp(2), Color.WHITE)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            addState(intArrayOf(), normalBg)
        }
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(ColorStateList.valueOf(Color.WHITE))
            background = states
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
