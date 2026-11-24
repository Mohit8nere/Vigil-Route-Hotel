package com.vigil.android.core.di.model

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

data class LoginResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("tokens") val tokens: Tokens?,
    @SerializedName("user_id") val userId: Int? = null,
    @SerializedName("name") val username: String? = null,
    @SerializedName("officer_id") val officerId: Int?,
    @SerializedName("stop_face_required") val stopFaceRequired: Boolean? = null,
    @SerializedName("officer_name") val officerName: String?,
    @SerializedName("role") val role: String?,
    @SerializedName("district_id") val districtId: Int? = null,
    @SerializedName("station_id") val stationId: Int? = null,
    @SerializedName("station_name") val stationName: String? = null,
    @SerializedName("district_name") val districtName: String? = null,
)

data class Tokens(
    @SerializedName("access") val access: String,
    @SerializedName("refresh") val refresh: String? = null
)
