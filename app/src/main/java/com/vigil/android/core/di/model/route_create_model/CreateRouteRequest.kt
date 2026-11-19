package com.vigil.android.core.di.model.route_create_model

import com.google.gson.annotations.SerializedName

data class CreateRouteRequestModel(
    @SerializedName("route_name")
    val routeName: String? = "",

    @SerializedName("route_description")
    val routeDescription: String? = "",

    @SerializedName("station_id")
    val stationId: Int? = null,

    val stops: ArrayList<CreateRouteStopRequest> = arrayListOf()
)

data class CreateRouteStopRequest(
    @SerializedName("stop_name")
    val stopName: String? = "",

    @SerializedName("stop_order")
    val stopOrder: Int? = null,

    val latitude: Double? = null,
    val longitude: Double? = null,
    val status: Int? = null,
    val frequency: Int? = null,

    @SerializedName("frequency_duration")
    val frequencyDuration: Int? = null
)

data class CreateRouteResponseModel(
    val success: Boolean,
    val message: String? = "",
    val data: CreateRouteData = CreateRouteData()
)

data class CreateRouteData(
    @SerializedName("route_id")
    val routeId: Int? = null,

    val stops: ArrayList<RouteStopResponse>? = arrayListOf(),

    @SerializedName("station_name")
    val stationName: String? = "",

    @SerializedName("route_name")
    val routeName: String? = "",

    @SerializedName("route_description")
    val routeDescription: String? = ""
)

data class RouteStopResponse(
    @SerializedName("stop_id")
    val stopId: Int? = null,

    @SerializedName("stop_name")
    val stopName: String? = "",

    @SerializedName("stop_hash")
    val stopHash: String? = "",

    @SerializedName("stop_order")
    val stopOrder: Int? = null,

    val latitude: String? = "",
    val longitude: String? = "",
    val status: Int? = null,

    @SerializedName("frequency_duration")
    val frequencyDuration: Int? = null,

    val frequency: Int? = null
)

data class UpdateRouteRequestModel(
    @SerializedName("route_name")
    val routeName: String? = "",

    @SerializedName("route_description")
    val routeDescription: String? = "",

    val stops: ArrayList<UpdateRouteStopRequest> = arrayListOf()
)

data class UpdateRouteStopRequest(
    @SerializedName("stop_id")
    val stopId: Long? = null,

    @SerializedName("stop_name")
    val stopName: String? = "",

    val latitude: Double? = null,
    val longitude: Double? = null,
    val frequency: Int? = null,

    @SerializedName("frequency_duration")
    val frequencyDuration: Int? = null
)