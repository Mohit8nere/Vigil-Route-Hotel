package com.vigil.android.presentation.feature_dashboard.officer_dashboard_screen.details_screen

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vigil.android.core.di.model.dashboard_model.Officer
import com.vigil.android.core.di.model.dashboard_model.RoutePoint
import com.vigil.android.utils.RouteMode
import com.vigil.android.presentation.components.CustomGradientTopAppBar
import com.vigil.android.presentation.feature_dashboard.officer_dashboard_screen.details_screen.viewmodel.OfficerViewModel
import com.vigil.android.presentation.feature_dashboard.track_map_screen.OfficerOsmMap


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficerDetailScreen(
    officerId: String?,
    onBack: () -> Unit,
    viewModel: OfficerViewModel = hiltViewModel()
) {
    LaunchedEffect(officerId) {
        officerId?.let { viewModel.fetchOfficerDetails(it) }
    }

    val selectedOfficer by viewModel.selectedOfficer.collectAsState()
    val apiRoutePoints by viewModel.routePoints.collectAsState()
    val verifiedStops by viewModel.verifiedStops.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRouteLoading by viewModel.isRouteLoading.collectAsState()

    var routeMode by remember { mutableStateOf(RouteMode.LIVE) }
    var isMapFullScreen by remember { mutableStateOf(false) }

    if (isMapFullScreen) {
        BackHandler { isMapFullScreen = false }
    }

    LaunchedEffect(routeMode, selectedOfficer) {
        if (routeMode == RouteMode.ROUTES) {
            selectedOfficer?.let { viewModel.fetchVerifiedStops(it.officerId, it.dutyStart, it.dutyEnd, it.officerRouteJoinId) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedOfficer == null) {
            Scaffold(
                topBar = { CustomGradientTopAppBar(title = "Officer Details", onBackClick = onBack) }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color(0xFF26A69A))
                    } else {
                        Text("Officer Details Not Found", color = Color.Gray)
                    }
                }
            }
        } else {
            val officer = selectedOfficer!!
            val currentPoints: List<RoutePoint> = remember(routeMode, apiRoutePoints, verifiedStops) {
                if (routeMode == RouteMode.LIVE) {   //todo:error = Return type mismatch: expected 'List<com.vigil.android.core.di.model.dashboard_model.RoutePoint>', actual 'List<com.vigil.android.presentation.feature_dashboard.officer_dashboard_screen.model.RoutePoint>'.
                    apiRoutePoints.sortedBy { it.rawTime }
                } else {
                    verifiedStops.sortedWith(
                        compareByDescending<RoutePoint> { it.isVerified == true }
                            .thenByDescending { it.rawTime }
                    )
                }
            }

            var manualSelectedPoint by remember { mutableStateOf<RoutePoint?>(null) }

            val selectedPoint = remember(manualSelectedPoint, currentPoints) {
                if (manualSelectedPoint != null && currentPoints.contains(manualSelectedPoint)) {
                    manualSelectedPoint
                } else {
                    currentPoints.lastOrNull()
                }
            }

            Scaffold(
                topBar = { CustomGradientTopAppBar(title = "Officer Details", onBackClick = onBack) }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { OfficerInfoCard(officer = officer) }

                    item {
                        RouteTrackingCard(
                            routeMode = routeMode,
                            onRouteModeChange = { routeMode = it },
                            routePoints = currentPoints,
                            selectedPoint = selectedPoint,
                            onPointClick = { manualSelectedPoint = it },
                            onFullScreenToggle = { isMapFullScreen = true },
                            isRouteLoading = isRouteLoading,
                            onRefresh = {
                                if (routeMode == RouteMode.LIVE) {
                                    viewModel.fetchRoute(officer.officerId, officer.dutyStart, officer.dutyEnd)
                                } else {
                                    viewModel.fetchVerifiedStops(officer.officerId, officer.dutyStart, officer.dutyEnd, officer.officerRouteJoinId)
                                }
                            },
                            getAddress = { lat, lng -> viewModel.getAddress(lat, lng) }
                        )
                    }

                    item {
                        RouteHistoryList(
                            routePoints = currentPoints,
                            selectedPoint = selectedPoint,
                            onPointClick = { manualSelectedPoint = it },
                            routeMode = routeMode
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isMapFullScreen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                FullScreenMap(
                    routePoints = currentPoints,
                    onPointClick = { manualSelectedPoint = it },
                    onClose = { isMapFullScreen = false },
                    selectedPoint = selectedPoint,
                    getAddress = { lat, lng -> viewModel.getAddress(lat, lng) },
                    routeMode = routeMode
                )
            }
        }
    }
}

