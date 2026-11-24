package com.vigil.android.presentation.feature_dashboard.officer_dashboard_screen.details_screen.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vigil.android.core.di.model.dashboard_model.Officer
import com.vigil.android.core.di.model.dashboard_model.RoutePoint
import com.vigil.android.core.di.repository.OfficerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class OfficerViewModel @Inject constructor(
    private val repository: OfficerRepository
) : ViewModel() {

    private val tag = "OfficerViewModel"

    private val _officers = MutableStateFlow<List<Officer>>(emptyList())
    val officers: StateFlow<List<Officer>> = _officers

    private val _listError = MutableStateFlow<String?>(null)
    val listError: StateFlow<String?> = _listError
    private val _mapError = MutableStateFlow<String?>(null)
    val mapError: StateFlow<String?> = _mapError
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    // ... rest of the properties ...

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _selectedOfficerId = MutableStateFlow<String?>(null)

    val selectedOfficer: StateFlow<Officer?> = combine(_officers, _selectedOfficerId) { list, id ->
        list.find { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _routePoints = MutableStateFlow<List<RoutePoint>>(emptyList())
    val routePoints: StateFlow<List<RoutePoint>> = _routePoints

    private val _verifiedStops = MutableStateFlow<List<RoutePoint>>(emptyList())
    val verifiedStops: StateFlow<List<RoutePoint>> = _verifiedStops

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // Shared date range — shown in the dashboard filter and used as default for route fetches
    private val _fromDate = MutableStateFlow(defaultStartDate())
    val fromDate: StateFlow<String> = _fromDate

    private val _toDate = MutableStateFlow(defaultEndDate())
    val toDate: StateFlow<String> = _toDate

    private val _isRouteLoading = MutableStateFlow(false)
    val isRouteLoading: StateFlow<Boolean> = _isRouteLoading

    private var currentOfficerId: String? = null

    private var officersJob: Job? = null
    private var routeJob: Job? = null
    private var stopsJob: Job? = null
    private var locationUpdateJob: Job? = null

    init {
        fetchOfficers(startDate = _fromDate.value, endDate = _toDate.value)
    }

    // ── date helpers ──────────────────────────────────────────────────────────

    private fun defaultEndDate(): String =
        dateFormat.format(Calendar.getInstance().time)

    private fun defaultStartDate(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)   // default: yesterday
        return dateFormat.format(cal.time)
    }

    // ── dashboard date filter ─────────────────────────────────────────────────

    /**
     * Called from [OfficerDashboard] when the user picks a new date range.
     * Updates the shared dates and re-fetches the officers list.
     */
    fun applyDashboardDateFilter(fromDate: String, toDate: String) {
        _fromDate.value = fromDate
        _toDate.value = toDate
        fetchOfficers(forceRefresh = true, startDate = fromDate, endDate = toDate)
    }

    // ── officers list ─────────────────────────────────────────────────────────

    fun fetchOfficers(
        forceRefresh: Boolean = false,
        isManualRefresh: Boolean = false,
        startDate: String = _fromDate.value,
        endDate: String = _toDate.value
    ) {
        if (!forceRefresh && _officers.value.isNotEmpty()) return

        officersJob?.cancel()
        officersJob = viewModelScope.launch {
            try {
                Log.d(tag, "Fetching officers: Start=$startDate, End=$endDate")
                if (isManualRefresh) {
                    _isRefreshing.value = true
                } else {
                    _isLoading.value = true
                }
                val result = repository.getOfficers(startDate, endDate)
                _listError.value = null
                _officers.value = result
                Log.d(tag, "_officers state updated. Current size: ${_officers.value.size}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _officers.value = emptyList()
                _listError.value = "Officer list unavailable. Pull to refresh."
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
            }
        }
    }

    fun refreshDashboard() {
        fetchOfficers(forceRefresh = true, isManualRefresh = true)
    }

    // ── detail screen ─────────────────────────────────────────────────────────

    fun fetchOfficerDetails(id: String) {
        _selectedOfficerId.value = id
        _routePoints.value = emptyList()
        _verifiedStops.value = emptyList()
        
        // Ensure immediate fetch when navigating to details
        val officer = _officers.value.find { it.id == id }
        officer?.let {
            currentOfficerId = it.officerId
            startLocationUpdates(it.officerId, it.dutyStart, it.dutyEnd, it.officerRouteJoinId)
        }
    }

    private fun startLocationUpdates(officerId: String, startDate: String, endDate: String, dutyId: String) {
        locationUpdateJob?.cancel()
        locationUpdateJob = viewModelScope.launch {
            while (true) {
                fetchRoute(officerId, startDate, endDate, dutyId)
                delay(5 * 60 * 1000L)   // 5 minutes
            }
        }
    }

    fun fetchRoute(
        officerId: String,
        startDate: String = _fromDate.value,
        endDate: String,
        dutyId: String
    ) {
        Log.d(tag, "fetchRoute: ID=$officerId, Start=$startDate, End=$endDate")
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            try {
                _isRouteLoading.value = true
                val logs = repository.getOfficerRouteLogs(
                    officerId = officerId,
                    startDate = startDate,
                    endDate = endDate,
                    officerRouteJoinId = dutyId
                )
                _mapError.value = null
                _routePoints.value = logs
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _routePoints.value = emptyList()
                _mapError.value = "GPS records unavailable. Refresh to try again."
            } finally {
                _isRouteLoading.value = false
            }
        }
    }

    fun fetchVerifiedStops(
        officerId: String,
        startDate: String = _fromDate.value,
        endDate: String = _toDate.value,
        officerRouteJoinId: String
    ) {
        Log.d(tag, "fetchVerifiedStops: ID=$officerId, JoinID=$officerRouteJoinId, Start=$startDate, End=$endDate")
        stopsJob?.cancel()
        stopsJob = viewModelScope.launch {
            try {
                _isRouteLoading.value = true
                val stops = repository.getOfficerVerifiedStops(
                    officerId = officerId,
                    startDate = startDate,
                    endDate = endDate,
                    officerRouteJoinId = officerRouteJoinId
                )
                _mapError.value = null
                _verifiedStops.value = stops
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _verifiedStops.value = emptyList()
                _mapError.value = "Stop records unavailable. Refresh to try again."
            } finally {
                _isRouteLoading.value = false
            }
        }
    }

    fun getAddress(lat: Double, lng: Double): String {
        return try {
            repository.getAddress(lat, lng)
        } catch (e: Exception) {
            "Location unavailable"
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationUpdateJob?.cancel()
        routeJob?.cancel()
        stopsJob?.cancel()
        officersJob?.cancel()
    }
}