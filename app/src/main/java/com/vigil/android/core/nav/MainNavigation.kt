package com.vigil.android.core.nav

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.vigil.android.features.fieldops.HotelEntryScreen
import com.vigil.android.presentation.feature_messaging.ui.MessagingScreen
import com.vigil.android.presentation.feature_messaging.ui.ChatListScreen
import com.vigil.android.features.fieldops.ChallanScreen
import com.vigil.android.presentation.traffic.TrafficDutyScreen
import com.vigil.android.presentation.traffic.endTrafficSession
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.compose.navigation
import com.vigil.android.presentation.auth.LoginScreen
import com.vigil.android.presentation.auth.viewmodel.AuthViewModel
import com.vigil.android.presentation.feature_attendance.face.FaceEmbeddingStore
import com.vigil.android.presentation.feature_attendance.patroling_ui.FaceRegistrationActivity
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs
import com.vigil.android.presentation.feature_attendance.patroling_ui.RoutesActivity
import com.vigil.android.presentation.feature_dashboard.list_screen.OfficerDashboard
import com.vigil.android.presentation.feature_dashboard.officer_dashboard_screen.details_screen.OfficerDetailScreen
import com.vigil.android.presentation.feature_dashboard.officer_dashboard_screen.details_screen.viewmodel.OfficerViewModel
import com.vigil.android.presentation.route_creation.RouteListScreen

