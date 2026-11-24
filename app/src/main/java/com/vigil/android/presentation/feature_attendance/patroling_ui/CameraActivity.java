package com.vigil.android.presentation.feature_attendance.patroling_ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.vigil.android.R;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "FACE_CAMERA";
    private static final int CAMERA_PERMISSION_REQUEST = 1;

    private PreviewView previewView;
    private Button btnCapture;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private boolean captureInProgress = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewView = findViewById(R.id.preview);
        btnCapture = findViewById(R.id.btnCapture);
        btnCapture.setEnabled(false);

        btnCapture.setOnClickListener(v -> captureSelfie());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Camera Access Notice")
                    .setMessage("Vigil accesses your camera to capture live verification images for security patrols.")
                    .setPositiveButton("Continue", (dialog, which) -> {
                        ActivityCompat.requestPermissions(
                                CameraActivity.this,
                                new String[]{Manifest.permission.CAMERA},
                                CAMERA_PERMISSION_REQUEST
                        );
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        Toast.makeText(CameraActivity.this, "Camera permission is required", Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .setCancelable(false)
                    .show();
        } else {
            startCamera();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector;
                if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                } else {
                    cameraSelector = new CameraSelector.Builder().build();
                }

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture
                );

                btnCapture.setEnabled(true);
                Log.d(TAG, "Camera ready (selector: " + cameraSelector + ")");

            } catch (Exception e) {
                Log.e(TAG, "Unable to start camera", e);
                Toast.makeText(this, "Unable to start camera", Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureSelfie() {
        if (captureInProgress || imageCapture == null) return;

        captureInProgress = true;
        btnCapture.setEnabled(false);
        btnCapture.setText("Capturing...");

        File outputFile = new File(getFilesDir(), "captured_full.jpg");
        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(outputFile).build();

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults outputFileResults
                    ) {
                        Log.d(TAG, "Selfie saved: " + outputFile.getAbsolutePath());

                        Intent result = new Intent();
                        result.putExtra("image_path", outputFile.getName());

                        stopCamera();
                        setResult(Activity.RESULT_OK, result);
                        finish();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Selfie capture failed", exception);
                        captureInProgress = false;
                        btnCapture.setEnabled(true);
                        btnCapture.setText("TAKE SELFIE");
                        Toast.makeText(
                                CameraActivity.this,
                                "Capture failed. Try again.",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
        );
    }

    private void stopCamera() {
        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping camera", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                previewView.post(this::startCamera);
            } else {
                Toast.makeText(
                        this,
                        "Camera permission is required",
                        Toast.LENGTH_SHORT
                ).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopCamera();
        super.onDestroy();
    }
}
