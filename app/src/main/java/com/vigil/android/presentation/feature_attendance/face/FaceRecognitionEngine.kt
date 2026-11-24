package com.vigil.android.presentation.feature_attendance.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

/**
 * On-device officer face recognition using InsightFace buffalo_sc models:
 *
 * 1) SCRFD-500MF (det_500m.onnx) detects a face and 5 landmarks.
 * 2) The face is aligned to the standard ArcFace 112x112 geometry.
 * 3) MobileFaceNet (w600k_mbf.onnx) produces a 512-D embedding.
 * 4) Embeddings are L2-normalized and compared using cosine similarity.
 *
 * Liveness is handled separately by FaceAntiSpoofEngine.
 */
class FaceRecognitionEngine private constructor(context: Context) {

    class FaceRecognitionException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    data class Detection(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        /** x0,y0,x1,y1,... for left eye, right eye, nose, left mouth, right mouth */
        val landmarks: FloatArray
    )

    companion object {
        private const val DETECTOR_MODEL = "models/det_500m.onnx"
        private const val RECOGNITION_MODEL = "models/w600k_mbf.onnx"

        private const val DET_INPUT_SIZE = 640
        private const val REC_INPUT_SIZE = 112

        private const val DET_THRESHOLD = 0.50f
        private const val NMS_THRESHOLD = 0.40f

        /**
         * STARTING threshold only. Tune this using genuine and impostor tests
         * from your actual officers/phones before final deployment.
         *
         * Do NOT reuse KBY's old 0.7 threshold: the score scale is different.
         */
        const val MATCH_THRESHOLD = 0.40f

        const val EMBEDDING_SIZE = 512

        /** Rebuild cached reference vectors when the decoding/preprocessing contract changes. */
        const val EMBEDDING_PIPELINE_REVISION = 2

        private val ARC_FACE_DST = floatArrayOf(
            38.2946f, 51.6963f,
            73.5318f, 51.5014f,
            56.0252f, 71.7366f,
            41.5493f, 92.3655f,
            70.7299f, 92.2041f
        )

        @Volatile
        private var instance: FaceRecognitionEngine? = null

        fun getInstance(context: Context): FaceRecognitionEngine {
            return instance ?: synchronized(this) {
                instance ?: FaceRecognitionEngine(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val detectorSession: OrtSession
    private val recognitionSession: OrtSession
    private val detectorInputName: String
    private val recognitionInputName: String

    init {
        try {
            val detectorBytes = context.assets.open(DETECTOR_MODEL).use { it.readBytes() }
            val recognitionBytes = context.assets.open(RECOGNITION_MODEL).use { it.readBytes() }

            detectorSession = OrtSession.SessionOptions().use { options ->
                env.createSession(detectorBytes, options)
            }

            recognitionSession = OrtSession.SessionOptions().use { options ->
                env.createSession(recognitionBytes, options)
            }

            detectorInputName = detectorSession.inputNames.first()
            recognitionInputName = recognitionSession.inputNames.first()

            if (detectorSession.outputNames.size != 9) {
                throw FaceRecognitionException(
                    "det_500m.onnx is not the expected 9-output SCRFD model"
                )
            }
        } catch (e: FaceRecognitionException) {
            throw e
        } catch (e: Exception) {
            throw FaceRecognitionException(
                "Could not load face recognition models. Check app/src/main/assets/models/",
                e
            )
        }
    }

    /**
     * Detect exactly one face and return its box + 5 landmarks.
     *
     * This is public so the same SCRFD result can be reused by both the
     * anti-spoof model and the recognition model without detecting twice.
     */
    @Throws(FaceRecognitionException::class)
    fun detectSingleFace(bitmap: Bitmap): Detection {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            throw FaceRecognitionException("Invalid face image")
        }

        val faces = detectFaces(bitmap)

        if (faces.isEmpty()) {
            throw FaceRecognitionException("No face detected")
        }

        if (faces.size > 1) {
            throw FaceRecognitionException("Multiple faces detected. Keep only one face in view")
        }

        return faces[0]
    }

    /**
     * Convenience method for callers that only need recognition.
     */
    @Throws(FaceRecognitionException::class)
    fun extractEmbedding(bitmap: Bitmap): FloatArray {
        val face = detectSingleFace(bitmap)
        return extractEmbedding(bitmap, face)
    }

    /**
     * Align and recognize a face that was already detected by SCRFD.
     * Reusing the Detection avoids a second detector pass after liveness.
     */
    @Throws(FaceRecognitionException::class)
    fun extractEmbedding(bitmap: Bitmap, face: Detection): FloatArray {
        val aligned = alignFace(bitmap, face.landmarks)
        return try {
            runRecognition(aligned)
        } finally {
            if (!aligned.isRecycled) aligned.recycle()
        }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        return try {
            require(a.size == EMBEDDING_SIZE && b.size == EMBEDDING_SIZE) { "Unexpected face embedding size" }
            FaceInferenceMath.cosineSimilarity(a, b)
        } catch (error: IllegalArgumentException) {
            throw FaceRecognitionException("Invalid face embedding. Sync reference photos and try again.", error)
        }
    }

    private fun detectFaces(bitmap: Bitmap): List<Detection> {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        val imageRatio = originalHeight.toFloat() / originalWidth.toFloat()
        val newWidth: Int
        val newHeight: Int

        if (imageRatio > 1f) {
            newHeight = DET_INPUT_SIZE
            newWidth = (DET_INPUT_SIZE / imageRatio).toInt().coerceAtLeast(1)
        } else {
            newWidth = DET_INPUT_SIZE
            newHeight = (DET_INPUT_SIZE * imageRatio).toInt().coerceAtLeast(1)
        }

        // Same top-left letterbox strategy used by InsightFace SCRFD.
        val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val detectorBitmap = Bitmap.createBitmap(
            DET_INPUT_SIZE,
            DET_INPUT_SIZE,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(detectorBitmap)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(resized, 0f, 0f, null)
        if (resized !== bitmap && !resized.isRecycled) resized.recycle()

        val detScale = newHeight.toFloat() / originalHeight.toFloat()
        val inputData = bitmapToNchw(detectorBitmap, mean = 127.5f, std = 128f)
        detectorBitmap.recycle()

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputData),
            longArrayOf(1, 3, DET_INPUT_SIZE.toLong(), DET_INPUT_SIZE.toLong())
        )

        val outputs: Array<FloatArray>
        try {
            detectorSession.run(
                Collections.singletonMap<String, OnnxTensor>(detectorInputName, tensor)
            ).use { result ->
                if (result.size() != 9) {
                    throw FaceRecognitionException(
                        "Unexpected SCRFD output count: ${result.size()}"
                    )
                }
                outputs = Array(9) { index -> tensorToFloatArray(result.get(index)) }
            }
        } catch (e: FaceRecognitionException) {
            throw e
        } catch (e: Exception) {
            throw FaceRecognitionException("Face detection failed", e)
        } finally {
            tensor.close()
        }

        if (outputs.any { values -> values.any { !it.isFinite() } }) {
            throw FaceRecognitionException("Invalid face detector output. Retake a clear photo.")
        }

        val strides = intArrayOf(8, 16, 32)
        val candidates = ArrayList<Detection>()

        for (level in strides.indices) {
            val stride = strides[level]
            val scores = outputs[level]
            val bbox = outputs[level + 3]
            val kps = outputs[level + 6]

            val featureWidth = DET_INPUT_SIZE / stride
            val featureHeight = DET_INPUT_SIZE / stride
            val anchorsPerPoint = 2
            val expectedAnchors = featureWidth * featureHeight * anchorsPerPoint

            if (scores.size != expectedAnchors ||
                bbox.size != expectedAnchors * 4 ||
                kps.size != expectedAnchors * 10
            ) {
                throw FaceRecognitionException(
                    "Unexpected SCRFD tensor shape at stride $stride"
                )
            }

            for (anchorIndex in 0 until expectedAnchors) {
                val score = scores[anchorIndex]
                if (score < DET_THRESHOLD) continue

                val gridIndex = anchorIndex / anchorsPerPoint
                val gridY = gridIndex / featureWidth
                val gridX = gridIndex % featureWidth

                val centerX = gridX.toFloat() * stride
                val centerY = gridY.toFloat() * stride

                val boxOffset = anchorIndex * 4
                val left = bbox[boxOffset] * stride
                val top = bbox[boxOffset + 1] * stride
                val right = bbox[boxOffset + 2] * stride
                val bottom = bbox[boxOffset + 3] * stride

                val x1 = (centerX - left) / detScale
                val y1 = (centerY - top) / detScale
                val x2 = (centerX + right) / detScale
                val y2 = (centerY + bottom) / detScale
                if (x2 <= x1 || y2 <= y1 || x2 <= 0f || y2 <= 0f ||
                    x1 >= originalWidth || y1 >= originalHeight) continue

                val landmarks = FloatArray(10)
                val kpsOffset = anchorIndex * 10
                for (point in 0 until 5) {
                    val px = centerX + kps[kpsOffset + point * 2] * stride
                    val py = centerY + kps[kpsOffset + point * 2 + 1] * stride
                    landmarks[point * 2] = px / detScale
                    landmarks[point * 2 + 1] = py / detScale
                }

                candidates.add(
                    Detection(
                        x1 = x1.coerceIn(0f, originalWidth.toFloat()),
                        y1 = y1.coerceIn(0f, originalHeight.toFloat()),
                        x2 = x2.coerceIn(0f, originalWidth.toFloat()),
                        y2 = y2.coerceIn(0f, originalHeight.toFloat()),
                        score = score,
                        landmarks = landmarks
                    )
                )
            }
        }

        return nms(candidates, NMS_THRESHOLD)
    }

    private fun nms(detections: List<Detection>, threshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (iou(best, candidate) > threshold) {
                    iterator.remove()
                }
            }
        }

        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val xx1 = max(a.x1, b.x1)
        val yy1 = max(a.y1, b.y1)
        val xx2 = min(a.x2, b.x2)
        val yy2 = min(a.y2, b.y2)

        val w = max(0f, xx2 - xx1 + 1f)
        val h = max(0f, yy2 - yy1 + 1f)
        val intersection = w * h

        val areaA = max(0f, a.x2 - a.x1 + 1f) * max(0f, a.y2 - a.y1 + 1f)
        val areaB = max(0f, b.x2 - b.x1 + 1f) * max(0f, b.y2 - b.y1 + 1f)
        val union = areaA + areaB - intersection

        return if (union <= 0f) 0f else intersection / union
    }

