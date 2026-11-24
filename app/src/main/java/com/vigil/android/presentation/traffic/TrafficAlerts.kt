package com.vigil.android.presentation.traffic

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vigil.android.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.OffsetDateTime

internal object TrafficAlerts {
    private val lock = Mutex()
    const val SERVICE_CHANNEL = "vigil_traffic_duty"
    private const val ALERT_CHANNEL = "vigil_traffic_alerts"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(SERVICE_CHANNEL, "Traffic duty location", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(ALERT_CHANNEL, "Control room traffic alerts", NotificationManager.IMPORTANCE_HIGH)
        ))
    }

    fun appIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 7301, Intent(context, MainActivity::class.java).also {
            it.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    suspend fun refresh(ops: TrafficOperations, owner: String): List<TrafficAlert> = lock.withLock {
        if (ops.owner != owner) return@withLock emptyList()
        val alerts = ops.api.alerts()
        if (ops.owner != owner) return@withLock emptyList()
        val context = ops.context
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannels(context)
        val allowed = manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED)
        val prefs = ops.prefs(owner)
        val notified = prefs.getLong("last_notified", 0L)
        val currentUnread = alerts.filter { !it.read }.map { it.id.toInt() }.toSet()
        manager.activeNotifications.filter { it.tag == "traffic:$owner" && it.id !in currentUnread }
            .forEach { manager.cancel(it.tag, it.id) }
        if (allowed && manager.getNotificationChannel(ALERT_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
            alerts.filter { !it.read && it.id > notified }.sortedBy { it.id }.forEach { alert ->
                val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(alert.title)
                    .setContentText(alert.detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alert.detail))
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setContentIntent(appIntent(context))
                    .setAutoCancel(true)
                    .also { builder ->
                        alert.expires_at?.let {
                            val expiry = runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
                            if (expiry != null) builder.setTimeoutAfter((expiry - System.currentTimeMillis()).coerceAtLeast(1))
                        }
                    }
                    .build()
                manager.notify("traffic:$owner", alert.id.toInt(), notification)
            }
            val maxId = alerts.maxOfOrNull { it.id } ?: 0
            if (maxId > notified) prefs.edit().putLong("last_notified", maxId).apply()
        }
        alerts
    }

    fun dismiss(context: Context, account: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == "traffic:$account" }.forEach { manager.cancel(it.tag, it.id) }
    }
}

class TrafficAlertJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val ops = TrafficOperations(applicationContext)
        val owner = ops.owner ?: return false
        jobs[params.jobId] = scope.launch {
            var retry = false
            try {
                ops.flushStop(owner)
                TrafficAlerts.refresh(ops, owner)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                retry = true
            } finally {
                jobs.remove(params.jobId)
            }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PERIODIC = 7314
        private const val RETRY = 7315

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(PERIODIC) != null) return
            scheduler.schedule(JobInfo.Builder(PERIODIC, ComponentName(context, TrafficAlertJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build())
        }

        fun requestRetry(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.schedule(JobInfo.Builder(RETRY, ComponentName(context, TrafficAlertJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build())
        }
    }
}
