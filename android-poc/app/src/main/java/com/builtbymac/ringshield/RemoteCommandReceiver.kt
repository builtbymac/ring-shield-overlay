package com.builtbymac.ringshield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Exported receiver so Shield TV remote remappers (Button Mapper, tvQuickActions, ADB, etc.)
 * can toggle Fullscreen, pop up Live View on demand, or dismiss the overlay with one key press.
 */
class RemoteCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val svcIntent = Intent(context, DoorbellOverlayService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(context, svcIntent)
    }
}
