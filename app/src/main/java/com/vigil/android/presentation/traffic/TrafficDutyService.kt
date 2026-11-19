package com.vigil.android.presentation.traffic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.Instant
import java.util.concurrent.TimeUnit

class TrafficDutyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ops by lazy { TrafficOperations(applicationContext) }
    private val gps by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val uploadLock = Mutex()
    private var account: String? = null
    private var duty = 0L
    private var latestFix: Location? = null
    private var running = false
    private val authPrefs by lazy { getSharedPreferences("vigil_prefs", MODE_PRIVATE) }
    private val sessionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val original = account
        if (running && original != null && ops.owner != original) {
            TrafficAlerts.dismiss(this, original)
            finishDuty(original, tellServer = true)
        }
    }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val current = account ?: return
            if (!valid(current)) return
            val fix = result.lastLocation ?: return
            if (!fix.hasAccuracy() || fix.accuracy <= 0 || !fix.accuracy.isFinite() || ageMillis(fix) !in 0L..60_000L) return
            latestFix = fix
            scope.launch { flushPoint(current) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        TrafficAlerts.createChannels(this)
        authPrefs.registerOnSharedPreferenceChangeListener(sessionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = ops.owner
        val requested = intent?.getStringExtra("account") ?: current
        if (current == null || requested != current || ops.dutyId(current) == 0L) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running && account == current && duty == ops.dutyId(current)) return START_STICKY
        account = current
        duty = ops.dutyId(current)
        try {
            val notification = NotificationCompat.Builder(this, TrafficAlerts.SERVICE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Traffic duty is on")
                .setContentText("Sharing your duty location every 5 minutes. Tap to manage.")
                .setContentIntent(TrafficAlerts.appIntent(this))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification)
            running = true
            if (!valid(current)) return START_NOT_STICKY
            registerLocation(current)
        } catch (e: Exception) {
            finishDuty(current, tellServer = true, reason = "Location tracking could not start. Open the app and check permissions.")
            return START_NOT_STICKY
        }
        trackingOwner = current
        trackingDuty = duty

        // A separate clock check also enforces the end of a shift during an outage.
        scope.launch {
            while (isActive && valid(current)) delay(5_000L)
        }
        scope.launch {
            while (isActive && valid(current)) {
                try {
                    val assigned = ops.api.duties().firstOrNull { it.id == duty }
                    if (assigned == null || !assigned.active || !assigned.eligible ||
                        assigned.session_started_at != ops.prefs(current).getString("session", null)) {
                        finishDuty(current, tellServer = false, reason = "The control room closed or changed this duty.")
                        return@launch
                    }
                    val end = assigned.deadline()
                    ops.prefs(current).edit().putLong("until", end).apply()
                    flushPoint(current)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the latest recent fix while the control room is unreachable. */ }
                delay(30_000L)
            }
        }
        scope.launch {
            while (isActive && valid(current)) {
                try { TrafficAlerts.refresh(ops, current) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Reconnect on the next poll. */ }
                delay(30_000L)
            }
        }
        return START_STICKY
    }

    private fun valid(current: String): Boolean {
        if (!running) return false
        if (ops.owner != current || ops.dutyId(current) != duty) {
            if (ops.owner != current && ops.dutyId(current) == duty) finishDuty(current, true)
            else { running = false; stopSelf() }
            return false
        }
        if (System.currentTimeMillis() >= ops.prefs(current).getLong("until", 0L)) {
            finishDuty(current, true, "The assigned duty period has ended.")
            return false
        }
        if (!hasFinePermission(this)) {
            finishDuty(current, true, "Location permission was removed. Duty tracking stopped.")
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun registerLocation(current: String) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL)
            .setMinUpdateIntervalMillis(INTERVAL)
            .setMaxUpdateAgeMillis(0)
            .setMaxUpdateDelayMillis(0)
            .setWaitForAccurateLocation(false)
            .build()
        gps.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener {
                if (running) finishDuty(current, true, "Location updates failed. Check GPS and start duty again.")
            }
    }

    private fun ageMillis(fix: Location): Long =
        TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos)

    private suspend fun flushPoint(current: String): Unit = uploadLock.withLock {
        if (!valid(current)) return@withLock
        val fix = latestFix ?: return@withLock
        if (ageMillis(fix) !in 0L..600_000L) {
            latestFix = null
            return@withLock
        }
        try {
            val updated = ops.api.point(duty, TrafficFix(fix.latitude, fix.longitude, fix.accuracy,
                Instant.ofEpochMilli(fix.time).toString(), ops.prefs(current).getString("session", null)))
            if (latestFix === fix) latestFix = null
            if (!updated.active || !updated.eligible) finishDuty(current, false, "Tracking ended for this duty.")
        } catch (e: HttpException) {
            when (e.code()) {
                404, 409 -> finishDuty(current, false, "This duty is no longer active.")
                400 -> if (latestFix === fix) latestFix = null
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Retry this fix until a newer one is available. */ }
    }

    private fun finishDuty(current: String, tellServer: Boolean, reason: String? = null) {
        running = false
        if (duty > 0 && ops.dutyId(current) == duty) {
            val prefs = ops.prefs(current)
            if (tellServer) ops.queueStop(current, duty, prefs.getString("session", null)) else ops.clearDuty(current)
            reason?.let { prefs.edit().putString("problem", it).apply() }
        }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        trackingOwner = null
        trackingDuty = 0
        latestFix = null
        gps.removeLocationUpdates(callback)
        authPrefs.unregisterOnSharedPreferenceChangeListener(sessionListener)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 7310
        private const val INTERVAL = 5 * 60_000L
        @Volatile internal var trackingOwner: String? = null
            private set
        @Volatile internal var trackingDuty: Long = 0
            private set

        internal fun hasFinePermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