@Composable
fun MainNavigation(overrideStartDestination: String? = null) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        authViewModel.sessionExpired.collect {
            navController.navigate("login") {
                popUpTo(navController.graph.startDestinationId) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    val scope = rememberCoroutineScope()
    val startDestination = remember(authViewModel, overrideStartDestination) {
        if (authViewModel.isSessionValid()) {
            val role = authViewModel.getRole()?.uppercase()
            when {
                overrideStartDestination == "traffic_chat" && role in setOf("PATROL OFFICER","TRAFFIC POLICE","ROUTE CREATOR","TI","SP","SUPER ADMIN","SI") -> "traffic_chat"
                overrideStartDestination == "traffic_duties" && role in setOf("PATROL OFFICER","TRAFFIC POLICE") -> "traffic_duties"
                else -> destination(role)
            }
        } else {
            "login"
        }
    }
    fun logout() {
        authViewModel.clearSession()
        navController.navigate("login") { popUpTo(navController.graph.id) { inclusive = true } }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login") {
            LoginScreen(onLoginSuccess = { role ->
                val next = destination(role)
                if (next == "login") {
                    android.widget.Toast.makeText(context, "This role does not have a mobile workspace.", android.widget.Toast.LENGTH_LONG).show()
                    authViewModel.clearSession()
                } else navController.navigate(next) { popUpTo("login") { inclusive = true } }
            })
        }
        composable("volunteer_duties") {
            com.vigil.android.presentation.volunteers.VolunteerDutyScreen(onLogout = { logout() })
        }
        composable("hotel_entries") { HotelEntryScreen(onLogout = { logout() }) }
        composable("traffic_duties") {
            TrafficHome(
                onSurveys = { navController.navigate("assigned_surveys") },
                onPatrol = { navController.navigate("patrol_flow") },
                onVip = { context.startActivity(Intent(context, com.vigil.android.presentation.vip.VipDutyActivity::class.java)) },
                onChat = { navController.navigate("traffic_chat") },
                onChallan = if (authViewModel.getRole().equals("Traffic Police", true)) ({ navController.navigate("traffic_challan") }) else null,
                onLogout = { scope.launch { try { endTrafficSession(context) } finally { logout() } } }
            )
        }
        composable("assigned_surveys") {
            com.vigil.android.features.surveys.AssignedSurveysScreen(onBack = { navController.popBackStack() })
        }
        composable("traffic_challan") { ChallanScreen(onBack = { navController.popBackStack() }) }
        composable("traffic_chat") {
            val chatListViewModel: com.vigil.android.presentation.feature_messaging.viewmodel.ChatListViewModel = hiltViewModel()
            ChatListScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        (context as? android.app.Activity)?.finish()
                    }
                },
                onChatClick = { channelId ->
                    navController.navigate("traffic_chat_detail/$channelId")
                },
                viewModel = chatListViewModel
            )
        }
        composable("traffic_chat_detail/{channelId}") { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString("channelId") ?: ""
            val messagingViewModel: com.vigil.android.presentation.feature_messaging.viewmodel.MessagingViewModel = hiltViewModel()
            val numericId = channelId.toLongOrNull() ?: return@composable
            MessagingScreen(
                onBack = { navController.popBackStack() },
                threadId = numericId,
                viewModel = messagingViewModel
            )
        }

        composable("patrol_flow") {
            LaunchedEffect(Unit) {
                com.vigil.android.presentation.verification.VerificationJobs.schedule(context)
                launchPatrolFlow(context)
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        navigation(startDestination = "route_list_screen", route = "route_flow") {
            composable("route_list_screen") {
                val backStackEntry = remember(it) { navController.getBackStackEntry("route_flow") }
                val vm: com.vigil.android.presentation.route_creation.viewmodel.RouteViewModel = hiltViewModel(backStackEntry)
                val routes by vm.routes.collectAsState()
                val isRefreshing by vm.isRefreshing.collectAsState()
                val form by vm.form.collectAsState()
                val isSaveEnabled by vm.isSaveEnabled.collectAsState()
                val addStop by vm.addStop.collectAsState()

                RouteListScreen(
                    routes = routes,
                    isRefreshing = isRefreshing,
                    onRefresh = vm::refreshRoutes,
                    onAdd = vm::openAddRoute,
                    onChat = { navController.navigate("traffic_chat") },
                    onVip = { context.startActivity(Intent(context, com.vigil.android.presentation.vip.VipDutyActivity::class.java)) },
                    onSurveys = { navController.navigate("assigned_surveys") },
                    onEdit = vm::openEditRoute,
                    onDetails = { navController.navigate("route_details/$it") },
                    onLogout = {
                        authViewModel.clearSession()
                        navController.navigate("login") {
                            popUpTo("route_flow") { inclusive = true }
                        }
                    }
                )

                if (form != null) {
                    com.vigil.android.presentation.route_creation.dialog.RouteFormSheet(
                        form = form!!,
                        isSaveEnabled = isSaveEnabled,
                        onName = vm::onRouteName,
                        onThana = vm::onThana,
                        onAddStop = vm::openAddStop,
                        onDeleteStop = vm::deleteStop,
                        onSave = vm::saveRoute,
                        onDismiss = vm::dismissForm
                    )
                }

                if (addStop.visible) {
                    com.vigil.android.presentation.route_creation.dialog.AddStopDialog(
                        state = addStop,
                        onName = vm::onStopName,
                        onSubmit = { freq, time -> vm.captureAndAddStop(freq, time) },
                        onPermissionDenied = vm::onPermissionDenied,
                        onDismiss = vm::dismissAddStop
                    )
                }
            }

            composable(
                route = "route_details/{routeId}",
                arguments = listOf(navArgument("routeId") { type = NavType.LongType })
            ) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry("route_flow") }
                val vm: com.vigil.android.presentation.route_creation.viewmodel.RouteViewModel = hiltViewModel(parentEntry)
                val routes by vm.routes.collectAsState()
                val id = entry.arguments?.getLong("routeId") ?: 0L
                val route = routes.firstOrNull { it.id == id }
                
                com.vigil.android.presentation.route_creation.RouteDetailsScreen(
                    route = route,
                    onBack = { navController.popBackStack() },
                    onEdit = { vm.openEditRoute(id) }
                )
            }
        }

        navigation(startDestination = "dashboard", route = "admin_dest") {
            composable("dashboard") {
                val backStackEntry = remember(it) { navController.getBackStackEntry("admin_dest") }
                val officerViewModel: OfficerViewModel = hiltViewModel(backStackEntry)
                OfficerDashboard(
                    onChat = { navController.navigate("traffic_chat") },
                    onOfficerClick = { officerId ->
                        navController.navigate("detail/$officerId")
                    },
                    onLogout = {
                        authViewModel.clearSession()
                        navController.navigate("login") {
                            popUpTo("admin_dest") { inclusive = true }
                        }
                    },
                    viewModel = officerViewModel
                )
            }
            composable(
                route = "detail/{officerId}",
                arguments = listOf(navArgument("officerId") { type = NavType.StringType })
            ) { backStackEntry ->
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("admin_dest") }
                val officerViewModel: OfficerViewModel = hiltViewModel(parentEntry)
                val officerId = backStackEntry.arguments?.getString("officerId")
                OfficerDetailScreen(
                    officerId = officerId,
                    onBack = { navController.popBackStack() },
                    viewModel = officerViewModel
                )
            }
        }
    }
}

private fun destination(role: String?) = when (role?.uppercase()) {
    "TI", "SP", "SUPER ADMIN" -> "admin_dest"
    "PATROL OFFICER" -> "patrol_flow"
    "ROUTE CREATOR" -> "route_flow"
    "VOLUNTEER" -> "volunteer_duties"
    "HOTEL STAFF" -> "hotel_entries"
    "TRAFFIC POLICE" -> "traffic_duties"
    else -> "login"
}

private fun launchPatrolFlow(context: Context) {
    val prefs = Prefs(context)
    val officerId = prefs.getPatrolId()
    if (!officerId.isNullOrBlank() && (!prefs.stopFaceRequired() || FaceEmbeddingStore.existsForOfficer(context, officerId))) {
        val intent = Intent(context, RoutesActivity::class.java)
        context.startActivity(intent)
        if (context is android.app.Activity) {
            context.finish()
        }
    } else {
        context.startActivity(Intent(context, FaceRegistrationActivity::class.java))
    }
}
