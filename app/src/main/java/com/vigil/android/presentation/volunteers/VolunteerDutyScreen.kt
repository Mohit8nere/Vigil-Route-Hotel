package com.vigil.android.presentation.volunteers

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun formatTime(iso: String): String = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("dd MMM, HH:mm"))

@Composable
fun VolunteerDutyScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()
    val client = remember { VolunteerClient(context.applicationContext) }
    val account = client.account
    val active by VolunteerLocationService.active.collectAsState()
    val serviceStatus by VolunteerLocationService.status.collectAsState()
    var duties by remember(account) { mutableStateOf<List<VolunteerDuty>>(emptyList()) }
    var message by remember(account) { mutableStateOf("") }
    var pending by remember(account) { mutableStateOf<VolunteerDuty?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(account, revision, lifecycle) {
        lifecycle?.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try { duties = client.api.duties(); message = "" }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "Could not load current duties. Check your connection and refresh." }
                finally { loading = false; now = System.currentTimeMillis() }
                delay(30_000L)
            }
        }
    }
    fun stopSharing() { context.stopService(Intent(context, VolunteerLocationService::class.java)) }
    fun startDuty(d: VolunteerDuty) {
        if (account == null || !d.current(System.currentTimeMillis())) { message = "Duty is outside the assigned period."; return }
        try {
            ContextCompat.startForegroundService(context, Intent(context, VolunteerLocationService::class.java)
                .putExtra("duty", d.id).putExtra("owner", account).putExtra("until", d.end))
        } catch (_: Exception) { message = "Open the app and enable precise location to start sharing." }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            pending?.let(::startDuty)
        } else message = "Precise location access is required during duty."
        pending = null
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Volunteer duties", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { stopSharing(); onLogout() }) { Text("Sign out") }
        }
        OutlinedButton(onClick = { revision++ }) { Text("Refresh duties") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AnimatedVisibility(message.isNotBlank()) { Text(message, color = MaterialTheme.colorScheme.error) }
        if (!loading && duties.isEmpty() && message.isBlank()) Text("No current or upcoming duties.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(duties, key = { it.id }) { d ->
                val cardBackgroundBrush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFEAF5F0), // lightest greeny tint top
                        Color(0xFFF7FBF9)  // soft light green bottom
                    )
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBackgroundBrush, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(d.label, style = MaterialTheme.typography.titleMedium)
                        Text(d.station)
                        Text("${formatTime(d.starts_at)} – ${formatTime(d.ends_at)}")
                        Text("Duty area · ${d.radius_m} m")
                        Text("Arrival · ${d.arrived_at?.let(::formatTime) ?: "Awaiting check-in"}")
                        TextButton(onClick = {
                            val geo = Uri.parse("geo:${d.latitude},${d.longitude}?q=${d.latitude},${d.longitude}")
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
                                .onFailure { message = "No map application is installed." }
                        }) { Text("View assigned post") }
                        if (active == d.id) {
                            Text(serviceStatus)
                            OutlinedButton(onClick = { stopSharing() }) { Text("Stop sharing location") }
                        } else if (d.current(now) && active == null) {
                            Button(onClick = {
                                pending = d
                                val requested = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                                if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
                                if (requested.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) startDuty(d)
                                else permissions.launch(requested.toTypedArray())
                            }) { Text("Share location during duty") }
                        } else if (!d.current(now)) Text("Location sharing opens when this duty starts.")
                    }
                }
            }
        }
    }
}
