package com.vigil.android.core.common.session

import android.content.Context
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs
import com.vigil.android.presentation.verification.VerificationJobs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("vigil_prefs", Context.MODE_PRIVATE)

    companion object {
        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val USER_ID = "user_id"
        const val USER_NAME = "user_name"
        const val USER_ROLE = "user_role"
        private val lock = Any()
        private val expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    val sessionExpired: SharedFlow<Unit> = expired.asSharedFlow()

    fun saveLogin(access: String, refresh: String?, userId: String?, name: String?, role: String?) {
        synchronized(lock) {
            prefs.edit().putString(ACCESS_TOKEN, access).putString(REFRESH_TOKEN, refresh)
                .putString(USER_ID, userId).putString(USER_NAME, name).putString(USER_ROLE, role).commit()
        }
    }
    fun getRole(): String? = prefs.getString(USER_ROLE, null)
    fun getUserId(): String? = prefs.getString(USER_ID, null)
    fun getUserName(): String? = prefs.getString(USER_NAME, null)
    fun fetchAuthToken(): String? = prefs.getString(ACCESS_TOKEN, null)
    fun fetchRefreshToken(): String? = prefs.getString(REFRESH_TOKEN, null)
    fun isLoggedIn(): Boolean = !fetchAuthToken().isNullOrEmpty()
    fun isSessionValid(): Boolean = isLoggedIn() && !getRole().isNullOrEmpty()

    fun replaceTokens(previous: String, access: String, refresh: String): Boolean = synchronized(lock) {
        if (fetchAuthToken() != previous) return false
        prefs.edit().putString(ACCESS_TOKEN, access).putString(REFRESH_TOKEN, refresh).commit()
        Prefs(context).saveToken(access)
        true
    }

    fun clearSession() {
        synchronized(lock) {
            if (getRole().equals("Patrol Officer", ignoreCase = true)) VerificationJobs.clearSession(context)
            Prefs(context).clearLoginSession()
            prefs.edit().clear().commit()
        }
    }

    fun expireIfCurrent(previous: String) {
        synchronized(lock) {
            if (previous != fetchAuthToken()) return
            clearSession()
            expired.tryEmit(Unit)
        }
    }
}
