package com.vigil.android.presentation.feature_attendance.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.vigil.android.presentation.feature_attendance.service.PatrolForegroundService
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = Prefs(context)
            if (prefs.isTracking() && prefs.getUserRole().equals("Patrol Officer", ignoreCase = true)) {
                // On Android 14+ (API 34), starting a location FGS from the background 
                // requires ACCESS_BACKGROUND_LOCATION permission.
                val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                if (hasBackgroundLocation) {
                    val serviceIntent = Intent(context, PatrolForegroundService::class.java).apply {
                        action = PatrolForegroundService.ACTION_START_PATROL
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}
