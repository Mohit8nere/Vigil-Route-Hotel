package com.vigil.android.presentation.auth

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vigil.android.R
import com.vigil.android.presentation.auth.viewmodel.AuthViewModel
import com.vigil.android.presentation.auth.viewmodel.LoginState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: (String) -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var usernameError by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableStateOf(0) }

    val isSecurityRiskDetected = remember {
        com.vigil.android.utils.SecurityUtils.isDeviceRooted() || com.vigil.android.utils.SecurityUtils.isHookingDetected()
    }
    
    val loginState by viewModel.loginState.collectAsState()
    val context = LocalContext.current

    val isLockedOut = loginState is LoginState.LockedOut || countdownSeconds > 0
    val isInputEnabled = !isLockedOut && loginState !is LoginState.Loading

    fun formatLockoutTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) String.format(java.util.Locale.US, "%02d:%02d", m, s) else "$s second${if (s == 1) "" else "s"}"
    }

    LaunchedEffect(Unit) {
        viewModel.checkLockoutStatus()
    }

    LaunchedEffect(loginState) {
        if (loginState is LoginState.Success) {
            onLoginSuccess((loginState as LoginState.Success).role)
        } else if (loginState is LoginState.Error) {
            password = ""
            Toast.makeText(context, (loginState as LoginState.Error).message, Toast.LENGTH_SHORT).show()
        } else if (loginState is LoginState.LockedOut) {
            password = ""
            countdownSeconds = (loginState as LoginState.LockedOut).remainingSeconds
            while (countdownSeconds > 0) {
                kotlinx.coroutines.delay(1000)
                countdownSeconds--
            }
            viewModel.checkLockoutStatus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FEFF))
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(30.dp))
        
        // Logo Placeholder
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "Vigil Logo",
            modifier = Modifier.size(130.dp),
            contentScale = ContentScale.Fit
        )

        Text(
            text = "PATROLLING • PROTECTING • SECURING",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A5276),
            modifier = Modifier.padding(top = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "VIGIL LOGIN",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A5276),
            modifier = Modifier.padding(bottom = 20.dp)
        )

        if (isSecurityRiskDetected) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFFFF8E1),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB300)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = "⚠️ Security Notice: Rooted device or runtime inspection artifacts detected.",
                    fontSize = 12.sp,
                    color = Color(0xFF795548),
                    modifier = Modifier.padding(12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        if (isLockedOut) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFFFEBEE),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF5350)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🚨 ACCOUNT LOCKED",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFFC62828)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Too many failed login attempts.\nPlease wait ${formatLockoutTime(countdownSeconds)} before trying again.",
                        fontSize = 12.sp,
                        color = Color(0xFFB71C1C),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        OutlinedTextField(
            value = username,
            onValueChange = { 
                username = it
                usernameError = false
            },
            placeholder = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            enabled = isInputEnabled,
            isError = usernameError,
            supportingText = {
                if (usernameError) {
                    Text("Username is required", color = MaterialTheme.colorScheme.error)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { 
                password = it
                passwordError = false
            },
            placeholder = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                val image = if (passwordVisible)
                    Icons.Filled.Visibility
                else Icons.Filled.VisibilityOff

                val description = if (passwordVisible) "Hide password" else "Show password"

                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(imageVector = image, contentDescription = description)
                }
            },
            singleLine = true,
            enabled = isInputEnabled,
            isError = passwordError,
            supportingText = {
                if (passwordError) {
                    Text("Password is required", color = MaterialTheme.colorScheme.error)
                }
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { 
                val cleanUsername = username.trim()
                val enteredPassword = password

                if (cleanUsername.isBlank()) {
                    usernameError = true
                }
                if (enteredPassword.isBlank()) {
                    passwordError = true
                }
                
                if (!usernameError && !passwordError) {
                    viewModel.login(cleanUsername, enteredPassword)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent
            ),
            contentPadding = PaddingValues(),
            enabled = isInputEnabled
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = if (isInputEnabled) {
                                listOf(Color(0xFF3498DB), Color(0xFF1F618D))  //gradient blue
                            } else {
                                listOf(Color(0xFFBDC3C7), Color(0xFF95A5A6))
                            }
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (loginState is LoginState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (isLockedOut) "LOCKED ($countdownSeconds S)" else "LOGIN",
                        fontSize = 18.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
