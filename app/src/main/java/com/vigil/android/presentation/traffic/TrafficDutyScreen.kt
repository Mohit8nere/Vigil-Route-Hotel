package com.vigil.android.presentation.traffic

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun localTime(iso: String?): String = iso?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.of("Asia/Kolkata"))
            .format(DateTimeFormatter.ofPattern("dd MMM, HH:mm 'IST'"))
    }.getOrDefault(it)
} ?: "—"

@Composable
fun TrafficDutyScreen() {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val ops = remember(context) { TrafficOperations(context.applicationContext) }
    val owner = ops.owner ?: return
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
    val prefs = remember(owner) { ops.prefs(owner) }
    var duties by remember(owner) { mutableStateOf<List<TrafficDuty>>(emptyList()) }
    var alerts by remember(owner) { mutableStateOf<List<TrafficAlert>>(emptyList()) }
    var busy by remember(owner) { mutableStateOf(false) }
    var loaded by remember(owner) { mutableStateOf(false) }
    var synchronizing by remember(owner) { mutableStateOf(false) }
    val infiniteTransition = rememberInfiniteTransition(label = "alertBorder")
    val borderAnimColor by infiniteTransition.animateColor(
        initialValue = Color.Black,
        targetValue = Color.Red,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alertBorderColor"
    )
    val alertCardBorderColor = if (synchronizing) borderAnimColor else Color.Black
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var serverIssue by remember(owner) { mutableStateOf<String?>(null) }
    var update by remember(owner) { mutableIntStateOf(0) }
    var localId by remember(owner) { mutableLongStateOf(ops.dutyId(owner)) }
    var waitingStop by remember(owner) { mutableStateOf(ops.hasPendingStop(owner)) }
    var serviceIssue by remember(owner) { mutableStateOf(prefs.getString("problem", null)) }
    var pendingStart by rememberSaveable(owner) { mutableLongStateOf(0) }
    var notificationsEnabled by remember {
        mutableStateOf(
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        )
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            localId = ops.dutyId(owner)
            waitingStop = ops.hasPendingStop(owner)
            serviceIssue = prefs.getString("problem", null)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(owner) {
        TrafficAlerts.createChannels(context)
        TrafficAlertJob.schedule(context)
    }
    LaunchedEffect(owner, update, lifecycleOwner) {
        lifecycleOwner?.lifecycle?.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (isActive) {
                    synchronizing = true
                    notificationsEnabled =
                        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                    try {
                        ops.flushStop(owner)
                        duties = ops.api.duties()
                        alerts = TrafficAlerts.refresh(ops, owner)
                        serverIssue = null
                        loaded = true
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        serverIssue = trafficError(e)
                    } finally {
                        synchronizing = false
                    }
                    delay(30_000L)
                }
            } finally {
                synchronizing = false
            }
        }
    }

    fun start(duty: TrafficDuty) {
        if (busy) return
        if (!LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))) {
            error = "Turn on device location, then start duty."
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                ops.start(duty)
            } catch (e: Exception) {
                error = trafficError(e)
            } finally {
                busy = false; update++
            }
        }
    }

    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            notificationsEnabled =
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            val selected = duties.firstOrNull { it.id == pendingStart }
            if (selected != null) {
                if (TrafficDutyService.hasFinePermission(context)) start(selected)
                else error = "Allow precise location in app settings to start traffic duty."
            }
            pendingStart = 0
        }

    fun requestStart(duty: TrafficDuty) {
        val needed = mutableListOf<String>()
        if (!TrafficDutyService.hasFinePermission(context)) needed.addAll(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        )
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isEmpty()) start(duty)
        else {
            pendingStart = duty.id; permissions.launch(needed.toTypedArray())
        }
    }

    fun stop(id: Long, started: String?) {
        // Release GPS even when the network is down; only the server acknowledgement is retried.
        ops.queueStop(owner, id, started)
        error = null
        busy = true
        scope.launch {
            try {
                ops.flushStop(owner)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Tracking is off. The control room will receive your stop when connected."
            } finally {
                busy = false; update++
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { update++ }, enabled = !synchronizing && !busy) { Text("Refresh") }
            }
            if (!loaded && synchronizing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (waitingStop) item { Text("Tracking is off. Waiting for control room acknowledgement.") }
        listOfNotNull(error, serviceIssue, serverIssue).distinct().forEach { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        if (!notificationsEnabled) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = {
                    permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }) { Text("Enable alerts") }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }) { Text("Notification settings") }
            }
        }
        if (error?.contains("app settings") == true) item {
            TextButton(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            }) { Text("Open app settings") }
        }
        if (localId > 0 && duties.none { it.id == localId }) item {
            OutlinedButton(onClick = { stop(localId, prefs.getString("session", null)) }, enabled = !busy) {
                Text("Stop current duty")
            }
        }
        if (loaded && duties.isEmpty()) item {
            val cardBackgroundBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFEAF5F0),
                    Color(0xFFF7FBF9)
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
                Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text("No traffic duty has been assigned.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(duties, key = { "duty-${it.id}" }) { duty ->
            val local = localId == duty.id
            val tracking = local && TrafficDutyService.trackingOwner == owner &&
                    TrafficDutyService.trackingDuty == duty.id
            val isOn = local || (duty.active && !waitingStop)
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
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            duty.label,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = Bold
                        )
                        Switch(
                            checked = isOn, enabled = !busy && !waitingStop &&
                                    (isOn || (duty.eligible && localId == 0L)), onCheckedChange = { on ->
                                if (on) requestStart(duty)
                                else stop(
                                    duty.id,
                                    duty.session_started_at ?: prefs.getString("session", null)
                                )
                            })
                    }
                    val hours = when (duty.mode) {
                        "timed" -> "${localTime(duty.starts_at)} – ${localTime(duty.ends_at)}"
                        "full_day" -> "${duty.duty_date} · Full day"
                        else -> "${duty.duty_date} · Officer starts and ends duty"
                    }
                    Text(hours, style = MaterialTheme.typography.bodyMedium)
                    Text("Duty boundary · ${duty.radius_m ?: 200} m", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = {
                        links.openUri("https://www.google.com/maps/search/?api=1&query=${duty.latitude},${duty.longitude}")
                    }) { Text("View assigned post · ${duty.latitude}, ${duty.longitude}") }
                    if (isOn) {
                        val presence = when (duty.presence) {
                            "inside" -> "Inside ${duty.radius_m ?: 200} m"
                            "outside" -> "Outside ${duty.radius_m ?: 200} m"
                            "uncertain" -> "GPS accuracy uncertain"
                            "stale" -> "Location update overdue"
                            else -> "Awaiting location"
                        }
                        Text(presence + (duty.distance_m?.let { " · ${it.toInt()} m from post" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium)
                        duty.last_seen?.let {
                            Text(
                                "Last update: ${localTime(it)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!tracking && duty.active && duty.eligible)
                            Button(
                                enabled = !busy,
                                onClick = { requestStart(duty) }) { Text("Resume tracking") }
                    } else if (!duty.eligible) Text(
                        "Outside duty hours",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Control room alerts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (loaded && alerts.isEmpty()) item {
            val cardBackgroundBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFEAF5F0),
                    Color(0xFFF7FBF9)
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
                Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text("No traffic alerts.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(alerts, key = { "alert-${it.id}" }) { alert ->
            val cardBackgroundBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFEAF5F0),
                    Color(0xFFF7FBF9)
                )
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBackgroundBrush, RoundedCornerShape(16.dp)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        alert.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = Bold
                    )
                    Text(alert.detail, style = MaterialTheme.typography.bodyMedium)
                    alert.congestionMap?.let { url ->
                        OutlinedButton(onClick = { links.openUri(url) }) { Text("Open congestion in Google Maps") }
                    }
                    if (alert.kind == "congestion") Row {
                        TextButton(onClick = { links.openUri("https://maps.google.com/help/terms_maps/") }) {
                            Text("Google Maps terms")
                        }
                        TextButton(onClick = { links.openUri("https://policies.google.com/privacy") }) {
                            Text("Privacy")
                        }
                    }
                    val dispatch =
                        listOfNotNull(alert.station_name, alert.route_name).filter { it.isNotBlank() }
                    if (dispatch.isNotEmpty()) Text(
                        dispatch.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(localTime(alert.created_at), style = MaterialTheme.typography.bodySmall)
                    if (!alert.read) TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val response = ops.api.markRead(alert.id)
                                if (!response.isSuccessful) throw retrofit2.HttpException(response)
                                alerts = alerts.map { if (it.id == alert.id) it.copy(read = true) else it }
                                context.getSystemService(NotificationManager::class.java)
                                    .cancel("traffic:$owner", alert.id.toInt())
                            } catch (e: Exception) {
                                error = trafficError(e)
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Mark read") }
                }
            }
        }
    }
}
