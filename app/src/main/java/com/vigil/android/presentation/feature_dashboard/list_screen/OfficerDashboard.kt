package com.vigil.android.presentation.feature_dashboard.list_screen

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.hilt.navigation.compose.hiltViewModel
import com.vigil.android.R
import com.vigil.android.core.di.model.dashboard_model.Officer
import com.vigil.android.presentation.common.PrivacyPolicyDialog
import com.vigil.android.presentation.components.CustomGradientTopAppBar
import com.vigil.android.presentation.components.DateRangeFilterRow
import com.vigil.android.presentation.feature_dashboard.officer_dashboard_screen.details_screen.viewmodel.OfficerViewModel
import com.vigil.android.utils.OfficerStatus
import com.vigil.android.utils.SortField


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficerDashboard(
    onOfficerClick: (String) -> Unit,
    onChat: () -> Unit,
    onLogout: () -> Unit,
    viewModel: OfficerViewModel = hiltViewModel()
) {
    val officers by viewModel.officers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val fromDate by viewModel.fromDate.collectAsState()
    val toDate by viewModel.toDate.collectAsState()

    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    var showDutyMap by remember { mutableStateOf(false) }
    if (showDutyMap) {
        BackHandler { showDutyMap = false }
        com.vigil.android.presentation.volunteers.SupervisorDutyMap(onBack = { showDutyMap = false })
        return
    }
    BackHandler {
        showExitDialog = true
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit App") },
            text = { Text("Are you sure you want to exit the app?") },
            confirmButton = {
                TextButton(onClick = { (context as? Activity)?.finish() }) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result handled by the system dialog. 
        // We just need the prompt to trigger.
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PermissionChecker.PERMISSION_GRANTED
            
            if (!isGranted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val loadError by viewModel.listError.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var sortField by remember { mutableStateOf(SortField.DATE) }
    var sortAscending by remember { mutableStateOf(true) }
    var showSortMenu by remember { mutableStateOf(false) }

    val filteredOfficers = remember(searchQuery, sortField, sortAscending, officers, fromDate, toDate) {
        val filtered = officers.filter {
            val matchesSearch = (it.name.contains(searchQuery, ignoreCase = true) ||
                    it.badgeId.contains(searchQuery, ignoreCase = true) ||
                    it.officerId.contains(searchQuery, ignoreCase = true))
            val matchesDate = try {
                if (it.dutyStart.isEmpty()) true 
                else {
                    // Simple string comparison for dates in yyyy-MM-dd format
                    it.dutyStart >= fromDate && it.dutyStart <= toDate
                }
            } catch (e: Exception) {
                true
            }
            
            matchesSearch && matchesDate
        }
        val sorted = when (sortField) {
            SortField.NAME -> filtered.sortedBy { it.name.lowercase() }
            SortField.BADGE_ID -> filtered.sortedBy { it.badgeId }
            SortField.DATE -> filtered.sortedBy { it.dutyDate }
        }
        if (sortAscending) sorted else sorted.reversed()
    }

    Scaffold(
        topBar = {
            CustomGradientTopAppBar(
                title = "Officers List",
                onLogoutClick = { showLogoutDialog = true },
                onPrivacyClick = { PrivacyPolicyDialog.show(context) }
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshDashboard() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showDutyMap = true }) { Text("Live duty map") }
                    TextButton(onClick = onChat) { Text("Chats") }
                }

                // ── Search + sort ──────────────────────────────────────────────
                SearchAndFilterBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    sortField = sortField,
                    sortAscending = sortAscending,
                    showSortMenu = showSortMenu,
                    onToggleSortMenu = { showSortMenu = !showSortMenu },
                    onSortFieldSelected = { sortField = it; showSortMenu = false },
                    onToggleSortDirection = { sortAscending = !sortAscending }
                )

                // ── Date range filter (default: last 2 days) ──────────────────
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    DateRangeFilterRow(
                        fromDate = fromDate,
                        toDate = toDate,
                        onApply = { from, to ->
                            viewModel.applyDashboardDateFilter(from, to)
                        }
                    )
                }

                // ── List / loading / empty ─────────────────────────────────────
                if (isLoading && !isRefreshing) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (filteredOfficers.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(loadError ?: "No officers match your search.", color = Color.Gray)
                    }
                } else {
                    Text("Total : ${officers.size}", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        itemsIndexed(filteredOfficers, key = { index, officer -> "${officer.id}_$index" }) { _, officer ->
                            OfficerCard(officer = officer, onClick = { onOfficerClick(officer.id) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchAndFilterBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sortField: SortField,
    sortAscending: Boolean,
    showSortMenu: Boolean,
    onToggleSortMenu: () -> Unit,
    onSortFieldSelected: (SortField) -> Unit,
    onToggleSortDirection: () -> Unit
) {
    Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 2.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_by_name_or_badge_id)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear Search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                AssistChip(
                    onClick = onToggleSortMenu,
                    label = { Text("Sort: ${sortField.label}") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                DropdownMenu(expanded = showSortMenu, onDismissRequest = onToggleSortMenu) {
                    SortField.values().forEach { field ->
                        DropdownMenuItem(
                            text = { Text(field.label) },
                            onClick = { onSortFieldSelected(field) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            AssistChip(
                onClick = onToggleSortDirection,
                label = { Text(if (sortAscending) stringResource(R.string.ascending) else stringResource(
                    R.string.descending
                )) },
                leadingIcon = {
                    Icon(
                        imageVector = if (sortAscending) Icons.Default.ArrowUpward
                        else Icons.Default.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
fun OfficerCard(officer: Officer, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF009688), Color(0xFF26A69A))
                    )
                )
                .padding(10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = officer.badgeId,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    Color.White.copy(alpha = 0.2f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "Duty: ${officer.dutyStart}",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    StatusPill(status = officer.status)
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = officer.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "View Details",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Text(
                    text = "Route: ${officer.routeName}",
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatusPill(status: OfficerStatus) {
    val (label, color) = when (status) {
        OfficerStatus.ON_DUTY -> "On Duty" to Color(0xFF2ECC71)
        OfficerStatus.SCHEDULED -> "Scheduled" to Color(0xFFBD8B00)
        OfficerStatus.UNKNOWN -> "Unavailable" to Color.Gray
        OfficerStatus.OFF_DUTY -> "Off Duty" to Color(0xFFE74C3C)
    }
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}