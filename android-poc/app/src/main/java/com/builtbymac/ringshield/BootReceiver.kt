package com.builtbymac.ringshield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BootReceiver", "Starting DoorbellOverlayService after ${intent.action}")
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DoorbellOverlayService::class.java)
            )
        }.onFailure { Log.w("BootReceiver", "Service start failed: ${it.message}") }
    }
}
