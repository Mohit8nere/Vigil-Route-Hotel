package com.vigil.android.core.common.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FileUtils {

    const val MAX_VOICE_RECORDING_DURATION_SEC = 110 // 1 minute 50 seconds
    const val MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024L // 2 MB limit for documents/files
    const val TARGET_MIN_IMAGE_SIZE_BYTES = 450 * 1024L // 450 KB
    const val TARGET_MAX_IMAGE_SIZE_BYTES = 950 * 1024L // 950 KB (< 1 MB)

    /**
     * Compresses and optimizes an image URI so its file size is strictly between 450KB and 950KB (< 1MB).
     */
    fun compressImageToTargetSize(context: Context, uri: Uri): Uri? {
        return runCatching {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) return@runCatching null

            val cacheFile = File(context.cacheDir, "compressed_img_${System.currentTimeMillis()}.jpg")
            var quality = 90
            var stream: FileOutputStream
            var fileSize = 0L

            // Iterative compression to hit target range 450KB - 950KB
            var scale = 1.0f
            var workingBitmap = originalBitmap

            while (quality > 10) {
                cacheFile.delete()
                stream = FileOutputStream(cacheFile)
                workingBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.flush()
                stream.close()

                fileSize = cacheFile.length()
                if (fileSize in TARGET_MIN_IMAGE_SIZE_BYTES..TARGET_MAX_IMAGE_SIZE_BYTES) {
                    break
                } else if (fileSize > TARGET_MAX_IMAGE_SIZE_BYTES) {
                    quality -= 15
                    if (quality <= 30 && scale > 0.5f) {
                        scale -= 0.25f
                        workingBitmap = Bitmap.createScaledBitmap(
                            originalBitmap,
                            (originalBitmap.width * scale).toInt(),
                            (originalBitmap.height * scale).toInt(),
                            true
                        )
                        quality = 80
                    }
                } else {
                    // If too small (< 450KB), increase quality slightly if possible or accept if max quality reached
                    if (quality >= 90) break
                    quality += 10
                }
            }

            Uri.fromFile(cacheFile)
        }.getOrNull()
    }

    /**
     * Validates selected file (non-image) against 2MB limit and ensures it's not an image or video.
     */
    fun validateDocumentFile(context: Context, uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
            return false // Images and videos are not allowed through file picker
        }

        val sizeBytes = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            it.length
        } ?: 0L

        return sizeBytes <= MAX_FILE_SIZE_BYTES
    }
}
