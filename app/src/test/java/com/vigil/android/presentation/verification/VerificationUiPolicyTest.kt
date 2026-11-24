package com.vigil.android.presentation.verification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationUiPolicyTest {
    @Test fun scanNeedsUsableCurrentReferencesButCanWorkOffline() {
        assertFalse(verificationReferencesReady(-1, 12, null))
        assertFalse(verificationReferencesReady(7, 0, 7))
        assertFalse(verificationReferencesReady(7, 12, 8))
        assertTrue(verificationReferencesReady(7, 12, null))
        assertTrue(verificationReferencesReady(7, 12, 7))
    }

    @Test fun permissionOrCaptureResultCannotCrossModesSubjectsOrReferenceUpdates() {
        val requested = VerificationScanContext(10, 0, 31, 7)
        assertTrue(requested.isCurrent(requested, true))
        assertFalse(requested.isCurrent(requested.copy(tab = 1, subjectId = null), true))
        assertFalse(requested.isCurrent(requested.copy(subjectId = 32), true))
        assertFalse(requested.isCurrent(requested.copy(referenceVersion = 8), true))
        assertFalse(requested.isCurrent(requested, false))
    }

    @Test fun returningToSameTabDoesNotAcceptItsOldCapture() {
        val requested = VerificationScanContext(10, 1, null, 7)
        assertFalse(requested.isCurrent(requested.copy(generation = 12), true))
    }

    @Test fun onlyFreshFiniteAndUsableLocationsCanBeRecorded() {
        assertNull(verificationLocationProblem(23.17, 75.78, 20f, 2_000_000_000))
        assertNull(verificationLocationProblem(23.17, 75.78, 100f, 60_000_000_000))
        assertNotNull(verificationLocationProblem(23.17, 75.78, 20f, -1))
        assertNotNull(verificationLocationProblem(23.17, 75.78, 20f, 60_000_000_001))
        assertNotNull(verificationLocationProblem(Double.NaN, 75.78, 20f, 0))
        assertNotNull(verificationLocationProblem(23.17, 181.0, 20f, 0))
        assertNotNull(verificationLocationProblem(23.17, 75.78, Float.NaN, 0))
        assertNotNull(verificationLocationProblem(23.17, 75.78, null, 0))
        assertNotNull(verificationLocationProblem(23.17, 75.78, -1f, 0))
        assertNotNull(verificationLocationProblem(23.17, 75.78, 101f, 0))
    }
}
