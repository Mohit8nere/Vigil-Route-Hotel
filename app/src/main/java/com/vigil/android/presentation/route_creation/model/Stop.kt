package com.vigil.android.presentation.route_creation.model

import com.google.gson.Gson

data class Stop(
    val id: Long = System.nanoTime(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val frequency: Int = 1,
    val frequencyTime: Int = 0,
)

data class PatrolRoute(
    val id: Long,
    val name: String,
    val thana: String,
    val stops: List<Stop>,
)


data class RouteFormState(
    val editingRouteId: Long? = null,
    val routeName: String = "",
    val thana: String = "",
    val stops: List<Stop> = emptyList(),
    val routeNameError: String? = null,
    val thanaError: String? = null,
    val stopsError: String? = null,
) {
    val isEdit get() = editingRouteId != null
}

data class DraftRoute(
    val routeName: String = "",
    val thana: String = "",
    val stops: List<Stop> = emptyList()
) {
    fun toJson(): String = Gson().toJson(this)
    companion object {
        fun fromJson(json: String): DraftRoute? = try {
            Gson().fromJson(json, DraftRoute::class.java)
        } catch (e: Exception) { null }
    }
}

sealed interface LocationState {
    data object Idle : LocationState
    data object Fetching : LocationState
    data class Success(val lat: Double, val lng: Double) : LocationState
    data object PermissionDenied : LocationState
    data class Error(val message: String) : LocationState
}

data class AddStopState(
    val visible: Boolean = false,
    val stopName: String = "",
    val nameError: String? = null,
    val location: LocationState = LocationState.Idle,
)
