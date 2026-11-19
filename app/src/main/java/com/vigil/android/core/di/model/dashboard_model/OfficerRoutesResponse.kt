package com.vigil.android.core.di.model.dashboard_model

data class OfficerRoutesDetailsResponseDataModel(
    val success: Boolean,
    val message: String,
    val data: List<OfficerRouteDetailsList>
)

data class OfficerRouteDetailsList(
    val officer_route_join_id: Int,
    val officer_id: Int,
    val officer_name: String,
    val badge_number: String,
    val officer_rank: String,
    val officer_phone_no: String,
    val officer_status: Int,
    val officer_image: String,
    val route_name: String,

    val route_id : Int? = null,
    val duty_date : String? = "",
    val duty_start : String? = "",
    val duty_end : String?  = "",
    val duty_shift : String? = "",
    val status : Int? = null
)