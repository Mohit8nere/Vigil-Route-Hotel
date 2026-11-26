package com.vigil.android.utils

import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.os.Build

object LocationUtils {

    private val KNOWN_SPOOFER_PACKAGES = arrayOf(
        "com.lexa.fakegps",
        "com.spotlengin.fakegps",
        "com.roaming.fakegps",
        "com.incorporateapps.fakegps",
        "com.lsa.fakegps",
        "org.location.mock",
        "com.mobi.fakegps",
        "com.fakegps.android"
    )

    private var lastVerifiedLocation: Location? = null
    private var lastVerifiedTimeMillis: Long = 0L

    /**
     * Detects if a location is mocked (GPS Spoofing).
     * Works across Android 11 to 16.
     */
    fun isMockLocation(location: Location?): Boolean {
        return location != null && if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31) and above
            location.isMock
        } else {
            // Android 11 (API 30) and below
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }

    /**
     * Multi-layer GPS spoof detection combining native flags, AppOps,
     * teleportation speed checks, accuracy anomalies, and spoofer app package detection.
     */
    fun isMockLocation(location: Location?, context: Context?): Boolean {
        return getSpoofReason(location, context) != null
    }

    /**
     * Evaluates location and returns a human-readable detection reason if spoofed,
     * or null if the location fix is legitimate.
     */
    fun getSpoofReason(location: Location?, context: Context?): String? {
        if (location == null) return null

        if (isNativeMockFlag(location)) {
            return "Native mock location provider flag detected"
        }
        if (isAccuracyAnomaly(location)) {
            return "Unrealistic GPS accuracy anomaly detected"
        }
        if (isTeleportationSpeed(location)) {
            return "Impossible location jump (teleportation speed >150 km/h) detected"
        }
        if (hasFakeGpsAppsInstalled(context)) {
            return "Known location spoofer application installed"
        }
        if (isAppOpsMockLocation(context)) {
            return "AppOps mock location mode enabled"
        }
        return null
    }

    private fun isNativeMockFlag(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }

    @Suppress("DEPRECATION")
    private fun isAppOpsMockLocation(context: Context?): Boolean {
        return context != null && try {
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            val mode = appOpsManager?.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun isTeleportationSpeed(current: Location): Boolean {
        val prev = lastVerifiedLocation ?: run {
            if (!isNativeMockFlag(current)) {
                lastVerifiedLocation = current
                lastVerifiedTimeMillis = System.currentTimeMillis()
            }
            return false
        }

        val timeDeltaSeconds = (System.currentTimeMillis() - lastVerifiedTimeMillis) / 1000.0
        if (timeDeltaSeconds <= 0.5) return false

        val distanceMeters = prev.distanceTo(current)
        val speedMetersPerSecond = distanceMeters / timeDeltaSeconds
        val maxAllowedMetersPerSecond = 41.6 // ~150 km/h

        if (speedMetersPerSecond > maxAllowedMetersPerSecond && distanceMeters > 500) {
            return true
        }

        if (!isNativeMockFlag(current)) {
            lastVerifiedLocation = current
            lastVerifiedTimeMillis = System.currentTimeMillis()
        }
        return false
    }

    private fun isAccuracyAnomaly(location: Location): Boolean {
        return location.hasAccuracy() && location.accuracy == 0.0f && (location.latitude != 0.0 || location.longitude != 0.0)
    }

    private fun hasFakeGpsAppsInstalled(context: Context?): Boolean {
        if (context == null) return false
        val pm = context.packageManager ?: return false
        for (pkg in KNOWN_SPOOFER_PACKAGES) {
            try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: Exception) {
                // Not installed
            }
        }
        return false
    }
}
