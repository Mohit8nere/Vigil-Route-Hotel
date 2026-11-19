package com.vigil.android.core.di.model.dashboard_model

import com.google.gson.annotations.SerializedName

data class RouteLocationLogsResponseModel(

    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String,

    @SerializedName("data")
    val data: List<RouteLocationLog>? = arrayListOf()
)

data class RouteLocationLog(

    @SerializedName("location_id")
    val locationId: Int? = null,

    @SerializedName("officer_id")
    val officerId: Int? = null,

    @SerializedName("officer_route_join_id")
    val officerRouteJoinId: Int? = null,

    @SerializedName("officer_name")
    val officerName: String? = "",

    @SerializedName("latitude")
    val latitude: String? = null ,

    @SerializedName("longitude")
    val longitude: String? = null,

    @SerializedName("recorded_at")
    val recordedAt: String? = ""
)