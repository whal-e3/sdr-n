package org.satelliteeavesdropper.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.satelliteeavesdropper.orbit.ObserverLocation
import java.util.Locale
import kotlin.math.abs

private data class MapPoint(val longitude: Double, val latitude: Double)
private data class LandPolygon(val rings: List<List<MapPoint>>)

/** Equirectangular viewport; longitude wraps while latitude stops at the poles. */
internal data class WorldMapViewport(
    val centerLatitude: Double = 0.0,
    val centerLongitude: Double = 0.0,
    val zoom: Double = 1.0,
) {
    fun coordinateAt(x: Double, y: Double, width: Double, height: Double): ObserverLocation {
        require(width > 0.0 && height > 0.0)
        return ObserverLocation(
            latitudeDegrees = (centerLatitude - (y - height / 2.0) * 180.0 / (height * zoom)).coerceIn(-90.0, 90.0),
            longitudeDegrees = wrapLongitude(centerLongitude + (x - width / 2.0) * 360.0 / (width * zoom)),
        )
    }

    fun transformed(
        centroidX: Double,
        centroidY: Double,
        panX: Double,
        panY: Double,
        zoomChange: Double,
        width: Double,
        height: Double,
    ): WorldMapViewport {
        if (width <= 0.0 || height <= 0.0) return this
        val anchor = coordinateAt(centroidX - panX, centroidY - panY, width, height)
        val newZoom = (zoom * zoomChange).coerceIn(1.0, 16.0)
        val latitudeLimit = 90.0 - 90.0 / newZoom
        return WorldMapViewport(
            centerLatitude = (anchor.latitudeDegrees + (centroidY - height / 2.0) * 180.0 / (height * newZoom))
                .coerceIn(-latitudeLimit, latitudeLimit),
            centerLongitude = wrapLongitude(
                anchor.longitudeDegrees - (centroidX - width / 2.0) * 360.0 / (width * newZoom),
            ),
            zoom = newZoom,
        )
    }
}

internal fun wrapLongitude(longitude: Double): Double = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

/**
 * Offline map picker backed by Natural Earth land outlines. Tapping chooses a coordinate; the
 * confirmation button passes it to the caller. The map data is bundled, so no map account or
 * network connection is required. It is a regional locator, not a street map.
 */
