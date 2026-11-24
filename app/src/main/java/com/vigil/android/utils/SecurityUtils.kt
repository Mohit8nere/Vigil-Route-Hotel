package com.vigil.android.utils

import android.os.Build
import com.vigil.android.BuildConfig
import java.io.File

object SecurityUtils {

    /**
     * Checks if the device is rooted by inspecting common su binary paths and build tags.
     * Bypassed in Debug / QA builds to allow functional testing on test devices/emulators.
     */
    fun isDeviceRooted(): Boolean {
        if (BuildConfig.DEBUG) return false
        return checkBuildTags() || checkSuBinaryPaths()
    }

    private fun checkBuildTags(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkSuBinaryPaths(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        for (path in paths) {
            try {
                if (File(path).exists()) return true
            } catch (_: Exception) {
                // Ignore file permission errors
            }
        }
        return false
    }

    /**
     * Checks for common hooking or instrumentation frameworks (e.g. Frida, Xposed).
     * Bypassed in Debug / QA builds to allow functional testing.
     */
    fun isHookingDetected(): Boolean {
        if (BuildConfig.DEBUG) return false
        val hookingFiles = arrayOf(
            "/system/framework/XposedBridge.jar",
            "/data/local/tmp/frida-server"
        )
        for (path in hookingFiles) {
            try {
                if (File(path).exists()) return true
            } catch (_: Exception) {
                // Ignore
            }
        }
        return false
    }
}
