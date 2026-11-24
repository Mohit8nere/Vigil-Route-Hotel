package com.vigil.android.presentation.volunteers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.text.TextUtils
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun rolePin(context: Context, kind: String): BitmapDrawable {
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.parseColor(when (kind) { "traffic" -> "#A64107"; "volunteer" -> "#166648"; else -> "#15548B" })
    when (kind) {
        "traffic" -> canvas.drawPath(Path().apply { moveTo(32f, 1f); lineTo(63f, 62f); lineTo(1f, 62f); close() }, paint)
        "volunteer" -> canvas.drawRoundRect(2f, 2f, 62f, 62f, 12f, 12f, paint)
        else -> canvas.drawCircle(32f, 32f, 30f, paint)
    }
    paint.color = Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = 34f
    paint.isFakeBoldText = true
    canvas.drawText(when(kind) { "traffic" -> "T"; "volunteer" -> "V"; else -> "P" }, 32f, if (kind == "traffic") 54f else 45f, paint)
    return BitmapDrawable(context.resources, bitmap)
}

@Composable
internal fun SupervisorDutyMap(onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    val api = remember { VolunteerClient(context.applicationContext).api }
    var positions by remember { mutableStateOf<List<DutyMarker>>(emptyList()) }
    var message by remember { mutableStateOf("Loading duty locations…") }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(owner, revision) {
        owner?.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    positions = api.markers().filter { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }
                    message = if (positions.isEmpty()) "No GPS observations for active duties." else ""
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { positions = emptyList(); message = "Duty locations unavailable. Refresh to try again." }
                delay(30_000L)
            }
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Live duty map", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { revision++ }) { Text("Refresh") }
        }
        Text("P  Police     T  Traffic     V  Volunteer", style = MaterialTheme.typography.labelLarge)
        if (message.isNotBlank()) Text(message)
        if (positions.isNotEmpty()) {
            val map = remember(context) {
                Configuration.getInstance().userAgentValue = context.packageName
                MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true) }
            }
            val icons = remember(context) { listOf("police","traffic","volunteer").associateWith { rolePin(context,it) } }
            DisposableEffect(map, owner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) map.onResume()
                    else if (event == Lifecycle.Event.ON_PAUSE) map.onPause()
                }
                owner?.lifecycle?.addObserver(observer)
                onDispose { owner?.lifecycle?.removeObserver(observer); map.onDetach() }
            }
            var previousKeys by remember(map) { mutableStateOf(emptySet<String>()) }
            AndroidView(factory = {map}, modifier = Modifier.fillMaxWidth().weight(1f), update = { view ->
                view.overlays.clear()
                val points = positions.map { d ->
                    val point = GeoPoint(d.lat, d.lng)
                    val observed = runCatching { OffsetDateTime.parse(d.observed).atZoneSameInstant(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss")) }.getOrDefault(d.observed)
                    view.overlays.add(Marker(view).apply {
                        position = point
                        title = TextUtils.htmlEncode(d.officerName)
                        snippet = TextUtils.htmlEncode("${d.routeName} · ${d.presence} · GPS ${observed}")
                        icon = icons[d.kind] ?: icons["police"]
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    })
                    point
                }
                val keys = positions.map { it.key }.toSet()
                if (keys != previousKeys) {
                    previousKeys = keys
                    view.post {
                        if (points.distinctBy { it.latitude to it.longitude }.size == 1) {
                            view.controller.setCenter(points.first()); view.controller.setZoom(16.0)
                        } else view.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 55)
                    }
                }
                view.invalidate()
            })
            Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
        }
    }
}
