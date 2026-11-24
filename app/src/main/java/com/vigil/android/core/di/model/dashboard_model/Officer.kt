package com.vigil.android.core.di.model.dashboard_model

import com.vigil.android.utils.OfficerStatus

data class Officer(
    val id: String, // Unique identifier for Compose lists and selection
    val officerId: String, // This is the real backend officer ID
    val name: String,
    val badgeId: String,
    val routeName: String,
    val dutyStart: String = "",
    val dutyEnd: String = "",
    val dutyDate: String = "",
    val dutyShift: String = "",
    val status: OfficerStatus = OfficerStatus.UNKNOWN,
    val rank: String = "",
    val phoneNo: String = "",
    val imageUrl: String = "",
    val officerRouteJoinId: String = "0" // Backend officer_route_join_id
)

data class RoutePoint(
    val lat: Double,
    val lng: Double,
    val timestamp: String, // e.g. "09:45 AM"
    val address: String? = null,
    val isVerified: Boolean? = null, // null for raw logs, true/false for verified stops
    val rawTime: Long = 0L, // Timestamp in millis for sorting
    val displayDate: String? = null, // e.g. "29-07-2026"
    val displayTime: String? = null  // e.g. "17:12:08 PM"
)
