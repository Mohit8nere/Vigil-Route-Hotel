package com.vigil.android.presentation.verification

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
import com.vigil.android.core.nav.VerificationInboxActivity
import com.vigil.android.core.common.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

object VerificationJobs {
    private const val JOB_ID = 61413
    private const val CHANNEL = "vigil_verification"
    private const val NOTICE = 61414

    fun schedule(context: Context) {
        val session = SessionManager(context)
        if (!session.isLoggedIn() || session.getRole() != "Patrol Officer") return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) == null) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, VerificationJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build()
            scheduler.schedule(job)
        }
    }

    fun clearSession(context: Context) {
        if (SessionManager(context).getRole() == "Patrol Officer") {
            runCatching { VerificationStore(context).use { it.purgeReferences() } }
        }
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTICE)
    }

    internal fun publish(context: Context, store: VerificationStore, state: JSONObject) {
        val version = state.getLong("version")
        val pending = state.getJSONArray("assignments").objects()
        val outdated = version != store.summary().version
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!outdated && pending.isEmpty()) {
            manager.cancel(NOTICE)
            store.saveState("notice",JSONObject())
            return
        }
        val signature = "${if (outdated) version else -1}:" + pending.joinToString(",") { it.getLong("id").toString() }
        if (store.state("notice").optString("value") == signature) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Officer verification",NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context,NOTICE,Intent(context,VerificationInboxActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = buildList {
            if (outdated) add("Reference database ready to sync")
            if (pending.isNotEmpty()) add("${pending.size} pending assignment(s)")
        }.joinToString(" · ")
        manager.notify(NOTICE,NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Vigil verification").setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(intent).build())
        store.saveState("notice",JSONObject().put("value",signature))
    }
}

class VerificationJob : JobService() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var current: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val session = SessionManager(this)
        if (!session.isLoggedIn() || session.getRole() != "Patrol Officer") return false
        current = scope.launch {
            runCatching {
                VerificationStore(this@VerificationJob).use { store ->
                    store.flush()
                    VerificationJobs.publish(this@VerificationJob,store,store.refreshStatus())
                }
            }
            jobFinished(params,false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        current?.cancel()
        return true
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
