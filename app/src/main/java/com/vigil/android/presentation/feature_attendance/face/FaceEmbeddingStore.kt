package com.vigil.android.presentation.feature_attendance.face

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Stores exactly one officer's ONNX face embedding in app-private storage.
 * The officer id is written into the file so a different logged-in officer
 * can never accidentally reuse the previous officer's enrolled face.
 */
object FaceEmbeddingStore {

    private const val FILE_NAME = "face_embedding_v3.dat"
    private const val TEMP_FILE_NAME = "face_embedding_v3.tmp"
    private const val MAGIC = 0x46455633 // FEV3
    private const val VERSION = 3
    private const val MAX_REASONABLE_DIMENSION = 4096

    fun existsForOfficer(context: Context, officerId: String): Boolean {
        return try {
            loadForOfficer(context, officerId) != null
        } catch (_: Exception) {
            false
        }
    }

    @Throws(Exception::class)
    fun save(context: Context, officerId: String, embedding: FloatArray) {
        require(officerId.isNotBlank()) { "Officer id is empty" }
        require(embedding.isNotEmpty()) { "Face embedding is empty" }
        require(embedding.size <= MAX_REASONABLE_DIMENSION) { "Unexpected embedding size" }

        val target = File(context.filesDir, FILE_NAME)
        val temp = File(context.filesDir, TEMP_FILE_NAME)

        DataOutputStream(BufferedOutputStream(FileOutputStream(temp, false))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(officerId)
            out.writeInt(embedding.size)
            embedding.forEach(out::writeFloat)
            out.flush()
        }

        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IllegalStateException("Could not replace old face embedding")
        }

        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun loadForOfficer(context: Context, officerId: String): FloatArray? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null

        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val magic = input.readInt()
            if (magic != MAGIC) return null

            val version = input.readInt()
            if (version != VERSION) return null

            val storedOfficerId = input.readUTF()
            if (storedOfficerId != officerId) return null

            val dimension = input.readInt()
            if (dimension <= 0 || dimension > MAX_REASONABLE_DIMENSION) {
                throw IllegalStateException("Invalid face embedding dimension: $dimension")
            }

            FloatArray(dimension) { input.readFloat() }
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
        File(context.filesDir, TEMP_FILE_NAME).delete()
    }
}
