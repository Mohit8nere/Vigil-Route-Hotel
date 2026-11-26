package com.vigil.android.presentation.route_creation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigil.android.core.di.api.ApiService
import com.vigil.android.core.di.model.route_create_model.CreateRouteRequestModel
import com.vigil.android.core.di.model.route_create_model.CreateRouteStopRequest
import com.vigil.android.core.di.model.route_create_model.UpdateRouteRequestModel
import com.vigil.android.core.di.model.route_create_model.UpdateRouteStopRequest
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs
import com.vigil.android.presentation.route_creation.location_helper.LocationHelper
import com.vigil.android.presentation.route_creation.model.AddStopState
import com.vigil.android.presentation.route_creation.model.DraftRoute
import com.vigil.android.presentation.route_creation.model.LocationState
import com.vigil.android.presentation.route_creation.model.PatrolRoute
import com.vigil.android.presentation.route_creation.model.RouteFormState
import com.vigil.android.presentation.route_creation.model.Stop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@HiltViewModel
class RouteViewModel @Inject constructor(
    private val apiService: ApiService,
    app: Application
) : AndroidViewModel(app) {

    private val locationHelper = LocationHelper(app)

    private val _routes = MutableStateFlow<List<PatrolRoute>>(emptyList())
    val routes: StateFlow<List<PatrolRoute>> = _routes.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var originalStopIds = setOf<Long>()

    private val _isSaveEnabled = MutableStateFlow(true)
    val isSaveEnabled: StateFlow<Boolean> = _isSaveEnabled.asStateFlow()

    private fun updateSaveEnabledState() {
        val f = _form.value
        _isSaveEnabled.value = !(f != null && f.isEdit) || f.stops.any { it.id !in originalStopIds }
    }

    // null => form band hai
    private val _form = MutableStateFlow<RouteFormState?>(null)
    val form: StateFlow<RouteFormState?> = _form.asStateFlow()

    private val _addStop = MutableStateFlow(AddStopState())
    val addStop: StateFlow<AddStopState> = _addStop.asStateFlow()

    init {
        fetchRoutes()
    }

    fun fetchRoutes() {
        viewModelScope.launch {
            try {
                val prefs = Prefs(getApplication())
                val districtId = prefs.getDistrictId()
                val stationId = prefs.getStationId()
                val response = apiService.getRoutesByStationApi(districtId, stationId)
                if (response.success) {
                    val mappedRoutes = response.data.map { r ->
                        val uniqueStops = r.stops.groupBy { Triple(it.stopName ?: "", it.latitude, it.longitude) }
                            .map { (_, group) ->
                                val first = group.first()
                                Stop(
                                    id = first.stopId.toLong(),
                                    name = first.stopName ?: "",
                                    latitude = first.latitude?.toDoubleOrNull() ?: 0.0,
                                    longitude = first.longitude?.toDoubleOrNull() ?: 0.0,
                                    frequency = group.maxOfOrNull { it.frequency } ?: first.frequency,
                                    frequencyTime = first.frequencyDuration
                                )
                            }
                        PatrolRoute(
                            id = r.routeId.toLong(),
                            name = r.routeName ?: "",
                            thana = r.stationName ?: r.routeDescription ?: "",
                            stops = uniqueStops
                        )
                    }
                    _routes.value = mappedRoutes
                }
            } catch (e: Exception) {
                // Fallback or log error
            }
        }
    }

    fun refreshRoutes() {
        viewModelScope.launch {
            _isRefreshing.value = true
            fetchRoutes()
            _isRefreshing.value = false
        }
    }

    private fun saveDraft(form: RouteFormState?) {
        val prefs = getApplication<Application>().getSharedPreferences("route_drafts", android.content.Context.MODE_PRIVATE)
        if (form == null || form.isEdit) {
            prefs.edit().remove("draft_route").apply()
        } else {
            val draft = DraftRoute(form.routeName, form.thana, form.stops)
            prefs.edit().putString("draft_route", draft.toJson()).apply()
        }
    }

    fun routeById(id: Long) = _routes.value.firstOrNull { it.id == id }

    // ---------- Route form ----------
    fun openAddRoute() { 
        originalStopIds = emptySet()
        val prefs = Prefs(getApplication())
        val defaultThana = prefs.getStationName() ?: ""

        val draftJson = getApplication<Application>()
            .getSharedPreferences("route_drafts", android.content.Context.MODE_PRIVATE)
            .getString("draft_route", null)
        
        if (draftJson != null) {
            DraftRoute.fromJson(draftJson)?.let { draft ->
                _form.value = RouteFormState(
                    routeName = draft.routeName,
                    thana = if (draft.thana.isBlank()) defaultThana else draft.thana,
                    stops = draft.stops
                )
                updateSaveEnabledState()
                return
            }
        }
        _form.value = RouteFormState(thana = defaultThana) 
        updateSaveEnabledState()
    }

    fun openEditRoute(id: Long) {
        routeById(id)?.let {
            originalStopIds = it.stops.map { s -> s.id }.toSet()
            _form.value = RouteFormState(
                editingRouteId = it.id, routeName = it.name,
                thana = it.thana, stops = it.stops,
            )
            updateSaveEnabledState()
        }
    }

    fun dismissForm() { 
        saveDraft(_form.value)
        _form.value = null; _addStop.value = AddStopState() 
        originalStopIds = emptySet()
        updateSaveEnabledState()
    }

    fun onRouteName(v: String) {
        _form.update { it?.copy(routeName = v, routeNameError = null) }
        saveDraft(_form.value)
        updateSaveEnabledState()
    }
    fun onThana(v: String) {
        _form.update { it?.copy(thana = v, thanaError = null) }
        saveDraft(_form.value)
        updateSaveEnabledState()
    }
    fun deleteStop(stopId: Long) {
        _form.update { it?.copy(stops = it.stops.filterNot { s -> s.id == stopId }) }
        saveDraft(_form.value)
        updateSaveEnabledState()
    }

    /** Validate + save. Call createRouteApi or updateRouteApi */
    fun saveRoute() {
        val f = _form.value ?: return
        val nameErr = if (f.routeName.isBlank()) "Route name is required" else null
        val thanaErr = if (f.thana.isBlank()) "Thana name is required" else null
        val stopsErr = if (f.stops.isEmpty()) "Add at least one stop" else null
        if (nameErr != null || thanaErr != null || stopsErr != null) {
            _form.value = f.copy(routeNameError = nameErr, thanaError = thanaErr, stopsError = stopsErr)
            return
        }

        viewModelScope.launch {
            try {
                val prefs = Prefs(getApplication())
                val stationId = prefs.getStationId()

                val success = if (f.isEdit) {
                    val newStops = f.stops.filter { it.id !in originalStopIds }
                    val request = UpdateRouteRequestModel(
                        routeName = f.routeName.trim(),
                        routeDescription = f.thana,
                        stops = ArrayList(newStops.map { stop ->
                            UpdateRouteStopRequest(
                                stopId = null,
                                stopName = stop.name,
                                latitude = stop.latitude,
                                longitude = stop.longitude,
                                frequency = stop.frequency,
                                frequencyDuration = stop.frequencyTime
                            )
                        })
                    )
                    val response = apiService.updateRouteApi(f.editingRouteId!!, request)
                    response.success
                } else {
                    val request = CreateRouteRequestModel(
                        routeName = f.routeName.trim(),
                        routeDescription = f.thana,
                        stationId = stationId,
                        stops = ArrayList(f.stops.mapIndexed { index, stop ->
                            CreateRouteStopRequest(
                                stopName = stop.name,
                                stopOrder = index + 1,
                                latitude = stop.latitude,
                                longitude = stop.longitude,
                                status = 1,
                                frequency = stop.frequency,
                                frequencyDuration = stop.frequencyTime
                            )
                        })
                    )
                    val response = apiService.createRouteApi(request)
                    response.success
                }

                if (success) {
                    // Clear draft on successful save/update
                    getApplication<Application>()
                        .getSharedPreferences("route_drafts", android.content.Context.MODE_PRIVATE)
                        .edit().remove("draft_route").apply()
                    dismissForm()
                    delay(300)
                    fetchRoutes()
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    // ---------- Add Stop ----------
    fun openAddStop() { _addStop.value = AddStopState(visible = true) }
    fun dismissAddStop() { _addStop.value = AddStopState() }
    fun onStopName(v: String) = _addStop.update { it.copy(stopName = v, nameError = null) }
    fun onPermissionDenied() = _addStop.update { it.copy(location = LocationState.PermissionDenied) }

    fun captureAndAddStop(frequency: Int = 1, frequencyTime: Int = 0) {
        val name = _addStop.value.stopName.trim()
        if (name.isEmpty()) {
            _addStop.update { it.copy(nameError = "Stop name is required") }
            return
        }
        viewModelScope.launch {
            _addStop.update { it.copy(location = LocationState.Fetching) }
            try {
                val loc = withTimeout(15_000) { locationHelper.getCurrentLocation() }
                if (loc == null) {
                    _addStop.update {
                        it.copy(location = LocationState.Error("Unable to get location. Please turn on GPS and retry."))
                    }
                    return@launch
                }
                _addStop.update { it.copy(location = LocationState.Success(loc.latitude, loc.longitude)) }
                delay(900) // success message user ko dikhe
                _form.update {
                    it?.copy(
                        stops = it.stops + Stop(name = name, latitude = loc.latitude, longitude = loc.longitude, frequency = frequency, frequencyTime = frequencyTime),
                        stopsError = null,
                    )
                }
                saveDraft(_form.value)
                updateSaveEnabledState()
                dismissAddStop()
            } catch (e: TimeoutCancellationException) {
                _addStop.update { it.copy(location = LocationState.Error("Location timed out. Please retry.")) }
            } catch (e: Exception) {
                _addStop.update { it.copy(location = LocationState.Error("Could not fetch location. Please retry.")) }
            }
        }
    }
}
