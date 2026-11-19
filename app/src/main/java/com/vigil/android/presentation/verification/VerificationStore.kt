package com.vigil.android.presentation.verification

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.common.session.SessionInterceptor
import com.vigil.android.core.di.api.ApiNames
import com.vigil.android.presentation.feature_attendance.face.FaceAntiSpoofEngine
import com.vigil.android.presentation.feature_attendance.face.FaceRecognitionEngine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.max
import kotlin.math.roundToInt

internal class ApiFailure(val code: Int, message: String) : IOException(message)

internal data class Candidate(
    val id: Long, val revision: Long, val name: String, val description: String,
    val category: String, val address: String, val thumbnail: ByteArray, val score: Float
)

internal data class LocalSummary(val version: Long, val count: Int, val pending: Int, val failed: Int, val unusable: String)
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private class Cache(context: Context, scope: String) : SQLiteOpenHelper(
    context, File(context.noBackupFilesDir, "verification-$scope.db").absolutePath, null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE profiles(id INTEGER PRIMARY KEY, revision INTEGER NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE stage(id INTEGER PRIMARY KEY, revision INTEGER NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE state(name TEXT PRIMARY KEY, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY, payload BLOB NOT NULL, failed INTEGER DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

internal class VerificationStore(rawContext: Context) : AutoCloseable {
    private val context = rawContext.applicationContext
    private val session = SessionManager(context)
    private val owner = checkNotNull(session.getUserId()) { "Sign in again to open verification." }
    init { require(session.getRole() == "Patrol Officer") { "Patrol Officer access required." } }

    private val scopeId = MessageDigest.getInstance("SHA-256")
        .digest("${ApiNames.BASE_URL}|$owner".toByteArray()).joinToString("") { "%02x".format(it) }
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .addInterceptor(SessionInterceptor(session))
        .addInterceptor(ChuckerInterceptor(context))
        .addNetworkInterceptor { chain ->
            checkOwner()
            val sent = chain.request().header("Authorization")
            if (sent != "Bearer ${session.fetchAuthToken()}") throw IOException("Session changed. Retry the operation.")
            chain.proceed(chain.request())
        }.build()
    private val cache = Cache(context, scopeId)
    private val db get() = cache.writableDatabase
    private val key by lazy { storageKey("vigil.verification.$scopeId") }

    companion object {
        private val syncLock = Any()

        @Synchronized private fun storageKey(alias: String): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec); generateKey() }
        }
    }

    override fun close() = cache.close()

    private fun checkOwner() {
        check(session.getRole() == "Patrol Officer" && session.getUserId() == owner && session.isLoggedIn()) {
            "Officer session changed. Sign in again."
        }
    }

    private fun seal(value: JSONObject): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(value.toString().toByteArray())
    }

    private fun open(bytes: ByteArray): JSONObject {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        }
        return JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
    }

    private fun request(path: String, body: JSONObject? = null): ByteArray {
        checkOwner()
        val builder = Request.Builder().url("${ApiNames.BASE_URL}api/v1/verification/$path")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        return http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 403) purgeReferences()
                val detail = runCatching { JSONObject(response.body?.string().orEmpty()).optString("detail") }.getOrNull()
                throw ApiFailure(response.code, detail?.takeIf { it.isNotBlank() } ?: "Request failed (${response.code}).")
            }
            checkOwner()
            response.body?.bytes() ?: throw IOException("Empty response.")
        }
    }

    fun state(name: String): JSONObject = db.rawQuery("SELECT payload FROM state WHERE name=?", arrayOf(name)).use {
        if (it.moveToFirst()) open(it.getBlob(0)) else JSONObject()
    }

    fun saveState(name: String, value: JSONObject) {
        db.execSQL("INSERT OR REPLACE INTO state(name,payload) VALUES(?,?)", arrayOf(name,seal(value)))
    }

    private fun scalar(query: String): Int = db.rawQuery(query,null).use { it.moveToFirst(); it.getInt(0) }
    fun summary() = LocalSummary(state("catalogue").optLong("version", -1L), scalar("SELECT COUNT(*) FROM profiles"),
        scalar("SELECT COUNT(*) FROM events WHERE failed=0"),scalar("SELECT COUNT(*) FROM events WHERE failed=1"),
        state("catalogue").optString("unusable"))

    fun refreshStatus(): JSONObject {
        val result = JSONObject(String(request("status/"), Charsets.UTF_8))
        saveState("status",result)
        return result
    }

    // Build a staged catalogue, then flip the complete snapshot in a single local transaction.
    // Invalid enrolment photos are marked unavailable without preventing unrelated removals.
    fun sync(onProgress: (Int, Int) -> Unit) = synchronized(syncLock) {
        val manifest = JSONObject(String(request("manifest/"),Charsets.UTF_8))
        val subjects = manifest.getJSONArray("subjects").objects()
        val version = manifest.getLong("version")
        val engine = FaceRecognitionEngine.getInstance(context)
        db.execSQL("DELETE FROM stage")
        val unusable = mutableListOf<String>()
        for ((index, subject) in subjects.withIndex()) {
            checkOwner()
            val id = subject.getLong("id")
            val revision = subject.getLong("revision")
            val cached = db.rawQuery("SELECT payload FROM profiles WHERE id=? AND revision=?", arrayOf(id.toString(),revision.toString())).use {
                if (it.moveToFirst()) it.getBlob(0) else null
            }
            val payload = cached ?: run {
                val vectors = JSONArray()
                var thumb = ""
                for (photo in subject.getJSONArray("photos").objects()) {
                    val bytes = request("photos/${photo.getLong("id")}/")
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Unreadable reference.")
                    try {
                        val vector = try { engine.extractEmbedding(bitmap) } catch (e: FaceRecognitionEngine.FaceRecognitionException) {
                            // The detector reports zero/multiple faces as an enrolment quality issue.
                            if (e.message?.startsWith("No face") == true || e.message?.startsWith("Multiple faces") == true) continue
                            throw e
                        }
                        val buffer = ByteBuffer.allocate(vector.size * 4)
                        for (value in vector) buffer.putFloat(value)
                        vectors.put(Base64.encodeToString(buffer.array(),Base64.NO_WRAP))
                        if (thumb.isEmpty()) {
                            val scale = 180f / max(bitmap.width,bitmap.height).coerceAtLeast(180)
                            val small = Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).roundToInt().coerceAtLeast(1),
                                (bitmap.height*scale).roundToInt().coerceAtLeast(1),true)
                            val out = ByteArrayOutputStream()
                            small.compress(Bitmap.CompressFormat.JPEG,82,out)
                            if (small !== bitmap) small.recycle()
                            thumb = Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
                        }
                    } finally { bitmap.recycle() }
                }
                subject.remove("photos")
                subject.put("vectors",vectors).put("thumbnail",thumb)
                seal(subject)
            }
            if (open(payload).getJSONArray("vectors").length() == 0) unusable.add(subject.getString("name"))
            db.execSQL("INSERT INTO stage(id,revision,payload) VALUES(?,?,?)", arrayOf(id,revision,payload))
            onProgress(index+1,subjects.size)
        }
        val latest = refreshStatus().getLong("version")
        check(latest == version) { "Catalogue changed during sync. Please sync again." }
        checkOwner()
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM profiles")
            db.execSQL("INSERT INTO profiles SELECT * FROM stage")
            db.execSQL("DELETE FROM stage")
            saveState("catalogue",JSONObject().put("version",version).put("unusable",unusable.joinToString(", ")))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun purgeReferences() {
        // Pending confirmations remain encrypted for delivery by this officer after the next login.
        db.beginTransaction()
        try {
            for (table in listOf("profiles","stage","state")) db.delete(table,null,null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun match(bitmap: Bitmap, selectedId: Long?): Candidate? {
        checkOwner()
        val engine = FaceRecognitionEngine.getInstance(context)
        val detection = engine.detectSingleFace(bitmap)
        check(FaceAntiSpoofEngine.getInstance(context).predict(bitmap, detection).isLive) {
            "Live face check was inconclusive. Retake the photo in better light."
        }
        val probe = engine.extractEmbedding(bitmap,detection)
        var best: Candidate? = null
        var runnerUp = -1f
        val selection = if (selectedId != null) " WHERE id=?" else ""
        db.rawQuery("SELECT payload FROM profiles$selection", selectedId?.let { arrayOf(it.toString()) }).use { cursor ->
            while (cursor.moveToNext()) {
                val profile = open(cursor.getBlob(0))
                val vectors = profile.getJSONArray("vectors")
                var score = -1f
                for (i in 0 until vectors.length()) {
                    val floats = ByteBuffer.wrap(Base64.decode(vectors.getString(i),Base64.DEFAULT)).asFloatBuffer()
                    val reference = FloatArray(floats.remaining()).also { floats.get(it) }
                    score = max(score,engine.cosineSimilarity(probe,reference))
                }
                val current = best
                if (score > (current?.score ?: -1f)) {
                    runnerUp = current?.score ?: -1f
                    best = Candidate(profile.getLong("id"),profile.getLong("revision"),profile.getString("name"),
                        profile.optString("description"),profile.optString("category_name"),profile.optString("address"),
                        Base64.decode(profile.getString("thumbnail"),Base64.DEFAULT),score)
                } else runnerUp = max(runnerUp,score)
            }
        }
        return best?.takeIf { it.score >= FaceRecognitionEngine.MATCH_THRESHOLD && it.score-runnerUp >= 0.05f }
    }

    fun queue(candidate: Candidate, latitude: Double, longitude: Double, accuracy: Float, timestamp: String) {
        checkOwner()
        val id = UUID.randomUUID().toString()
        val event = JSONObject().put("event_id",id).put("subject",candidate.id).put("revision",candidate.revision)
            .put("verified_at",timestamp).put("latitude",latitude).put("longitude",longitude)
            .put("accuracy_m",accuracy.toDouble()).put("similarity",candidate.score.toDouble()).put("confirmed",true)
            .put("local_subject_name",candidate.name)
        db.execSQL("INSERT INTO events(id,payload) VALUES(?,?)",arrayOf(id,seal(event)))
    }

    fun flush() {
        val items = db.rawQuery("SELECT id,payload FROM events WHERE failed=0 ORDER BY rowid",null).use {
            buildList { while (it.moveToNext()) add(it.getString(0) to open(it.getBlob(1))) }
        }
        for ((id, payload) in items) {
            try {
                request("events/",payload)
                db.delete("events","id=?", arrayOf(id))
            } catch (e: ApiFailure) {
                if (e.code != 409 && e.code != 400) throw e
                payload.put("rejection",e.message)
                db.execSQL("UPDATE events SET failed=1,payload=? WHERE id=?", arrayOf(seal(payload),id))
            }
        }
    }

    fun failedEvents(): List<Pair<String,String>> = db.rawQuery("SELECT id,payload FROM events WHERE failed=1", null).use {
        buildList { while (it.moveToNext()) {
            val row = open(it.getBlob(1))
            add(it.getString(0) to "${row.optString("local_subject_name")} · ${row.optString("rejection")}")
        } }
    }
    fun acknowledgeFailure(id: String) { db.delete("events","id=? AND failed=1",arrayOf(id)) }
}
