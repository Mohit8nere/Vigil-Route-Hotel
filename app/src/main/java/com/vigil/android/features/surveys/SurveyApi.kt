package com.vigil.android.features.surveys

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.vigil.android.features.fieldops.FieldOperations
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class AssignedSurvey(val id: Long, val title: String, val instructions: String,
    val location: String, val latitude: Double, val longitude: Double, val radius: Int,
    val status: String, val description: String)

internal data class SurveyPage(val surveys: List<AssignedSurvey>, val hasNext: Boolean)

internal suspend fun FieldOperations.surveys(page: Int): SurveyPage {
    val response = JSONObject(request("surveys/assigned/?page=$page"))
    val rows = response.getJSONArray("results")
    return SurveyPage((0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        AssignedSurvey(row.getLong("id"), row.getString("title"), row.getString("instructions"),
            row.getString("location_name"), row.getDouble("latitude"), row.getDouble("longitude"),
            row.getInt("radius_m"), row.getString("status"), row.getString("description"))
    }, !response.isNull("next"))
}

@SuppressLint("MissingPermission")
internal suspend fun surveyFix(context: Context): Location =
    suspendCancellableCoroutine { cont ->
        val cancel = CancellationTokenSource()
        cont.invokeOnCancellation { cancel.cancel() }
        val request = CurrentLocationRequest.Builder().setMaxUpdateAgeMillis(5000)
            .setDurationMillis(20000).setPriority(Priority.PRIORITY_HIGH_ACCURACY).build()
        LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, cancel.token)
            .addOnSuccessListener { fix ->
                if (!cont.isActive) return@addOnSuccessListener
                if (fix == null || !fix.hasAccuracy() || fix.accuracy !in 0.01f..50f ||
                    SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos !in 0L..90_000_000_000L ||
                    fix.isFromMockProvider) {
                    cont.resumeWithException(IllegalStateException("A fresh, precise GPS fix is needed. Try again outdoors."))
                } else cont.resume(fix)
            }.addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

private fun Location.fields() = mapOf("latitude" to latitude.toString(), "longitude" to longitude.toString(),
    "accuracy_m" to accuracy.toString(), "sampled_at" to Instant.ofEpochMilli(time).toString())

internal suspend fun FieldOperations.arrive(survey: AssignedSurvey, fix: Location) {
    val form = FormBody.Builder()
    fix.fields().forEach { (name, value) -> form.add(name, value) }
    request("surveys/${survey.id}/arrive/", form.build())
}

internal suspend fun FieldOperations.submit(survey: AssignedSurvey, fix: Location, notes: String, photos: List<File>) {
    val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("description", notes)
    fix.fields().forEach { (name, value) -> body.addFormDataPart(name, value) }
    photos.forEachIndexed { index, file ->
        require(file.isFile && file.length() > 0) { "Photo ${index + 1} is missing. Remove it and attach it again." }
        body.addFormDataPart("photos", "survey-${index + 1}.jpg", file.asRequestBody("image/jpeg".toMediaType()))
    }
    request("surveys/${survey.id}/submit/", body.build())
}
