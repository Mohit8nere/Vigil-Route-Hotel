package com.vigil.android.presentation.feature_attendance.face

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Bitmap behavior cannot be tested with the local android.jar stubs. */
@RunWith(AndroidJUnit4::class)
class FaceBitmapPreprocessingTest {
    @Test fun sameSizeLivenessInputDoesNotAliasOrRecycleCallerImage() {
        val source = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val result = FaceBitmapPreprocessing.livenessCrop(source, FaceInferenceMath.CropBounds(0, 0, 80, 80), 80)
        try {
            assertNotSame(source, result)
            assertFalse(result.isRecycled)
            assertEquals(Color.RED, result.getPixel(40, 40))
        } finally { result.recycle() }
        try {
            assertFalse(source.isRecycled)
            assertEquals(Color.RED, source.getPixel(40, 40))
        } finally { source.recycle() }
    }

    @Test fun eightyPixelSubcropRemainsReadableUntilItsOwnerRecyclesIt() {
        val source = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GREEN)
        val result = FaceBitmapPreprocessing.livenessCrop(source, FaceInferenceMath.CropBounds(20, 20, 100, 100), 80)
        try {
            assertFalse(result.isRecycled)
            assertEquals(Color.GREEN, result.getPixel(40, 40))
            assertFalse(source.isRecycled)
        } finally { result.recycle(); source.recycle() }
    }
}
