package com.vigil.android.presentation.verification

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.common.session.SessionInterceptor
import com.vigil.android.core.di.api.ApiNames
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs
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
    val category: String, val address: String, val thumbnail: ByteArray, val score: Float, val matchKey: String
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
    // Keep the original PatrolOfficer-scoped database/key so existing queues
    // remain available. JWT user_id is the distinct LoginUser receipt owner.
    private val patrolPrefs = Prefs(context)
    private val owner = checkNotNull(patrolPrefs.getPatrolId()) { "Sign in again to open verification." }
    private val accountId = checkNotNull(tokenAccount(session.fetchAuthToken())) { "Sign in again to open verification." }
    init { require(session.getRole() == "Patrol Officer") { "Patrol Officer access required." } }

    private val scopeId = MessageDigest.getInstance("SHA-256")
        .digest("${ApiNames.BASE_URL}|$owner".toByteArray()).joinToString("") { "%02x".format(it) }
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .addInterceptor(SessionInterceptor(session))
        .addNetworkInterceptor { chain ->
            checkOwner()
            val sent = chain.request().header("Authorization")
            if (sent != "Bearer ${session.fetchAuthToken()}") throw IOException("Session changed. Retry the operation.")
            chain.proceed(chain.request())
        }.build()
    private val cache = Cache(context, scopeId)
    private var closed = false
    private val db get(): SQLiteDatabase {
        check(!closed) { "Verification screen closed." }
        return cache.writableDatabase
    }
    private val key by lazy { storageKey("vigil.verification.$scopeId") }

    companion object {
        private val syncLock = Any()

        // Claims bind local work to the account whose token the server verifies;
        // this decoding is not a substitute for server JWT authentication.
        private fun tokenAccount(token: String?): Long? = runCatching {
            val parts = token.orEmpty().split('.')
            if (parts.size != 3) return@runCatching null
            val claims = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            if (claims.optString("scope") != "mobile" || claims.optString("role") != "Patrol Officer") return@runCatching null
            claims.opt("user_id")?.toString()?.toLongOrNull()?.takeIf { it > 0 }
        }.getOrNull()

        @Synchronized private fun storageKey(alias: String): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec); generateKey() }
        }
    }

    @Synchronized override fun close() { closed = true; cache.close() }

    private fun checkOwner() {
        check(!closed && session.getRole() == "Patrol Officer" && patrolPrefs.getPatrolId() == owner &&
            session.isLoggedIn() && tokenAccount(session.fetchAuthToken()) == accountId) {
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

    private fun request(path: String, body: JSONObject? = null, acceptedCodes: Set<Int>? = null): ByteArray {
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
            if (acceptedCodes != null && response.code !in acceptedCodes) {
                throw IOException("Confirmation receipt is incomplete. The saved confirmation will be retried.")
            }
            response.body?.bytes() ?: throw IOException("Empty response.")
        }
    }

    @Synchronized fun state(name: String): JSONObject {
        checkOwner()
        return db.rawQuery("SELECT payload FROM state WHERE name=?", arrayOf(name)).use {
            if (it.moveToFirst()) open(it.getBlob(0)) else JSONObject()
        }
    }

    @Synchronized fun saveState(name: String, value: JSONObject) {
        checkOwner()
        db.execSQL("INSERT OR REPLACE INTO state(name,payload) VALUES(?,?)", arrayOf(name,seal(value)))
    }

    private fun scalar(query: String): Int = db.rawQuery(query,null).use { it.moveToFirst(); it.getInt(0) }
    private fun compatibleCatalogue(catalogue: JSONObject = state("catalogue")): Boolean = catalogue.optBoolean("captures_reference_id") && VerificationContract.compatibleCache(
        catalogue.optLong("version", -1), catalogue.optInt("pipeline", -1),
        FaceRecognitionEngine.EMBEDDING_PIPELINE_REVISION, catalogue.optLong("account_id", -1), accountId)

    @Synchronized fun summary(): LocalSummary {
        val catalogue = state("catalogue")
        val compatible = compatibleCatalogue(catalogue)
        return LocalSummary(if (compatible) catalogue.getLong("version") else -1,
            if (compatible) catalogue.optInt("usable_count",0).coerceAtLeast(0) else 0,
            scalar("SELECT COUNT(*) FROM events WHERE failed=0"), scalar("SELECT COUNT(*) FROM events WHERE failed=1"),
            if (compatible) catalogue.optString("unusable") else "")
    }

    @Synchronized fun refreshStatus(): JSONObject {
        val result = JSONObject(String(request("status/"), Charsets.UTF_8))
        require(result.getLong("version") >= 0 && result.getJSONArray("assignments").objects().all {
            it.getLong("id") > 0 && it.getLong("subject") > 0
        }) { "The server returned an invalid verification status." }
        saveState("status",result)
        return result
    }

    @Synchronized fun getOfficerHistory(officerId: String, startDate: String, endDate: String): JSONObject {
        return JSONObject(String(request("officer-history/?officer_id=$officerId&start_date=$startDate&end_date=$endDate"), Charsets.UTF_8))
    }

    fun getOfficerId(): String {
        checkOwner()
        return accountId.toString()
    }

    private fun referenceVector(encoded: String): FloatArray {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size == FaceRecognitionEngine.EMBEDDING_SIZE * 4) { "Invalid saved reference. Sync again." }
        val buffer = ByteBuffer.wrap(bytes).asFloatBuffer()
        return FloatArray(buffer.remaining()).also {
            buffer.get(it)
            require(VerificationContract.validEmbedding(it, FaceRecognitionEngine.EMBEDDING_SIZE)) {
                "Invalid saved reference. Sync again."
            }
        }
    }

    private fun reusablePayload(payload: ByteArray, id: Long, revision: Long): Boolean = runCatching {
        val data = open(payload)
        data.getLong("id") == id && data.getLong("revision") == revision &&
            data.optInt("pipeline", -1) == FaceRecognitionEngine.EMBEDDING_PIPELINE_REVISION &&
            data.getJSONArray("vectors").let { vectors ->
                vectors.length() in 0..5 && (0 until vectors.length()).all { referenceVector(vectors.getString(it)).isNotEmpty() } &&
                    (vectors.length() == 0 || Base64.decode(data.getString("thumbnail"), Base64.DEFAULT).isNotEmpty())
            }
    }.getOrDefault(false)

    // Build a staged catalogue, then flip the complete snapshot in a single local transaction.
    // Invalid enrolment photos are marked unavailable without preventing unrelated removals.
    @Synchronized fun sync(onProgress: (Int, Int) -> Unit) = synchronized(syncLock) {
        val manifest = JSONObject(String(request("manifest/"),Charsets.UTF_8))
        val subjects = manifest.getJSONArray("subjects").objects()
        val version = manifest.getLong("version")
        require(version >= 0 && subjects.map { it.getLong("id") }.distinct().size == subjects.size) {
            "The server returned an invalid reference manifest."
        }
        val mayReuse = compatibleCatalogue()
        val engine = FaceRecognitionEngine.getInstance(context)
        db.execSQL("DELETE FROM stage")
        val unusable = mutableListOf<String>()
        val vectorCounts = mutableListOf<Int>()
        for ((index, subject) in subjects.withIndex()) {
            checkOwner()
            val id = subject.getLong("id")
            val revision = subject.getLong("revision")
            val photos = subject.getJSONArray("photos").objects()
            require(id > 0 && revision in 1..version && subject.getString("name").isNotBlank() &&
                photos.size in 1..5 && photos.all { it.getLong("id") > 0 } &&
                photos.map { it.getLong("id") }.distinct().size == photos.size) {
                "The server returned an incomplete reference."
            }
            val cached = if (mayReuse) db.rawQuery("SELECT payload FROM profiles WHERE id=? AND revision=?", arrayOf(id.toString(),revision.toString())).use {
                if (it.moveToFirst()) it.getBlob(0) else null
            }?.takeIf { reusablePayload(it, id, revision) } else null
            val payload = cached ?: run {
                val vectors = JSONArray()
                val photoIds = JSONArray()
                val previews = JSONArray()
                var thumb = ""
                for (photo in photos) {
                    val bytes = request("photos/${photo.getLong("id")}/")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 24_000_000) { "Unreadable reference image." }
                    // The backend applies EXIF transpose before returning its JPEG.
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Unreadable reference.")
                    try {
                        val vector = try { engine.extractEmbedding(bitmap) } catch (e: FaceRecognitionEngine.FaceRecognitionException) {
                            // The detector reports zero/multiple faces as an enrolment quality issue.
                            if (e.message?.startsWith("No face") == true || e.message?.startsWith("Multiple faces") == true) continue
                            throw e
                        }
                        require(VerificationContract.validEmbedding(vector, FaceRecognitionEngine.EMBEDDING_SIZE)) {
                            "The reference produced an invalid face representation. Retake its photo."
                        }
                        val buffer = ByteBuffer.allocate(vector.size * 4)
                        for (value in vector) buffer.putFloat(value)
                        vectors.put(Base64.encodeToString(buffer.array(),Base64.NO_WRAP))
                        photoIds.put(photo.getLong("id"))
                        run {
                            val scale = 180f / max(bitmap.width,bitmap.height).coerceAtLeast(180)
                            val small = Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).roundToInt().coerceAtLeast(1),
                                (bitmap.height*scale).roundToInt().coerceAtLeast(1),true)
                            val out = ByteArrayOutputStream()
                            small.compress(Bitmap.CompressFormat.JPEG,82,out)
                            if (small !== bitmap) small.recycle()
                            val preview = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            previews.put(preview)
                            if (thumb.isEmpty()) thumb = preview
                        }
                    } finally { bitmap.recycle() }
                }
                subject.remove("photos")
                subject.put("vectors",vectors).put("thumbnail",thumb).put("photo_ids",photoIds).put("previews",previews)
                    .put("pipeline", FaceRecognitionEngine.EMBEDDING_PIPELINE_REVISION)
                seal(subject)
            }
            val count = open(payload).getJSONArray("vectors").length()
            vectorCounts.add(count)
            if (count == 0) unusable.add(subject.getString("name"))
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
            saveState("catalogue",JSONObject().put("version",version).put("unusable",unusable.joinToString(", "))
                .put("pipeline",FaceRecognitionEngine.EMBEDDING_PIPELINE_REVISION).put("account_id",accountId)
                .put("captures_reference_id", true).put("usable_count",VerificationContract.usableReferenceCount(vectorCounts)))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun purgeReferences() {
        // Pending confirmations remain encrypted for delivery by this officer after the next login.
        db.beginTransaction()
        try {
            for (table in listOf("profiles","stage","state")) db.delete(table,null,null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun requireReadyCatalogue() {
        val catalogue = state("catalogue")
        check(compatibleCatalogue(catalogue)) { "Sync the reference database before checking a face." }
        check(state("status").optLong("version", catalogue.getLong("version")) == catalogue.getLong("version")) {
            "The reference database changed. Sync before checking a face."
        }
        check(catalogue.optInt("usable_count",0) > 0) { "No usable reference photos are saved. Ask the administrator to replace the photos, then sync." }
    }

    @Synchronized fun match(bitmap: Bitmap, selectedId: Long?): Candidate? {
        checkOwner()
        requireReadyCatalogue()
        if (selectedId != null) {
            check(getProfile(selectedId)?.optJSONArray("vectors")?.length()?.let { it > 0 } == true) {
                "This record has no usable reference photo. Ask the administrator to replace its photos, then sync."
            }
        }
        val engine = FaceRecognitionEngine.getInstance(context)
        val detection = engine.detectSingleFace(bitmap)
        check(FaceAntiSpoofEngine.getInstance(context).predict(bitmap, detection).isLive) {
            "Live face check was inconclusive. Retake the photo in better light."
        }
        val probe = engine.extractEmbedding(bitmap,detection)
        require(VerificationContract.validEmbedding(probe, FaceRecognitionEngine.EMBEDDING_SIZE)) { "Retake the live face photo." }
        val scores = mutableListOf<VerificationContract.Score>()
        var bestProfile: JSONObject? = null
        var bestScore = -1f
        var bestIndex = 0
        // Compare against all usable people, even for an assigned target, so
        // selecting a record cannot hide a stronger or ambiguous alternative.
        db.rawQuery("SELECT id,revision,payload FROM profiles", null).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = cursor.getBlob(2)
                check(reusablePayload(payload, cursor.getLong(0), cursor.getLong(1))) { "A saved reference is invalid. Sync again." }
                val profile = open(payload)
                val vectors = profile.getJSONArray("vectors")
                if (vectors.length() == 0) continue
                var score = -1f
                var index = 0
                for (i in 0 until vectors.length()) {
                    val reference = referenceVector(vectors.getString(i))
                    val similarity = engine.cosineSimilarity(probe,reference)
                    if (similarity > score) { score = similarity; index = i }
                }
                scores.add(VerificationContract.Score(profile.getLong("id"), score))
                if (bestProfile == null || score > bestScore) { bestProfile = profile; bestScore = score; bestIndex = index }
            }
        }
        checkOwner()
        val best = VerificationContract.select(scores, selectedId, FaceRecognitionEngine.MATCH_THRESHOLD, 0.05f) ?: return null
        val profile = checkNotNull(bestProfile).also { check(it.getLong("id") == best.subjectId) }
        val id = UUID.randomUUID().toString()
        val scale = 800f / max(bitmap.width, bitmap.height).coerceAtLeast(800)
        val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width*scale).roundToInt().coerceAtLeast(1),
            (bitmap.height*scale).roundToInt().coerceAtLeast(1), true)
        val capture = ByteArrayOutputStream().use { out ->
            try { check(resized.compress(Bitmap.CompressFormat.JPEG, 78, out)); Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) }
            finally { if (resized !== bitmap) resized.recycle() }
        }
        val report = JSONObject().put("event_key",id).put("subject",best.subjectId)
            .put("revision",profile.getLong("revision")).put("reference_key",profile.getJSONArray("photo_ids").getLong(bestIndex))
            .put("source", if (selectedId == null) "General scan" else "Assigned verification")
            .put("matched_at", VerificationContract.timestamp(java.time.Instant.now().toString()))
            .put("score",best.similarity.toDouble()).put("capture",capture)
        enqueueReport(id, "match", report, profile.getString("name"))
        return Candidate(profile.getLong("id"),profile.getLong("revision"),profile.getString("name"),
            profile.optString("description"),profile.optString("category_name"),profile.optString("address"),
            Base64.decode(profile.getJSONArray("previews").getString(bestIndex),Base64.DEFAULT),best.similarity,id)
    }

    private fun enqueueReport(id: String, action: String, body: JSONObject, name: String) {
        val item = JSONObject().put("local_action", action).put("body", body)
            .put("local_account_id", accountId).put("local_subject_name", name)
        db.execSQL("INSERT INTO events(id,payload) VALUES(?,?)",arrayOf(id,seal(item)))
    }

    @Synchronized fun reject(candidate: Candidate) {
        checkOwner()
        enqueueReport(UUID.randomUUID().toString(), "reject", JSONObject().put("match_key",candidate.matchKey)
            .put("reviewed_at", VerificationContract.timestamp(java.time.Instant.now().toString())), candidate.name)
    }

    @Synchronized fun queue(candidate: Candidate, latitude: Double, longitude: Double, accuracy: Float, timestamp: String) {
        checkOwner()
        requireReadyCatalogue()
        val reference = getProfile(candidate.id)
        check(reference != null && reference.getLong("revision") == candidate.revision &&
            reference.getString("name") == candidate.name && reference.getJSONArray("vectors").length() > 0) {
            "This reference changed. Sync and verify the person again."
        }
        val id = UUID.randomUUID().toString()
        val evidence = VerificationContract.Event(id, candidate.id, candidate.revision, candidate.name,
            VerificationContract.timestamp(timestamp), latitude, longitude, accuracy.toDouble(), candidate.score.toDouble())
        require(VerificationContract.validEvent(evidence, freshCapture = true)) {
            "A valid match, current device time and location accurate to 100 metres are required."
        }
        val event = JSONObject().put("event_id",id).put("match_key",candidate.matchKey).put("subject",candidate.id).put("revision",candidate.revision)
            .put("verified_at",evidence.verifiedAt).put("latitude",latitude).put("longitude",longitude)
            .put("accuracy_m",accuracy.toDouble()).put("similarity",candidate.score.toDouble()).put("confirmed",true)
            .put("local_subject_name",candidate.name).put("local_account_id",accountId)
        checkOwner()
        db.execSQL("INSERT INTO events(id,payload) VALUES(?,?)",arrayOf(id,seal(event)))
    }

    private fun evidence(payload: JSONObject) = VerificationContract.Event(
        payload.getString("event_id"),payload.getLong("subject"),payload.getLong("revision"),
        payload.optString("local_subject_name"),payload.getString("verified_at"),payload.getDouble("latitude"),
        payload.getDouble("longitude"),payload.getDouble("accuracy_m"),payload.getDouble("similarity"))

    private fun receipt(bytes: ByteArray): VerificationContract.Receipt {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.has("subject")) { "Missing receipt subject." }
        fun integer(name: String): Long = requireNotNull(json.get(name).toString().toLongOrNull()) { "Invalid receipt $name." }
        return VerificationContract.Receipt(integer("id"), if (json.isNull("subject")) null else integer("subject"),
            json.getString("subject_name"),integer("officer"),json.getString("verified_at"),
            json.getDouble("latitude"),json.getDouble("longitude"),json.getDouble("accuracy_m"),
            if (json.has("event_id")) json.getString("event_id") else null,
            if (json.has("revision")) integer("revision") else null)
    }

    private fun holdForReview(id: String, payload: JSONObject, reason: String?) {
        payload.put("rejection",reason ?: "The confirmation needs review.")
        db.execSQL("UPDATE events SET failed=1,payload=? WHERE id=?", arrayOf(seal(payload),id))
    }

    @Synchronized fun flush() {
        checkOwner()
        // Keep captures out of large CursorWindow result sets and process each item in order.
        val ids = db.rawQuery("SELECT id FROM events WHERE failed=0 ORDER BY rowid",null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        for (id in ids) {
            val payload = db.rawQuery("SELECT payload FROM events WHERE id=? AND failed=0", arrayOf(id)).use {
                if (it.moveToFirst()) open(it.getBlob(0)) else null
            } ?: continue
            checkOwner()
            // Legacy events remain eligible in the original origin + unique
            // PatrolOfficer scope. Hold only an explicit account mismatch.
            if (payload.has("local_account_id") && payload.get("local_account_id").toString().toLongOrNull() != accountId) {
                holdForReview(id,payload,"This confirmation belongs to another login account. It has been retained for review.")
                continue
            }
            if (payload.has("local_action")) {
                val body = payload.getJSONObject("body")
                val reject = payload.getString("local_action") == "reject"
                val path = if (reject) "matches/${body.getString("match_key")}/reject/" else "matches/"
                try {
                    val reply = JSONObject(String(request(path, body, setOf(200,201)), Charsets.UTF_8))
                    check(if (reject) reply.optString("status") == "rejected" else reply.optString("event_key") == id)
                    db.delete("events","id=?", arrayOf(id))
                } catch (e: ApiFailure) {
                    if (e.code !in setOf(400,404,409,413)) throw e
                    holdForReview(id,payload,e.message)
                }
                continue
            }
            val saved = runCatching { evidence(payload) }.getOrNull()
            if (saved == null || saved.eventId != id || !payload.optBoolean("confirmed") ||
                !VerificationContract.validEvent(saved)) {
                holdForReview(id,payload,"The saved confirmation contains invalid details. Verify the person again.")
                continue
            }
            try {
                val outgoing = JSONObject(payload.toString()).apply {
                    remove("local_subject_name"); remove("local_account_id"); remove("rejection")
                }
                val response = request("events/",outgoing,setOf(200,201))
                val ack = runCatching { receipt(response) }.getOrElse {
                    throw IOException("The server did not return a valid confirmation receipt. The saved record will be retried.",it)
                }
                if (!VerificationContract.receiptMatches(saved,ack,accountId)) {
                    throw IOException("The server receipt does not match this confirmation. The saved record has been retained.")
                }
                checkOwner()
                db.delete("events","id=?", arrayOf(id))
            } catch (e: ApiFailure) {
                if (e.code !in setOf(409,400,404)) throw e
                holdForReview(id,payload,e.message)
            }
        }
    }

    @Synchronized fun failedEvents(): List<Pair<String,String>> {
        checkOwner()
        return db.rawQuery("SELECT id,payload FROM events WHERE failed=1", null).use {
            buildList { while (it.moveToNext()) {
                val row = open(it.getBlob(1))
                add(it.getString(0) to "${row.optString("local_subject_name")} · ${row.optString("rejection")}")
            } }
        }
    }
    @Synchronized fun acknowledgeFailure(id: String) { checkOwner(); db.delete("events","id=? AND failed=1",arrayOf(id)) }

    @Synchronized fun getProfile(subjectId: Long): JSONObject? {
        checkOwner()
        if (!compatibleCatalogue()) return null
        db.rawQuery("SELECT payload FROM profiles WHERE id=?", arrayOf(subjectId.toString())).use { cursor ->
            if (cursor.moveToFirst()) {
                return open(cursor.getBlob(0))
            }
        }
        return null
    }
}
