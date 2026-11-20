package com.builtbymac.ringshield

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Zero-UI translucent shortcut activity that finishes in <1 ms.
 * Map any Shield TV remote button (e.g. Netflix/Menu via Button Mapper) to launch this activity:
 *  - If PiP overlay is showing  -> expands smoothly to Fullscreen (1080p/4K scaled)
 *  - If Fullscreen is showing   -> collapses back to corner PiP
 *  - If no overlay is showing   -> opens Front Door Live View immediately
 */
class QuickToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(
            this,
            Intent(this, DoorbellOverlayService::class.java).apply {
                action = DoorbellOverlayService.ACTION_TOGGLE_FULLSCREEN
            }
        )
        finish()
        overridePendingTransition(0, 0)
    }
}
