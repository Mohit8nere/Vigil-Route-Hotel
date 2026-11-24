package com.vigil.android.presentation.verification

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

/** Pure validation shared by the encrypted queue and its receipt checks. */
internal object VerificationContract {
    const val MAX_FIX_ACCURACY_METRES = 100.0

    data class Score(val subjectId: Long, val similarity: Float)
    data class Event(
        val eventId: String,
        val subjectId: Long,
        val revision: Long,
        val subjectName: String,
        val verifiedAt: String,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double,
        val similarity: Double,
    )
    data class Receipt(
        val recordId: Long,
        val subjectId: Long?,
        val subjectName: String,
        val officerAccountId: Long,
        val verifiedAt: String,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double,
        val eventId: String? = null,
        val revision: Long? = null,
    )

    fun compatibleCache(version: Long, pipeline: Int, expectedPipeline: Int,
                        accountId: Long, expectedAccountId: Long): Boolean =
        version >= 0 && pipeline == expectedPipeline && accountId == expectedAccountId && accountId > 0

    fun usableReferenceCount(vectorCounts: List<Int>): Int {
        require(vectorCounts.all { it in 0..5 }) { "Invalid reference photo count." }
        return vectorCounts.count { it > 0 }
    }

    fun validEmbedding(vector: FloatArray, expectedSize: Int): Boolean {
        if (expectedSize <= 0 || vector.size != expectedSize || vector.any { !it.isFinite() }) return false
        val squaredNorm = vector.sumOf { it.toDouble() * it.toDouble() }
        return squaredNorm.isFinite() && squaredNorm > 1e-12
    }

    /** A selected assignment must also beat every other enrolled person. */
    fun select(scores: List<Score>, selectedId: Long?, threshold: Float, margin: Float): Score? {
        if (!threshold.isFinite() || !margin.isFinite() || margin < 0f) return null
        if (scores.any { it.subjectId <= 0 || !it.similarity.isFinite() || it.similarity !in -1f..1f }) return null
        if (scores.map { it.subjectId }.distinct().size != scores.size) return null
        val ranked = scores.sortedByDescending { it.similarity }
        val best = ranked.firstOrNull() ?: return null
        if (selectedId != null && best.subjectId != selectedId) return null
        if (best.similarity < threshold) return null
        val second = ranked.getOrNull(1)
        if (second != null && best.similarity - second.similarity < margin) return null
        return best
    }

    // Django's datetime storage/serializer has microsecond precision. Normalise
    // new submissions and compare legacy nanosecond strings at that precision.
    fun timestamp(value: String): String = Instant.parse(value).truncatedTo(ChronoUnit.MICROS).toString()

    fun validEvent(event: Event, now: Instant = Instant.now(), freshCapture: Boolean = false): Boolean = runCatching {
        val canonicalId = UUID.fromString(event.eventId).toString()
        canonicalId == event.eventId.lowercase() && event.subjectId > 0 && event.revision > 0 &&
            event.latitude.isFinite() && event.latitude in -90.0..90.0 &&
            event.longitude.isFinite() && event.longitude in -180.0..180.0 &&
            event.accuracy.isFinite() && event.accuracy >= 0 &&
            (!freshCapture || event.accuracy <= MAX_FIX_ACCURACY_METRES) &&
            event.similarity.isFinite() && event.similarity in 0.4..1.0 &&
            Instant.parse(event.verifiedAt).let { recorded -> !freshCapture || !recorded.isAfter(now.plusSeconds(300)) }
    }.getOrDefault(false)

    fun receiptMatches(event: Event, receipt: Receipt, officerAccountId: Long): Boolean = runCatching {
        // A deleted reference may be null on an idempotent replay; the backend
        // preserves its historical name. Missing fields are rejected by decoding.
        val sameSubject = receipt.subjectId == event.subjectId ||
            (receipt.subjectId == null && event.subjectName.isNotBlank() && receipt.subjectName == event.subjectName)
        val sameName = event.subjectName.isBlank() || receipt.subjectName == event.subjectName
        receipt.recordId > 0 && officerAccountId > 0 && receipt.officerAccountId == officerAccountId &&
            sameSubject && sameName && timestamp(receipt.verifiedAt) == timestamp(event.verifiedAt) &&
            receipt.latitude.isFinite() && abs(receipt.latitude - event.latitude) <= 1e-7 &&
            receipt.longitude.isFinite() && abs(receipt.longitude - event.longitude) <= 1e-7 &&
            receipt.accuracy.isFinite() && abs(receipt.accuracy - event.accuracy) <= 1e-3 &&
            (receipt.eventId == null || receipt.eventId == event.eventId) &&
            (receipt.revision == null || receipt.revision == event.revision)
    }.getOrDefault(false)
}
