package com.vigil.android.presentation.verification

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VerificationContractTest {
    private val now = Instant.parse("2026-11-20T06:00:00Z")
    private val event = VerificationContract.Event(
        "43b1a8e8-b5f1-4a8c-846c-c28c917de863", 7, 12, "Reference Seven",
        "2026-11-20T05:59:30.123456789Z", 23.1828, 75.7772, 8.0, 0.75,
    )
    private val receipt = VerificationContract.Receipt(
        22, 7, "Reference Seven", 51, "2026-11-20T05:59:30.123456Z", 23.1828, 75.7772, 8.0,
    )

    @Test fun changedPipelineOrAccountCannotReuseReferenceCache() {
        assertTrue(VerificationContract.compatibleCache(12, 2, 2, 51, 51))
        assertFalse(VerificationContract.compatibleCache(12, -1, 2, 51, 51))
        assertFalse(VerificationContract.compatibleCache(12, 1, 2, 51, 51))
        assertFalse(VerificationContract.compatibleCache(12, 2, 2, 51, 52))
        assertFalse(VerificationContract.compatibleCache(-1, 2, 2, 51, 51))
    }

    @Test fun invalidVectorsAreRejectedBeforeComparingPeople() {
        assertTrue(VerificationContract.validEmbedding(floatArrayOf(0.6f, 0.8f), 2))
        assertFalse(VerificationContract.validEmbedding(floatArrayOf(0.6f), 2))
        assertFalse(VerificationContract.validEmbedding(floatArrayOf(Float.NaN, 1f), 2))
        assertFalse(VerificationContract.validEmbedding(floatArrayOf(Float.POSITIVE_INFINITY, 1f), 2))
        assertFalse(VerificationContract.validEmbedding(floatArrayOf(0f, 0f), 2))
    }

    @Test fun referencesWithoutUsableFacesCannotEnableScanning() {
        assertEquals(0, VerificationContract.usableReferenceCount(emptyList()))
        assertEquals(0, VerificationContract.usableReferenceCount(listOf(0,0,0)))
        assertEquals(2, VerificationContract.usableReferenceCount(listOf(0,1,5,0)))
        assertThrows(IllegalArgumentException::class.java) { VerificationContract.usableReferenceCount(listOf(-1)) }
    }

    @Test fun selectedRecordMustBeTheUnambiguousBestPerson() {
        val scores = listOf(VerificationContract.Score(7, 0.8f), VerificationContract.Score(9, 0.9f))
        assertNull(VerificationContract.select(scores, 7, 0.4f, 0.05f))
        assertEquals(9L, VerificationContract.select(scores, null, 0.4f, 0.05f)?.subjectId)
        assertEquals(9L, VerificationContract.select(scores, 9, 0.4f, 0.05f)?.subjectId)
    }

    @Test fun tiesSmallMarginsNonfiniteAndDuplicateScoresCannotProduceCandidate() {
        for (second in listOf(0.9f, 0.88f)) {
            assertNull(VerificationContract.select(listOf(VerificationContract.Score(7, 0.9f),
                VerificationContract.Score(9, second)), null, 0.4f, 0.05f))
        }
        assertNull(VerificationContract.select(listOf(VerificationContract.Score(7, Float.NaN)), null, 0.4f, 0.05f))
        assertNull(VerificationContract.select(listOf(VerificationContract.Score(7, 0.9f),
            VerificationContract.Score(7, 0.6f)), null, 0.4f, 0.05f))
        assertNull(VerificationContract.select(listOf(VerificationContract.Score(7, 0.3f)), null, 0.4f, 0.05f))
    }

    @Test fun acknowledgementUsesLoginAccountAndMatchesDjangoMicrosecondTime() {
        assertTrue(VerificationContract.receiptMatches(event, receipt, 51))
        assertFalse(VerificationContract.receiptMatches(event, receipt, 7)) // PatrolOfficer != LoginUser.
        assertEquals("2026-11-20T05:59:30.123456Z", VerificationContract.timestamp(event.verifiedAt))
    }

    @Test fun wrongOrIncompleteAcknowledgementCannotDeletePendingEvent() {
        assertFalse(VerificationContract.receiptMatches(event, receipt.copy(recordId = 0), 51))
        assertFalse(VerificationContract.receiptMatches(event, receipt.copy(subjectId = 8), 51))
        assertFalse(VerificationContract.receiptMatches(event, receipt.copy(latitude = 24.0), 51))
        assertFalse(VerificationContract.receiptMatches(event, receipt.copy(accuracy = Double.NaN), 51))
        assertFalse(VerificationContract.receiptMatches(event, receipt.copy(verifiedAt = "not-a-date"), 51))
        assertFalse(VerificationContract.receiptMatches(event, receipt.copy(eventId = "another-event"), 51))
        assertFalse(VerificationContract.receiptMatches(event, receipt.copy(revision = 13), 51))
    }

    @Test fun deletedSubjectReplayNeedsMatchingHistoricalName() {
        assertTrue(VerificationContract.receiptMatches(event, receipt.copy(subjectId = null), 51))
        assertFalse(VerificationContract.receiptMatches(event, receipt.copy(subjectId = null, subjectName = "Other"), 51))
        assertFalse(VerificationContract.receiptMatches(event.copy(subjectName = ""), receipt.copy(subjectId = null), 51))
    }

    @Test fun invalidLocationScoreOrFutureTimeCannotEnterNewQueue() {
        assertTrue(VerificationContract.validEvent(event, now, freshCapture = true))
        assertFalse(VerificationContract.validEvent(event.copy(latitude = Double.NaN), now, true))
        assertFalse(VerificationContract.validEvent(event.copy(longitude = 181.0), now, true))
        assertFalse(VerificationContract.validEvent(event.copy(accuracy = 101.0), now, true))
        assertFalse(VerificationContract.validEvent(event.copy(similarity = 1.1), now, true))
        assertFalse(VerificationContract.validEvent(event.copy(verifiedAt = "2026-11-20T06:06:00Z"), now, true))
        // Existing queues remain deliverable under the server's historical contract.
        assertTrue(VerificationContract.validEvent(event.copy(accuracy = 200.0), now, false))
        // A phone clock correction must not locally reject a previously saved event;
        // the backend clock remains authoritative at delivery.
        assertTrue(VerificationContract.validEvent(event, now.minusSeconds(3600), false))
    }
}
