package com.vigil.android.presentation.feature_attendance.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.FloatBuffer
import java.util.Collections

/**
 * Passive RGB face anti-spoofing using MiniFASNetV2 ONNX.
 *
 * Expected model: models/MiniFASNetV2.onnx
 * Reference preprocessing for this model:
 * - one SCRFD face box
 * - expand crop by 2.7x around the face
 * - resize to 80x80
 * - BGR channel order
 * - float values in the original 0..255 range (no mean/std normalization)
 * - softmax over the 3 output logits
 * - class index 1 = REAL
 */
class FaceAntiSpoofEngine private constructor(context: Context) {

    class AntiSpoofException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    data class Result(
        val isLive: Boolean,
        val realProbability: Float,
        val predictedClass: Int,
        val probabilities: FloatArray
    )

    companion object {
        private const val MODEL_PATH = "models/MiniFASNetV2.onnx"
        private const val INPUT_SIZE = 80
        private const val CROP_SCALE = 2.7

        /**
         * Starting safety threshold. Keep this visible in Debug builds and tune
         * it using real officers plus print/phone-screen replay tests.
         *
         * The reference implementation calls class 1 REAL. We additionally
         * require at least 0.50 probability to reject ambiguous predictions.
         */
        const val LIVENESS_THRESHOLD = 0.50f

        @Volatile
        private var instance: FaceAntiSpoofEngine? = null

        fun getInstance(context: Context): FaceAntiSpoofEngine {
            return instance ?: synchronized(this) {
                instance ?: FaceAntiSpoofEngine(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        try {
            val modelBytes = context.assets.open(MODEL_PATH).use { it.readBytes() }
            session = OrtSession.SessionOptions().use { options ->
                env.createSession(modelBytes, options)
            }
            inputName = session.inputNames.first()

            if (session.outputNames.isEmpty()) {
                throw AntiSpoofException("MiniFASNetV2 model has no output")
            }
        } catch (e: AntiSpoofException) {
            throw e
        } catch (e: Exception) {
            throw AntiSpoofException(
                "Could not load MiniFASNetV2.onnx. Check app/src/main/assets/models/",
                e
            )
        }
    }

    /**
     * Run passive anti-spoofing on the already-detected SCRFD face.
     */
    @Throws(AntiSpoofException::class)
    fun predict(
        bitmap: Bitmap,
        face: FaceRecognitionEngine.Detection
    ): Result {
        if (bitmap.isRecycled || bitmap.width <= 1 || bitmap.height <= 1) {
            throw AntiSpoofException("Invalid selfie image")
        }

        val crop = cropFace(bitmap, face)
        val inputData = try {
            bitmapToBgrNchw(crop)
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputData),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )

        val logits: FloatArray
        try {
            session.run(
                Collections.singletonMap<String, OnnxTensor>(inputName, tensor)
            ).use { result ->
                if (result.size() < 1) {
                    throw AntiSpoofException("MiniFASNetV2 returned no output")
                }
                logits = tensorToFloatArray(result.get(0))
            }
        } catch (e: AntiSpoofException) {
            throw e
        } catch (e: Exception) {
            throw AntiSpoofException("Liveness inference failed", e)
        } finally {
            tensor.close()
        }

        if (logits.size != 3) {
            throw AntiSpoofException(
                "Unexpected MiniFASNetV2 output size: ${logits.size}; expected 3 classes"
            )
        }

        val probs = softmax(logits)
        var predictedClass = 0
        for (i in 1 until probs.size) {
            if (probs[i] > probs[predictedClass]) predictedClass = i
        }

        val realProbability = probs[1]
        val isLive = predictedClass == 1 && realProbability >= LIVENESS_THRESHOLD

        return Result(
            isLive = isLive,
            realProbability = realProbability,
            predictedClass = predictedClass,
            probabilities = probs
        )
    }

    /**
     * Match the reference MiniFASNetV2 crop used by the ONNX implementation.
     */
    private fun cropFace(
        source: Bitmap,
        face: FaceRecognitionEngine.Detection
    ): Bitmap {
        try {
            val bounds = FaceInferenceMath.expandedCrop(
                source.width, source.height, face.x1, face.y1, face.x2, face.y2, CROP_SCALE
            )
            return FaceBitmapPreprocessing.livenessCrop(source, bounds, INPUT_SIZE)
        } catch (error: IllegalArgumentException) {
            throw AntiSpoofException("Invalid face crop. Retake a clear photo.", error)
        }
    }

    /**
     * MiniFASNetV2 ONNX reference code receives OpenCV BGR images and simply
     * converts them to float32 NCHW without normalization.
     */
    private fun bitmapToBgrNchw(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val output = FloatArray(pixelCount * 3)
        val bOffset = 0
        val gOffset = pixelCount
        val rOffset = pixelCount * 2

        for (i in 0 until pixelCount) {
            val color = pixels[i]
            output[bOffset + i] = Color.blue(color).toFloat()
            output[gOffset + i] = Color.green(color).toFloat()
            output[rOffset + i] = Color.red(color).toFloat()
        }

        return output
    }

    private fun softmax(values: FloatArray): FloatArray {
        return try {
            FaceInferenceMath.threeClassSoftmax(values)
        } catch (error: IllegalArgumentException) {
            throw AntiSpoofException("Invalid liveness model output", error)
        }
    }

    private fun tensorToFloatArray(value: OnnxValue): FloatArray {
        val tensor = value as? OnnxTensor
            ?: throw AntiSpoofException("Expected float ONNX tensor output")
        val buffer = tensor.floatBuffer
            ?: throw AntiSpoofException("Expected float ONNX tensor output")

        val output = FloatArray(buffer.remaining())
        buffer.get(output)
        return output
    }
}
