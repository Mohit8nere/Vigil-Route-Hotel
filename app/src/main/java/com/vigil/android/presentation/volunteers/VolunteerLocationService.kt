package com.vigil.android.presentation.volunteers

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import com.vigil.android.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.Instant

class VolunteerLocationService : Service() {
    companion object {
        private const val CHANNEL = "volunteer_location"
        internal val active = MutableStateFlow<Long?>(null)
        internal val status = MutableStateFlow("")
    }
    private val client by lazy { VolunteerClient(this) }
    private val gps by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uploadLock = Mutex()
    private var owner: String? = null
    private var deadline = 0L
    private var duty = 0L

    private fun valid(): Boolean {
        if (client.account != owner || owner == null || System.currentTimeMillis() >= deadline ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return false
        }
        return true
    }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!valid()) return
            val fix = result.lastLocation ?: return
            val age = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
            if (!fix.hasAccuracy() || fix.accuracy !in 0.1f..100f || age !in 0..90_000) {
                status.value = "Waiting for an accurate GPS fix"
                return
            }
            val body = VolunteerFix(fix.latitude, fix.longitude, fix.accuracy, Instant.ofEpochMilli(fix.time).toString())
            scope.launch {
                uploadLock.withLock {
                    if (!valid()) return@withLock
                    try {
                        val state = client.api.location(duty, body)
                        status.value = "Last GPS sent · ${state.presence}"
                    } catch (e: CancellationException) { throw e }
                    catch (e: HttpException) {
                        if (e.code() in setOf(401, 403, 404)) { status.value = "Duty or sign-in is no longer active"; stopSelf() }
                        else status.value = "Fix was not accepted. Waiting for the next GPS update."
                    } catch (_: Exception) { status.value = "Offline · waiting to send the next GPS fix" }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Volunteer duty location", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Volunteer duty location is on").setContentText("Open Vigil to view your post or stop sharing.")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7206, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(7206, notification)
            val requestedId = intent?.getLongExtra("duty", 0L) ?: 0L
            if (active.value != null && requestedId == duty && valid()) return START_NOT_STICKY
            if (active.value != null) { stopSelf(); return START_NOT_STICKY }
            owner = intent?.getStringExtra("owner")
            deadline = intent?.getLongExtra("until", 0L) ?: 0L
            duty = requestedId
            if (!valid() || duty <= 0) { stopSelf(); return START_NOT_STICKY }
            status.value = "Waiting for GPS"
            active.value = duty
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
                .setMinUpdateIntervalMillis(20_000L).build()
            gps.requestLocationUpdates(request, callback, Looper.getMainLooper()).addOnFailureListener {
                status.value = "Location service unavailable. Check device location settings."
                stopSelf()
            }
            scope.launch {
                while (isActive && valid()) {
                    try {
                        if (client.api.duties().none { it.id == duty && it.current(System.currentTimeMillis()) }) {
                            status.value = "Duty ended or cancelled"; stopSelf(); break
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: HttpException) { if (e.code() == 401 || e.code() == 403) { stopSelf(); break } }
                    catch (_: Exception) { /* End time is enforced locally during an outage. */ }
                    delay(30_000L)
                }
            }
            scope.launch { while (isActive && valid()) delay(1_000L) }
        } catch (_: SecurityException) { status.value = "Precise location permission required"; stopSelf() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        gps.removeLocationUpdates(callback)
        active.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
