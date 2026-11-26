package com.vigil.android.presentation.feature_attendance.patroling_ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.util.Locale
import com.vigil.android.R
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs
import com.vigil.android.presentation.feature_attendance.db.RouteDbHelper
import com.vigil.android.presentation.feature_attendance.service.PatrolForegroundService
import org.json.JSONObject
import java.io.File

/**
 * ✅ OFFLINE SUPPORT
 *
 * Capturing the photo, compressing it, and saving it locally
 * (insertStopImage) already worked with zero network — that part is
 * unchanged. The only change is uploadBackgroundImage(): instead of
 * firing the POST directly with OkHttp (which just failed silently with
 * no signal, and never retried), it now hands the payload to
 * PatrolForegroundService's durable sync queue, which guarantees
 * eventual delivery in the correct order whether the device is online
 * right now or not.
 */
class EvidenceUploadActivity : AppCompatActivity() {

    private var stopId: Int = -1
    private var routeId: Int = -1
    private var officerRouteJoinId: Int = -1
    private var officerStopId: Int = -1
    private var officerId: Int = -1
    private var lat: Double = 0.0
    private var lon: Double = 0.0
    private var currentBase64: String? = null

    private lateinit var imageFile: File
    private lateinit var etDescription: EditText

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0]
                if (::etDescription.isInitialized) {
                    val existing = etDescription.text.toString()
                    etDescription.setText(if (existing.isBlank()) spokenText else "$existing $spokenText")
                    etDescription.setSelection(etDescription.text.length)
                }
            }
        }
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (!::imageFile.isInitialized || !imageFile.exists()) {
                Toast.makeText(this, "Image not captured", Toast.LENGTH_SHORT).show()
                finish()
                return@registerForActivityResult
            }

            val (base64, compressedWebpFile) = compressImageAndSaveWebp(imageFile)
            imageFile = compressedWebpFile
            showDescriptionDialog(base64)
        } else {
            finish()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchSpeechRecognizer()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchSpeechRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak description...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openBackCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            stopId = savedInstanceState.getInt("stop_id", -1)
            routeId = savedInstanceState.getInt("route_id", -1)
            officerRouteJoinId = savedInstanceState.getInt("officer_route_join_id", -1)
            officerStopId = savedInstanceState.getInt("officer_stop_id", -1)
            officerId = savedInstanceState.getInt("officer_id", -1)
            lat = savedInstanceState.getDouble("lat", 0.0)
            lon = savedInstanceState.getDouble("lon", 0.0)
            val path = savedInstanceState.getString("image_file_path")
            if (path != null) {
                imageFile = File(path)
            }
            currentBase64 = savedInstanceState.getString("current_base64")
            if (currentBase64 != null) {
                showDescriptionDialog(currentBase64!!)
                val desc = savedInstanceState.getString("description")
                if (desc != null && ::etDescription.isInitialized) {
                    etDescription.setText(desc)
                    etDescription.setSelection(etDescription.text.length)
                }
                return
            }
        }

        stopId = intent.getIntExtra("stop_id", -1)
        routeId = intent.getIntExtra("route_id", -1)
        officerRouteJoinId = intent.getIntExtra("officer_route_join_id", -1)
        officerStopId = intent.getIntExtra("officer_stop_id", -1)
        officerId = intent.getIntExtra("officer_id", -1)
        lat = intent.getDoubleExtra("lat", 0.0)
        lon = intent.getDoubleExtra("lon", 0.0)

        if (
            stopId == -1 ||
            routeId == -1 ||
            officerRouteJoinId == -1 ||
            officerStopId == -1 ||
            officerId == -1
        ) {
            Toast.makeText(this, "Invalid data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openBackCamera()
        } else {
            showCameraDisclosureDialog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("stop_id", stopId)
        outState.putInt("route_id", routeId)
        outState.putInt("officer_route_join_id", officerRouteJoinId)
        outState.putInt("officer_stop_id", officerStopId)
        outState.putInt("officer_id", officerId)
        outState.putDouble("lat", lat)
        outState.putDouble("lon", lon)
        if (currentBase64 != null) {
            outState.putString("current_base64", currentBase64)
        }
        if (::imageFile.isInitialized && imageFile.exists()) {
            outState.putString("image_file_path", imageFile.absolutePath)
        }
        if (::etDescription.isInitialized) {
            outState.putString("description", etDescription.text.toString())
        }
    }

    private fun showCameraDisclosureDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Camera Access Notice")
            .setMessage("Vigil accesses your camera to capture patrol evidence photos required for security verification.")
            .setPositiveButton("Continue") { _, _ ->
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun openBackCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

        imageFile = File.createTempFile("evidence_", ".webp", cacheDir)

        val uri: Uri = FileProvider.getUriForFile(
            this,
            "$packageName.provider",
            imageFile
        )

        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        intent.putExtra("android.intent.extras.CAMERA_FACING", 0)

        cameraCaptureLauncher.launch(intent)
    }

    private fun showDescriptionDialog(base64: String) {
        currentBase64 = base64

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0D1117"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val imageView = ImageView(this).apply {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val tvLabel = TextView(this).apply {
            text = "Description (optional)"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#C9D1D9"))
            setPadding(24, 20, 24, 4)
        }

        etDescription = EditText(this).apply {
            hint = "Enter description..."
            setHintTextColor(android.graphics.Color.parseColor("#6E7681"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setBackgroundColor(android.graphics.Color.parseColor("#161B22"))
            setPadding(24, 16, 96, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            minLines = 2
            maxLines = 4
        }

        val btnMic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = null
            setColorFilter(android.graphics.Color.parseColor("#009688"))
            setPadding(16, 16, 16, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                rightMargin = 16
            }
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@EvidenceUploadActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    launchSpeechRecognizer()
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 0, 16, 0)
            }
            addView(etDescription)
            addView(btnMic)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 130)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnRetry = Button(this).apply {
            text = "Retry"
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(0, 0, 8, 0)
            }
        }

        val btnOk = Button(this).apply {
            text = "OK"
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#238636"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(8, 0, 0, 0)
            }
        }

        btnRow.addView(btnRetry)
        btnRow.addView(btnOk)

        root.addView(imageView)
        root.addView(tvLabel)
        root.addView(container)
        root.addView(btnRow)

        setContentView(root)

        btnRetry.setOnClickListener {
            openBackCamera()
        }

        btnOk.setOnClickListener {
            val description = etDescription.text.toString().trim()

            // ✅ Save locally FIRST (for the popup gallery) — this already
            // worked with zero network, unchanged.
            val db = RouteDbHelper(this)
            db.insertStopImage(
                officerRouteJoinId,
                officerStopId,
                officerId,
                routeId,
                stopId,
                base64,
                description
            )

            enqueueEvidenceUpload(base64, description)
        }
    }

    /**
     * ✅ OFFLINE SUPPORT — replaces the old uploadBackgroundImage(), which
     * called OkHttp directly and just showed "Upload failed" with zero
     * retry on poor/no signal, even though the image was safely saved
     * locally a moment earlier. Now we hand off to the service's durable
     * queue and finish immediately — the officer is never blocked waiting
     * on a network call that might not succeed for hours.
     */
    private fun enqueueEvidenceUpload(base64: String, description: String) {
        val prefs = Prefs(this)
        val token = prefs.getToken()

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "Session expired", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val payload = JSONObject().apply {
            put("stopId", stopId)
            put("routeId", routeId)
            put("officerRouteJoinId", officerRouteJoinId)
            put("officerStopId", officerStopId)
            put("officerId", officerId)
            put("imageFilePath", if (::imageFile.isInitialized) imageFile.absolutePath else "")
            put("imageBase64", base64)
            put("description", description)
            put("lat", lat)
            put("lon", lon)
        }

        PatrolForegroundService.enqueueSyncAction(
            this,
            PatrolForegroundService.SYNC_ACTION_EVIDENCE,
            payload.toString()
        )

        Toast.makeText(this, "Evidence saved ✅", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun compressImageAndSaveWebp(file: File): Pair<String, File> {

        var bitmap = BitmapFactory.decodeFile(file.absolutePath)

        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }

        bitmap = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            600,
            800,
            true
        )

        val webpFormat = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        val webpFile = File(file.parent, "evidence_compressed_${System.currentTimeMillis()}.webp")
        webpFile.outputStream().use { out ->
            resized.compress(
                webpFormat,
                80,
                out
            )
        }
        if (resized != bitmap) {
            resized.recycle()
        }
        bitmap.recycle()

        val webpBytes = webpFile.readBytes()
        val base64 = Base64.encodeToString(
            webpBytes,
            Base64.NO_WRAP
        )
        return Pair(base64, webpFile)
    }
}