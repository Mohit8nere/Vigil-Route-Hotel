package com.vigil.android.presentation.route_creation


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigil.android.presentation.route_creation.model.PatrolRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteListScreen(
    routes: List<PatrolRoute>,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onDetails: (Long) -> Unit,
    onLogout: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout") },
            text = { Text("Are you sure you want to logout?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) {
                    Text("Logout", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val filteredRoutes = if (searchQuery.isBlank()) routes 
        else routes.filter { it.name.contains(searchQuery, ignoreCase = true) || it.thana.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery, onValueChange = { searchQuery = it },
                            placeholder = { Text("Search routes...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.White,
                            )
                        )
                    } else {
                        Text("Routes", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton({ isSearchActive = !isSearchActive; if (!isSearchActive) searchQuery = "" }) {
                        Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    IconButton({ showLogoutDialog = true }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Logout") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    actionIconContentColor = androidx.compose.ui.graphics.Color.White,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = androidx.compose.ui.graphics.Color.White,
                shape = androidx.compose.foundation.shape.CircleShape,
            ) { Icon(Icons.Default.Add, "Add Route") }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            if (filteredRoutes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.Route, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(if (searchQuery.isBlank()) "No routes yet" else "No routes found", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        if (searchQuery.isBlank()) Text("Tap + to add your first route", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(filteredRoutes, key = { index, r -> "${r.id}_$index" }) { _, r ->
                        RouteCard(r, { onEdit(r.id) }, { onDetails(r.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun RouteCard(route: PatrolRoute, onEdit: () -> Unit, onDetails: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(route.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(4.dp))
                    Text(route.thana, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                StopCountChip(route.stops.sumOf { it.frequency })
            }
            FilledTonalIconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit route") }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = onDetails) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Route details")
            }
        }
    }
}

@Composable
fun StopCountChip(count: Int) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            "$count ${if (count == 1) "Stop" else "Stops"}",
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------- Route Details ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailsScreen(route: PatrolRoute?, onBack: () -> Unit, @Suppress("UNUSED_PARAMETER") onEdit: (Long) -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Route Details", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White,
                    actionIconContentColor = androidx.compose.ui.graphics.Color.White,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        if (route == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Route not found", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val expandedStops = route.stops.flatMap { stop ->
                List(stop.frequency) { index ->
                    stop to (index + 1)
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(route.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Place, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                                Text(" ${route.thana}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        StopCountChip(expandedStops.size)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Stops (${expandedStops.size})", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            itemsIndexed(expandedStops, key = { index, (s, visitIndex) -> "${s.id}_${visitIndex}_$index" }) { i, (s, visitIndex) ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Text(
                                "${i + 1}", Modifier.size(28.dp).wrapContentSize(),
                                color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${s.name} (${visitIndex}/${s.frequency})", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("Lat: ${"%.4f".format(s.latitude)} | Long: ${"%.4f".format(s.longitude)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "${s.frequencyTime}m",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
