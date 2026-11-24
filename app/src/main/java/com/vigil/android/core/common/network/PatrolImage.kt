package com.vigil.android.core.common.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.api.ApiNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayOutputStream

/** Load legacy inline thumbnails or an authenticated patrol image on the configured origin. */
internal suspend fun loadPatrolImage(context: Context, raw: String): Bitmap? = withContext(Dispatchers.IO) {
    if (raw.isBlank() || raw == "null") return@withContext null
    val limit = 12 * 1024 * 1024
    try {
        val reference = raw.toHttpUrlOrNull()?.encodedPath ?: raw
        val bytes = if (reference.startsWith("/media/")) {
            val url = ApiNames.BASE_URL.toHttpUrl().newBuilder().encodedPath(reference).build()
            val http = NetworkClient.create(context.applicationContext, SessionManager(context.applicationContext))
                .newBuilder().followRedirects(false).followSslRedirects(false).build()
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body ?: return@withContext null
                if (!response.isSuccessful || body.contentLength() > limit) return@withContext null
                val buffer = ByteArray(16384)
                val output = ByteArrayOutputStream()
                body.byteStream().use { stream ->
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > limit) return@withContext null
                        output.write(buffer, 0, count)
                    }
                }
                output.toByteArray()
            }
        } else {
            if (raw.length > 4 * limit / 3 || raw.contains("://")) return@withContext null
            Base64.decode(raw.substringAfter("base64,",raw),Base64.DEFAULT)
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
        options.inSampleSize = 1
        while (maxOf(options.outWidth,options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: java.io.IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
