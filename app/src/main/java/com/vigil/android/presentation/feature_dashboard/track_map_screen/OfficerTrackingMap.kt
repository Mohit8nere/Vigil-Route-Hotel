package com.vigil.android.presentation.feature_dashboard.track_map_screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigil.android.core.di.model.dashboard_model.RoutePoint
import kotlin.collections.dropLast

/**
 * Lightweight, dependency-free visualisation of an officer's patrol trail.
 *
 * This is a placeholder: it draws [routePoints] on a plain Canvas, scaled to
 * fit, so the screen is fully functional before the Maps API key and live
 * tracking backend are ready.
 *
 * To upgrade later:
 *  1. Add `com.google.maps.android:maps-compose` and a Maps API key.
 *  2. Replace the Box/Canvas body below with a `GoogleMap { ... }` composable.
 *  3. Plot the same [routePoints] as `Marker`/`Polyline` - callers of this
 *     composable (RouteTrackingCard) don't need to change at all.
 */
@Composable
fun OfficerTrackingMap(
    routePoints: List<RoutePoint>,
    modifier: Modifier = Modifier,
    trailColor: Color =   Color(0xFF009688),
    liveColor: Color = Color(0xFFFF5252)
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFF9FF))
    ) {
        if (routePoints.isEmpty()) {
            Text(
                text = "No location data available",
                modifier = Modifier.align(Alignment.Center),
                color = Color.Gray
            )
        } else {
            val lats = routePoints.map { it.lat }
            val lngs = routePoints.map { it.lng }
            val minLat = lats.min()
            val maxLat = lats.max()
            val minLng = lngs.min()
            val maxLng = lngs.max()
            val latSpan = (maxLat - minLat).let { if (it > 0.0001) it else 0.0001 }
            val lngSpan = (maxLng - minLng).let { if (it > 0.0001) it else 0.0001 }

            Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                fun project(point: RoutePoint): Offset {
                    val x = ((point.lng - minLng) / lngSpan) * size.width
                    // Latitude increases upward but canvas Y increases downward, so invert.
                    val y = size.height - ((point.lat - minLat) / latSpan) * size.height
                    return Offset(x.toFloat(), y.toFloat())
                }

                // Dashed trail connecting each ping in order.
                for (i in 0 until routePoints.size - 1) {
                    drawLine(
                        color = trailColor,
                        start = project(routePoints[i]),
                        end = project(routePoints[i + 1]),
                        strokeWidth = 6f,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)
                    )
                }

                // Waypoint dots for every ping except the latest.
                routePoints.dropLast(1).forEach { point ->
                    drawCircle(color = trailColor, radius = 10f, center = project(point))
                    drawCircle(color = Color.White, radius = 4f, center = project(point))
                }

                // Highlight the most recent position.
                val last = routePoints.last()
                drawCircle(color = liveColor, radius = 16f, center = project(last))
                drawCircle(color = Color.White, radius = 6f, center = project(last))
            }

            Text(
                text = "Map preview (dummy data) \u2013 live GPS pending API",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Gray,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            )
        }
    }
}


//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.foundation.shape.RoundedCornerShape
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.remember
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.draw.clip
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.unit.dp
//import com.vigil.android_dashboard.officer_dashboard.model.RoutePoint
//import com.google.android.gms.maps.model.BitmapDescriptorFactory
//import com.google.android.gms.maps.model.CameraPosition
//import com.google.android.gms.maps.model.LatLng
//import com.google.maps.android.compose.GoogleMap
//import com.google.maps.android.compose.Marker
//import com.google.maps.android.compose.MarkerState
//import com.google.maps.android.compose.Polyline
//import com.google.maps.android.compose.rememberCameraPositionState

/**
 * Real Google Maps version of the tracking view.
 *
 * Drop-in replacement for [OfficerTrackingMap] - identical `routePoints`
 * input, so switching is a one-line change in RouteTrackingCard once setup
 * below is done:
 *
 *   OfficerTrackingMap(routePoints = routePoints, ...)   // before
 *   OfficerGoogleMap(routePoints = routePoints, ...)      // after
 *
 * ---------------------------------------------------------------------
 * SETUP (do this when the Maps API key is ready - not needed before then)
 * ---------------------------------------------------------------------
 * 1. app/build.gradle.kts dependencies:
 *      implementation("com.google.maps.android:maps-compose:4.3.3")
 *      implementation("com.google.android.gms:play-services-maps:18.2.0")
 *
 * 2. AndroidManifest.xml, inside <application>:
 *      <meta-data
 *          android:name="com.google.android.geo.API_KEY"
 *          android:value="${MAPS_API_KEY}" />
 *    (Reference a gradle property/secret rather than hardcoding the key -
 *    e.g. via the Secrets Gradle Plugin for Android, so it never lands in
 *    source control.)
 *
 * 3. Get the key from Google Cloud Console: enable "Maps SDK for Android",
 *    then restrict the key to your app's package name + release/debug SHA-1
 *    fingerprints.
 *
 * 4. AndroidManifest.xml already needs INTERNET permission for API calls
 *    elsewhere in the app; Maps rendering uses the same permission, no new
 *    location permission is required just to *display* dummy/API points
 *    (only add ACCESS_FINE_LOCATION later if you want to show the device's
 *    own live GPS position on the map).
 */
//@Composable
//fun OfficerGoogleMap(
//    routePoints: List<RoutePoint>,
//    modifier: Modifier = Modifier
//) {
//    if (routePoints.isEmpty()) return
//
//    val latLngPoints = remember(routePoints) { routePoints.map { LatLng(it.lat, it.lng) } }
//    val current = latLngPoints.last()
//
//    val cameraPositionState = rememberCameraPositionState {
//        position = CameraPosition.fromLatLngZoom(current, 15f)
//    }
//
//    GoogleMap(
//        modifier = modifier
//            .fillMaxSize()
//            .clip(RoundedCornerShape(12.dp)),
//        cameraPositionState = cameraPositionState
//    ) {
//        // Trail connecting every ping in order.
//        Polyline(
//            points = latLngPoints,
//            color = Color(0xFF0083B0),
//            width = 8f
//        )
//
//        // Earlier checkpoints.
//        latLngPoints.dropLast(1).forEach { point ->
//            Marker(
//                state = MarkerState(position = point),
//                title = "Checkpoint"
//            )
//        }
//
//        // Most recent / current position, highlighted.
//        Marker(
//            state = MarkerState(position = current),
//            title = "Current position",
//            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
//        )
//    }
//}