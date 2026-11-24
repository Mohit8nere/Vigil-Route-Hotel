package com.vigil.android.presentation.feature_attendance.face

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceInferenceMathTest {
    @Test fun livenessCropMovesInsideFrameInsteadOfLosingContext() {
        val topLeft = FaceInferenceMath.expandedCrop(640, 480, 0f, 0f, 99f, 99f, 2.7)
        assertEquals(FaceInferenceMath.CropBounds(0, 0, 271, 271), topLeft)
        val bottomRight = FaceInferenceMath.expandedCrop(640, 480, 540f, 380f, 639f, 479f, 2.7)
        assertEquals(FaceInferenceMath.CropBounds(369, 209, 640, 480), bottomRight)
    }

    @Test fun fullImageCropUsesValidExclusiveEdgesEvenAtModelSize() {
        assertEquals(FaceInferenceMath.CropBounds(0, 0, 80, 80),
            FaceInferenceMath.expandedCrop(80, 80, 0f, 0f, 79f, 79f, 2.7))
        assertEquals(FaceInferenceMath.CropBounds(0, 0, 2, 2),
            FaceInferenceMath.expandedCrop(2, 2, 0f, 0f, 1f, 1f, 2.7))
    }

    @Test fun cropRejectsInvalidGeometryRatherThanProducingPixels() {
        for (box in listOf(
            floatArrayOf(Float.NaN, 0f, 30f, 30f),
            floatArrayOf(0f, 0f, Float.POSITIVE_INFINITY, 30f),
            floatArrayOf(30f, 0f, 20f, 30f),
            floatArrayOf(100f, 100f, 150f, 150f)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                FaceInferenceMath.expandedCrop(80, 80, box[0], box[1], box[2], box[3], 2.7)
            }
        }
    }

    @Test fun cropStaysWithinImageForNonSquareFramesAndBoundaryFaces() {
        for ((width, height) in listOf(1920 to 1080, 1080 to 1920, 13 to 7)) {
            for ((x, y) in listOf(0f to 0f, (width - 3f) to (height - 3f))) {
                val bounds = FaceInferenceMath.expandedCrop(width, height, x, y, x + 2f, y + 2f, 2.7)
                assertTrue(bounds.left >= 0 && bounds.top >= 0)
                assertTrue(bounds.right <= width && bounds.bottom <= height)
                assertTrue(bounds.width > 0 && bounds.height > 0)
            }
        }
    }

    @Test fun alignmentRecoversKnownRotationScaleAndTranslation() {
        val source = floatArrayOf(-2f, 1f, 2f, 1f, 0f, 0f, -1f, -2f, 1f, -2f)
        val target = FloatArray(10) { index ->
            if (index % 2 == 0) -2f * source[index + 1] + 13f else 2f * source[index - 1] - 4f
        }
        assertArrayEquals(floatArrayOf(0f, -2f, 13f, 2f, 0f, -4f, 0f, 0f, 1f),
            FaceInferenceMath.similarityTransform(source, target), 0.00001f)
    }

    @Test fun alignmentRejectsNonFiniteAndCollapsedLandmarks() {
        val target = floatArrayOf(10f, 10f, 30f, 10f, 20f, 20f, 12f, 30f, 28f, 30f)
        for (source in listOf(FloatArray(10), target.copyOf().also { it[4] = Float.NaN }, FloatArray(8))) {
            assertThrows(IllegalArgumentException::class.java) {
                FaceInferenceMath.similarityTransform(source, target)
            }
        }
    }

    @Test fun cosineHasKnownIdentityOrthogonalAndOppositeResults() {
        val a = floatArrayOf(3f, 4f)
        assertEquals(1f, FaceInferenceMath.cosineSimilarity(a, floatArrayOf(6f, 8f)), 0.000001f)
        assertEquals(0f, FaceInferenceMath.cosineSimilarity(a, floatArrayOf(-4f, 3f)), 0.000001f)
        assertEquals(-1f, FaceInferenceMath.cosineSimilarity(a, floatArrayOf(-3f, -4f)), 0.000001f)
        assertEquals(-1f, FaceInferenceMath.cosineSimilarity(a, floatArrayOf(0f, 0f)), 0f)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), FaceInferenceMath.normalize(a), 0.000001f)
    }

    @Test fun invalidEmbeddingsCannotBecomeSimilarityCandidates() {
        val valid = floatArrayOf(1f, 0f)
        for (invalid in listOf(floatArrayOf(Float.NaN, 0f), floatArrayOf(Float.POSITIVE_INFINITY, 0f), floatArrayOf())) {
            assertThrows(IllegalArgumentException::class.java) { FaceInferenceMath.normalize(invalid) }
            assertThrows(IllegalArgumentException::class.java) { FaceInferenceMath.cosineSimilarity(valid, invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { FaceInferenceMath.normalize(floatArrayOf(0f, 0f)) }
    }

    @Test fun livenessSoftmaxIsStableAndRejectsWrongClassCount() {
        val values = FaceInferenceMath.threeClassSoftmax(floatArrayOf(-Float.MAX_VALUE, Float.MAX_VALUE, 0f))
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), values, 0f)
        assertEquals(1f, values.sum(), 0.000001f)
        for (invalid in listOf(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f, 4f),
            floatArrayOf(0f, Float.NaN, 0f), floatArrayOf(0f, Float.POSITIVE_INFINITY, 0f))) {
            assertThrows(IllegalArgumentException::class.java) { FaceInferenceMath.threeClassSoftmax(invalid) }
        }
    }
}
