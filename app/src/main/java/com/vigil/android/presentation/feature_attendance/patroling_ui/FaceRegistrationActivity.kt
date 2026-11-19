package com.vigil.android.presentation.feature_attendance.patroling_ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vigil.android.R
import com.vigil.android.BuildConfig
import com.vigil.android.MainActivity
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs
import com.vigil.android.presentation.feature_attendance.face.FaceAntiSpoofEngine
import com.vigil.android.presentation.feature_attendance.face.FaceEmbeddingStore
import com.vigil.android.presentation.feature_attendance.face.FaceRecognitionEngine
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One-time face enrollment after username/password login.
 *
 * Flow:
 * selfie -> SCRFD -> MiniFASNetV2 liveness -> MobileFaceNet embedding -> save.
 */
class FaceRegistrationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FACE_REGISTER"
    }

    private lateinit var prefs: Prefs
    private lateinit var captureBtn: Button
    private val worker = Executors.newSingleThreadExecutor()

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
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
        setContentView(R.layout.activity_face_registration)

        prefs = Prefs(this)
        captureBtn = findViewById(R.id.captureFaceBtn)

        val patrolId = prefs.getPatrolId()
        val token = prefs.getToken()

        if (patrolId.isNullOrBlank() || token.isNullOrBlank()) {
            finish()
            return
        }

        captureBtn.setOnClickListener {
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
            .setMessage("Vigil accesses your camera to capture facial registration and attendance verification photos required for security shift validation.")
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
        val path = data?.getStringExtra("image_path")
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "Image path missing", Toast.LENGTH_SHORT).show()
            return
        }

        val selfieFile = File(filesDir, path)
        if (!selfieFile.exists()) {
            Toast.makeText(this, "Selfie file not found", Toast.LENGTH_SHORT).show()
            return
        }

        captureBtn.isEnabled = false
        captureBtn.text = "PROCESSING..."

        worker.execute {
            try {
                val bitmap = BitmapFactory.decodeFile(selfieFile.absolutePath)
                    ?: throw FaceRecognitionEngine.FaceRecognitionException("Failed to load selfie")

                try {
                    val faceEngine = FaceRecognitionEngine.getInstance(applicationContext)
                val antiSpoofEngine = FaceAntiSpoofEngine.getInstance(applicationContext)

                // Run SCRFD only once and reuse the same face box for liveness + recognition.
                val face = faceEngine.detectSingleFace(bitmap)

                val liveness = antiSpoofEngine.predict(bitmap, face)

                Log.d(
                    TAG,
                    "LIVENESS_REAL=${liveness.realProbability} " +
                        "CLASS=${liveness.predictedClass} " +
                        "THRESHOLD=${FaceAntiSpoofEngine.LIVENESS_THRESHOLD} " +
                        "LIVE=${liveness.isLive}"
                )

                if (BuildConfig.DEBUG) {
//                    runOnUiThread {
//                        Toast.makeText(
//                            this,
//                            "Liveness: %.3f | Threshold: %.2f".format(
//                                Locale.US,
//                                liveness.realProbability,
//                                FaceAntiSpoofEngine.LIVENESS_THRESHOLD
//                            ),
//                            Toast.LENGTH_LONG
//                        ).show()
//                    }
                }

                if (!liveness.isLive) {
                    throw FaceAntiSpoofEngine.AntiSpoofException(
                        "Spoof detected. Use your real face and try again."
                    )
                }

                val currentEmbedding = faceEngine.extractEmbedding(bitmap, face)

                val officerId = prefs.getPatrolId()
                    ?: throw IllegalStateException("Officer id missing")

                FaceEmbeddingStore.save(this, officerId, currentEmbedding)

                // Keep the existing User Detail screen working.
                selfieFile.copyTo(File(filesDir, "profile.jpg"), overwrite = true)

                // Old proprietary template is no longer used.
                File(filesDir, "face_template.dat").delete()

                    runOnUiThread {
                        Toast.makeText(this, "Face registered successfully", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }

            } catch (e: FaceAntiSpoofEngine.AntiSpoofException) {
                Log.e(TAG, "Liveness check failed", e)
                runOnUiThread {
                    resetButton()
                    Toast.makeText(
                        this,
                        e.message ?: "Liveness check failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: FaceRecognitionEngine.FaceRecognitionException) {
                Log.e(TAG, "Face enrollment rejected", e)
                runOnUiThread {
                    resetButton()
                    Toast.makeText(
                        this,
                        e.message ?: "Could not register face",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Face enrollment failed", e)
                runOnUiThread {
                    resetButton()
                    Toast.makeText(this, "Face registration failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resetButton() {
        captureBtn.isEnabled = true
        captureBtn.text = "TAKE SELFIE"
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
