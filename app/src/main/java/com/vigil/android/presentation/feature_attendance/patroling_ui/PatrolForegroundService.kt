package com.vigil.android.presentation.feature_attendance.patroling_ui

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vigil.android.core.common.network.NetworkClient
import com.vigil.android.core.common.`object`.SecureTime
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.api.ApiNames.BASE_URL
import com.vigil.android.presentation.feature_attendance.db.RouteDbHelper
import com.vigil.android.presentation.feature_attendance.db.ShiftEligibility
import com.vigil.android.presentation.feature_attendance.service.notification.PatrolNotifications
import com.vigil.android.utils.LocationUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val NOTIFICATION_ID_GPS_FORCE = 2007
@dagger.hilt.android.AndroidEntryPoint
class PatrolForegroundService : Service() {

    @javax.inject.Inject
    lateinit var sessionManager: SessionManager

    companion object {
        private const val TAG = "PatrolService"

        private const val CHANNEL_ID_SERVICE = "patrol_service_channel"
        private const val CHANNEL_ID_ALERTS = "patrol_alerts_channel"

        private const val NOTIFICATION_ID_SERVICE = 2001
        private const val NOTIFICATION_ID_ROUTE = 2002
        private const val NOTIFICATION_ID_REACHED = 2003
        private const val NOTIFICATION_ID_EXIT = 2004
        private const val NOTIFICATION_ID_NET = 2005
        private const val NOTIFICATION_ID_COMPLETE = 2006

        private const val MAX_ACCEPTABLE_ACCURACY_METERS = 35f

        const val ACTION_START_PATROL = "com.vigil.android.action.START_PATROL"
        const val ACTION_STOP_PATROL = "com.vigil.android.action.STOP_PATROL"
        const val ACTION_APP_FOREGROUND = "com.vigil.android.action.APP_FOREGROUND"
        const val ACTION_APP_BACKGROUND = "com.vigil.android.action.APP_BACKGROUND"
        const val ACTION_REFRESH_ROUTES = "com.vigil.android.action.REFRESH_ROUTES"

        const val ACTION_START_ROUTE = "com.vigil.android.action.START_ROUTE"
        const val ACTION_COMPLETE_ROUTE = "com.vigil.android.action.COMPLETE_ROUTE"

        const val EXTRA_ROUTE_ID = "extra_route_id"
        const val EXTRA_OFFICER_ROUTE_JOIN_ID = "extra_officer_route_join_id"
        const val EXTRA_FORCE_COMPLETE = "extra_force_complete"
        const val EXTRA_ROUTE_SUMMARY = "extra_route_summary"
        const val EXTRA_SPOOF_DETECTED = "extra_spoof_detected"
        const val EXTRA_SPOOF_REASON = "extra_spoof_reason"
        const val ACTION_ROUTE_DATA_CHANGED = "com.vigil.android.action.ROUTE_DATA_CHANGED"
        const val ACTION_ROUTE_API_RESULT = "com.vigil.android.action.ROUTE_API_RESULT"
        const val ACTION_LOCATION_CHANGED = "com.vigil.android.action.LOCATION_CHANGED"
        const val EXTRA_RESULT_MESSAGE = "extra_result_message"
        const val EXTRA_RESULT_SUCCESS = "extra_result_success"
        const val ACTION_ENQUEUE_SYNC_ACTION =
            "com.vigil.android.action.ENQUEUE_SYNC_ACTION"
        const val EXTRA_SYNC_ACTION_TYPE = "extra_sync_action_type"
        const val EXTRA_SYNC_PAYLOAD_JSON = "extra_sync_payload_json"

        const val SYNC_ACTION_VERIFY_UPLOAD = "verify_upload"
        const val SYNC_ACTION_EVIDENCE = "evidence"
        const val ACTION_PERIODIC_LOCATION_LOG = "com.vigil.android.action.PERIODIC_LOCATION_LOG"

        fun scheduleLocationAlarm(context: Context) {
            val intent = Intent(context, PatrolForegroundService::class.java).apply {
                action = ACTION_PERIODIC_LOCATION_LOG
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    1001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    context,
                    1001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
            val triggerTime = System.currentTimeMillis() + 5 * 60 * 1000L // 5 minutes
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException scheduling exact alarm, falling back to inexact alarm", e)
                try {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to schedule fallback location alarm", ex)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule location alarm", e)
            }
        }

        fun cancelLocationAlarm(context: Context) {
            val intent = Intent(context, PatrolForegroundService::class.java).apply {
                action = ACTION_PERIODIC_LOCATION_LOG
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    1001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    context,
                    1001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
            try {
                alarmManager.cancel(pendingIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel location alarm", e)
            }
        }

        fun enqueueSyncAction(context: Context, actionType: String, payloadJson: String) {
            val intent = Intent(context, PatrolForegroundService::class.java).apply {
                action = ACTION_ENQUEUE_SYNC_ACTION
                putExtra(EXTRA_SYNC_ACTION_TYPE, actionType)
                putExtra(EXTRA_SYNC_PAYLOAD_JSON, payloadJson)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startPatrol(context: Context) {
            val intent = Intent(context, PatrolForegroundService::class.java).apply {
                action = ACTION_START_PATROL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun notifyAppForeground(context: Context) {
            val intent = Intent(context, PatrolForegroundService::class.java).apply {
                action = ACTION_APP_FOREGROUND
            }
            context.startService(intent)
        }

        fun notifyAppBackground(context: Context) {
            val intent = Intent(context, PatrolForegroundService::class.java).apply {
                action = ACTION_APP_BACKGROUND
            }
            context.startService(intent)
        }

        fun refreshRoutes(context: Context) {
            val intent = Intent(context, PatrolForegroundService::class.java).apply {
                action = ACTION_REFRESH_ROUTES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startRoute(context: Context, routeId: Int, officerRouteJoinId: Int) {
            val intent = Intent(context, PatrolForegroundService::class.java).apply {
                action = ACTION_START_ROUTE
                putExtra(EXTRA_ROUTE_ID, routeId)
                putExtra(EXTRA_OFFICER_ROUTE_JOIN_ID, officerRouteJoinId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun completeRoute(
            context: Context,
            routeId: Int,
            officerRouteJoinId: Int,
            force: Boolean = false,
            routeSummary: String = ""
        ) {
            val intent = Intent(context, PatrolForegroundService::class.java).apply {
                action = ACTION_COMPLETE_ROUTE
                putExtra(EXTRA_ROUTE_ID, routeId)
                putExtra(EXTRA_OFFICER_ROUTE_JOIN_ID, officerRouteJoinId)
                putExtra(EXTRA_FORCE_COMPLETE, force)
                putExtra(EXTRA_ROUTE_SUMMARY, routeSummary)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var dbHelper: RouteDbHelper
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val okHttpClient by lazy {
        NetworkClient.create(
            context = applicationContext,
            sessionManager = sessionManager
        ).newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                SecureTime.capture(applicationContext, response.header("Date"))
                response
            }
            .build()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var trackingRunnable: Runnable? = null
    private var routeRunnable: Runnable? = null
    private var locationCallback: LocationCallback? = null

    private var currentLocation: Location? = null
    private val consecutiveOutsideReadings = mutableMapOf<Int, Int>()
    private val REQUIRED_CONSECUTIVE_OUTSIDE_READINGS = 2
    private var isUiForeground: Boolean = false

    private var lastNotifiedRouteSignature: String? = null
    private var lastOfflineNotifiedAt: Long = 0L
    private var patrolCompletedHandled: Boolean = false

    private fun isWithinShift8Hours(): Boolean {
        val startTime = prefs.getShiftStartTime()
        if (startTime == 0L) return false
        val eightHoursMillis = 8 * 60 * 60 * 1000L
        return (System.currentTimeMillis() - startTime) < eightHoursMillis
    }


    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isDrainingSyncQueue: Boolean = false
    private var syncRetryRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        dbHelper = RouteDbHelper(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        SecureTime.restoreFromPrefs(this)

        createNotificationChannels()
        
        ensureForegroundState()

        registerNetworkCallback()

        drainSyncQueue()

        if (isWithinShift8Hours()) {
            printDebug("AUTO_RESUME_SHIFT: Within 8hr shift window on service create. Starting monitoring.")
            startMonitoringIfNeeded()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundState()
        
        when (intent?.action) {
            ACTION_START_PATROL -> {
                printDebug("ACTION_START_PATROL")
                prefs.saveTrackingState(true)
                patrolCompletedHandled = false
                updateForegroundText("Patrol is active")
                startMonitoringIfNeeded()
            }

            ACTION_STOP_PATROL -> {
                printDebug("ACTION_STOP_PATROL")
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_APP_FOREGROUND -> {
                printDebug("ACTION_APP_FOREGROUND")
                isUiForeground = true
                ensureForegroundState()
                updateForegroundText("Patrol active - app open")
            }

            ACTION_APP_BACKGROUND -> {
                printDebug("ACTION_APP_BACKGROUND")
                isUiForeground = false
                ensureForegroundState()
                updateForegroundText("Patrol active - background monitoring")
            }

            ACTION_REFRESH_ROUTES -> {
                printDebug("ACTION_REFRESH_ROUTES")
                callRouteApi()
            }

            ACTION_ENQUEUE_SYNC_ACTION -> {
                val actionType = intent.getStringExtra(EXTRA_SYNC_ACTION_TYPE)
                val payloadJson = intent.getStringExtra(EXTRA_SYNC_PAYLOAD_JSON)
                if (actionType != null && payloadJson != null) {
                    dbHelper.enqueueSyncAction(actionType, payloadJson)
                    printDebug("SYNC_ENQUEUED actionType=$actionType")
                    drainSyncQueue()
                }
            }

            ACTION_START_ROUTE -> {
                val routeId = intent.getIntExtra(EXTRA_ROUTE_ID, -1)
                val officerRouteJoinId = intent.getIntExtra(EXTRA_OFFICER_ROUTE_JOIN_ID, -1)
                printDebug("ACTION_START_ROUTE routeId=$routeId officerRouteJoinId=$officerRouteJoinId")
                if (routeId != -1 && officerRouteJoinId != -1) {
                    handleStartRoute(routeId, officerRouteJoinId)
                }
            }

            ACTION_COMPLETE_ROUTE -> {
                val routeId = intent.getIntExtra(EXTRA_ROUTE_ID, -1)
                val officerRouteJoinId = intent.getIntExtra(EXTRA_OFFICER_ROUTE_JOIN_ID, -1)
                val force = intent.getBooleanExtra(EXTRA_FORCE_COMPLETE, false)
                val routeSummary = intent.getStringExtra(EXTRA_ROUTE_SUMMARY).orEmpty()
                printDebug("ACTION_COMPLETE_ROUTE routeId=$routeId officerRouteJoinId=$officerRouteJoinId force=$force summary=$routeSummary")
                if (routeId != -1 && officerRouteJoinId != -1) {
                    handleCompleteRoute(routeId, officerRouteJoinId, force, routeSummary)
                }
            }

            ACTION_PERIODIC_LOCATION_LOG -> {
                printDebug("ACTION_PERIODIC_LOCATION_LOG")
                if (isWithinShift8Hours() || prefs.isTracking()) {
                    getLocationAndUpload()
                    checkInternetAndNotify()
                    drainSyncQueue()
                    scheduleLocationAlarm(applicationContext)
                }
            }

            else -> {
                printDebug("ACTION_DEFAULT")
                if (prefs.isTracking() || isWithinShift8Hours()) {
                    startMonitoringIfNeeded()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        unregisterNetworkCallback()
        syncRetryRunnable?.let { handler.removeCallbacks(it) }
        printDebug("SERVICE_DESTROYED")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        printDebug("onTaskRemoved - app removed from recents")
        if (prefs.isTracking() || isWithinShift8Hours()) {
            val restartServiceIntent = Intent(applicationContext, this.javaClass).apply {
                setPackage(packageName)
                action = ACTION_START_PATROL
            }

            val restartServicePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext,
                    1,
                    restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    applicationContext,
                    1,
                    restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val alarmService =
                applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
            alarmService.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 500,
                restartServicePendingIntent
            )
            scheduleLocationAlarm(applicationContext)
        }
    }


    private fun startMonitoringIfNeeded() {
        val hasStopsInDb = dbHelper.getAllStopsFull().isNotEmpty()
        if (!isWithinShift8Hours() && !hasStopsInDb) {
            printDebug("startMonitoringIfNeeded skipped: outside 8hr shift window and no stops in DB")
            return
        }

        if (isPatrolCompleted() && !isWithinShift8Hours()) {
            handlePatrolCompleted()
            return
        }

        startLocationUpdates()
        startTrackingLoop()
        startRoutePollingLoop()
        scheduleLocationAlarm(applicationContext)
    }

    private fun stopMonitoring() {
        trackingRunnable?.let { handler.removeCallbacks(it) }
        trackingRunnable = null

        routeRunnable?.let { handler.removeCallbacks(it) }
        routeRunnable = null

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null

        consecutiveOutsideReadings.clear()
        cancelLocationAlarm(applicationContext)

        printDebug("Monitoring stopped")
    }

    private fun startTrackingLoop() {
        if (trackingRunnable != null) return

        trackingRunnable = object : Runnable {
            override fun run() {
                val hasStopsInDb = dbHelper.getAllStopsFull().isNotEmpty()
                if (!isWithinShift8Hours() && !hasStopsInDb) {
                    printDebug("tracking loop check: outside 8hr shift window and no stops in DB")
                    handler.postDelayed(this, 300000)
                    return
                }

                if (isPatrolCompleted() && !isWithinShift8Hours()) {
                    handlePatrolCompleted()
                    return
                }

                if (forceGpsIfDisabled()) {
                    printDebug("GPS_DISABLED_DURING_PATROL - blocking location-dependent work")
                    handler.postDelayed(this, 5000)
                    return
                }

                try {
                    getLocationAndUpload()
                    processGeofence()
                    checkInternetAndNotify()
                    reportGpsStatus(isGpsEnabled())

                    if (isPatrolCompleted() && !isWithinShift8Hours()) {
                        handlePatrolCompleted()
                        return
                    }

                } catch (e: Exception) {
                    printDebug("TRACK_LOOP crash=${e.message}")
                }

                handler.postDelayed(this, 300000) // 5 minutes
            }
        }
        handler.post(trackingRunnable!!)
    }

    private fun startRoutePollingLoop() {
        if (routeRunnable != null) return

        routeRunnable = object : Runnable {
            override fun run() {
                val hasStopsInDb = dbHelper.getAllStopsFull().isNotEmpty()
                if (!isWithinShift8Hours() && !hasStopsInDb) return

                if (isPatrolCompleted() && !isWithinShift8Hours()) {
                    handlePatrolCompleted()
                    return
                }

                callRouteApi()

                handler.postDelayed(this, 300000) // 5 minutes
            }
        }

        handler.post(routeRunnable!!)
    }

    private fun handlePatrolCompleted() {
        if (patrolCompletedHandled) {
            printDebug("handlePatrolCompleted skipped: already handled")
            return
        }

        patrolCompletedHandled = true
        printDebug("PATROL_COMPLETED_LOCALLY")

        showAlertNotification(
            id = NOTIFICATION_ID_COMPLETE,
            title = "Patrol Completed",
            body = "All routes and stops have been completed locally."
        )

        updateForegroundText("Patrol finished - syncing data...")

        if (isWithinShift8Hours()) {
            printDebug("PATROL_COMPLETED_ROUTE_FINISH: Route completed but keeping location service alive until 8 hours end.")
            prefs.saveActiveRouteId(null)
            prefs.saveActiveOfficerRouteJoinId(null)
            broadcastDataChanged()
            
            // Ensure tracking loop remains active
            prefs.saveTrackingState(true)
            startMonitoringIfNeeded()

            // Turn off route updates loop only, keep tracking loop active
            routeRunnable?.let { handler.removeCallbacks(it) }
            routeRunnable = null
        } else {
            stopMonitoring()
            prefs.saveTrackingState(false)
            prefs.saveActiveRouteId(null)
            prefs.saveActiveOfficerRouteJoinId(null)
            broadcastDataChanged()

            if (!dbHelper.hasPendingSyncActions()) {
                printDebug("Sync queue empty. Stopping service.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                printDebug("Sync queue NOT empty. Service will remain alive until drained.")
                drainSyncQueue()
            }
        }
    }

    private fun handleStartRoute(routeId: Int, officerRouteJoinId: Int) {
        val alreadyActiveRouteId = prefs.getActiveRouteId()

        if (alreadyActiveRouteId != null && alreadyActiveRouteId != routeId) {
            broadcastApiResult(false, "Complete current route first")
            return
        }
        SecureTime.restoreFromPrefs(applicationContext)
        val secureNow = SecureTime.now() ?: System.currentTimeMillis()

        val firstStop = dbHelper.getStopsForRoute(routeId).firstOrNull()
        val dutyShift = firstStop?.get("duty_shift")
        val dutyStart = firstStop?.get("duty_start")
        if (!ShiftEligibility.isEligibleNow(dutyShift, dutyStart, secureNow)) {
            val reason = ShiftEligibility.ineligibleReason(dutyShift, dutyStart, secureNow)
            broadcastApiResult(false, reason)
            return
        }

        prefs.saveActiveRouteId(routeId)
        prefs.saveActiveOfficerRouteJoinId(officerRouteJoinId)
        
        // Only set the shift anchor if it hasn't been set yet by auto-start logic
        if (prefs.getShiftStartTime() == 0L) {
            prefs.saveShiftStartTime(System.currentTimeMillis())
            prefs.saveShiftOfficerRouteJoinId(officerRouteJoinId)
        }
        
        prefs.saveTrackingState(true)
        patrolCompletedHandled = false

        dbHelper.clearActiveStopId()
        val time = getTime()
        val startedRouteName =
            dbHelper.getStopsForRoute(routeId).firstOrNull()?.get("route_name") ?: "Route"

        if (isInternetAvailable()) {
            callPatrolUpdateApi(routeId, officerRouteJoinId, status = 1) { success ->
                if (success) {
                    PatrolNotifications.notifyRouteStarted(this, startedRouteName)
                    broadcastApiResult(true, "Route Started")
                } else {
                    enqueuePatrolUpdate(routeId, officerRouteJoinId, status = 1, time = time)
                    broadcastApiResult(true, "Route Started (will sync)")
                }
                broadcastDataChanged()
            }
        } else {
            enqueuePatrolUpdate(routeId, officerRouteJoinId, status = 1, time = time)
            PatrolNotifications.notifyRouteStarted(this, startedRouteName)
            broadcastApiResult(true, "Route Started (offline — will sync)")
        }
        startMonitoringIfNeeded()

        broadcastDataChanged()
    }

    private fun enqueuePatrolUpdate(
        routeId: Int,
        officerRouteJoinId: Int,
        status: Int,
        time: String,
        routeSummary: String = ""
    ) {
        val officerId = prefs.getPatrolId()?.toIntOrNull() ?: -1
        val payload = JSONObject().apply {
            put("routeId", routeId)
            put("officerRouteJoinId", officerRouteJoinId)
            put("officerId", officerId)
            put("status", status)
            put("time", time)
            if (routeSummary.isNotBlank()) {
                put("route_summary", routeSummary)
            }
        }
        dbHelper.enqueueSyncAction("patrol_update", payload.toString())
        drainSyncQueue()
    }

    private fun enqueueReach(
        stopId: Int,
        routeId: Int,
        officerRouteJoinId: Int,
        officerStopId: Int,
        time: String
    ) {
        val officerId = prefs.getPatrolId()?.toIntOrNull() ?: -1
        val payload = JSONObject().apply {
            put("stopId", stopId)
            put("routeId", routeId)
            put("officerRouteJoinId", officerRouteJoinId)
            put("officerStopId", officerStopId)
            put("officerId", officerId)
            put("time", time)
        }
        dbHelper.enqueueSyncAction("reach", payload.toString())
        drainSyncQueue()
    }

    private fun enqueueExit(
        stopId: Int,
        routeId: Int,
        officerRouteJoinId: Int,
        officerStopId: Int,
        time: String,
        stopHash: String
    ) {
        val officerId = prefs.getPatrolId()?.toIntOrNull() ?: -1
        val payload = JSONObject().apply {
            put("stopId", stopId)
            put("routeId", routeId)
            put("officerRouteJoinId", officerRouteJoinId)
            put("officerStopId", officerStopId)
            put("officerId", officerId)
            put("time", time)
            put("stopHash", stopHash)
        }
        dbHelper.enqueueSyncAction("exit", payload.toString())
        drainSyncQueue()
    }

    private fun handleCompleteRoute(
        routeId: Int,
        officerRouteJoinId: Int,
        force: Boolean,
        routeSummary: String = ""
    ) {
        val allVerified = dbHelper.areAllStopsVerifiedByAttempt(officerRouteJoinId)
        val pending = dbHelper.hasPendingStopsByAttempt(officerRouteJoinId)
        if (pending && !allVerified && !force) {
            broadcastApiResult(false, "PENDING_STOPS_CONFIRM_REQUIRED")
            return
        }

        if (allVerified) {
            val autoExitTime = getTime()
            val unexitedVerified = dbHelper.getUnexitedVerifiedStops(officerRouteJoinId)
            for (s in unexitedVerified) {
                val stopId = s["stop_id"]?.toIntOrNull() ?: continue
                val officerStopId = s["officer_stop_id"]?.toIntOrNull() ?: continue
                val stopHash = s["stop_hash"].orEmpty()

                dbHelper.updateExit(officerRouteJoinId, officerStopId, autoExitTime)
                if (dbHelper.getActiveStopId() == officerStopId) {
                    dbHelper.clearActiveStopId()
                }
                // same path as the normal geofence-exit case — goes into the
                // sync queue and hits the exit-time endpoint
                enqueueExit(stopId, routeId, officerRouteJoinId, officerStopId, autoExitTime, stopHash)

                printDebug("COMPLETE_AUTO_EXIT stopId=$stopId officerStopId=$officerStopId hash=$stopHash")
            }
            broadcastDataChanged()
        }

        val finishedRouteName =
            dbHelper.getStopsForRoute(routeId).firstOrNull()?.get("route_name") ?: "Route"
        val hadPendingStops = dbHelper.hasPendingStopsByAttempt(officerRouteJoinId)
        val time = getTime()

        prefs.saveActiveRouteId(null)
        prefs.saveActiveOfficerRouteJoinId(null)
        
        // De-couple route completion from tracking state.
        // Tracking persists if within the 8-hour shift window.
        if (!isWithinShift8Hours()) {
            prefs.saveTrackingState(false)
        } else {
            printDebug("COMPLETE_ROUTE: Route finished but shift still active. Tracking persists.")
            prefs.saveTrackingState(true)
            startMonitoringIfNeeded()
        }
        
        dbHelper.clearActiveStopId()

        fun onCompleteConfirmed() {
            dbHelper.clearRouteAttempt(officerRouteJoinId)

            if (hadPendingStops) {
                PatrolNotifications.notifyRouteCompletedWithPending(
                    this,
                    finishedRouteName,
                    officerRouteJoinId
                )
            } else {
                PatrolNotifications.notifyRouteCompletedClean(
                    this,
                    finishedRouteName,
                    officerRouteJoinId
                )
            }

            if (dbHelper.getAllRoutes().isEmpty()) {
                PatrolNotifications.notifyPatrolFullyCompleted(this)
            }

            broadcastDataChanged()
            if (isEverythingDone()) {
                handlePatrolCompleted()
            }
        }

        if (isInternetAvailable()) {
            callPatrolUpdateApi(routeId, officerRouteJoinId, status = 2, routeSummary = routeSummary) { success ->
                if (success) {
                    onCompleteConfirmed()
                    broadcastApiResult(true, "Route completed")
                } else {
                    enqueuePatrolUpdate(routeId, officerRouteJoinId, status = 2, time = time, routeSummary = routeSummary)
                    broadcastApiResult(true, "Route completed (will sync)")
                }
                broadcastDataChanged()
            }
        } else {
            enqueuePatrolUpdate(routeId, officerRouteJoinId, status = 2, time = time, routeSummary = routeSummary)
            broadcastApiResult(true, "Route completed (offline — will sync)")
        }

        broadcastDataChanged()
    }
    private fun startLocationUpdates() {
        if (locationCallback != null) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            printDebug("Location permission missing")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!prefs.isTracking()) return
                if (patrolCompletedHandled) return

                val loc = result.lastLocation ?: return

                val spoofReason = LocationUtils.getSpoofReason(loc, this@PatrolForegroundService)
                if (spoofReason != null) {
                    printDebug("GPS_SPOOF_DETECTED reason=$spoofReason lat=${loc.latitude} lon=${loc.longitude}")
                    PatrolNotifications.notifyGpsSpoofDetected(this@PatrolForegroundService)
                    broadcastSpoofDetected(spoofReason)
                    return
                }

                if (!isFixReliable(loc)) {
                    printDebug(
                        "LOCATION_REJECTED_LOW_ACCURACY lat=${loc.latitude} lon=${loc.longitude} " +
                                "accuracy=${loc.accuracy}m (max allowed=$MAX_ACCEPTABLE_ACCURACY_METERS m)"
                    )
                    return
                }

                currentLocation = loc
                printDebug("LOCATION lat=${loc.latitude}, lon=${loc.longitude} accuracy=${loc.accuracy}m")

                prefs.saveLastKnownLocation(loc.latitude, loc.longitude, loc.accuracy)
                broadcastLocationChanged()

                processGeofence()

                if (isPatrolCompleted()) {
                    handlePatrolCompleted()
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback as LocationCallback,
            Looper.getMainLooper()
        )
    }
    private fun isGpsEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private var lastGpsAlertAt: Long = 0L

    private fun forceGpsIfDisabled(): Boolean {
        if (isGpsEnabled()) return false

        val now = System.currentTimeMillis()
        if (now - lastGpsAlertAt > 30_000) {
            lastGpsAlertAt = now
            PatrolNotifications.notifyGpsOff(this)
            showForceGpsNotification()
        }
        return true
    }

    private fun showForceGpsNotification() {
        val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_GPS_FORCE,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlagImmutable()
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("GPS is OFF")
            .setContentText("GPS must be enabled during patrol. Tap to enable.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_GPS_FORCE, notification)
    }
    private fun getLocationAndUpload() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            printDebug("LOCATION_LOG_SKIP: Permission missing")
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
            val loc = lastLoc ?: currentLocation
            
            if (loc == null) {
                printDebug("LOCATION_LOG_SKIP: Both lastLocation and currentLocation are null")
                return@addOnSuccessListener
            }

            if (LocationUtils.isMockLocation(loc)) {
                printDebug("LOCATION_LOG_SKIP: Mock location detected")
                PatrolNotifications.notifyGpsSpoofDetected(this@PatrolForegroundService)
                return@addOnSuccessListener
            }

            var officerRouteJoinId = prefs.getActiveOfficerRouteJoinId()

            if (officerRouteJoinId == null || officerRouteJoinId <= 0) {
                if (isWithinShift8Hours()) {
                    officerRouteJoinId = prefs.getShiftOfficerRouteJoinId()
                    printDebug("LOCATION_LOG: Using shift join id=$officerRouteJoinId (within 8hr window)")
                }
            }

            if (officerRouteJoinId == null || officerRouteJoinId <= 0) {
                val firstStop = dbHelper.getAllStopsFull().firstOrNull()
                val fallbackJoinId = firstStop?.get("officer_route_join_id")?.toIntOrNull()
                if (fallbackJoinId != null && fallbackJoinId > 0) {
                    officerRouteJoinId = fallbackJoinId
                }
            }

            if (officerRouteJoinId == null || officerRouteJoinId <= 0) {
                return@addOnSuccessListener
            }

            val patrolId = prefs.getPatrolId()?.toIntOrNull()
            if (patrolId == null || patrolId <= 0) {
                return@addOnSuccessListener
            }

            val json = JSONObject().apply {
                put("officer_id", patrolId)
                put("officer_route_join_id", officerRouteJoinId)
                put("latitude", String.format(Locale.US, "%.6f", loc.latitude))
                put("longitude", String.format(Locale.US, "%.6f", loc.longitude))
                put("recorded_at", getTime())
            }

            printDebug("LOCATION_LOG_ATTEMPT: Uploading lat=${loc.latitude} lon=${loc.longitude} joinId=$officerRouteJoinId")

            val payload = json.toString()
            
            // Check if online. If not, queue for sync instead of calling API immediately.
            if (!isInternetAvailable()) {
                printDebug("LOCATION_LOG: Offline, queueing location log.")
                dbHelper.enqueueSyncAction("location_log", payload)
                drainSyncQueue()
                return@addOnSuccessListener
            }

            val body = payload.toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("${BASE_URL}api/v1/officers/location-logs/")
                .post(body)
                .addHeader("Authorization", "Bearer ${prefs.getToken()}")
                .build()

            okHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    printDebug("LOCATION_LOG_RESPONSE_FAIL: error=${e.message} - queueing.")
                    dbHelper.enqueueSyncAction("location_log", payload)
                    drainSyncQueue()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            printDebug("LOCATION_LOG_RESPONSE_FAIL: code=${it.code} - queueing.")
                            dbHelper.enqueueSyncAction("location_log", payload)
                            drainSyncQueue()
                        } else {
                            printDebug("LOCATION_LOG_RESPONSE: code=${it.code} success=${it.isSuccessful}")
                        }
                    }
                }
            })
        }
    }

    private fun processGeofence() {
        val loc = currentLocation ?: return
        if (patrolCompletedHandled) return

        val routeId = prefs.getActiveRouteId() ?: return
        val radius = getGeofenceRadius()

        val allStops = dbHelper.getAllStopsFull()
            .filter { it["route_id"]?.toIntOrNull() == routeId }
            .sortedBy { it["stop_order"]?.toIntOrNull() ?: 0 }

        if (allStops.isEmpty()) {
            printDebug("PROCESS_SKIP no stops for active route=$routeId")
            return
        }

        val activeStopId = dbHelper.getActiveStopId()

        if (activeStopId != null) {
            val activeStop = allStops.firstOrNull {
                it["officer_stop_id"]?.toIntOrNull() == activeStopId
            }

            if (activeStop != null) {
                handleStopGeofence(activeStop, loc, radius, locked = true)
                return
            } else {
                dbHelper.clearActiveStopId()
            }
        }

        var insideStop: Map<String, String>? = null

        for (stop in allStops) {
            val stopId = stop["stop_id"]?.toIntOrNull() ?: continue
            val officerStopId = stop["officer_stop_id"]?.toIntOrNull() ?: 0
            val stopHash = stop["stop_hash"].orEmpty()
            val lat = stop["latitude"]?.toDoubleOrNull() ?: continue
            val lon = stop["longitude"]?.toDoubleOrNull() ?: continue

            val status = stop["status"]?.toIntOrNull() ?: 0
            val verified = status == 2 || !stop["upload_time"].isNullOrEmpty()
            val exited = !stop["exit_time"].isNullOrEmpty()

            if (verified && exited) {
                printDebug("PROCESS_SKIP_DONE stopId=$stopId officerStopId=$officerStopId hash=$stopHash")
                continue
            }

            val dist = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, dist)

            val inside = dist[0] <= radius

            printDebug(
                "PROCESS_SCAN route=$routeId stopId=$stopId officerStopId=$officerStopId " +
                        "hash=$stopHash inside=$inside dist=${dist[0]} radius=$radius " +
                        "verified=$verified exited=$exited"
            )

            if (inside) {
                insideStop = stop
                break
            }
        }

        if (insideStop != null) {
            handleStopGeofence(insideStop, loc, radius, locked = false)
        } else {
            printDebug("PROCESS_NO_INSIDE_STOP route=$routeId radius=$radius")
        }
    }

    private fun handleStopGeofence(
        s: Map<String, String>,
        loc: Location,
        radius: Float,
        locked: Boolean
    ) {
        if (patrolCompletedHandled) return

        val stopId = s["stop_id"]?.toIntOrNull() ?: return
        val routeId = s["route_id"]?.toIntOrNull() ?: return
        val officerRouteJoinId = s["officer_route_join_id"]?.toIntOrNull() ?: return
        val officerStopId = s["officer_stop_id"]?.toIntOrNull() ?: return
        val stopHash = s["stop_hash"].orEmpty()
        val stopName = s["stop_name"].orEmpty()
        val lat = s["latitude"]?.toDoubleOrNull() ?: return
        val lon = s["longitude"]?.toDoubleOrNull() ?: return
        val status = s["status"]?.toIntOrNull() ?: 0
        val reached = !s["reached_time"].isNullOrEmpty()
        val verified = status == 2 || !s["upload_time"].isNullOrEmpty()
        val exited = !s["exit_time"].isNullOrEmpty()
        val freq = s["frequency_duration"]?.toIntOrNull() ?: 0

        val dist = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, dist)
        val inside = dist[0] <= radius

        printDebug(
            "GEOFENCE radius=$radius route=$routeId stopId=$stopId hash=$stopHash " +
                    "status=$status reached=$reached verified=$verified exited=$exited inside=$inside " +
                    "dist=${dist[0]} freq=$freq locked=$locked"
        )

        if (locked) {

            val exitRadius = radius + 20f
            val trulyOutside = dist[0] > exitRadius

            if (!trulyOutside) {

                if (consecutiveOutsideReadings.remove(officerStopId) != null) {
                    printDebug("EXIT_STREAK_RESET stopId=$stopId officerStopId=$officerStopId dist=${dist[0]} exitRadius=$exitRadius")
                }
                printDebug(
                    "EXIT_WAIT stopId=$stopId officerStopId=$officerStopId hash=$stopHash inside=$inside " +
                            "reached=$reached verified=$verified exited=$exited exitRadius=$exitRadius dist=${dist[0]}"
                )
                return
            }

            val streak = (consecutiveOutsideReadings[officerStopId] ?: 0) + 1
            consecutiveOutsideReadings[officerStopId] = streak

            printDebug(
                "EXIT_STREAK stopId=$stopId officerStopId=$officerStopId streak=$streak " +
                        "required=$REQUIRED_CONSECUTIVE_OUTSIDE_READINGS dist=${dist[0]} exitRadius=$exitRadius"
            )

            if (streak < REQUIRED_CONSECUTIVE_OUTSIDE_READINGS) {
                return
            }
            consecutiveOutsideReadings.remove(officerStopId)

            if (reached && !verified && !exited) {
                PatrolNotifications.notifyExitedWithoutVerify(this, stopName, stopId)

                val time = getTime()
                dbHelper.updateExit(officerRouteJoinId, officerStopId, time)

                if (dbHelper.getActiveStopId() == officerStopId) {
                    dbHelper.clearActiveStopId()
                }
                enqueueExit(stopId, routeId, officerRouteJoinId, officerStopId, time, stopHash)
                broadcastDataChanged()

                printDebug("EXIT_WITHOUT_VERIFY stopId=$stopId officerStopId=$officerStopId hash=$stopHash")
                return
            }

            if (reached && verified && !exited) {
                val time = getTime()

                dbHelper.updateExit(officerRouteJoinId, officerStopId, time)

                if (dbHelper.getActiveStopId() == officerStopId) {
                    dbHelper.clearActiveStopId()
                }

                enqueueExit(stopId, routeId, officerRouteJoinId, officerStopId, time, stopHash)
                broadcastDataChanged()

                PatrolNotifications.notifyExitedAfterVerify(this, stopName, stopId)

                printDebug("EXIT_TRIGGER stopId=$stopId officerStopId=$officerStopId hash=$stopHash route=$routeId")
            } else {
                printDebug(
                    "EXIT_ALREADY_HANDLED stopId=$stopId officerStopId=$officerStopId " +
                            "reached=$reached verified=$verified exited=$exited"
                )
            }
            return
        }

        if (!inside) {
            printDebug("REACHED_WAIT_OUTSIDE stopId=$stopId hash=$stopHash dist=${dist[0]} radius=$radius")
            return
        }

        val lastExit = dbHelper.getLastExitByHash(stopHash)
        val sameHashVerifiedExitedEarlier =
            dbHelper.isAnyStopVerifiedAndExitedWithHash(stopHash, stopId, routeId)

        if (!verified && freq > 0 && sameHashVerifiedExitedEarlier && lastExit != null) {
            val diffMin = (System.currentTimeMillis() - lastExit) / (1000 * 60)
            if (diffMin < freq) {
                printDebug(
                    "HANDLE_FREQ_BLOCK route=$routeId stopId=$stopId hash=$stopHash " +
                            "diffMin=$diffMin freq=$freq"
                )
                return
            }
        }

        if (!reached) {
            val time = getTime()
            dbHelper.updateReached(officerRouteJoinId, officerStopId, time)
            dbHelper.setActiveStopId(officerStopId)

            enqueueReach(stopId, routeId, officerRouteJoinId, officerStopId, time)

            PatrolNotifications.notifyReachedStop(this, stopName, stopId)

            broadcastDataChanged()

            printDebug("REACHED_TRIGGER stopId=$stopId hash=$stopHash route=$routeId")
        } else {
            printDebug("REACHED_ALREADY stopId=$stopId hash=$stopHash")
        }
    }


    private fun isEverythingDone(): Boolean {
        return dbHelper.getAllRoutes().isEmpty() || isPatrolCompleted()
    }

    private fun isPatrolCompleted(): Boolean {
        val stops = dbHelper.getAllStopsFull()
        if (stops.isEmpty()) {
            return false
        }
        for (stop in stops) {
            val status = stop["status"]?.toIntOrNull() ?: 0
            val uploadTime = stop["upload_time"].orEmpty()
            val exitTime = stop["exit_time"].orEmpty()

            val completed = status == 2 &&
                    uploadTime.isNotEmpty() &&
                    exitTime.isNotEmpty()
            if (!completed) {
                return false
            }
        }
        return true
    }

    private fun callRouteApi() {
        val token = prefs.getToken() ?: return
        val patrolId = prefs.getPatrolId() ?: return

        if (!sessionManager.isSessionValid()) {
            printDebug("ROUTE_API_ABORT: Session invalid")
            return
        }

        if (!isInternetAvailable()) {
            broadcastApiResult(false, "Offline mode")
            return
        }

        val request = Request.Builder()
            .url("${BASE_URL}api/v1/routes/officer-next-route/$patrolId/")
            .get()
            .addHeader("Authorization", "Bearer $token")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                printDebug("ROUTE_API_FAIL error=${e.message}")
                broadcastApiResult(false, "Offline mode")
                PatrolNotifications.notifyInternetLost(this@PatrolForegroundService)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string().orEmpty()
                printDebug("ROUTE_API_RESPONSE code=${response.code} body=$responseBody")

                val obj = try {
                    JSONObject(responseBody)
                } catch (e: Exception) {
                    JSONObject()
                }

                val message = obj.optString("message", "No message from server")
                if (response.isSuccessful && obj.has("stop_face_required")) {
                    prefs.setStopFaceRequired(obj.getBoolean("stop_face_required"))
                }
                val dataArray = obj.optJSONArray("data")

                if (response.code == 404 || dataArray == null || dataArray.length() == 0) {
                    printDebug("ROUTE_API_EMPTY: Server returned null/empty routes. Clearing local DB stops.")
                    dbHelper.clearStops()
                    prefs.saveActiveRouteId(null)
                    prefs.saveActiveOfficerRouteJoinId(null)
                    broadcastApiResult(true, "No routes available")
                    broadcastDataChanged()

                    if (prefs.getActiveRouteId() == null) {
                        if (isWithinShift8Hours()) {
                            printDebug("SUSPEND_ROUTE_POLLING: No active routes on server, but inside 8hr shift window. Keeping location tracking running.")
                        } else {
                            printDebug("AUTO_STOP_SERVICE: No shifts/routes available and outside 8hr shift window.")
                            prefs.saveTrackingState(false)
                            stopMonitoring()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                    return
                }

                for (r in 0 until dataArray.length()) {
                    val route = dataArray.getJSONObject(r)

                    val routeId = route.getInt("route_id")
                    val officerRouteJoinId = route.optInt("officer_route_join_id", 0)
                    val routeStatus = route.optInt("route_status", 0)

                    if (routeStatus == 0) {
                        removeIgnoredRoute(routeId)
                    }

                    if (routeStatus == 2 || (routeStatus != 0 && isIgnoredRoute(routeId))) {
                        printDebug("SKIP_ROUTE routeId=$routeId routeStatus=$routeStatus ignored=${isIgnoredRoute(routeId)}")
                        val isActiveRoute = officerRouteJoinId != 0 &&
                                officerRouteJoinId == prefs.getActiveOfficerRouteJoinId()

                        if (!isActiveRoute) {
                            dbHelper.clearRouteAttempt(officerRouteJoinId)
                        } else {
                            printDebug("SKIP_ROUTE_GUARD: not clearing active route officerRouteJoinId=$officerRouteJoinId")
                        }
                        continue
                    }

                    val routeName = route.getString("route_name")
                    val dutyShift = route.optString("duty_shift", "")
                    val rawDutyStart = route.optString("duty_start", "")
                    val rawDutyTime = route.optString("duty_time", "")
                    val dutyStart = if (rawDutyStart.isNotEmpty()) rawDutyStart else rawDutyTime
                    val dutyDate = route.optString("duty_date", "")

                    // AUTO-START & SHIFT TIME REFRESH LOGIC:
                    // If route is assigned (officerRouteJoinId > 0) or duty shift is present, ensure shift anchor is valid and start location logging immediately.
                    try {
                        val nowMillis = SecureTime.now() ?: System.currentTimeMillis()
                        val existingShiftStart = prefs.getShiftStartTime()
                        val eightHoursMillis = 8 * 60 * 60 * 1000L
                        val isShiftExpired = existingShiftStart == 0L || (nowMillis - existingShiftStart) >= eightHoursMillis
                        val isNewShiftJoinId = officerRouteJoinId > 0 && officerRouteJoinId != prefs.getShiftOfficerRouteJoinId()

                        if (isShiftExpired || isNewShiftJoinId) {
                            printDebug("REFRESH_SHIFT_START: Updating shift_start_time_millis to $nowMillis for officerRouteJoinId=$officerRouteJoinId")
                            prefs.saveShiftStartTime(nowMillis)
                            if (officerRouteJoinId > 0) {
                                prefs.saveShiftOfficerRouteJoinId(officerRouteJoinId)
                            }
                        }
                        
                        if (!prefs.isTracking()) {
                            printDebug("PATROL_AUTO_START: Shift/route time received for officerRouteJoinId=$officerRouteJoinId. Activating tracking.")
                            prefs.saveTrackingState(true)
                        }
                        
                        handler.post { 
                            startMonitoringIfNeeded()
                            getLocationAndUpload()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process auto-start on route assignment", e)
                    }

                    PatrolNotifications.notifyRouteAssigned(
                        this@PatrolForegroundService,
                        routeName,
                        officerRouteJoinId
                    )

                    val stopsArray = route.optJSONArray("stops") ?: continue
                    val list = mutableListOf<ContentValues>()

                    for (i in 0 until stopsArray.length()) {
                        val stop = stopsArray.getJSONObject(i)
                        val officerStopId = stop.optInt("officer_stop_id", 0)

                        if (officerStopId <= 0) {
                            continue
                        }

                        val cv = ContentValues()
                        cv.put("officer_route_join_id", officerRouteJoinId)
                        cv.put("officer_stop_id", officerStopId)
                        cv.put("stop_id", stop.getInt("stop_id"))
                        cv.put("officer_id", prefs.getPatrolId()?.toInt())
                        cv.put("route_id", routeId)
                        cv.put("route_name", routeName)
                        cv.put("stop_name", stop.getString("stop_name"))
                        cv.put("stop_order", i)
                        cv.put("stop_hash", stop.getString("stop_hash"))
                        cv.put("latitude", stop.getString("latitude"))
                        cv.put("longitude", stop.getString("longitude"))
                        cv.put("status", stop.optInt("status", 0))
                        cv.put("frequency_duration", stop.optInt("frequency_duration", 0))
                        cv.put("duty_shift", dutyShift)
                        cv.put("duty_start", dutyStart)
                        cv.put("duty_date", dutyDate)

                        list.add(cv)
                    }

                    if (list.isNotEmpty()) {
                        dbHelper.clearAndReplaceStops(routeName, routeId, officerRouteJoinId, list)
                        printDebug("API_REFRESH_REPLACED routeId=$routeId officerRouteJoinId=$officerRouteJoinId stopsCount=${list.size}")
                    } else {
                        Log.w(TAG, "ROUTE_$routeId had stops array but ALL stops were rejected (missing officer_stop_id) — nothing written to DB. Check backend payload.")
                    }
                }

                broadcastApiResult(response.isSuccessful, message)
                broadcastDataChanged()

                if (lastNotifiedRouteSignature != responseBody) {
                    lastNotifiedRouteSignature = responseBody
                }
            }
        })
    }

    private fun callPatrolUpdateApi(
        routeId: Int,
        officerRouteJoinId: Int,
        status: Int,
        routeSummary: String = "",
        onDone: ((Boolean) -> Unit)? = null
    ) {
        val json = JSONObject()
        json.put("officer_id", prefs.getPatrolId()?.toInt())
        json.put("route_id", routeId)
        json.put("officer_route_join_id", officerRouteJoinId)
        json.put("time", getTime())
        json.put("status", status)
        if (routeSummary.isNotBlank()) {
            json.put("route_summary", routeSummary)
        }

        printDebug("PATROL_UPDATE_REQUEST routeId=$routeId status=$status body=$json")

        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("${BASE_URL}api/v1/duties/patrol-update/")
            .patch(body)
            .addHeader("Authorization", "Bearer ${prefs.getToken()}")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                printDebug("PATROL_UPDATE_FAIL routeId=$routeId status=$status error=${e.message}")
                onDone?.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string().orEmpty()
                val isSuccess = response.isSuccessful

                printDebug("PATROL_UPDATE_RESPONSE routeId=$routeId status=$status code=${response.code} success=$isSuccess body=$responseText")

                if (response.code == 404) {
                    saveIgnoredRoute(routeId)
                    dbHelper.clearRouteAttempt(officerRouteJoinId)
                    onDone?.invoke(true)
                    return
                }

                onDone?.invoke(isSuccess)
            }
        })
    }


    private fun broadcastDataChanged() {
        val intent = Intent(ACTION_ROUTE_DATA_CHANGED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastLocationChanged() {
        val intent = Intent(ACTION_LOCATION_CHANGED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSpoofDetected(reason: String) {
        val intent = Intent(ACTION_ROUTE_DATA_CHANGED).apply {
            putExtra(EXTRA_SPOOF_DETECTED, true)
            putExtra(EXTRA_SPOOF_REASON, reason)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastApiResult(success: Boolean, message: String) {
        val intent = Intent(ACTION_ROUTE_API_RESULT).apply {
            putExtra(EXTRA_RESULT_SUCCESS, success)
            putExtra(EXTRA_RESULT_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun checkInternetAndNotify() {
        val now = System.currentTimeMillis()

        if (!isInternetAvailable()) {
            if (now - lastOfflineNotifiedAt > 60_000) {
                lastOfflineNotifiedAt = now
                showAlertNotification(
                    id = NOTIFICATION_ID_NET,
                    title = "Internet unavailable",
                    body = "Patrol sync may be delayed until connection returns."
                )
            }
        }
    }

    private fun buildServiceNotification(text: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlagImmutable()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle("Patrol running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateForegroundText(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_SERVICE, buildServiceNotification(text))
    }

    private fun ensureForegroundState() {
        // Safety call to ensure startForeground is called immediately, 
        // preventing ForegroundServiceDidNotStartInTimeException.
        try {
            val text = if (prefs.isTracking()) "Patrol service is active" else "Patrol service is ready"
            val notification = buildServiceNotification(text)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasLocation = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                
                if (hasLocation) {
                    startForeground(
                        NOTIFICATION_ID_SERVICE, 
                        notification, 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(NOTIFICATION_ID_SERVICE, notification)
                    printDebug("ensureForegroundState: starting WITHOUT location type (no permission)")
                }
            } else {
                startForeground(NOTIFICATION_ID_SERVICE, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure foreground state", e)
        }
    }

    private fun showAlertNotification(id: Int, title: String, body: String) {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingFlagImmutable()
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val serviceChannel = NotificationChannel(
            CHANNEL_ID_SERVICE,
            "Patrol Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows patrol background service status"
        }

        val alertsChannel = NotificationChannel(
            CHANNEL_ID_ALERTS,
            "Patrol Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Patrol reach, route, exit and network alerts"
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(alertsChannel)
    }

    private fun isFixReliable(loc: Location): Boolean {
        return loc.hasAccuracy() && loc.accuracy <= MAX_ACCEPTABLE_ACCURACY_METERS
    }

    private fun getGeofenceRadius(): Float {
        val raw = prefs.getRadius()?.trim().orEmpty()
        val cleaned = raw
            .replace("meter", "", ignoreCase = true)
            .replace("m", "", ignoreCase = true)
            .trim()

        return cleaned.toFloatOrNull() ?: 30f
    }

    private fun getTime(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                printDebug("NETWORK_RESTORED — draining sync queue")
                drainSyncQueue()
                callRouteApi()
            }
        }

        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            printDebug("NETWORK_CALLBACK_REGISTER_FAILED error=${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                printDebug("NETWORK_CALLBACK_UNREGISTER_FAILED error=${e.message}")
            }
        }
        networkCallback = null
    }

    private fun drainSyncQueue() {
        if (isDrainingSyncQueue) {
            printDebug("DRAIN_SKIPPED already draining")
            return
        }

        if (!isInternetAvailable()) {
            printDebug("DRAIN_SKIPPED no internet")
            scheduleSyncRetry()
            return
        }

        val item = dbHelper.peekOldestSyncAction()
        if (item == null) {
            printDebug("DRAIN_QUEUE_EMPTY")
            if (patrolCompletedHandled) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }

        isDrainingSyncQueue = true
        sendQueuedAction(item) { success, isPermanentFailure ->
            isDrainingSyncQueue = false

            when {
                success -> {
                    dbHelper.deleteSyncAction(item.id)
                    handleDeferredPatrolUpdateSideEffects(item)
                    drainSyncQueue()
                }

                isPermanentFailure -> {
                    dbHelper.markSyncAttemptFailed(item.id, "permanent_failure_dropped")
                    dbHelper.deleteSyncAction(item.id)
                    drainSyncQueue()
                }

                else -> {
                    dbHelper.markSyncAttemptFailed(item.id, "network_or_server_error")
                    printDebug("DRAIN_RETRY_LATER id=${item.id} type=${item.actionType}")
                    scheduleSyncRetry()
                }
            }
        }
    }

    private fun handleDeferredPatrolUpdateSideEffects(item: RouteDbHelper.SyncQueueItem) {
        if (item.actionType != "patrol_update") return

        val payload = try {
            JSONObject(item.payloadJson)
        } catch (e: Exception) {
            return
        }
        val status = payload.optInt("status", -1)
        if (status != 2) return

        val routeId = payload.optInt("routeId", -1)
        val officerRouteJoinId = payload.optInt("officerRouteJoinId", -1)
        if (routeId == -1 || officerRouteJoinId == -1) return
        val finishedRouteName =
            dbHelper.getStopsForRoute(routeId).firstOrNull()?.get("route_name") ?: "Route"
        val hadPendingStops = dbHelper.hasPendingStopsByAttempt(officerRouteJoinId)

        dbHelper.clearRouteAttempt(officerRouteJoinId)

        if (hadPendingStops) {
            PatrolNotifications.notifyRouteCompletedWithPending(
                this,
                finishedRouteName,
                officerRouteJoinId
            )
        } else {
            PatrolNotifications.notifyRouteCompletedClean(
                this,
                finishedRouteName,
                officerRouteJoinId
            )
        }

        if (dbHelper.getAllRoutes().isEmpty()) {
            PatrolNotifications.notifyPatrolFullyCompleted(this)
        }

        broadcastDataChanged()

        if (isEverythingDone()) {
            handlePatrolCompleted()
        }
    }

    private fun scheduleSyncRetry() {
        if (syncRetryRunnable != null) return

        syncRetryRunnable = Runnable {
            syncRetryRunnable = null
            if (dbHelper.hasPendingSyncActions()) {
                drainSyncQueue()
            }
        }
        handler.postDelayed(syncRetryRunnable!!, 30_000)
    }

    private fun sendQueuedAction(
        item: RouteDbHelper.SyncQueueItem,
        onResult: (success: Boolean, isPermanentFailure: Boolean) -> Unit
    ) {
        val payload = try {
            JSONObject(item.payloadJson)
        } catch (e: Exception) {
            printDebug("DRAIN_BAD_PAYLOAD id=${item.id} error=${e.message}")
            onResult(false, true) // malformed payload can never succeed — drop it
            return
        }

        when (item.actionType) {
            "patrol_update" -> {
                val routeId = payload.optInt("routeId", -1)
                val officerRouteJoinId = payload.optInt("officerRouteJoinId", -1)
                val officerId = payload.optInt("officerId", -1)
                val status = payload.optInt("status", -1)
                val time = payload.optString("time", getTime())
                val routeSummary = payload.optString("route_summary", "")

                if (routeId == -1 || officerRouteJoinId == -1 || status == -1) {
                    onResult(false, true)
                    return
                }

                sendPatrolUpdateRaw(routeId, officerRouteJoinId, officerId, status, time, routeSummary, onResult)
            }

            "reach" -> {
                val stopId = payload.optInt("stopId", -1)
                val routeId = payload.optInt("routeId", -1)
                val officerRouteJoinId = payload.optInt("officerRouteJoinId", -1)
                val officerStopId = payload.optInt("officerStopId", -1)
                val officerId = payload.optInt("officerId", -1)
                val time = payload.optString("time", getTime())

                if (stopId == -1 || routeId == -1 || officerRouteJoinId == -1 || officerStopId == -1) {
                    onResult(false, true)
                    return
                }
                sendReachRaw(stopId, routeId, officerRouteJoinId, officerStopId, officerId, time, onResult)
            }

            "exit" -> {
                val stopId = payload.optInt("stopId", -1)
                val routeId = payload.optInt("routeId", -1)
                val officerRouteJoinId = payload.optInt("officerRouteJoinId", -1)
                val officerStopId = payload.optInt("officerStopId", -1)
                val officerId = payload.optInt("officerId", -1)
                val time = payload.optString("time", getTime())

                if (stopId == -1 || routeId == -1 || officerRouteJoinId == -1 || officerStopId == -1) {
                    onResult(false, true)
                    return
                }
                sendExitRaw(stopId, routeId, officerRouteJoinId, officerStopId, officerId, time, onResult)
            }

            "location_log" -> {
                sendLocationLogRaw(payload.toString(), onResult)
            }

            SYNC_ACTION_VERIFY_UPLOAD -> {
                val officerRouteJoinId = payload.optInt("officerRouteJoinId", -1)
                val officerStopId = payload.optInt("officerStopId", -1)
                val officerId = payload.optInt("officerId", -1)
                val stopId = payload.optInt("stopId", -1)
                val routeId = payload.optInt("routeId", -1)
                val imageFilePath = payload.optString("imageFilePath", "")
                val imageBase64 = payload.optString("imageBase64", "")
                val lat = payload.optDouble("lat", 0.0)
                val lon = payload.optDouble("lon", 0.0)
                val time = payload.optString("time", getTime())

                if (officerRouteJoinId == -1 || officerStopId == -1 || (!payload.optBoolean("locationOnly") && imageFilePath.isEmpty() && imageBase64.isEmpty())) {
                    onResult(false, true)
                    return
                }

                sendVerifyUploadRaw(
                    stopId, routeId, officerRouteJoinId, officerStopId, officerId,
                    imageFilePath, imageBase64, lat, lon, time, onResult
                )
            }

            SYNC_ACTION_EVIDENCE -> {
                val officerRouteJoinId = payload.optInt("officerRouteJoinId", -1)
                val officerStopId = payload.optInt("officerStopId", -1)
                val stopId = payload.optInt("stopId", -1)
                val routeId = payload.optInt("routeId", -1)
                val officerId = payload.optInt("officerId", -1)
                val imageFilePath = payload.optString("imageFilePath", "")
                val imageBase64 = payload.optString("imageBase64", "")
                val description = payload.optString("description", "")
                val lat = payload.optDouble("lat", 0.0)
                val lon = payload.optDouble("lon", 0.0)

                if (officerRouteJoinId == -1 || officerStopId == -1 || (imageFilePath.isEmpty() && imageBase64.isEmpty())) {
                    onResult(false, true)
                    return
                }

                sendEvidenceRaw(
                    stopId, routeId, officerRouteJoinId, officerStopId, officerId,
                    imageFilePath, imageBase64, description, lat, lon, onResult
                )
            }

            else -> {
                printDebug("DRAIN_UNKNOWN_ACTION_TYPE type=${item.actionType}")
                onResult(false, true)
            }
        }
    }

    private fun sendPatrolUpdateRaw(
        routeId: Int,
        officerRouteJoinId: Int,
        officerId: Int,
        status: Int,
        time: String,
        routeSummary: String = "",
        onResult: (Boolean, Boolean) -> Unit
    ) {
        val json = JSONObject().apply {
            put("officer_id", if (officerId != -1) officerId else prefs.getPatrolId()?.toInt())
            put("route_id", routeId)
            put("officer_route_join_id", officerRouteJoinId)
            put("time", time)
            put("status", status)
            if (routeSummary.isNotBlank()) {
                put("route_summary", routeSummary)
            }
        }

        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("${BASE_URL}api/v1/duties/patrol-update/")
            .patch(body)
            .addHeader("Authorization", "Bearer ${prefs.getToken()}")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val isPermanent = it.code == 404
                    onResult(it.isSuccessful, isPermanent)
                }
            }
        })
    }

    private fun sendReachRaw(
        stopId: Int,
        routeId: Int,
        officerRouteJoinId: Int,
        officerStopId: Int,
        officerId: Int,
        time: String,
        onResult: (Boolean, Boolean) -> Unit
    ) {
        val json = JSONObject().apply {
            put("officer_id", if (officerId != -1) officerId else prefs.getPatrolId()?.toInt())
            put("stop_id", stopId)
            put("route_id", routeId)
            put("officer_route_join_id", officerRouteJoinId)
            put("officer_stop_id", officerStopId)
            put("time", time)
        }

        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("${BASE_URL}api/v1/duties/officer-stops/reach/")
            .patch(body)
            .addHeader("Authorization", "Bearer ${prefs.getToken()}")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val isPermanent = it.code == 404
                    onResult(it.isSuccessful, isPermanent)
                }
            }
        })
    }

    private fun sendExitRaw(
        stopId: Int,
        routeId: Int,
        officerRouteJoinId: Int,
        officerStopId: Int,
        officerId: Int,
        time: String,
        onResult: (Boolean, Boolean) -> Unit
    ) {
        val json = JSONObject().apply {
            put("officer_id", if (officerId != -1) officerId else prefs.getPatrolId()?.toInt())
            put("route_id", routeId)
            put("stop_id", stopId)
            put("officer_route_join_id", officerRouteJoinId)
            put("officer_stop_id", officerStopId)
            put("exit_time", time)
        }

        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("${BASE_URL}api/v1/duties/exit-time/")
            .patch(body)
            .addHeader("Authorization", "Bearer ${prefs.getToken()}")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val isPermanent = it.code == 404
                    onResult(it.isSuccessful, isPermanent)
                }
            }
        })
    }
    
    private fun sendLocationLogRaw(
        payload: String,
        onResult: (Boolean, Boolean) -> Unit
    ) {
        val body = payload.toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("${BASE_URL}api/v1/officers/location-logs/")
            .post(body)
            .addHeader("Authorization", "Bearer ${prefs.getToken()}")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val isPermanent = it.code == 404
                    onResult(it.isSuccessful, isPermanent)
                }
            }
        })
    }

    private fun createMultipartImageBody(imageFilePath: String, imageBase64Fallback: String): RequestBody {
        val file = if (imageFilePath.isNotEmpty()) File(imageFilePath) else null
        return if (file != null && file.exists()) {
            file.asRequestBody("image/webp".toMediaTypeOrNull())
        } else if (imageBase64Fallback.isNotEmpty()) {
            Base64.decode(imageBase64Fallback, Base64.DEFAULT).toRequestBody("image/webp".toMediaTypeOrNull())
        } else {
            byteArrayOf().toRequestBody("image/webp".toMediaTypeOrNull())
        }
    }

    private fun sendVerifyUploadRaw(
        stopId: Int,
        routeId: Int,
        officerRouteJoinId: Int,
        officerStopId: Int,
        officerId: Int,
        imageFilePath: String,
        imageBase64: String,
        lat: Double,
        lon: Double,
        time: String,
        onResult: (Boolean, Boolean) -> Unit
    ) {
        val actualOfficerId = if (officerId != -1) officerId else (prefs.getPatrolId()?.toInt() ?: 0)
        
        val imageBody = createMultipartImageBody(imageFilePath, imageBase64)
        val imagePart = MultipartBody.Part.createFormData("image", "evidence.webp", imageBody)

        val formattedLat = String.format(Locale.US, "%.6f", lat)
        val formattedLon = String.format(Locale.US, "%.6f", lon)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("officer_id", actualOfficerId.toString())
            .addFormDataPart("stop_id", stopId.toString())
            .addFormDataPart("route_id", routeId.toString())
            .addFormDataPart("latitude", formattedLat)
            .addFormDataPart("longitude", formattedLon)
            .addFormDataPart("time", time)
            .addFormDataPart("officer_stop_id", officerStopId.toString())
            .apply { if (imageFilePath.isNotEmpty() || imageBase64.isNotEmpty()) addPart(imagePart) }
            .build()

        val request = Request.Builder()
            .url("${BASE_URL}api/v1/duties/officer-stops/upload/")
            .patch(requestBody)
            .addHeader("Authorization", "Bearer ${prefs.getToken()}")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val isPermanent = it.code == 404
                    onResult(it.isSuccessful, isPermanent)
                }
            }
        })
    }

    private fun sendEvidenceRaw(
        stopId: Int,
        routeId: Int,
        officerRouteJoinId: Int,
        officerStopId: Int,
        officerId: Int,
        imageFilePath: String,
        imageBase64: String,
        description: String,
        lat: Double,
        lon: Double,
        onResult: (Boolean, Boolean) -> Unit
    ) {
        val actualOfficerId = if (officerId != -1) officerId else (prefs.getPatrolId()?.toInt() ?: 0)

        val imageBody = createMultipartImageBody(imageFilePath, imageBase64)
        val imagePart = MultipartBody.Part.createFormData("background_image", "background.webp", imageBody)

        val formattedLat = String.format(Locale.US, "%.6f", lat)
        val formattedLon = String.format(Locale.US, "%.6f", lon)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("route_id", routeId.toString())
            .addFormDataPart("stop_id", stopId.toString())
            .addFormDataPart("officer_id", actualOfficerId.toString())
            .addFormDataPart("officer_route_join_id", officerRouteJoinId.toString())
            .addFormDataPart("latitude", formattedLat)
            .addFormDataPart("longitude", formattedLon)
            .addFormDataPart("description", description)
            .addPart(imagePart)
            .build()

        val request = Request.Builder()
            .url("${BASE_URL}api/v1/duties/stop-background-images/")
            .post(requestBody)
            .addHeader("Authorization", "Bearer ${prefs.getToken()}")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val isPermanent = it.code == 404
                    onResult(it.isSuccessful, isPermanent)
                }
            }
        })
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun pendingFlagImmutable(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun saveIgnoredRoute(routeId: Int) {
        val sp = getSharedPreferences("patrol_ignored_routes", MODE_PRIVATE)
        val oldSet = sp.getStringSet("ignored_route_ids", emptySet()) ?: emptySet()
        val newSet = oldSet.toMutableSet()
        newSet.add(routeId.toString())
        sp.edit().putStringSet("ignored_route_ids", newSet).apply()
    }

    private fun removeIgnoredRoute(routeId: Int) {
        val sp = getSharedPreferences("patrol_ignored_routes", MODE_PRIVATE)
        val oldSet = sp.getStringSet("ignored_route_ids", emptySet()) ?: emptySet()
        val newSet = oldSet.toMutableSet()
        newSet.remove(routeId.toString())
        sp.edit().putStringSet("ignored_route_ids", newSet).apply()
    }

    private fun isIgnoredRoute(routeId: Int): Boolean {
        val sp = getSharedPreferences("patrol_ignored_routes", MODE_PRIVATE)
        val set = sp.getStringSet("ignored_route_ids", emptySet()) ?: emptySet()
        return set.contains(routeId.toString())
    }

    private fun reportGpsStatus(isEnabled: Boolean) {
        val token = prefs.getToken()
        val patrolId = prefs.getPatrolId()

        if (token.isNullOrEmpty() || patrolId.isNullOrEmpty()) return

        val json = JSONObject()
        json.put("officer_id", patrolId.toIntOrNull())
        json.put("is_gps_enabled", if (isEnabled) 1 else 0)

        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("${BASE_URL}api/v1/officers/officer-mobile/enable-gps/")
            .patch(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                printDebug("GPS_STATUS_REPORT_FAIL error=${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    printDebug("GPS_STATUS_REPORT_RESPONSE code=${it.code}")
                }
            }
        })
    }

    private fun printDebug(msg: String) {
        Log.d(TAG, msg)
    }
}
