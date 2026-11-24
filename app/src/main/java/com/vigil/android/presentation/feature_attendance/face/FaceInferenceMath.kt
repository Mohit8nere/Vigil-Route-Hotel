package com.vigil.android.presentation.feature_attendance.face

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/** Numeric contracts shared by inference and the regression tests. No Android state. */
internal object FaceInferenceMath {
    data class CropBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        // Android Rect uses exclusive right/bottom edges.
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Preserve the expanded MiniFAS context at image edges by moving the rectangle
     * into the image, instead of independently clipping its four sides.
     */
    fun expandedCrop(
        imageWidth: Int, imageHeight: Int,
        x1: Float, y1: Float, x2: Float, y2: Float, requestedScale: Double
    ): CropBounds {
        require(imageWidth > 1 && imageHeight > 1) { "Invalid face image" }
        require(listOf(x1, y1, x2, y2).all { it.isFinite() } && requestedScale.isFinite() && requestedScale > 0.0) {
            "Invalid face crop coordinates"
        }
        require(x2 > x1 && y2 > y1 && x2 > 0f && y2 > 0f &&
            x1 < imageWidth && y1 < imageHeight) { "Invalid face crop coordinates" }

        val maxX = (imageWidth - 1).toDouble()
        val maxY = (imageHeight - 1).toDouble()
        val left = x1.toDouble().coerceIn(0.0, maxX)
        val top = y1.toDouble().coerceIn(0.0, maxY)
        val right = x2.toDouble().coerceIn(0.0, maxX)
        val bottom = y2.toDouble().coerceIn(0.0, maxY)
        require(right > left && bottom > top) { "Invalid face crop coordinates" }
        val faceWidth = right - left + 1.0
        val faceHeight = bottom - top + 1.0
        val scale = min(requestedScale, min(maxX / faceWidth, maxY / faceHeight))
        val cropWidth = faceWidth * scale
        val cropHeight = faceHeight * scale
        val centreX = left + faceWidth / 2.0
        val centreY = top + faceHeight / 2.0

        // Moving the origin preserves the crop's context and aspect ratio.
        val cropLeft = (centreX - cropWidth / 2.0).coerceIn(0.0, (maxX - cropWidth).coerceAtLeast(0.0))
        val cropTop = (centreY - cropHeight / 2.0).coerceIn(0.0, (maxY - cropHeight).coerceAtLeast(0.0))
        return CropBounds(
            cropLeft.toInt(), cropTop.toInt(),
            (cropLeft + cropWidth).coerceAtMost(maxX).toInt() + 1,
            (cropTop + cropHeight).coerceAtMost(maxY).toInt() + 1
        )
    }

    fun normalize(values: FloatArray): FloatArray {
        require(values.isNotEmpty() && values.all { it.isFinite() }) { "Invalid face embedding" }
        val norm = sqrt(values.sumOf { it.toDouble() * it.toDouble() })
        require(norm.isFinite() && norm > 1e-12) { "Invalid zero-length face embedding" }
        return FloatArray(values.size) { (values[it].toDouble() / norm).toFloat() }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty()) { "Face embedding dimensions do not match" }
        require(a.all { it.isFinite() } && b.all { it.isFinite() }) { "Invalid face embedding" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denominator = sqrt(normA) * sqrt(normB)
        if (denominator <= 1e-12) return -1f
        require(denominator.isFinite() && dot.isFinite()) { "Invalid face embedding" }
        return (dot / denominator).toFloat().coerceIn(-1f, 1f)
    }

    fun threeClassSoftmax(logits: FloatArray): FloatArray {
        require(logits.size == 3 && logits.all { it.isFinite() }) { "Invalid liveness model output" }
        val largest = logits.maxOrNull()!!.toDouble()
        val weights = DoubleArray(3) { exp(logits[it].toDouble() - largest) }
        val total = weights.sum()
        require(total.isFinite() && total > 0.0) { "Invalid liveness model output" }
        return FloatArray(3) { (weights[it] / total).toFloat() }
    }

    /** Least-squares rotation/scale/translation, in Android Matrix row-major order. */
    fun similarityTransform(source: FloatArray, target: FloatArray): FloatArray {
        require(source.size == 10 && target.size == 10 &&
            source.all { it.isFinite() } && target.all { it.isFinite() }) { "Invalid face landmarks" }
        val sx = (0 until 5).sumOf { source[it * 2].toDouble() } / 5.0
        val sy = (0 until 5).sumOf { source[it * 2 + 1].toDouble() } / 5.0
        val tx = (0 until 5).sumOf { target[it * 2].toDouble() } / 5.0
        val ty = (0 until 5).sumOf { target[it * 2 + 1].toDouble() } / 5.0
        var denominator = 0.0
        var real = 0.0
        var imaginary = 0.0
        for (i in 0 until 5) {
            val x = source[i * 2] - sx
            val y = source[i * 2 + 1] - sy
            val u = target[i * 2] - tx
            val v = target[i * 2 + 1] - ty
            denominator += x * x + y * y
            real += x * u + y * v
            imaginary += x * v - y * u
        }
        require(denominator.isFinite() && denominator > 1e-9) { "Could not align face" }
        val a = real / denominator
        val b = imaginary / denominator
        require(a.isFinite() && b.isFinite() && a * a + b * b > 1e-12) { "Could not align face" }
        val result = floatArrayOf(
            a.toFloat(), (-b).toFloat(), (tx - a * sx + b * sy).toFloat(),
            b.toFloat(), a.toFloat(), (ty - b * sx - a * sy).toFloat(),
            0f, 0f, 1f
        )
        require(result.all { it.isFinite() }) { "Invalid face landmarks" }
        return result
    }
}
