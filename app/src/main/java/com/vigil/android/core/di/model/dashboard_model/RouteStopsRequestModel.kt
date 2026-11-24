package com.vigil.android.core.di.model.dashboard_model

import com.google.gson.annotations.SerializedName

data class RouteStopsRequest(
    @SerializedName("officer_id") val officerId: String,
    @SerializedName("start_date") val startDate: String,
    @SerializedName("end_date") val endDate: String,
    @SerializedName("officer_route_join_id") val officerRouteJoinId: String
)
