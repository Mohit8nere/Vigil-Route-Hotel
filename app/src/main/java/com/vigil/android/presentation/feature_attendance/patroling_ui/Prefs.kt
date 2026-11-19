package com.vigil.android.presentation.feature_attendance.patroling_ui

import android.content.Context

class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences("face_app", Context.MODE_PRIVATE)

    fun savePatrolId(id: String) {
        prefs.edit().putString("patrol_id", id).commit()
    }

    fun getPatrolId(): String? {
        return prefs.getString("patrol_id", null)
    }

    fun saveToken(token: String) {
        prefs.edit().putString("auth_token", token).commit()
    }

    fun getToken(): String? {
        return prefs.getString("auth_token", null)
    }

    fun saveLoginUsername(username: String) {
        prefs.edit().putString("login_username", username).apply()
    }

    fun getLoginUsername(): String? {
        return prefs.getString("login_username", null)
    }

    fun saveUserName(username: String) {
        prefs.edit().putString("officer_name", username).apply()
    }

    fun getUserName(): String? {
        return prefs.getString("officer_name", null)
    }

    fun saveRole(role: String) {
        prefs.edit().putString("officer_role", role).commit()
    }

    fun getUserRole(): String? {
        return prefs.getString("officer_role", null)
    }

    fun isLoggedIn(): Boolean {
        return !getToken().isNullOrEmpty()
    }

    fun clearLoginSession() {
        prefs.edit()
            .remove("auth_token")
            .remove("patrol_id")
            .remove("login_username")
            .remove("officer_name")
            .remove("officer_role")
            .remove("tracking_state")
            .remove("active_route_id")
            .remove("active_officer_route_join_id")
            .remove("shift_start_time_millis")
            .remove("shift_officer_route_join_id")
            .remove("district_id")
            .remove("station_id")
            .commit()
    }

    fun saveRadius(radius: String) {
        prefs.edit().putString("radius", radius).apply()
    }

    fun getRadius(): String? {
        return prefs.getString("radius", null)
    }

    fun saveTrackingState(isTracking: Boolean) {
        prefs.edit().putBoolean("tracking_state", isTracking).apply()
    }

    fun isTracking(): Boolean {
        return prefs.getBoolean("tracking_state", false)
    }

    fun saveActiveRouteId(routeId: Int?) {
        prefs.edit().putInt("active_route_id", routeId ?: -1).apply()
    }

    fun getActiveRouteId(): Int? {
        val id = prefs.getInt("active_route_id", -1)
        return if (id == -1) null else id
    }

    fun saveActiveOfficerRouteJoinId(id: Int?) {
        val editor = prefs.edit()
        if (id == null) {
            editor.remove("active_officer_route_join_id")
        } else {
            editor.putInt("active_officer_route_join_id", id)
        }
        editor.apply()
    }

    fun getActiveOfficerRouteJoinId(): Int? {
        val id = prefs.getInt("active_officer_route_join_id", -1)
        return if (id == -1) null else id
    }

    fun saveShiftStartTime(timeMillis: Long) {
        prefs.edit().putLong("shift_start_time_millis", timeMillis).apply()
    }

    fun getShiftStartTime(): Long {
        return prefs.getLong("shift_start_time_millis", 0L)
    }

    fun saveShiftOfficerRouteJoinId(id: Int) {
        prefs.edit().putInt("shift_officer_route_join_id", id).apply()
    }

    fun getShiftOfficerRouteJoinId(): Int {
        return prefs.getInt("shift_officer_route_join_id", -1)
    }

    fun saveProfileImageBase64(base64: String) {
        prefs.edit().putString("profile_image", base64).apply()
    }

    fun getProfileImageBase64(): String? {
        return prefs.getString("profile_image", null)
    }

    fun saveLastKnownLocation(lat: Double, lon: Double, accuracyMeters: Float = -1f) {
        prefs.edit()
            .putFloat("last_lat", lat.toFloat())
            .putFloat("last_lon", lon.toFloat())
            .putFloat("last_location_accuracy", accuracyMeters)
            .putLong("last_location_time", System.currentTimeMillis())
            .apply()
    }

    fun getLastKnownLocation(
        maxAgeMillis: Long = 2 * 60 * 1000L
    ): Triple<Double, Double, Float>? {
        if (!prefs.contains("last_lat") || !prefs.contains("last_lon")) return null

        val recordedAt = prefs.getLong("last_location_time", 0L)
        if (System.currentTimeMillis() - recordedAt > maxAgeMillis) return null

        return Triple(
            prefs.getFloat("last_lat", 0f).toDouble(),
            prefs.getFloat("last_lon", 0f).toDouble(),
            prefs.getFloat("last_location_accuracy", -1f)
        )
    }

    fun saveServerTimeAnchor(serverTimeMillis: Long, elapsedRealtimeAtCapture: Long) {
        prefs.edit()
            .putLong("server_time_anchor_millis", serverTimeMillis)
            .putLong("server_time_anchor_elapsed", elapsedRealtimeAtCapture)
            .apply()
    }

    fun hasServerTimeAnchor(): Boolean {
        return prefs.contains("server_time_anchor_millis") &&
            prefs.contains("server_time_anchor_elapsed")
    }

    fun getServerTimeAnchorMillis(): Long =
        prefs.getLong("server_time_anchor_millis", -1L)

    fun getServerTimeAnchorElapsed(): Long =
        prefs.getLong("server_time_anchor_elapsed", -1L)

    fun saveDistrictId(districtId: Int) {
        prefs.edit().putInt("district_id", districtId).apply()
    }

    fun getDistrictId(): Int {
        return prefs.getInt("district_id", 1)
    }

    fun saveStationId(stationId: Int) {
        prefs.edit().putInt("station_id", stationId).apply()
    }

    fun getStationId(): Int {
        return prefs.getInt("station_id", 1)
    }

    fun saveStationName(stationName: String) {
        prefs.edit().putString("station_name", stationName).apply()
    }

    fun getStationName(): String? {
        return prefs.getString("station_name", null)
    }

    fun saveDistrictName(districtName: String) {
        prefs.edit().putString("district_name", districtName).apply()
    }

    fun getDistrictName(): String? {
        return prefs.getString("district_name", null)
    }

    fun saveFailedLoginAttempts(attempts: Int) {
        prefs.edit().putInt("failed_login_attempts", attempts).apply()
    }

    fun getFailedLoginAttempts(): Int {
        return prefs.getInt("failed_login_attempts", 0)
    }

    fun saveLockoutStartTimestamp(timestampMillis: Long) {
        prefs.edit().putLong("lockout_start_timestamp", timestampMillis).apply()
    }

    fun getLockoutStartTimestamp(): Long {
        return prefs.getLong("lockout_start_timestamp", 0L)
    }

    fun saveLockoutDurationSeconds(durationSeconds: Int) {
        prefs.edit().putInt("lockout_duration_seconds", durationSeconds).apply()
    }

    fun getLockoutDurationSeconds(): Int {
        return prefs.getInt("lockout_duration_seconds", 60)
    }

    fun resetLoginLockout() {
        prefs.edit()
            .remove("failed_login_attempts")
            .remove("lockout_start_timestamp")
            .remove("lockout_duration_seconds")
            .apply()
    }
}