    /**
     * Estimate a least-squares similarity transform from the detected 5 points
     * to InsightFace's standard ArcFace 112x112 reference points.
     */
    private fun alignFace(source: Bitmap, src: FloatArray): Bitmap {
        val matrix = try {
            Matrix().apply { setValues(FaceInferenceMath.similarityTransform(src, ARC_FACE_DST)) }
        } catch (error: IllegalArgumentException) {
            throw FaceRecognitionException("Invalid face landmarks. Retake a clear photo.", error)
        }

        val output = Bitmap.createBitmap(
            REC_INPUT_SIZE,
            REC_INPUT_SIZE,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            source,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    private fun runRecognition(aligned: Bitmap): FloatArray {
        val inputData = bitmapToNchw(aligned, mean = 127.5f, std = 127.5f)
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputData),
            longArrayOf(1, 3, REC_INPUT_SIZE.toLong(), REC_INPUT_SIZE.toLong())
        )

        val rawEmbedding: FloatArray
        try {
            recognitionSession.run(
                Collections.singletonMap<String, OnnxTensor>(recognitionInputName, tensor)
            ).use { result ->
                if (result.size() < 1) {
                    throw FaceRecognitionException("Recognition model returned no output")
                }
                rawEmbedding = tensorToFloatArray(result.get(0))
            }
        } catch (e: FaceRecognitionException) {
            throw e
        } catch (e: Exception) {
            throw FaceRecognitionException("Face embedding extraction failed", e)
        } finally {
            tensor.close()
        }

        if (rawEmbedding.isEmpty()) {
            throw FaceRecognitionException("Recognition model returned an empty embedding")
        }

        // buffalo_sc w600k_mbf.onnx should output 512 dimensions.
        if (rawEmbedding.size != EMBEDDING_SIZE) {
            throw FaceRecognitionException(
                "Unexpected recognition embedding size: ${rawEmbedding.size}; expected $EMBEDDING_SIZE"
            )
        }

        return l2Normalize(rawEmbedding)
    }

