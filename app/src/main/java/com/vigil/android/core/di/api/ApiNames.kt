package com.vigil.android.core.di.api

object ApiNames {
//    val BASE_URL: String = com.vigil.android.BuildConfig.API_BASE_URL
    val BASE_URL: String = "http://192.168.1.134:8026/"

    const val AUTH_API = "api/v1/officers/mobile/auth/"

    const val OFFICERS_ROUTE_DETAILS_LIST_API = "api/v1/duties/officer-route-master/"

    const val ROUTE_LOCATION_LOGS_API = "api/v1/officers/location-logs/"
    const val ROUTE_VERIFIED_DETAILS_API = "api/v1/officers/verified-stops/"

    // route Creation Flow
    const val ROUTE_API = "api/v1/routes/"

    const val GET_ROUTE_BY_STATION_API = "api/v1/routes/by-station/"
}