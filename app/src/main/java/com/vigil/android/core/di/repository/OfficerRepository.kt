package com.vigil.android.core.di.repository

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.vigil.android.core.di.api.ApiService
import com.vigil.android.core.di.model.dashboard_model.*
import retrofit2.HttpException
import com.vigil.android.utils.OfficerStatus
import com.vigil.android.utils.toTitleCase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfficerRepository @Inject constructor(
    private val apiService: ApiService,
    @param:ApplicationContext private val context: Context
) {
    private val displayTimeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val dateOnlyFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    private val timeOnlyFormat = SimpleDateFormat("HH:mm:ss a", Locale.getDefault())
    private val customRouteFormat =
        SimpleDateFormat("'date' dd-MM-yyyy 'time -' HH:mm:ss a", Locale.getDefault())
    private val geocoder = Geocoder(context, Locale.getDefault())

    suspend fun getOfficers(startDate: String, endDate: String): List<Officer> {
        val response = apiService.getOfficersList(OfficerRouteMasterRequest(startDate, endDate))
        if (!response.isSuccessful) throw HttpException(response)
        return response.body()?.data.orEmpty().map { dto ->
            Officer(
                id = dto.officer_route_join_id.toString(),
                officerId = dto.officer_id.toString(),
                officerRouteJoinId = dto.officer_route_join_id.toString(),
                name = dto.officer_name?.toTitleCase().orEmpty(),
                badgeId = dto.badge_number.orEmpty(),
                status = when (dto.status) {
                    0 -> OfficerStatus.SCHEDULED
                    1 -> OfficerStatus.ON_DUTY
                    2 -> OfficerStatus.OFF_DUTY
                    else -> OfficerStatus.UNKNOWN
                },
                rank = dto.officer_rank?.toTitleCase().orEmpty(),
                phoneNo = dto.officer_phone_no.orEmpty(),
                imageUrl = dto.officer_image.orEmpty(),
                routeName = dto.route_name?.toTitleCase() ?: "Unavailable",
                dutyStart = dto.duty_start?.substringBefore("T").orEmpty(),
                dutyEnd = dto.duty_end?.substringBefore("T").orEmpty(),
                dutyDate = dto.duty_date.orEmpty(),
                dutyShift = dto.duty_shift?.toTitleCase().orEmpty()
            )
        }
    }

    suspend fun getOfficerRouteLogs(
        officerId: String,
        startDate: String,
        endDate: String,
        officerRouteJoinId: String
    ): List<RoutePoint> {
        val response = apiService.routeLocationLogsList(officerId, startDate, endDate, officerRouteJoinId)
        if (!response.isSuccessful) throw HttpException(response)
        return response.body()?.data.orEmpty().filter { it.officerRouteJoinId?.toString() == officerRouteJoinId }
            .mapNotNull(::mapLogToPoint).sortedBy { it.rawTime }
    }

    suspend fun getOfficerVerifiedStops(
        officerId: String,
        startDate: String,
        endDate: String,
        officerRouteJoinId: String
    ): List<RoutePoint> {
        val response = apiService.routeVerifiedDetailsList(RouteStopsRequest(officerId, startDate, endDate, officerRouteJoinId))
        if (!response.isSuccessful) throw HttpException(response)
        return response.body()?.data.orEmpty().mapNotNull { stop ->
            val lat = stop.stopLatitude ?: return@mapNotNull null
            val lng = stop.stopLongitude ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
            val time = parseTime(stop.verifiedTime)
            RoutePoint(
                lat = lat, lng = lng, timestamp = time?.let { customRouteFormat.format(it) } ?: "Unavailable",
                address = (stop.stopName ?: "Unnamed stop").toTitleCase(),
                isVerified = !stop.verifiedTime.isNullOrBlank(), rawTime = time?.time ?: 0L,
                displayDate = time?.let { dateOnlyFormat.format(it) },
                displayTime = time?.let { timeOnlyFormat.format(it) }
            )
        }
    }

    private fun parseTime(text: String?): java.util.Date? {
        if (text.isNullOrBlank()) return null
        val instant = runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant() }.getOrNull()
        return instant?.let { java.util.Date.from(it) }
    }

    fun getAddress(lat: Double, lng: Double): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION") /** todo its not work on new devices please remove
                            the all deprecated code from here
                   */
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val sb = StringBuilder()
                    for (i in 0..address.maxAddressLineIndex) {
                        sb.append(address.getAddressLine(i)).append(" ")
                    }
                    sb.toString().trim().toTitleCase()
                } else {
                    "Unknown Location"
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val sb = StringBuilder()
                    for (i in 0..address.maxAddressLineIndex) {
                        sb.append(address.getAddressLine(i)).append(" ")
                    }
                    sb.toString().trim().toTitleCase()
                } else {
                    "Unknown Location"
                }
            }
        } catch (e: Exception) {
            "Location Name Unavailable"
        }
    }

    private fun mapLogToPoint(log: RouteLocationLog): RoutePoint? {
        val lat = log.latitude?.toDoubleOrNull() ?: return null
        val lng = log.longitude?.toDoubleOrNull() ?: return null

        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        val date = parseTime(log.recordedAt)

        val time = date?.let { displayTimeFormat.format(it) } ?: "Unknown"

        return RoutePoint(
            lat = lat,
            lng = lng,
            timestamp = time,
            rawTime = date?.time ?: 0L,
            displayDate = date?.let { dateOnlyFormat.format(it) },
            displayTime = date?.let { timeOnlyFormat.format(it) }
        )
    }
}
