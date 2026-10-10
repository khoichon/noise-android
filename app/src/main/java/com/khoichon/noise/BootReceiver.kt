package com.khoichon.noise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Optional "start NOISE when the phone boots". */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Config(context).startOnBoot || !Settings.canDrawOverlays(context)) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NoiseService::class.java).setAction(NoiseService.ACTION_START)
            )
        }
    }
}
