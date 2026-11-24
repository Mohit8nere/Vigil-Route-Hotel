package com.vigil.android.presentation.verification

/** Identifies the request that opened the camera, including changes away and back. */
internal data class VerificationScanContext(
    val generation: Long,
    val tab: Int,
    val subjectId: Long?,
    val referenceVersion: Long,
) {
    fun isCurrent(current: VerificationScanContext, referencesReady: Boolean): Boolean =
        referencesReady && this == current
}

internal fun verificationReferencesReady(
    localVersion: Long,
    profileCount: Int,
    remoteVersion: Long?,
): Boolean = localVersion >= 0 && profileCount > 0 &&
    (remoteVersion == null || remoteVersion == localVersion)

internal const val MAX_VERIFICATION_ACCURACY_METERS = 100f

/** Device and backend both retain accuracy; do not silently record an old or unusable fix. */
internal fun verificationLocationProblem(
    latitude: Double,
    longitude: Double,
    accuracyMeters: Float?,
    ageNanos: Long,
): String? = when {
    !latitude.isFinite() || latitude !in -90.0..90.0 ||
        !longitude.isFinite() || longitude !in -180.0..180.0 ->
        "Current location is invalid. Move to an open area and retry."
    ageNanos < 0 || ageNanos > 60_000_000_000L ->
        "Current location is not fresh. Move to an open area and retry."
    accuracyMeters == null || !accuracyMeters.isFinite() || accuracyMeters < 0f ->
        "Location accuracy is unavailable. Move to an open area and retry."
    accuracyMeters > MAX_VERIFICATION_ACCURACY_METERS ->
        "Location is not precise enough. Move to an open area and retry."
    else -> null
}
