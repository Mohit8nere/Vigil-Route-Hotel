package com.vigil.android.core.di.model.dashboard_model

import com.google.gson.annotations.SerializedName

data class RouteVerifiedDetailsResponseModel(

    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String,

    @SerializedName("data")
    val data: List<RouteVerifiedStopModel>? = arrayListOf()

)

data class RouteVerifiedStopModel(

    @SerializedName("officer_id")
    val officerId: Int? = null,

    @SerializedName("stop_id")
    val stopId: Int? = null,

    @SerializedName("stop_name")
    val stopName: String? = "",

    @SerializedName("officer_name")
    val officerName: String? = "",

    @SerializedName("officer_route_join_id")
    val officerRouteJoinId: Int? = null,

    @SerializedName("stop_latitude")
    val stopLatitude: Double? = null,

    @SerializedName("stop_longitude")
    val stopLongitude: Double? = null,

    @SerializedName("verified_time")
    val verifiedTime: String? = ""
)