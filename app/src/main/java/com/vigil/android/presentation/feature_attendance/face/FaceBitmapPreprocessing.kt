package com.vigil.android.presentation.feature_attendance.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

internal object FaceBitmapPreprocessing {
    /**
     * Always owns the returned bitmap. Bitmap.createScaledBitmap may alias its
     * argument at the same size; drawing into a new bitmap avoids that contract.
     */
    fun livenessCrop(source: Bitmap, bounds: FaceInferenceMath.CropBounds, size: Int): Bitmap {
        require(!source.isRecycled && size > 0) { "Invalid face image" }
        require(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= source.width &&
            bounds.bottom <= source.height && bounds.width > 0 && bounds.height > 0) { "Invalid face crop" }
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            Canvas(result).drawBitmap(
                source,
                Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                Rect(0, 0, size, size),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            return result
        } catch (error: Exception) {
            result.recycle()
            throw error
        }
    }
}
