package com.vigil.android

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vigil.android.core.common.session.SessionManager
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun openingChatWithoutSessionShowsSignIn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SessionManager(context).clearSession()
        val link = Intent(context, MainActivity::class.java).putExtra("start_destination", "traffic_chat")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(link).use {
            compose.onNodeWithText("VIGIL LOGIN").assertIsDisplayed()
            compose.onNodeWithText("Vigil Chats").assertDoesNotExist()
        }
    }
}