    private fun l2Normalize(values: FloatArray): FloatArray {
        return try {
            FaceInferenceMath.normalize(values)
        } catch (error: IllegalArgumentException) {
            throw FaceRecognitionException("Invalid face embedding. Retake a clear photo.", error)
        }
    }

    /** Convert Android RGB bitmap pixels into an NCHW float tensor. */
    private fun bitmapToNchw(bitmap: Bitmap, mean: Float, std: Float): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val output = FloatArray(pixelCount * 3)
        val rOffset = 0
        val gOffset = pixelCount
        val bOffset = pixelCount * 2

        for (i in 0 until pixelCount) {
            val color = pixels[i]
            output[rOffset + i] = (Color.red(color) - mean) / std
            output[gOffset + i] = (Color.green(color) - mean) / std
            output[bOffset + i] = (Color.blue(color) - mean) / std
        }

        return output
    }

    private fun tensorToFloatArray(value: OnnxValue): FloatArray {
        val tensor = value as? OnnxTensor
            ?: throw FaceRecognitionException("Expected ONNX tensor output")
        val buffer = tensor.floatBuffer
            ?: throw FaceRecognitionException("Expected float ONNX tensor output")

        val output = FloatArray(buffer.remaining())
        buffer.get(output)
        return output
    }
}
