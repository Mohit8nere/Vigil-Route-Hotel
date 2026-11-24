package com.vigil.android.utils

enum class RouteMode { LIVE, ROUTES }
enum class SortField(val label: String) {
    NAME("Name"),
    BADGE_ID("Badge ID"),
    DATE("Duty date")
}
enum class OfficerStatus {
    ON_DUTY,
    SCHEDULED,
    UNKNOWN,
    OFF_DUTY
}