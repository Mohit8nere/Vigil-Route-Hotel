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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.vigil.android.R
import java.io.File

/** Normalize rotation and bound working memory before preparing a document for transfer. */
internal fun preparePhoto(context: Context, uri: Uri, targetDir: File, compact: Boolean = false): File {
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
    val longestEdge = maxOf(upright.width, upright.height)
    val outputBitmap = if (compact && longestEdge > 1600) {
        Bitmap.createScaledBitmap(upright, maxOf(1, upright.width * 1600 / longestEdge),
            maxOf(1, upright.height * 1600 / longestEdge), true)
    } else upright
    val out = File.createTempFile("upload_", ".jpg", targetDir)
    try {
        for (quality in if (compact) listOf(78, 66, 55) else listOf(90)) {
            check(out.outputStream().use { outputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }) { "Could not save photo." }
            if (!compact || out.length() <= 320 * 1024) break
        }
        if (compact) {
            for (scale in listOf(0.75, 0.60, 0.45, 0.30)) {
                if (out.length() <= 320 * 1024) break
                val smaller = Bitmap.createScaledBitmap(outputBitmap, maxOf(1, (outputBitmap.width * scale).toInt()),
                    maxOf(1, (outputBitmap.height * scale).toInt()), true)
                try {
                    check(out.outputStream().use { smaller.compress(Bitmap.CompressFormat.JPEG, 60, it) }) { "Could not save photo." }
                } finally { if (smaller !== outputBitmap) smaller.recycle() }
            }
            require(out.length() <= 320 * 1024) { "Could not compress this survey photo." }
        }
        require(out.length() in 1..8L * 1024 * 1024) { "Photograph exceeds the 8 MiB limit." }
        return out
    } catch (e: Exception) {
        out.delete()
        throw e
    } finally {
        if (outputBitmap !== upright) outputBitmap.recycle()
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
    modifier: Modifier = Modifier,
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
    Button(
        onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launch()
            else permission.launch(Manifest.permission.CAMERA)
        },
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = androidx.compose.ui.graphics.Color.White,
            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f)
        )
    ) {
        Icon(
            painter = painterResource(id = R.drawable.camera_a),
            contentDescription = label,
            modifier = Modifier.size(20.dp).padding(end = 6.dp)
        )
        Text(label)
    }
}
