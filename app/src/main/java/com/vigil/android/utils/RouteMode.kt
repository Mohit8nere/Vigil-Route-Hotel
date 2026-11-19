package com.vigil.android.utils

enum class RouteMode { LIVE, ROUTES }
enum class SortField(val label: String) {
    NAME("Name"),
    BADGE_ID("Badge ID"),
    DATE("Last Active Date")
}
enum class OfficerStatus {
    ON_DUTY,
    ON_BREAK,
    OFF_DUTY
}