package com.vigil.android.features.fieldops

import android.content.Context
import androidx.lifecycle.ViewModel
import com.vigil.android.core.common.network.NetworkClient
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.api.ApiNames
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal class SubmissionConflict(message: String) : IOException(message)

/** Sensitive submissions use the logged-in session without an HTTP body recorder. */
@HiltViewModel
class FieldOperations @Inject constructor(
    @ApplicationContext context: Context,
    private val session: SessionManager
) : ViewModel() {
    private val account = session.getUserId()
    val drafts = File(context.cacheDir, "field_drafts/" + account.orEmpty().filter(Char::isDigit)).apply {
        mkdirs()
        val expires = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        listFiles()?.filter { it.lastModified() < expires }?.forEach { it.delete() }
    }
    private val http = NetworkClient.create(context, session).newBuilder()
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private suspend fun request(path: String, upload: RequestBody? = null): String = withContext(Dispatchers.IO) {
        if (account == null || account != session.getUserId() || !session.isLoggedIn()) throw IOException("Please sign in again.")
        val builder = Request.Builder().url(ApiNames.BASE_URL.trimEnd('/') + "/api/v1/" + path)
            .header("Accept", "application/json")
        if (upload != null) builder.post(upload)
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(body) }.getOrNull()
                val detail = error?.optString("detail")?.takeIf { it.isNotBlank() }
                    ?: error?.let { json -> json.keys().asSequence().firstOrNull()?.let { key -> "$key: ${json.opt(key)}" } }
                if (response.code == 409) throw SubmissionConflict(detail ?: "A previous submission was already saved.")
                throw IOException(detail?.take(350) ?: "Server returned ${response.code}. Please try again.")
            }
            body
        }
    }

    suspend fun hotelName() = JSONObject(request("hotels/me/")).getString("name")

    suspend fun entries(date: String): List<String> {
        val rows = JSONArray(request("hotels/entries/?date=$date"))
        return (0 until rows.length()).map {
            val row = rows.getJSONObject(it)
            "${row.getString("guest_name")} • ${row.getInt("guest_count")} guest(s)\n" +
                "${row.getString("coming_from")}  ·  ${row.getString("mobile")}"
        }
    }

    suspend fun submitEntry(fields: Map<String, String>, files: List<File>) {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (key, value) -> form.addFormDataPart(key, value) }
        files.forEachIndexed { index, file ->
            check(file.isFile && file.length() > 0) { "Document ${index + 1} is missing. Please attach it again." }
            form.addFormDataPart("documents", "document-${index + 1}.jpg", file.asRequestBody("image/jpeg".toMediaType()))
        }
        request("hotels/entries/", form.build())
    }

    suspend fun submitChallan(clientId: String, file: File): Long {
        check(file.isFile && file.length() > 0) { "The photograph is missing. Please take it again." }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("client_id", clientId)
            .addFormDataPart("photo", "traffic-evidence.jpg", file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        return JSONObject(request("challans/", form)).getLong("id")
    }

    suspend fun challans(): List<String> {
        val rows = JSONArray(request("challans/"))
        return (0 until rows.length()).map {
            val row = rows.getJSONObject(it)
            if (row.getString("status") == "issued")
                "${row.getString("challan_number")} • ${row.getString("vehicle_number")}\n${row.getString("violation")}  ·  ₹${row.getString("amount")}"
            else "Photo #${row.getLong("id")} • Pending review"
        }
    }

    override fun onCleared() {
        http.dispatcher.cancelAll()
        super.onCleared()
    }
}
