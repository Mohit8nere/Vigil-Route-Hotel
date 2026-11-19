package com.vigil.android.presentation.feature_attendance.patroling_ui

import com.vigil.android.BuildConfig
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vigil.android.R
import com.vigil.android.presentation.feature_attendance.db.RouteDbHelper
import com.vigil.android.presentation.feature_attendance.face.FaceAntiSpoofEngine
import com.vigil.android.presentation.feature_attendance.face.FaceEmbeddingStore
import com.vigil.android.presentation.feature_attendance.face.FaceRecognitionEngine
import com.vigil.android.presentation.feature_attendance.service.PatrolForegroundService
import com.vigil.android.presentation.feature_attendance.service.notification.PatrolNotifications
import com.vigil.android.utils.LocationUtils
import com.google.android.gms.location.LocationServices
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Patrol-stop verification.
 *
 * Flow:
 * geofence -> SCRFD -> MiniFASNetV2 liveness -> MobileFaceNet -> cosine match.
 */
class UploadActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var stopId: Int = -1
    private var routeId: Int = -1
    private var officerRouteJoinId: Int = -1
    private var officerStopId: Int = -1
    private var stopHash: String = ""
    private var lat: Double = 0.0
    private var lon: Double = 0.0
    private var stopName: String = ""
    private val faceWorker = Executors.newSingleThreadExecutor()

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            handleCameraResult(result.data)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            cameraLauncher.launch(Intent(this, CameraActivity::class.java))
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload)

        prefs = Prefs(this)

        stopId = intent.getIntExtra("stop_id", -1)
        routeId = intent.getIntExtra("route_id", -1)
        officerRouteJoinId = intent.getIntExtra("officer_route_join_id", -1)
        officerStopId = intent.getIntExtra("officer_stop_id", -1)
        stopHash = intent.getStringExtra("stop_hash") ?: ""
        lat = intent.getDoubleExtra("lat", 0.0)
        lon = intent.getDoubleExtra("lon", 0.0)
        stopName = intent.getStringExtra("stop_name") ?: ""

        if (stopId == -1 || routeId == -1 ||
            officerRouteJoinId == -1 || officerStopId == -1
        ) {
            Toast.makeText(this, "Invalid stop id", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraLauncher.launch(Intent(this, CameraActivity::class.java))
            } else {
                showCameraDisclosureDialog()
            }
        }
    }

    private fun showCameraDisclosureDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Camera Access Notice")
            .setMessage("Vigil accesses your camera to capture stop verification photos required for security patrol reporting.")
            .setPositiveButton("Continue") { _, _ ->
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun handleCameraResult(data: Intent?) {
        val path = data?.getStringExtra("image_path") ?: return
        val file = File(filesDir, path)

        if (!file.exists()) {
            Toast.makeText(this, "Image not found", Toast.LENGTH_SHORT).show()
            return
        }

        getCurrentLocation { location ->
            if (LocationUtils.isMockLocation(location)) {
                Toast.makeText(this, "GPS Spoofing detected! Action blocked.", Toast.LENGTH_LONG).show()
                PatrolNotifications.notifyGpsSpoofDetected(this)
                return@getCurrentLocation
            }

            val userLat = location.latitude
            val userLon = location.longitude
            val radius = prefs.getRadius()?.toFloatOrNull() ?: 30f
            val within = isWithinGeofence(
                userLat,
                userLon,
                lat,
                lon,
                radius + 20f
            )

            Log.d(
                "PatrolDebug",
                "VERIFY_GEOFENCE stopId=$stopId hash=$stopHash userLat=$userLat " +
                    "userLon=$userLon stopLat=$lat stopLon=$lon within=$within"
            )

            if (!within) {
                Toast.makeText(this, "You are not at the stop location", Toast.LENGTH_LONG).show()
                return@getCurrentLocation
            }

            processFaceVerification(file, userLat, userLon)
        }
    }

    private fun processFaceVerification(file: File, userLat: Double, userLon: Double) {
        faceWorker.execute {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw FaceRecognitionEngine.FaceRecognitionException("Failed to load selfie")

                try {
                    val faceEngine = FaceRecognitionEngine.getInstance(applicationContext)
                val antiSpoofEngine = FaceAntiSpoofEngine.getInstance(applicationContext)

                // One SCRFD pass, then reuse the face for both liveness and identity.
                val face = faceEngine.detectSingleFace(bitmap)

                // Liveness happens BEFORE recognition.
                val liveness = antiSpoofEngine.predict(bitmap, face)


                if (BuildConfig.DEBUG) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Liveness: %.3f | Threshold: %.2f".format(
                                Locale.US,
                                liveness.realProbability,
                                FaceAntiSpoofEngine.LIVENESS_THRESHOLD
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                if (!liveness.isLive) {
                    throw FaceAntiSpoofEngine.AntiSpoofException(
                        "Spoof detected. Verification rejected."
                    )
                }

                val currentEmbedding = faceEngine.extractEmbedding(bitmap, face)

                val officerId = prefs.getPatrolId()
                    ?: throw IllegalStateException("Officer id missing")

                val savedEmbedding = FaceEmbeddingStore.loadForOfficer(this, officerId)
                    ?: throw IllegalStateException("Registered face not found")

                val score = faceEngine.cosineSimilarity(savedEmbedding, currentEmbedding)

                Log.d(
                    "FaceRecognition",
                    "MATCH_SCORE=$score THRESHOLD=${FaceRecognitionEngine.MATCH_THRESHOLD}"
                )

                if (score >= FaceRecognitionEngine.MATCH_THRESHOLD) {
                    val (base64Image, webpFile) = bitmapToBase64AndSaveWebp(file)
                    runOnUiThread {
                        Toast.makeText(this, "Face matched ✅", Toast.LENGTH_SHORT).show()
                    }
                    completeVerification(base64Image, webpFile.absolutePath, userLat, userLon)
                } else {
                    runOnUiThread {
                        if (BuildConfig.DEBUG) {
                            Toast.makeText(
                                this,
                                "Face score: %.3f | Threshold: %.2f".format(
                                    Locale.US,
                                    score,
                                    FaceRecognitionEngine.MATCH_THRESHOLD
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        Toast.makeText(this, "Face not matched", Toast.LENGTH_SHORT).show()
                    }
                }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }

            } catch (e: FaceAntiSpoofEngine.AntiSpoofException) {
                Log.e("FaceLiveness", "Verification rejected by liveness", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        e.message ?: "Liveness check failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: FaceRecognitionEngine.FaceRecognitionException) {
                Log.e("FaceRecognition", "Verification face rejected", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        e.message ?: "Face processing failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("FaceRecognition", "Face verification failed", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (e.message == "Registered face not found")
                            "Registered face not found. Please register face again."
                        else
                            "Face verification failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun bitmapToBase64AndSaveWebp(file: File): Pair<String, File> {
        var bitmap = BitmapFactory.decodeFile(file.absolutePath)

        // Resize to 600x800 to avoid Intent size limits (TransactionTooLargeException)
        val resized = Bitmap.createScaledBitmap(bitmap, 600, 800, true)
        if (resized != bitmap) {
            bitmap.recycle()
        }
        bitmap = resized

        val webpFormat = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        val webpFile = File(file.parent, "captured_verification.webp")
        webpFile.outputStream().use { out ->
            bitmap.compress(webpFormat, 80, out)
        }
        bitmap.recycle()

        val webpBytes = webpFile.readBytes()
        val base64 = Base64.encodeToString(webpBytes, Base64.NO_WRAP)
        return Pair(base64, webpFile)
    }

    private fun getCurrentLocation(callback: (Location) -> Unit) {
        val fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(this)

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    callback(location)
                } else {
                    val lastLoc = prefs.getLastKnownLocation(maxAgeMillis = 5 * 60 * 1000L)
                    if (lastLoc != null) {
                        val fallbackLoc = Location("prefs").apply {
                            latitude = lastLoc.first
                            longitude = lastLoc.second
                        }
                        callback(fallbackLoc)
                    } else {
                        Toast.makeText(this, "Unable to get location", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isWithinGeofence(
        userLat: Double,
        userLon: Double,
        stopLat: Double,
        stopLon: Double,
        radiusMeters: Float = 50f
    ): Boolean {
        val results = FloatArray(1)
        Location.distanceBetween(
            userLat,
            userLon,
            stopLat,
            stopLon,
            results
        )
        return results[0] <= radiusMeters
    }

    private fun completeVerification(
        imageBase64: String,
        imageFilePath: String,
        userLat: Double,
        userLon: Double
    ) {
        val time = getTime()

        val db = RouteDbHelper(this)
        db.updateReached(officerRouteJoinId, officerStopId, time)
        db.updateUpload(
            officerRouteJoinId,
            officerStopId,
            userLat,
            userLon,
            time,
            imageBase64
        )

        PatrolNotifications.notifyStopVerified(this, stopName, stopId)

        val officerId = prefs.getPatrolId()?.toIntOrNull() ?: -1

        val reachPayload = JSONObject().apply {
            put("stopId", stopId)
            put("routeId", routeId)
            put("officerRouteJoinId", officerRouteJoinId)
            put("officerStopId", officerStopId)
            put("officerId", officerId)
            put("time", time)
        }

        PatrolForegroundService.enqueueSyncAction(
            this,
            "reach",
            reachPayload.toString()
        )

        val payload = JSONObject().apply {
            put("stopId", stopId)
            put("routeId", routeId)
            put("officerRouteJoinId", officerRouteJoinId)
            put("officerStopId", officerStopId)
            put("officerId", officerId)
            put("imageFilePath", imageFilePath)
            put("imageBase64", imageBase64)
            put("lat", userLat)
            put("lon", userLon)
            put("time", time)
        }

        PatrolForegroundService.enqueueSyncAction(
            this,
            PatrolForegroundService.SYNC_ACTION_VERIFY_UPLOAD,
            payload.toString()
        )

        runOnUiThread {
            Toast.makeText(this, "Verified ✅", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun getTime(): String {
        return SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
    }

    override fun onDestroy() {
        faceWorker.shutdownNow()
        super.onDestroy()
    }
}
