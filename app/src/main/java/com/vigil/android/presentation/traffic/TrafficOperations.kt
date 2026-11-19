package com.vigil.android.presentation.traffic

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.vigil.android.core.common.session.SessionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.net.URI
import java.time.OffsetDateTime

internal data class TrafficDuty(
    val id: Long,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val mode: String,
    val duty_date: String,
    val starts_at: String?,
    val ends_at: String?,
    val window_end: String?,
    val eligible: Boolean,
    val active: Boolean,
    val session_started_at: String?,
    val presence: String,
    val last_seen: String?,
    val distance_m: Double?
) {
    fun deadline(): Long {
        if (mode == "manual" && window_end == null) return Long.MAX_VALUE
        return OffsetDateTime.parse(requireNotNull(window_end) { "Duty end time is missing." }).toInstant().toEpochMilli()
    }
}

internal data class TrafficAlert(
    val id: Long,
    val kind: String,
    val message: String,
    val created_at: String,
    val read: Boolean,
    val route_name: String?,
    val station_name: String?,
    val expires_at: String?,
    val google_maps_url: String? = null
) {
    val congestionMap: String? get() {
        if (kind != "congestion") return null
        val url = google_maps_url ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf {
            uri.scheme.equals("https", true) && uri.host.equals("www.google.com", true) &&
                uri.rawUserInfo == null && uri.port == -1 && uri.rawPath == "/maps/search/"
        }
    }

    val title: String get() = when (kind) {
        "assignment" -> "New traffic duty"
        "congestion" -> "Traffic congestion"
        "geofence" -> "Duty post alert"
        else -> "Control room alert"
    }

    val detail: String get() = if (kind == "congestion" && !message.contains("Google Maps", ignoreCase = true))
        "$message · Google Maps" else message
}

internal data class DutySwitch(val on: Boolean, val session_started_at: String? = null)

internal interface TrafficApi {
    @GET("api/v1/traffic/duties/") suspend fun duties(): List<TrafficDuty>
    @GET("api/v1/traffic/alerts/") suspend fun alerts(): List<TrafficAlert>
    @POST("api/v1/traffic/duties/{id}/switch/")
    suspend fun switch(@Path("id") id: Long, @Body action: DutySwitch): TrafficDuty
    @POST("api/v1/traffic/duties/{id}/location/")
    suspend fun point(@Path("id") id: Long, @Body fix: TrafficFix): TrafficDuty
    @POST("api/v1/traffic/alerts/{id}/read/")
    suspend fun markRead(@Path("id") id: Long, @Body body: Map<String, String> = emptyMap()): retrofit2.Response<Unit>
}

internal data class TrafficFix(
    val latitude: Double, val longitude: Double, val accuracy_m: Float,
    val captured_at: String, val session_started_at: String?
)

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TrafficDependencies {
    fun retrofit(): Retrofit
    fun sessionManager(): SessionManager
}

internal class TrafficOperations(val context: Context) {
    private val dependencies = EntryPointAccessors.fromApplication(context.applicationContext, TrafficDependencies::class.java)
    val api = dependencies.retrofit().create(TrafficApi::class.java)
    private val session = dependencies.sessionManager()
    val owner: String? get() {
        if (!session.isSessionValid() || !session.getRole().equals("Traffic Police", true)) return null
        return session.getUserId()?.takeIf { it.isNotBlank() } ?: session.getUserName()
    }
    fun prefs(account: String) = context.getSharedPreferences("traffic_duty_$account", Context.MODE_PRIVATE)
    fun dutyId(account: String) = prefs(account).getLong("duty", 0)
    fun hasPendingStop(account: String) = prefs(account).getLong("stop_duty", 0) > 0
    fun clearDuty(account: String) {
        prefs(account).edit().remove("duty").remove("session").remove("until").apply()
    }

    // The server establishes the session. A user can resume a confirmed shift after force-stop or reboot.
    suspend fun start(duty: TrafficDuty) {
        val account = owner ?: error("Sign in with a traffic police account.")
        flushStop(account)
        val active = if (duty.active) duty else api.switch(duty.id, DutySwitch(true))
        check(owner == account) { "The signed-in account changed." }
        check(active.active && active.eligible) { "This duty is outside its assigned hours." }
        val until = active.deadline()
        prefs(account).edit()
            .putLong("duty", active.id)
            .putString("session", active.session_started_at)
            .putLong("until", until)
            .remove("problem")
            .commit()
        try {
            val intent = Intent(context, TrafficDutyService::class.java).putExtra("account", account)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            queueStop(account, active.id, active.session_started_at)
            throw e
        }
    }

    fun queueStop(account: String, id: Long, sessionStart: String?) {
        prefs(account).edit()
            .remove("duty").remove("session").remove("until").remove("problem")
            .putLong("stop_duty", id).putString("stop_session", sessionStart).commit()
        context.stopService(Intent(context, TrafficDutyService::class.java))
        TrafficAlertJob.requestRetry(context)
    }

    suspend fun flushStop(account: String): Unit = stopLock.withLock {
        if (owner != account) return@withLock
        val data = prefs(account)
        val id = data.getLong("stop_duty", 0)
        if (id == 0L) return@withLock
        val sessionStart = data.getString("stop_session", null)
        val action = DutySwitch(false, sessionStart)
        try {
            api.switch(id, action)
        } catch (e: HttpException) {
            // Gone or reactivated under a different session. The old stop no longer applies.
            if (e.code() !in listOf(404, 409)) throw e
        }
        if (data.getLong("stop_duty", 0) == id && data.getString("stop_session", null) == sessionStart)
            data.edit().remove("stop_duty").remove("stop_session").apply()
    }

    companion object { private val stopLock = Mutex() }
}

internal fun trafficError(e: Exception): String {
    if (e is CancellationException) throw e
    if (e is HttpException) {
        val text = runCatching { JSONObject(e.response()?.errorBody()?.string().orEmpty()).optString("detail") }
            .getOrNull()
        return text?.takeIf { it.isNotBlank() } ?: "The control room returned HTTP ${e.code()}."
    }
    return e.localizedMessage ?: "Could not reach the control room."
}

suspend fun endTrafficSession(context: Context) {
    val ops = TrafficOperations(context.applicationContext)
    val account = ops.owner ?: return
    val id = ops.dutyId(account)
    if (id > 0) ops.queueStop(account, id, ops.prefs(account).getString("session", null))
    withTimeoutOrNull(3000) {
        try { ops.flushStop(account) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    TrafficAlerts.dismiss(context, account)
}
