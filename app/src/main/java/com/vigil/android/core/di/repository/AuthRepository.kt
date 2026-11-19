package com.vigil.android.core.di.repository

import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.api.ApiService
import com.vigil.android.core.di.model.LoginRequest
import com.vigil.android.core.di.model.LoginResponse
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs
import dagger.hilt.android.qualifiers.ApplicationContext
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context
) {
    suspend fun login(request: LoginRequest): Response<LoginResponse> {
        val response = apiService.login(request)
        if (response.isSuccessful && response.body()?.success == true) {
            val loginData = response.body() ?: return response
            val tokens = loginData.tokens ?: return response
            sessionManager.saveLogin(tokens.access, tokens.refresh,
                (loginData.officerId ?: loginData.userId)?.toString(),
                loginData.officerName ?: loginData.username, loginData.role)
            val prefs = Prefs(context)
            loginData.officerId?.toString()?.let { prefs.savePatrolId(it) }
            loginData.officerName?.let { prefs.saveUserName(it) }
            prefs.saveToken(tokens.access)
            loginData.role?.let { prefs.saveRole(it) }
            loginData.districtId?.let { prefs.saveDistrictId(it) }
            loginData.stationId?.let { prefs.saveStationId(it) }
            loginData.stationName?.let { prefs.saveStationName(it) }
            loginData.districtName?.let { prefs.saveDistrictName(it) }
        }
        return response
    }

    fun isLoggedIn() = sessionManager.isLoggedIn()

    fun logout() {
        sessionManager.clearSession()
        Prefs(context).clearLoginSession()
        com.vigil.android.presentation.feature_attendance.db.RouteDbHelper(context).clearAllData()
        context.getSharedPreferences("route_drafts", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