@Composable
fun WorldMapPicker(
    currentLocation: ObserverLocation?,
    onLocationChosen: (ObserverLocation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val land by produceState<List<LandPolygon>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { runCatching { loadLand(context) }.getOrDefault(emptyList()) }
    }
    var viewport by remember {
        mutableStateOf(
            currentLocation?.let { WorldMapViewport(it.latitudeDegrees.coerceIn(-54.0, 54.0), it.longitudeDegrees, 2.5) }
                ?: WorldMapViewport(),
        )
    }
    var candidate by remember { mutableStateOf(currentLocation) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose on map", style = MaterialTheme.typography.titleMedium)
        Text("Tap to choose a point. Drag to pan and pinch to zoom.", style = MaterialTheme.typography.bodySmall)
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(12.dp))
                .semantics {
                    contentDescription = "Offline world map. Drag to pan, pinch to zoom, or tap to choose a point."
                    onClick(label = "Choose the map center") {
                        candidate = ObserverLocation(viewport.centerLatitude, viewport.centerLongitude)
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var multiplePointers = false
                        var accumulatedPan = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1) multiplePointers = true
                            if (event.changes.any { it.pressed }) {
                                val pan = event.calculatePan()
                                val scale = event.calculateZoom()
                                accumulatedPan += pan
                                if (accumulatedPan.getDistance() > viewConfiguration.touchSlop || abs(scale - 1f) > 0.01f) {
                                    moved = true
                                }
                                if (moved) {
                                    val center = event.calculateCentroid(useCurrent = true)
                                    viewport = viewport.transformed(
                                        center.x.toDouble(), center.y.toDouble(), pan.x.toDouble(), pan.y.toDouble(),
                                        scale.toDouble(), size.width.toDouble(), size.height.toDouble(),
                                    )
                                    event.changes.forEach { it.consume() }
                                }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                        if (!moved && !multiplePointers) {
                            candidate = viewport.coordinateAt(
                                down.position.x.toDouble(), down.position.y.toDouble(),
                                size.width.toDouble(), size.height.toDouble(),
                            )
                        }
                    }
                },
        ) {
            val ocean = Color(0xFF102B39)
            val landColor = Color(0xFF547E71)
            val coastline = Color(0xFFA7CAB6)
            val graticule = Color(0xFF8EAAB4).copy(alpha = 0.28f)
            val marker = Color(0xFFFFCF76)
            drawRect(ocean)
            clipRect {
                for (latitude in -60..60 step 30) {
                    val y = size.height / 2f + ((viewport.centerLatitude - latitude) / 180.0 * size.height * viewport.zoom).toFloat()
                    if (y in 0f..size.height) drawLine(graticule, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                for (longitude in -180..180 step 30) {
                    for (repeat in -1..1) {
                        val x = size.width / 2f + ((longitude + repeat * 360.0 - viewport.centerLongitude) / 360.0 * size.width * viewport.zoom).toFloat()
                        if (x in 0f..size.width) drawLine(graticule, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                }
                land?.forEach { polygon ->
                    for (repeat in -1..1) {
                        val path = Path().apply { fillType = PathFillType.EvenOdd }
                        polygon.rings.forEach { ring ->
                            ring.forEachIndexed { index, point ->
                                val x = size.width / 2f + ((point.longitude + repeat * 360.0 - viewport.centerLongitude) / 360.0 * size.width * viewport.zoom).toFloat()
                                val y = size.height / 2f + ((viewport.centerLatitude - point.latitude) / 180.0 * size.height * viewport.zoom).toFloat()
                                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            path.close()
                        }
                        drawPath(path, landColor)
                        drawPath(path, coastline, style = Stroke(width = 0.8.dp.toPx()))
                    }
                }
                candidate?.let { selection ->
                    val deltaLongitude = wrapLongitude(selection.longitudeDegrees - viewport.centerLongitude)
                    val x = size.width / 2f + (deltaLongitude / 360.0 * size.width * viewport.zoom).toFloat()
                    val y = size.height / 2f + ((viewport.centerLatitude - selection.latitudeDegrees) / 180.0 * size.height * viewport.zoom).toFloat()
                    if (x in 0f..size.width && y in 0f..size.height) {
                        drawCircle(ocean, radius = 9.dp.toPx(), center = Offset(x, y))
                        drawCircle(marker, radius = 7.dp.toPx(), center = Offset(x, y))
                        drawCircle(ocean, radius = 2.dp.toPx(), center = Offset(x, y))
                    }
                }
                drawLine(marker.copy(alpha = 0.75f), Offset(size.width / 2f - 7.dp.toPx(), size.height / 2f), Offset(size.width / 2f + 7.dp.toPx(), size.height / 2f), 1.dp.toPx())
                drawLine(marker.copy(alpha = 0.75f), Offset(size.width / 2f, size.height / 2f - 7.dp.toPx()), Offset(size.width / 2f, size.height / 2f + 7.dp.toPx()), 1.dp.toPx())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewport = WorldMapViewport() }) { Text("World") }
            OutlinedButton(onClick = { viewport = viewport.transformed(0.5, 0.5, 0.0, 0.0, 1.0 / 1.7, 1.0, 1.0) }) { Text("Zoom −") }
            OutlinedButton(onClick = { viewport = viewport.transformed(0.5, 0.5, 0.0, 0.0, 1.7, 1.0, 1.0) }) { Text("Zoom +") }
        }
        OutlinedButton(onClick = {
            candidate = ObserverLocation(viewport.centerLatitude, viewport.centerLongitude)
        }) { Text("Select map center") }
        Text(
            candidate?.let { "Selected: ${formatMapCoordinate(it.latitudeDegrees, true)}, ${formatMapCoordinate(it.longitudeDegrees, false)}" }
                ?: "Tap the map to select a location.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { candidate?.let(onLocationChosen) }, enabled = candidate != null) {
            Text("Set observer location")
        }
        Text("Map outlines: Natural Earth (public domain). Coordinates use WGS84; map detail is regional.", style = MaterialTheme.typography.labelSmall)
        if (land?.isEmpty() == true) Text("Map outlines could not be loaded.", color = MaterialTheme.colorScheme.error)
    }
}

private fun formatMapCoordinate(value: Double, latitude: Boolean): String =
    String.format(Locale.US, "%.5f° %s", abs(value), if (latitude) { if (value >= 0) "N" else "S" } else { if (value >= 0) "E" else "W" })

private fun loadLand(context: Context): List<LandPolygon> {
    val json = context.assets.open("maps/ne_110m_land.geojson").bufferedReader().use { JSONObject(it.readText()) }
    val features = json.getJSONArray("features")
    return List(features.length()) { index ->
        val rings = features.getJSONObject(index).getJSONObject("geometry").getJSONArray("coordinates")
        LandPolygon(List(rings.length()) { ringIndex ->
            val points = rings.getJSONArray(ringIndex)
            List(points.length()) { pointIndex ->
                val point = points.getJSONArray(pointIndex)
                MapPoint(point.getDouble(0), point.getDouble(1))
            }
        })
    }
}
