package com.vigil.android.presentation.auth.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class LoginFailurePolicyTest {
    @Test fun genuineCredentialRejectionsCountForBothDrfAuthenticationStatuses() {
        for (status in listOf(401, 403)) {
            val failure = classifyLoginFailure(status, errorBody = """{"detail":"Invalid credentials."}""")
            assertTrue(failure.countCredentialAttempt)
            assertEquals("Invalid username or password.", failure.message)
        }
    }

    @Test fun lanHtml400IsConfigurationFailureNotWrongPassword() {
        val failure = classifyLoginFailure(400, errorBody = "<html><body>Bad Request</body></html>")
        assertFalse(failure.countCredentialAttempt)
        assertTrue(failure.message.contains("HTTP 400"))
        assertFalse(failure.message.contains("<html>"))
    }

    @Test fun accountDeactivationAndValidationAreNotWrongPasswordAttempts() {
        val deactivated = classifyLoginFailure(403, errorBody = """{"detail":"Your account has been deactivated."}""")
        assertFalse(deactivated.countCredentialAttempt)
        assertEquals("Your account has been deactivated.", deactivated.message)
        val validation = classifyLoginFailure(400, errorBody = """{"username":["This field is required."]}""")
        assertFalse(validation.countCredentialAttempt)
        assertEquals("username: This field is required.", validation.message)
    }

    @Test fun outagesCannotConsumeCredentialAttemptsEvenWithMisleadingBodies() {
        for (status in listOf(500, 502, 503)) {
            val failure = classifyLoginFailure(status, errorBody = """{"detail":"Invalid credentials."}""")
            assertFalse(failure.countCredentialAttempt)
            assertTrue(failure.message.contains("temporarily unavailable"))
        }
    }

    @Test fun serverThrottleUsesRetryAfterWithoutCountingAnotherFailure() {
        val failure = classifyLoginFailure(429, retryAfter = "120")
        assertFalse(failure.countCredentialAttempt)
        assertEquals(120, failure.retryAfterSeconds)
        assertEquals(60, classifyLoginFailure(429, retryAfter = "bad-value").retryAfterSeconds)
        assertNull(classifyLoginFailure(404).retryAfterSeconds)
    }

    @Test fun malformedSuccessIsNotClaimedToBeBadCredentials() {
        val failure = classifyLoginFailure(200)
        assertFalse(failure.countCredentialAttempt)
        assertTrue(failure.message.contains("incomplete"))
    }

    @Test fun connectionMessagesExplainRetryWithoutEchoingRawServerData() {
        assertTrue(loginTransportMessage(SocketTimeoutException("raw private address")).contains("too long"))
        assertFalse(loginTransportMessage(IOException("raw private address")).contains("raw private"))
    }
}
