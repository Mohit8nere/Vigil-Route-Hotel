package com.vigil.android.core.nav

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import com.vigil.android.MainActivity
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.presentation.verification.VerificationScreen
import com.vigil.android.ui.theme.VigilTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class VerificationInboxActivity : ComponentActivity() {
    @Inject lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!session.isSessionValid() || session.getRole() != "Patrol Officer") {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContent {
            VigilTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    TextButton(onClick = { finish() }) { Text("Back") }
                    VerificationScreen()
                }
            }
        }
    }
}
