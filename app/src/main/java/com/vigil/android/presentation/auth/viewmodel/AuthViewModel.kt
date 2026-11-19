package com.vigil.android.presentation.auth.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.model.LoginRequest
import com.vigil.android.core.di.repository.AuthRepository
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository,
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs by lazy { Prefs(context) }
    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState
    val sessionExpired: SharedFlow<Unit> = sessionManager.sessionExpired

    fun checkLockoutStatus(): Boolean {
        val lockoutStart = prefs.getLockoutStartTimestamp()
        val durationSeconds = prefs.getLockoutDurationSeconds()
        if (lockoutStart > 0 && durationSeconds > 0) {
            val totalLockoutMillis = durationSeconds * 1000L
            val elapsed = System.currentTimeMillis() - lockoutStart
            if (elapsed < totalLockoutMillis) {
                val remainingSeconds = (((totalLockoutMillis - elapsed) + 999) / 1000).toInt().coerceAtLeast(1)
                _loginState.value = LoginState.LockedOut(remainingSeconds)
                return true
            } else {
                prefs.resetLoginLockout()
                if (_loginState.value is LoginState.LockedOut) {
                    _loginState.value = LoginState.Idle
                }
            }
        }
        return false
    }

    private fun handleFailedLogin(errorMessage: String) {
        val attempts = prefs.getFailedLoginAttempts() + 1
        prefs.saveFailedLoginAttempts(attempts)

        if (attempts >= 5) {
            val durationSeconds = when {
                attempts >= 10 -> 900 // 15 minutes
                attempts >= 8 -> 300  // 5 minutes
                else -> 60            // 1 minute
            }
            val now = System.currentTimeMillis()
            prefs.saveLockoutStartTimestamp(now)
            prefs.saveLockoutDurationSeconds(durationSeconds)
            _loginState.value = LoginState.LockedOut(durationSeconds)
        } else {
            val remainingAttempts = 5 - attempts
            _loginState.value = LoginState.Error(
                "$errorMessage ($remainingAttempts attempt${if (remainingAttempts == 1) "" else "s"} remaining before lockout)"
            )
        }
    }

    fun login(rawUsername: String, rawPassword: String) {
        if (checkLockoutStatus()) {
            return
        }

        val username = rawUsername.trim()
        val password = rawPassword

        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            try {
                val response = repository.login(LoginRequest(username, password))
                if (response.isSuccessful && response.body()?.success == true) {
                    prefs.resetLoginLockout()
                    val role = sessionManager.getRole()
                    _loginState.value = LoginState.Success(role ?: "")
                } else {
                    val msg = response.body()?.message ?: "Invalid credentials"
                    handleFailedLogin(msg)
                }
            } catch (e: Exception) {
                handleFailedLogin(e.localizedMessage ?: "Network error")
            }
        }
    }

//    fun checkLoginStatus(): Boolean = repository.isLoggedIn() //todo temp unused

    fun isSessionValid(): Boolean = sessionManager.isSessionValid()
    
    fun getRole(): String? = sessionManager.getRole()
    
    fun clearSession() = repository.logout()
}

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    data class Success(val role: String) : LoginState()
    data class Error(val message: String) : LoginState()
    data class LockedOut(val remainingSeconds: Int) : LoginState()
}
