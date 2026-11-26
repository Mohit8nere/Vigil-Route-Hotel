package com.vigil.android.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import com.vigil.android.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

object SecurityUtils {

    /**
     * Checks if the device is rooted by inspecting common su binary paths, Magisk files, and build tags.
     */
    fun isDeviceRooted(): Boolean {
        return !BuildConfig.DEBUG && (checkBuildTags() || checkSuBinaryPaths() || checkMagisk())
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
            "/data/local/su",
            "/su/bin/su"
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

    private fun checkMagisk(): Boolean {
        val magiskPaths = arrayOf(
            "/sbin/.magisk",
            "/cache/magisk.log",
            "/data/adb/magisk"
        )
        for (path in magiskPaths) {
            try {
                if (File(path).exists()) return true
            } catch (_: Exception) {
                // Ignore
            }
        }
        return false
    }

    /**
     * Detects dynamic instrumentation / hooking frameworks (e.g. Frida, Xposed, Substrate).
     * Checks loaded libraries in memory maps and binary artifacts.
     */
    fun isHookingDetected(): Boolean {
        return !BuildConfig.DEBUG && (checkHookingFiles() || checkMemoryMapsForFrida())
    }

    private fun checkHookingFiles(): Boolean {
        val hookingFiles = arrayOf(
            "/system/framework/XposedBridge.jar",
            "/data/local/tmp/frida-server",
            "/data/local/tmp/re.frida.server"
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

    private fun checkMemoryMapsForFrida(): Boolean {
        return try {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.exists()) return false
            BufferedReader(FileReader(mapsFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.lowercase() ?: continue
                    if (l.contains("frida") || l.contains("xposed") || l.contains("substrate") || l.contains("gadget")) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if a debugger is attached or if the application is debuggable in release mode.
     */
    fun isDebuggerDetected(context: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val isDebuggerConnected = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        return isDebuggable || isDebuggerConnected
    }

    /**
     * Checks if the application is running inside an emulator/virtualized environment.
     */
    fun isEmulatorDetected(): Boolean {
        if (BuildConfig.DEBUG) return false
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val product = Build.PRODUCT.lowercase()
        val brand = Build.BRAND.lowercase()

        return fingerprint.startsWith("generic") ||
                fingerprint.startsWith("unknown") ||
                model.contains("google_sdk") ||
                model.contains("emulator") ||
                model.contains("android sdk built for x86") ||
                manufacturer.contains("genymotion") ||
                hardware.contains("goldfish") ||
                hardware.contains("vbox86") ||
                hardware.contains("ranchu") ||
                product.contains("sdk") ||
                product.contains("google_sdk") ||
                product.contains("sdk_gphone") ||
                brand.contains("generic")
    }

    /**
     * Checks overall environment security against reverse engineering, root, hooking, and debugging.
     */
    fun isEnvironmentCompromised(context: Context): Boolean {
        return isDeviceRooted() || isHookingDetected() || isDebuggerDetected(context)
    }
}
