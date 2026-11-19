package com.vigil.android.core.di.model.dashboard_model

import com.google.gson.annotations.SerializedName

data class OfficerRouteMasterRequest(
    @SerializedName("start_date") val startDate: String,
    @SerializedName("end_date") val endDate: String
)
