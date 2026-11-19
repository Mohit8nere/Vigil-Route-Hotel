package com.vigil.android.features.fieldops

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Normalize rotation and bound working memory before preparing a document for transfer. */
internal fun preparePhoto(context: Context, uri: Uri, targetDir: File): File {
    val resolver = context.contentResolver
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    require(options.outWidth > 0 && options.outHeight > 0) { "This image cannot be opened." }
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > 3200) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("This image cannot be opened.")
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull()
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
    }
    val upright = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    val out = File.createTempFile("upload_", ".jpg", targetDir)
    try {
        check(out.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 90, it) }) { "Could not save photo." }
        require(out.length() in 1..8L * 1024 * 1024) { "Photograph exceeds the 8 MiB limit." }
        return out
    } catch (e: Exception) {
        out.delete()
        throw e
    } finally {
        if (upright !== bitmap) upright.recycle()
        bitmap.recycle()
    }
}

/** Retain camera evidence byte-for-byte when the original already fits the server's limits. */
internal fun prepareChallanPhoto(context: Context, capture: File, directory: File): File {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(capture.absolutePath, bounds)
    if (bounds.outMimeType == "image/jpeg" && capture.length() in 1L..8L * 1024 * 1024 &&
        bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000) {
        return capture
    }
    return preparePhoto(context, Uri.fromFile(capture), directory)
}

@Composable
internal fun CameraButton(
    label: String, directory: File, enabled: Boolean,
    onError: (String) -> Unit, onTaken: (File) -> Unit
) {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pending?.let(::File)
        pending = null
        if (ok && file != null && file.length() > 0) onTaken(file)
        else {
            file?.delete()
            if (ok) onError("The camera returned an empty photo. Please try again.")
        }
    }
    fun launch() {
        try {
            val file = File.createTempFile("camera_", ".jpg", directory)
            pending = file.absolutePath
            capture.launch(FileProvider.getUriForFile(context, context.packageName + ".provider", file))
        } catch (e: Exception) {
            pending?.let { File(it).delete() }
            pending = null
            onError(e.localizedMessage ?: "Camera unavailable.")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launch() else onError("Camera permission is required. You can allow it in Android Settings.")
    }
    Button(onClick = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launch()
        else permission.launch(Manifest.permission.CAMERA)
    }, enabled = enabled) { Text(label) }
}
