package com.vigil.android.core.common.`object`

import android.content.Context
import android.os.SystemClock
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object SecureTime {
    private var anchorServerMillis: Long = -1L
    private var anchorElapsedRealtime: Long = -1L

    fun capture(context: Context, serverDateHeader: String?) {
        val parsedMillis = parseHttpDate(serverDateHeader) ?: return
        anchorServerMillis = parsedMillis
        anchorElapsedRealtime = SystemClock.elapsedRealtime()
        Prefs(context).saveServerTimeAnchor(anchorServerMillis, anchorElapsedRealtime)
    }

    fun restoreFromPrefs(context: Context) {
        if (anchorServerMillis != -1L) return
        val prefs = Prefs(context)
        if (!prefs.hasServerTimeAnchor()) return
        anchorServerMillis = prefs.getServerTimeAnchorMillis()
        anchorElapsedRealtime = prefs.getServerTimeAnchorElapsed()
    }

    fun isReady(): Boolean = anchorServerMillis != -1L && anchorElapsedRealtime != -1L

    fun now(): Long? {
        if (!isReady()) return null
        val elapsedSinceAnchor = SystemClock.elapsedRealtime() - anchorElapsedRealtime
        return anchorServerMillis + elapsedSinceAnchor
    }

    private fun parseHttpDate(headerValue: String?): Long? {
        if (headerValue.isNullOrBlank()) return null
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.parse(headerValue)?.time
        } catch (e: Exception) {
            null
        }
    }
}