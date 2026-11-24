package com.vigil.android.presentation.volunteers

import android.content.Context
import com.vigil.android.core.common.session.SessionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.time.OffsetDateTime

internal data class VolunteerDuty(
    val id: Long, val label: String, val station: String, val starts_at: String, val ends_at: String,
    val latitude: Double, val longitude: Double, val radius_m: Int, val presence: String, val arrived_at: String?
) {
    val end: Long get() = OffsetDateTime.parse(ends_at).toInstant().toEpochMilli()
    fun current(now: Long): Boolean = now >= OffsetDateTime.parse(starts_at).toInstant().toEpochMilli() && now < end
}

internal data class VolunteerFix(val latitude: Double, val longitude: Double, val accuracy_m: Float, val captured_at: String)

internal data class DutyMarker(
    val key: String, val kind: String, val officerName: String, val routeName: String,
    val lat: Double, val lng: Double, val observed: String, val presence: String
)

internal interface VolunteerApi {
    @GET("api/v1/volunteers/markers/")
    suspend fun markers(): List<DutyMarker>

    @GET("api/v1/volunteers/mobile/duties/")
    suspend fun duties(): List<VolunteerDuty>

    @POST("api/v1/volunteers/mobile/duties/{id}/location/")
    suspend fun location(@Path("id") id: Long, @Body fix: VolunteerFix): VolunteerDuty
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface VolunteerDependencies {
    fun retrofit(): Retrofit
    fun sessionManager(): SessionManager
}

internal class VolunteerClient(context: Context) {
    private val deps = EntryPointAccessors.fromApplication(context.applicationContext, VolunteerDependencies::class.java)
    val api: VolunteerApi = deps.retrofit().create(VolunteerApi::class.java)
    val account: String? get() {
        val session = deps.sessionManager()
        if (!session.isSessionValid() || !session.getRole().equals("Volunteer", true)) return null
        return session.getUserId()?.takeIf { it.isNotBlank() } ?: session.getUserName()
    }
}
