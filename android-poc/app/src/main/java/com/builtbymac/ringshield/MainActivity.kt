package com.builtbymac.ringshield

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Minimal Leanback-friendly PoC status screen for Nvidia Shield Pro.
 * Displays overlay permission state, loaded config.json path, and a D-pad test trigger.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(
            this,
            Intent(this, DoorbellOverlayService::class.java)
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            setBackgroundColor(Color.parseColor("#FF10141A"))
        }

        val title = TextView(this).apply {
            text = "Ring Shield Overlay — Phase 1 PoC"
            textSize = 26f
            setTextColor(Color.WHITE)
        }
        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#FFB0BEC5"))
            setPadding(0, 24, 0, 32)
        }

        val testBtn = Button(this).apply {
            text = "Test Live Overlay Now"
            isFocusable = true
            setOnClickListener {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, DoorbellOverlayService::class.java).apply {
                        action = DoorbellOverlayService.ACTION_TEST_TRIGGER
                    }
                )
            }
        }

        val permBtn = Button(this).apply {
            text = "Open Overlay Permission Settings"
            isFocusable = true
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(testBtn)
        root.addView(permBtn)
        setContentView(root)
        testBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val cfg = ConfigStore.load(this)
        val canOverlay = Settings.canDrawOverlays(this)
        val cfgPath = ConfigStore.configFile(this).absolutePath
        statusView.text = buildString {
            appendLine("Overlay Permission : ${if (canOverlay) "GRANTED" else "MISSING (grant via Settings or ADB)"}")
            appendLine("Relay WebSocket    : ${if (DoorbellOverlayService.isRelayConnected) "CONNECTED" else "CONNECTING..."}")
            appendLine("Relay URL          : ${cfg.relayUrl}")
            appendLine("Device ID          : ${cfg.deviceId.ifBlank { "(not set — push config.json)" }}")
            appendLine("Mode               : ${cfg.mode} | SW Decoder: ${cfg.softwareDecoder} | Strip TWCC: ${cfg.stripTwcc}")
            appendLine("Config Path        : $cfgPath")
        }
    }
}
