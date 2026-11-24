package com.vigil.android.presentation.vip

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
import androidx.core.location.LocationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class VipLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client by lazy { VipClient(this) }
    private val gps by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var post = 0L
    private var owner: String? = null
    private var until = 0L
    private var sending = false
    private fun valid() = owner != null && owner == client.account() && System.currentTimeMillis() < until &&
        ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val callback = object: LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!valid()) { stopSelf(); return }
            val fix = result.lastLocation ?: return
            val age = (SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos)/1_000_000
            if (sending || !fix.hasAccuracy() || fix.accuracy <= 0 || age !in 0L..60_000L || LocationCompat.isMock(fix)) return
            sending = true
            scope.launch {
                try {
                    val response = client.api.send(post, VipFix(fix.latitude,fix.longitude,fix.accuracy,Instant.ofEpochMilli(fix.time).toString()))
                    response.body()?.close()
                    response.errorBody()?.close()
                    if (response.code() in listOf(401,403,404,409)) stopSelf()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The next fresh fix retries; old coordinates must not trigger new exits. */ }
                finally { sending=false }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags:Int, startId:Int):Int {
        val ctxIntent = Intent(this,VipDutyActivity::class.java)
        val open = PendingIntent.getActivity(this,7420,ctxIntent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"VIP duty location",NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("VIP duty location sharing").setContentText("Tap to manage your active duty.")
            .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT>=29) startForeground(7420,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(7420,notification)
        if (post != 0L) return START_NOT_STICKY
        owner=intent?.getStringExtra("owner")
        post=intent?.getLongExtra("post",0L) ?: 0L
        until=intent?.getLongExtra("end",0L) ?: 0L
        if (!valid() || post<=0) { stopSelf();return START_NOT_STICKY }
        mutableSharing.value=post
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,20_000L)
            .setMinUpdateIntervalMillis(15_000L).setMaxUpdateAgeMillis(0L).build()
        try {gps.requestLocationUpdates(request,callback,Looper.getMainLooper()).addOnFailureListener{stopSelf()}}
        catch (_:SecurityException){stopSelf();return START_NOT_STICKY}
        scope.launch {
            while (isActive && valid()) {
                try { if (client.api.posts().none { it.id==post && it.active }) break }
                catch(e:CancellationException){throw e}
                catch (_:Exception){ /* The local deadline remains effective without connectivity. */ }
                delay(20_000L)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        gps.removeLocationUpdates(callback)
        scope.cancel()
        mutableSharing.value=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        private const val CHANNEL="vip_duty_location"
        private val mutableSharing=MutableStateFlow<Long?>(null)
        internal val sharing:StateFlow<Long?> = mutableSharing
    }
}
