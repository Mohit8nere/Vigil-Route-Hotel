package com.vigil.android.core.di.repository

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.vigil.android.core.di.api.ApiService
import com.vigil.android.core.di.model.dashboard_model.*
import com.vigil.android.presentation.feature_dashboard.officer_dashboard_screen.dummy_data.DummyOfficerData
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
    private val tag = "OfficerRepository"
    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    private val displayTimeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val dateOnlyFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    private val timeOnlyFormat = SimpleDateFormat("HH:mm:ss a", Locale.getDefault())
    private val customRouteFormat =
        SimpleDateFormat("'date' dd-MM-yyyy 'time -' HH:mm:ss a", Locale.getDefault())
    private val geocoder = Geocoder(context, Locale.getDefault())

    suspend fun getOfficers(startDate: String, endDate: String): List<Officer> {
        return try {
            val request = OfficerRouteMasterRequest(startDate, endDate)
            val response = apiService.getOfficersList(request)
            if (response.isSuccessful) {
                val data = response.body()?.data
                Log.d(tag, "API Success: Received ${data?.size ?: 0} items")
                
                data?.mapIndexed { index, dto ->
                    try {
                        val dummyMatch = DummyOfficerData.officers.find { it.badgeId == dto.badge_number }

                        val startDt = dto.duty_start?.split("T")?.firstOrNull() ?: ""
                        val endDt = dto.duty_end?.split("T")?.firstOrNull() ?: ""

                        Officer(
                            id = "${dto.officer_route_join_id}_${dto.officer_id}_$index",
                            officerId = dto.officer_id.toString(),
                            officerRouteJoinId = dto.officer_route_join_id.toString(),
                            name = dto.officer_name.toTitleCase(),
                            badgeId = dto.badge_number,
                            status = when (dto.status) {
                                1 -> OfficerStatus.ON_DUTY
                                2 -> OfficerStatus.OFF_DUTY
                                else -> OfficerStatus.ON_DUTY
                            },
                            rank = dto.officer_rank.toTitleCase(),
                            phoneNo = dto.officer_phone_no,
                            imageUrl = dto.officer_image ?: "",
                            routeName = (dto.route_name ?: "N/A").toTitleCase(),
                            patrolId = dummyMatch?.patrolId ?: "N/A",

                            lastActiveDate = dummyMatch?.lastActiveDate ?: "Active Now",
                            currentLat = dummyMatch?.currentLat ?: 22.7196,
                            currentLng = dummyMatch?.currentLng ?: 75.8577,
                            dutyStart = startDt,
                            dutyEnd = endDt,
                            dutyDate = dto.duty_date ?: "N/A",
                            dutyShift = (dto.duty_shift ?: "N/A").toTitleCase()
                        )
                    } catch (e: Exception) {
                        Log.e(tag, "Mapping error at index $index: ${e.message}")
                        throw e
                    }
                } ?: emptyList()
            } else {
                Log.e(tag, "API Error: ${response.code()} - ${response.errorBody()?.string()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(tag, "getOfficers exception: ${e.message}")
            emptyList()
        }
    }

    suspend fun getOfficerRouteLogs(
        officerId: String,
        startDate: String,
        endDate: String
    ): List<RoutePoint> {
        Log.d(tag, "getOfficerRouteLogs: Requesting for ID=$officerId from $startDate to $endDate")
        return try {
            val response = apiService.routeLocationLogsList(officerId, startDate, endDate)
            if (response.isSuccessful) {
                Log.d(tag, "getOfficerRouteLogs success: ${response.body()?.data?.size ?: 0} points found")
                response.body()?.data
                    ?.sortedBy { it.recordedAt }
                    ?.mapNotNull { log ->
                        mapLogToPoint(log)
                    } ?: emptyList()
            } else {
                Log.e(tag, "getOfficerRouteLogs API error: ${response.code()} - ${response.message()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(tag, "getOfficerRouteLogs exception: ${e.message}")
            emptyList()
        }
    }

    suspend fun getOfficerVerifiedStops(
        officerId: String,
        startDate: String,
        endDate: String,
        officerRouteJoinId: String
    ): List<RoutePoint> {
        return try {
            val request = RouteStopsRequest(officerId, startDate, endDate, officerRouteJoinId)
            val response = apiService.routeVerifiedDetailsList(request)
            if (response.isSuccessful) {
                response.body()?.data?.map { stop ->
                    val rawDate = try { apiDateFormat.parse(stop.verifiedTime ?: "") } catch (e: Exception) { null }
                    val formattedTime = rawDate?.let { customRouteFormat.format(it) } ?: "Pending"

                    RoutePoint(
                        lat = stop.stopLatitude ?: 0.0,
                        lng = stop.stopLongitude ?: 0.0,
                        timestamp = formattedTime,
                        address = (stop.stopName ?: "Unnamed Stop").toTitleCase(),
                        isVerified = stop.verifiedTime != null,
                        rawTime = rawDate?.time ?: 0L,
                        displayDate = rawDate?.let { dateOnlyFormat.format(it) },
                        displayTime = rawDate?.let { timeOnlyFormat.format(it) }
                    )
                } ?: emptyList()
            } else {
                Log.e(tag, "API Error: ${response.code()} - ${response.errorBody()?.string()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(tag, "getOfficers exception: ${e.message}")
            emptyList()
        }
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

        val date = try {
            apiDateFormat.parse(log.recordedAt ?: "")
        } catch (e: Exception) {
            null
        }

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
