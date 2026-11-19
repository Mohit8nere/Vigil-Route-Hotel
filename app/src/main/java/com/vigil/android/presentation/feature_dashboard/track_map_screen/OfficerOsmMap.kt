package com.vigil.android.presentation.feature_dashboard.track_map_screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.vigil.android.R
import com.vigil.android.core.di.model.dashboard_model.RoutePoint
import com.vigil.android.utils.RouteMode
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

private const val SATELLITE_SOURCE_MAX_ZOOM = 19
private const val MAP_VIEW_MAX_ZOOM = 21.0

// Minimum time the loading overlay stays up, so the transition into imagery/labels
// feels deliberate and smooth instead of a flicker, even on a fast connection.
private const val MIN_LOADING_MILLIS = 450L

private val SatelliteTileSource = object : OnlineTileSourceBase(
    "EsriSatellite",
    0, SATELLITE_SOURCE_MAX_ZOOM, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex)
    }
}

// Transparent labels/roads/boundaries layer drawn on top of the imagery for readability
private val SatelliteLabelsTileSource = object : OnlineTileSourceBase(
    "EsriLabels",
    0, SATELLITE_SOURCE_MAX_ZOOM, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex)
    }
}


private object OsmdroidBootstrap {
    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            Configuration.getInstance().apply {
                load(appContext, appContext.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                userAgentValue = appContext.packageName

                val basePath = File(appContext.filesDir, "osmdroid")
                val tileCachePath = File(basePath, "tiles")
                basePath.mkdirs()
                tileCachePath.mkdirs()
                osmdroidBasePath = basePath
                osmdroidTileCache = tileCachePath

                cacheMapTileCount = 64
                cacheMapTileOvershoot = 12
                tileFileSystemCacheMaxBytes = 500L * 1024 * 1024
                tileFileSystemCacheTrimBytes = 450L * 1024 * 1024
                tileDownloadThreads = 8
                tileFileSystemThreads = 8
            }
            initialized = true
        }
    }
}

