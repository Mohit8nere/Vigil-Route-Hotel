package com.vigil.android.core.di.api

object ApiNames {
    val BASE_URL: String = com.vigil.android.BuildConfig.API_BASE_URL

    const val AUTH_API = "api/v1/officers/mobile/auth/"

    const val OFFICERS_ROUTE_DETAILS_LIST_API = "api/v1/duties/officer-route-master/"

    const val ROUTE_LOCATION_LOGS_API = "api/v1/officers/location-logs/"
    const val ROUTE_VERIFIED_DETAILS_API = "api/v1/officers/verified-stops/"

    // route Creation Flow
    const val ROUTE_API = "api/v1/routes/"

    const val GET_ROUTE_BY_STATION_API = "api/v1/routes/by-station/"

    // Chat APIs
    const val CHAT_BASE_PATH = "api/v1/chat/"
    const val CHAT_ME_API = "api/v1/chat/me/"
    const val CHAT_ROSTER_API = "api/v1/chat/roster/"
    const val CHAT_CONVERSATIONS_API = "api/v1/chat/conversations/"
    const val CHAT_SYNC_API = "api/v1/chat/sync/"
    const val CHAT_WS_TICKET_API = "api/v1/chat/ws-ticket/"
}
