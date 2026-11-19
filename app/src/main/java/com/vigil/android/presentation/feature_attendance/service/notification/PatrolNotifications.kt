package com.vigil.android.presentation.feature_attendance.service.notification

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * PatrolNotifications — single isolated place for ALL patrol notification logic.
 * Safe to delete this file + remove its call sites elsewhere to fully disable notifications.
 * Does not touch any existing geofence/API/DB logic.
 */
object PatrolNotifications {

    private const val CHANNEL_ID = "patrol_alerts_channel_v2"
    private const val PREFS_NAME = "patrol_notif_state"

    // Notification ID bases — kept in 5000+ range to avoid colliding with
    // PatrolForegroundService's existing 2000-series IDs.
    private const val ID_ROUTE_ASSIGNED = 5001
    private const val ID_ROUTE_STARTED = 5002
    private const val ID_REACHED = 5100        // + stopId
    private const val ID_VERIFIED = 5200       // + stopId
    private const val ID_EXIT_NO_VERIFY = 5300 // + stopId
    private const val ID_EXIT_VERIFIED = 5400  // + stopId
    private const val ID_ROUTE_COMPLETE_CLEAN = 5500   // + officerRouteJoinId
    private const val ID_ROUTE_COMPLETE_PENDING = 5600 // + officerRouteJoinId
    private const val ID_PATROL_DONE = 5700
    private const val ID_INTERNET_LOST = 5800
    private const val ID_GPS_OFF = 5900
    private const val ID_FREQ_COOLDOWN = 6000  // + stopId
    private const val ID_GPS_SPOOF = 6100

    private var lastInternetNotifiedAt: Long = 0L
    private const val INTERNET_THROTTLE_MS = 60_000L

    // ============== CHANNEL ==============

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Patrol Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Patrol route, stop, and status alerts"
            }
            nm.createNotificationChannel(channel)
        }
    }

    // ============== CORE SEND ==============

    private fun send(context: Context, id: Int, title: String, body: String) {
        ensureChannel(context)

        val openIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlagImmutable()
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notification)
    }

    private fun pendingFlagImmutable(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============== #1 ROUTE ASSIGNED ==============
    // Dedupes per officerRouteJoinId so it only fires once per assignment.
    fun notifyRouteAssigned(context: Context, routeName: String, officerRouteJoinId: Int) {
        val key = "route_assigned_$officerRouteJoinId"
        val p = prefs(context)
        if (p.getBoolean(key, false)) return
        p.edit().putBoolean(key, true).apply()

        send(
            context,
            ID_ROUTE_ASSIGNED + officerRouteJoinId,
            "Route Assigned",
            "Patrol route \"$routeName\" has been assigned to you."
        )
    }

    // ============== #2 ROUTE STARTED ==============
    fun notifyRouteStarted(context: Context, routeName: String) {
        send(
            context,
            ID_ROUTE_STARTED,
            "Route Started",
            "Patrol \"$routeName\" is now active."
        )
    }

    // ============== #4 REACHED STOP ==============
    fun notifyReachedStop(context: Context, stopName: String, stopId: Int) {
        send(
            context,
            ID_REACHED + stopId,
            "Reached Stop",
            "You have reached $stopName. Please verify your presence."
        )
    }

    // ============== #5 STOP VERIFIED ==============
    fun notifyStopVerified(context: Context, stopName: String, stopId: Int) {
        send(
            context,
            ID_VERIFIED + stopId,
            "Stop Verified",
            "Stop $stopName verified successfully ✅"
        )
    }

    // ============== #6 EXITED WITHOUT VERIFYING ==============
    fun notifyExitedWithoutVerify(context: Context, stopName: String, stopId: Int) {
        send(
            context,
            ID_EXIT_NO_VERIFY + stopId,
            "Left Without Verifying",
            "⚠️ You left $stopName without verifying. This has been recorded."
        )
    }

    // ============== #7 EXITED AFTER VERIFICATION ==============
    fun notifyExitedAfterVerify(context: Context, stopName: String, stopId: Int) {
        send(
            context,
            ID_EXIT_VERIFIED + stopId,
            "Stop Exited",
            "You have exited $stopName ✅. Move to next stop."
        )
    }

    // ============== #8 ROUTE COMPLETED CLEANLY ==============
    fun notifyRouteCompletedClean(context: Context, routeName: String, officerRouteJoinId: Int) {
        send(
            context,
            ID_ROUTE_COMPLETE_CLEAN + officerRouteJoinId,
            "Route Completed",
            "Route \"$routeName\" completed successfully 🎉"
        )
    }

    // ============== #9 ROUTE COMPLETED WITH PENDING STOPS ==============
    fun notifyRouteCompletedWithPending(context: Context, routeName: String, officerRouteJoinId: Int) {
        send(
            context,
            ID_ROUTE_COMPLETE_PENDING + officerRouteJoinId,
            "Route Completed (Incomplete)",
            "⚠️ Route \"$routeName\" completed with incomplete stops."
        )
    }

    // ============== #10 PATROL FULLY COMPLETED ==============
    fun notifyPatrolFullyCompleted(context: Context) {
        send(
            context,
            ID_PATROL_DONE,
            "Patrol Completed",
            "All routes completed for today. Good job! 👍"
        )
    }

    // ============== #12 INTERNET LOST ==============
    // Throttled to once per 60s so it doesn't spam on every failed call.
    fun notifyInternetLost(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastInternetNotifiedAt < INTERNET_THROTTLE_MS) return
        lastInternetNotifiedAt = now

        send(
            context,
            ID_INTERNET_LOST,
            "Internet Unavailable",
            "⚠️ No internet connection. Patrol data will sync when reconnected."
        )
    }

    // ============== #13 GPS OFF DURING PATROL ==============
    fun notifyGpsOff(context: Context) {
        send(
            context,
            ID_GPS_OFF,
            "GPS Disabled",
            "⚠️ GPS is off. Please enable it to continue patrol tracking."
        )
    }

    // ============== #14 GPS SPOOFING DETECTED ==============
    fun notifyGpsSpoofDetected(context: Context) {
        send(
            context,
            ID_GPS_SPOOF,
            "GPS Spoofing Detected",
            "⚠️ Warning: Mock location detected. Patrol actions are disabled."
        )
    }

    // ============== #15 FREQUENCY COOLDOWN ==============
    fun notifyFrequencyCooldown(context: Context, stopName: String, stopId: Int, minutesLeft: Long) {
        send(
            context,
            ID_FREQ_COOLDOWN + stopId,
            "Cooldown Active",
            "You can re-verify $stopName in $minutesLeft min."
        )
    }
}