@Composable
private fun OfficerInfoCard(officer: Officer) {
    val bitmap = remember(officer.imageUrl) {
        try {
            if (officer.imageUrl.isNotEmpty()) {
                val imageBytes = Base64.decode(officer.imageUrl, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color(0xFF26A69A), CircleShape)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Officer Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = officer.officerId,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = officer.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Badge ID: ${officer.badgeId}",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Duty Date: ${officer.dutyDate}",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Shift: ${officer.dutyShift}",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Route: ${officer.routeName}",
                    fontSize = 14.sp,
                    color = Color(0xFF26A69A),
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FullScreenMap(
    routePoints: List<RoutePoint>,
    onPointClick: (RoutePoint) -> Unit,
    onClose: () -> Unit,
    selectedPoint: RoutePoint? = null,
    getAddress: ((Double, Double) -> String)? = null,
    routeMode: RouteMode = RouteMode.LIVE
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        OfficerOsmMap(
            routePoints = routePoints,
            onPointClick = onPointClick,
            onFullScreenClick = onClose,
            modifier = Modifier.fillMaxSize(),
            selectedPoint = selectedPoint,
            getAddress = getAddress,
            routeMode = routeMode
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 8.dp
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Exit Full Screen",
                    tint = Color(0xFF26A69A)
                )
            }
        }

        Text(
            "Full View Mode",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp),
            fontWeight = FontWeight.Bold,
            color = Color(0xFF26A69A)
        )
    }
}

@Composable
private fun RouteTrackingCard(
    routeMode: RouteMode,
    onRouteModeChange: (RouteMode) -> Unit,
    routePoints: List<RoutePoint>,
    selectedPoint: RoutePoint?,
    onPointClick: (RoutePoint) -> Unit,
    onFullScreenToggle: () -> Unit,
    isRouteLoading: Boolean,
    onRefresh: () -> Unit,
    getAddress: ((Double, Double) -> String)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Route Monitoring", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Route",
                        tint = Color(0xFF009688)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            SegmentedRouteToggle(routeMode = routeMode, onRouteModeChange = onRouteModeChange)
            Spacer(modifier = Modifier.height(12.dp))

            if (isRouteLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = Color(0xFF009688)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Loading Route Map...", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            } else if (routePoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No Routes Available", fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                }
            } else {
                OfficerOsmMap(
                    routePoints = routePoints,
                    onPointClick = onPointClick,
                    onFullScreenClick = onFullScreenToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    selectedPoint = selectedPoint,
                    getAddress = getAddress,
                    routeMode = routeMode
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            SelectedPointPanel(point = selectedPoint, getAddress = getAddress)
        }
    }
}

@Composable
private fun SelectedPointPanel(
    point: RoutePoint?,
    getAddress: ((Double, Double) -> String)? = null
) {
    if (point == null) return
    val address = remember(point) {
        getAddress?.invoke(point.lat, point.lng) ?: "Address Unavailable"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF2F8FA), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.PinDrop,
            contentDescription = null,
            tint = Color(0xFF009688),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(text = address, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Time: ${point.timestamp} – Lat ${"%.5f".format(point.lat)}, Lng ${"%.5f".format(point.lng)}",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun SegmentedRouteToggle(
    routeMode: RouteMode,
    onRouteModeChange: (RouteMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEFEFEF), RoundedCornerShape(10.dp))
            .padding(4.dp)
    ) {
        SegmentButton(
            text = "Live Tracking",
            selected = routeMode == RouteMode.LIVE,
            modifier = Modifier.weight(1f),
            onClick = { onRouteModeChange(RouteMode.LIVE) }
        )
        SegmentButton(
            text = "Routes",
            selected = routeMode == RouteMode.ROUTES,
            modifier = Modifier.weight(1f),
            onClick = { onRouteModeChange(RouteMode.ROUTES) }
        )
    }
}

@Composable
private fun SegmentButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF009688) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color.DarkGray,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RouteHistoryList(
    routePoints: List<RoutePoint>,
    selectedPoint: RoutePoint?,
    onPointClick: (RoutePoint) -> Unit,
    routeMode: RouteMode
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Tracking Points", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))

            if (routePoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No Location Logs Found For This Period",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            } else {
                routePoints.forEach { point ->
                    val isSelected = point == selectedPoint
                    val isVerified = point.isVerified == true

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFFFFFFFF) else Color.Transparent)
                            .clickable { onPointClick(point) }
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (routeMode == RouteMode.ROUTES) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (isVerified) Color(0xFF2ECC71) else Color.LightGray,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${point.address ?: "Location Log"} - ${point.displayDate ?: ""}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isVerified) Color.Black else Color.DarkGray
                                )
                                Text(
                                    text = if (isVerified)
                                        "Verified: ${point.displayTime ?: point.timestamp}"
                                    else
                                        "Pending: ${point.timestamp}",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = null,
                                tint = Color(0xFF009688),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Recorded At: ${point.timestamp}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Text(
                            text = "Lat ${"%.3f".format(point.lat)}, Lng ${"%.3f".format(point.lng)}",
                            fontSize = 10.sp,
                            color = Color.Gray.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}