@Composable
fun OfficerOsmMap(
    routePoints: List<RoutePoint>,
    onPointClick: (RoutePoint) -> Unit,
    onFullScreenClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedPoint: RoutePoint? = null,
    getAddress: ((Double, Double) -> String)? = null,
    routeMode: RouteMode = RouteMode.LIVE
) {
    if (routePoints.isEmpty()) return

    val context = LocalContext.current

    val officerIcon = remember(context) {
        try {
            val size = 140
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#26A69A")
            }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, borderPaint)

            ContextCompat.getDrawable(context, R.drawable.ic_menu_myplaces)?.let {
                it.setBounds(size / 4, size / 4, 3 * size / 4, 3 * size / 4)
                it.setTint(android.graphics.Color.WHITE)
                it.draw(canvas)
            }
            BitmapDrawable(context.resources, bitmap)
        } catch (e: Exception) {
            null
        }
    }

    val mapView = remember {
        try {
            OsmdroidBootstrap.ensureInitialized(context)

            MapView(context).apply {
                setTileSource(SatelliteTileSource)
                setMultiTouchControls(true)
                setBuiltInZoomControls(false)
                setTilesScaledToDpi(true)
                isFlingEnabled = true
                setVerticalMapRepetitionEnabled(false)
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                minZoomLevel = 4.0
                maxZoomLevel = MAP_VIEW_MAX_ZOOM

                overlayManager.tilesOverlay?.apply {
                    loadingBackgroundColor = android.graphics.Color.parseColor("#101418")
                    loadingLineColor = android.graphics.Color.TRANSPARENT
                }
            }
        } catch (e: Exception) {
            MapView(context)
        }
    }

    val labelsOverlay = remember(mapView) {
        try {
            val labelsProvider = MapTileProviderBasic(context, SatelliteLabelsTileSource)
            TilesOverlay(labelsProvider, context).apply {
                loadingBackgroundColor = android.graphics.Color.TRANSPARENT
                loadingLineColor = android.graphics.Color.TRANSPARENT
            }
        } catch (e: Exception) {
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mapView.onDetach()
            } catch (e: Exception) {
            }
        }
    }

    var lastPointsHash by remember { mutableStateOf(0) }
    var hasInitialCameraSet by remember { mutableStateOf(false) }
    // Drives the loading overlay. Reset to false whenever the underlying dataset changes,
    // so switching officers/modes shows a brief clean loading state instead of a stale frame.
    var isMapReady by remember { mutableStateOf(false) }

    val currentPointsHash = remember(routePoints, routeMode) {
        routePoints.hashCode() + routeMode.hashCode()
    }

    if (lastPointsHash != currentPointsHash) {
        lastPointsHash = currentPointsHash
        hasInitialCameraSet = false
        isMapReady = false
    }

    // Once the camera has been positioned for the first time, wait a small minimum
    // duration (so it doesn't flicker) before revealing the map. This guarantees the
    // user only ever sees a polished spinner, never a half-loaded/blank tile grid.
    LaunchedEffect(hasInitialCameraSet, currentPointsHash) {
        if (hasInitialCameraSet) {
            delay(MIN_LOADING_MILLIS)
            isMapReady = true
        }
    }

    Box(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                try {
                    view.overlays.clear()
                    labelsOverlay?.let { view.overlays.add(it) }

                    val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            view.overlays.filterIsInstance<Marker>()
                                .forEach { it.closeInfoWindow() }
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    })
                    view.overlays.add(eventsOverlay)

                    val validPoints = routePoints.filter {
                        it.lat != 0.0 && it.lng != 0.0
                                && it.lat in -90.0..90.0
                                && it.lng in -180.0..180.0
                    }
                    if (validPoints.isEmpty()) return@AndroidView

                    val geoPoints = validPoints.map { GeoPoint(it.lat, it.lng) }

                    try {
                        val line = Polyline(view).apply {
                            setPoints(geoPoints)
                            outlinePaint.color = android.graphics.Color.parseColor("#FFEB3B")
                            outlinePaint.strokeWidth = 10f
                            setOnClickListener { _, _, _ ->
                                view.overlays.filterIsInstance<Marker>()
                                    .forEach { it.closeInfoWindow() }
                                true
                            }
                        }
                        view.overlays.add(line)
                    } catch (e: Exception) {
                    }

                    validPoints.forEachIndexed { index, point ->
                        try {
                            val isVerified = point.isVerified == true
                            val resolvedAddress =
                                point.address ?: "Lat %.5f, Lng %.5f".format(point.lat, point.lng)

                            val marker = Marker(view).apply {
                                position = GeoPoint(point.lat, point.lng)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

                                title = if (isVerified) {
                                    "✅ Verified: $resolvedAddress\nTime: ${point.timestamp}"
                                } else {
                                    "Recorded at: ${point.timestamp}\n$resolvedAddress"
                                }

                                icon = if (index == validPoints.size - 1) {
                                    officerIcon ?: createSmallDot(context, isVerified)
                                } else {
                                    createSmallDot(context, isVerified)
                                }

                                if (point == selectedPoint) {
                                    showInfoWindow()
                                }

                                setOnMarkerClickListener { m, _ ->
                                    view.overlays.filterIsInstance<Marker>()
                                        .forEach { it.closeInfoWindow() }
                                    onPointClick(point)
                                    m.showInfoWindow()
                                    view.controller.animateTo(m.position, 18.5, 800L)
                                    true
                                }
                            }
                            view.overlays.add(marker)
                        } catch (e: Exception) {
                        }

                        if (routeMode == RouteMode.LIVE && index > 0) {
                            try {
                                val prev = validPoints[index - 1]
                                val prevGeo = GeoPoint(prev.lat, prev.lng)
                                val curGeo = GeoPoint(point.lat, point.lng)
                                if (prevGeo.distanceToAsDouble(curGeo) > 10.0) {
                                    val bearing = prevGeo.bearingTo(curGeo).toFloat()
                                    val midGeo = prevGeo.destinationPoint(
                                        prevGeo.distanceToAsDouble(curGeo) / 2,
                                        bearing.toDouble()
                                    )

                                    val arrow = Marker(view).apply {
                                        position = midGeo
                                        icon = createArrowIcon(context)
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                        infoWindow = null
                                        isFlat = true
                                        rotation = -bearing
                                        setOnMarkerClickListener { _, _ ->
                                            view.overlays.filterIsInstance<Marker>()
                                                .forEach { it.closeInfoWindow() }
                                            true
                                        }
                                    }
                                    view.overlays.add(arrow)
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }

                    if (selectedPoint != null) {
                        val targetGeo = GeoPoint(selectedPoint.lat, selectedPoint.lng)
                        if (!hasInitialCameraSet) {
                            view.controller.setCenter(targetGeo)
                            view.controller.setZoom(18.5)
                            hasInitialCameraSet = true
                        } else {
                            view.controller.animateTo(targetGeo, 18.5, 800L)
                        }
                    } else if (!hasInitialCameraSet) {
                        if (geoPoints.size >= 2) {
                            try {
                                val bbox = BoundingBox.fromGeoPoints(geoPoints)
                                view.zoomToBoundingBox(bbox.increaseByScale(1.3f), false)
                                hasInitialCameraSet = true
                            } catch (e: Exception) {
                            }
                        } else if (geoPoints.size == 1) {
                            view.controller.setCenter(geoPoints.first())
                            view.controller.setZoom(16.0)
                            hasInitialCameraSet = true
                        }
                    }

                    view.invalidate()
                } catch (e: Exception) {
                }
            }
        )

        // Loading overlay — shown until the camera has settled on real data. Replaces any
        // chance of the user seeing a blank/placeholder tile grid or an error-looking screen.
        AnimatedVisibility(
            visible = !isMapReady,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF101418)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color(0xFF26A69A),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Loading satellite map…",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.9f),
            shadowElevation = 4.dp
        ) {
            IconButton(onClick = onFullScreenClick) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "Full Screen",
                    tint = Color(0xFF009688)
                )
            }
        }
    }
}

private fun createArrowIcon(context: Context): Drawable {
    val size = 40
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#FFA500")
    }
    val path = Path().apply {
        moveTo(size / 2f, size * 0.1f)
        lineTo(size * 0.85f, size * 0.9f)
        lineTo(size / 2f, size * 0.75f)
        lineTo(size * 0.15f, size * 0.9f)
        close()
    }
    canvas.drawPath(path, paint)
    return BitmapDrawable(context.resources, bitmap)
}

private fun createSmallDot(context: Context, isVerified: Boolean = false): Drawable {
    val size = if (isVerified) 80 else 60
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isVerified)
            android.graphics.Color.parseColor("#2ECC71")
        else
            android.graphics.Color.parseColor("#00B2DB")
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, borderPaint)
    return BitmapDrawable(context.resources, bitmap)
}