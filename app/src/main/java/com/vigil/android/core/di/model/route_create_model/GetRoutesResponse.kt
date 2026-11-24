package com.vigil.android.core.di.model.route_create_model

import com.google.gson.annotations.SerializedName

data class GetRoutesResponseModel(
    val success: Boolean = false,
    val message: String? = null,
    val data: ArrayList<RouteDataModel> = arrayListOf()
)

data class RouteDataModel(
    @SerializedName("route_id")
    val routeId: Int = 0,

    val stops: ArrayList<RouteStopModel> = arrayListOf(),

    @SerializedName("station_name")
    val stationName: String? = null,

    @SerializedName("route_name")
    val routeName: String? = null,

    @SerializedName("route_description")
    val routeDescription: String? = null
)

data class RouteStopModel(
    @SerializedName("stop_id")
    val stopId: Int = 0,

    @SerializedName("stop_name")
    val stopName: String? = null,

    @SerializedName("stop_hash")
    val stopHash: String? = null,

    @SerializedName("stop_order")
    val stopOrder: Int = 0,

    val latitude: String? = null,

    val longitude: String? = null,

    val status: Int = 0,

    @SerializedName("frequency_duration")
    val frequencyDuration: Int = 0,

    val frequency: Int = 0
)
