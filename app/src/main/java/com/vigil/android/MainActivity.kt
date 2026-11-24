package com.vigil.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.vigil.android.core.common.update.InAppUpdateManager
import com.vigil.android.core.nav.MainNavigation
import com.vigil.android.ui.theme.VigilTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var updateManager: InAppUpdateManager

    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            // Update flow failed or was cancelled by the user.
            // For an immediate/force update, you may choose to handle cancellation or re-prompt.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // window.setFlags(
        //     android.view.WindowManager.LayoutParams.FLAG_SECURE,
        //     android.view.WindowManager.LayoutParams.FLAG_SECURE
        // )
        enableEdgeToEdge()
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        updateManager = InAppUpdateManager(this)
        updateManager.checkForUpdate(updateLauncher)

        val overrideStartDestination = intent.getStringExtra("start_destination")

        setContent {
            VigilTheme {
                MainNavigation(overrideStartDestination = overrideStartDestination)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateManager.resumeUpdate(updateLauncher)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val overrideStartDestination = intent.getStringExtra("start_destination")
        if (!overrideStartDestination.isNullOrBlank()) {
            setContent {
                VigilTheme {
                    MainNavigation(overrideStartDestination = overrideStartDestination)
                }
            }
        }
    }
}
