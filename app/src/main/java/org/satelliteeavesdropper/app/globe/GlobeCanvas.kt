package org.satelliteeavesdropper.app.globe

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.OrbitColors
import org.satelliteeavesdropper.orbit.EARTH_RADIUS_KM
import org.satelliteeavesdropper.orbit.EarthFixedPosition
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.earthSurfacePosition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal data class GlobeMarker(
    val noradId: String,
    val name: String,
    val position: EarthFixedPosition,
    val aboveHorizon: Boolean = false,
)

private data class GlobeSurfaceState(val bitmap: Bitmap? = null, val failed: Boolean = false)

/**
 * Offline orthographic 3D Earth. Positions and trails are supplied by the orbit engine;
 * rendering has no animation loop, network access, or influence on receiver time.
 */
@Composable
internal fun GlobeCanvas(
    markers: List<GlobeMarker>,
    selectedNoradId: String?,
    orbitPath: List<EarthFixedPosition>,
    groundTrack: List<EarthFixedPosition>,
    observer: ObserverLocation?,
    onSatelliteSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val surface by produceState(GlobeSurfaceState(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching { GlobeSurfaceState(loadGlobeSurface(context)) }.getOrElse { GlobeSurfaceState(failed = true) }
        }
    }
    var latitude by rememberSaveable { mutableDoubleStateOf(observer?.latitudeDegrees ?: 20.0) }
    var longitude by rememberSaveable { mutableDoubleStateOf(observer?.longitudeDegrees ?: 0.0) }
    var zoom by rememberSaveable { mutableDoubleStateOf(1.0) }
    var focusRequestedNoradId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFocusedNoradId by rememberSaveable { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val selected = markers.firstOrNull { it.noradId == selectedNoradId }
    val camera = remember(latitude, longitude) { GlobeCamera(latitude, longitude) }
    val extent = remember(selected?.position, orbitPath) { stableGlobeExtentKm(globeExtentKm(selected?.position, orbitPath)) }
    val scale = canvasSize.width.coerceAtMost(canvasSize.height) * 0.46 * zoom / extent
    val projection = remember(camera, canvasSize, scale) {
        GlobeProjection(camera, canvasSize.width / 2.0, canvasSize.height / 2.0, scale)
    }
    // Keep default gestures light on older phones; retain detail when the surface is enlarged.
    val hemisphere = if (zoom >= 2.5) GlobeHemisphere.detailed else GlobeHemisphere.standard
    val meshVertices = remember(hemisphere, canvasSize, scale) { hemisphere.screenVertices(projection) }
    val textureMesh = remember(hemisphere, camera) { hemisphere.textureMesh(camera) }
    val graticule = remember(projection) { globeGraticule(projection) }
    val surfacePaint = remember(surface.bitmap) {
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            surface.bitmap?.let { shader = BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP) }
        }
    }
    val density = LocalDensity.current
    val labelPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OrbitColors.white.toArgb()
            textSize = with(density) { 12.sp.toPx() }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val currentMarkers by rememberUpdatedState(markers)
    val currentProjection by rememberUpdatedState(projection)
    val currentOnSatelliteSelected by rememberUpdatedState(onSatelliteSelected)
    val tapTolerance = with(density) { 20.dp.toPx().toDouble() }
    val visibleCount = remember(markers, projection) {
        markers.count {
            val point = projection.camera.project(it.position)
            globePointVisible(point) && projection.screenX(point) in 0.0..canvasSize.width.toDouble() &&
                projection.screenY(point) in 0.0..canvasSize.height.toDouble()
        }
    }
    val selectedPoint = selected?.let { camera.project(it.position) }
    val selectedViewStatus = when {
        selectedPoint == null -> null
        !globePointVisible(selectedPoint) -> "Selected target is behind Earth · tap Target to center"
        projection.screenX(selectedPoint) !in 0.0..canvasSize.width.toDouble() ||
            projection.screenY(selectedPoint) !in 0.0..canvasSize.height.toDouble() -> "Selected target is outside view · tap Fit orbit"
        else -> null
    }

    // Focus a new selection once its marker arrives. Filter/search rebuilds can temporarily
    // remove every marker; retain camera and focus history across those gaps and recreation.
    LaunchedEffect(selectedNoradId, selected?.noradId) {
        if (focusRequestedNoradId != selectedNoradId) {
            focusRequestedNoradId = selectedNoradId
            lastFocusedNoradId = null
        }
        selected?.takeIf { it.noradId != lastFocusedNoradId }?.let {
            latitude = it.position.geocentricLatitudeDegrees
            longitude = it.position.longitudeDegrees
            zoom = 1.0
            lastFocusedNoradId = it.noradId
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))) {
            Canvas(
                Modifier.matchParentSize().onSizeChanged { canvasSize = it }.testTag("globe_canvas")
                    .semantics {
                        contentDescription = buildString {
                            append("3D Earth globe, $visibleCount satellite markers in view. Drag to rotate, pinch to zoom, and tap a satellite to select it.")
                            selected?.let { append(" Selected satellite: ${it.name}, NORAD ${it.noradId}.") }
                            selectedViewStatus?.let { append(" $it.") }
                        }
                        customActions = listOf(
                            CustomAccessibilityAction("Rotate west") { longitude = wrapGlobeLongitude(longitude - 30.0); true },
                            CustomAccessibilityAction("Rotate east") { longitude = wrapGlobeLongitude(longitude + 30.0); true },
                            CustomAccessibilityAction("Rotate north") { latitude = (latitude + 20.0).coerceIn(-90.0, 90.0); true },
                            CustomAccessibilityAction("Rotate south") { latitude = (latitude - 20.0).coerceIn(-90.0, 90.0); true },
                        )
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
                                    val zoomChange = event.calculateZoom()
                                    accumulatedPan += pan
                                    if (accumulatedPan.getDistance() > viewConfiguration.touchSlop || abs(zoomChange - 1f) > 0.01f) moved = true
                                    if (moved || multiplePointers) {
                                        val diameter = (EARTH_RADIUS_KM * currentProjection.pixelsPerKm * 2.0).coerceAtLeast(size.width * 0.35)
                                        longitude = wrapGlobeLongitude(longitude - pan.x / diameter * 180.0)
                                        latitude = (latitude + pan.y / diameter * 180.0).coerceIn(-90.0, 90.0)
                                        zoom = (zoom * zoomChange).coerceIn(0.6, 6.0)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                if (event.changes.none { it.pressed }) break
                            }
                            if (!moved && !multiplePointers) {
                                globeMarkerAt(currentMarkers, currentProjection, down.position.x.toDouble(), down.position.y.toDouble(), tapTolerance)
                                    ?.let(currentOnSatelliteSelected)
                            }
                        }
                    },
            ) {
                drawRect(Brush.radialGradient(listOf(OrbitColors.surface, OrbitColors.background), radius = size.maxDimension * 0.7f))
                for (index in 0 until 42) {
                    val x = ((index * 73 + 19) % 997) / 997f * size.width
                    val y = ((index * 193 + 83) % 991) / 991f * size.height
                    drawCircle(OrbitColors.muted.copy(alpha = if (index % 5 == 0) 0.38f else 0.16f), if (index % 5 == 0) 1.dp.toPx() else 0.6.dp.toPx(), Offset(x, y))
                }
                val center = Offset(projection.centerX.toFloat(), projection.centerY.toFloat())
                val earthPixels = (EARTH_RADIUS_KM * scale).toFloat()
                if (earthPixels > 0f) {
                    clipRect {
                        drawCircle(
                            Brush.radialGradient(
                                0.0f to Color.Transparent,
                                0.88f to Color.Transparent,
                                0.94f to OrbitColors.cyan.copy(alpha = 0.16f),
                                1.0f to Color.Transparent,
                                center = center,
                                radius = earthPixels * 1.065f,
                            ),
                            earthPixels * 1.065f, center,
                        )
                        drawCircle(Brush.radialGradient(listOf(Color(0xFF244B5B), Color(0xFF102B3B)), center, earthPixels), earthPixels, center)
                        if (surface.bitmap != null) drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawVertices(
                                android.graphics.Canvas.VertexMode.TRIANGLES,
                                meshVertices.size, meshVertices, 0, textureMesh.textureCoordinates, 0,
                                hemisphere.shades, 0, textureMesh.indices, 0, textureMesh.indices.size, surfacePaint,
                            )
                        }
                        drawPath(graticule, OrbitColors.cyan.copy(alpha = 0.14f), style = Stroke(0.6.dp.toPx()))
                        drawCircle(OrbitColors.cyan.copy(alpha = 0.46f), earthPixels, center, style = Stroke(1.dp.toPx()))
                        drawGlobeTrail(groundTrack, projection, OrbitColors.cyan.copy(alpha = 0.7f), 1.2.dp.toPx(), surface = true, dashed = true)
                        drawGlobeTrail(orbitPath, projection, OrbitColors.amber.copy(alpha = 0.85f), 1.5.dp.toPx())

                        val observerPosition = observer?.let { globeSurfacePosition(earthSurfacePosition(it)) }
                        if (observerPosition != null && selected?.aboveHorizon == true) {
                            val from = camera.project(observerPosition)
                            val to = camera.project(selected.position)
                            visibleGlobeSegments(from, to).forEach { (start, end) ->
                                drawLine(OrbitColors.cyan.copy(alpha = 0.8f), projection.offset(start), projection.offset(end), 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                            }
                        }
                        markers.map { it to camera.project(it.position) }.filter { globePointVisible(it.second) }
                            .sortedBy { it.second.z }.forEach { (marker, point) ->
                                val position = projection.offset(point)
                                if (position.x !in -16.dp.toPx()..(size.width + 16.dp.toPx()) || position.y !in -16.dp.toPx()..(size.height + 16.dp.toPx())) return@forEach
                                val isSelected = marker.noradId == selectedNoradId
                                val color = when {
                                    isSelected -> OrbitColors.amber
                                    marker.aboveHorizon -> OrbitColors.cyan
                                    else -> OrbitColors.blue
                                }
                                drawCircle(color.copy(alpha = if (isSelected) 0.18f else 0.10f), if (isSelected) 11.dp.toPx() else 6.dp.toPx(), position)
                                drawCircle(color, if (isSelected) 4.dp.toPx() else 2.8.dp.toPx(), position)
                                if (isSelected) drawCircle(OrbitColors.white, 7.dp.toPx(), position, style = Stroke(1.2.dp.toPx()))
                            }
                        observerPosition?.let { position ->
                            val point = camera.project(position)
                            if (point.z >= 0.0) {
                                val screen = projection.offset(point)
                                drawCircle(OrbitColors.background, 6.dp.toPx(), screen)
                                drawCircle(OrbitColors.white, 3.5.dp.toPx(), screen)
                                drawCircle(OrbitColors.cyan, 6.dp.toPx(), screen, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                        if (selected != null && selectedPoint != null && globePointVisible(selectedPoint)) {
                            drawGlobeMarkerLabel(selected.name, projection.offset(selectedPoint), labelPaint)
                        }
                    }
                }
            }
            Text(
                "$visibleCount markers in view",
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = OrbitColors.muted,
            )
            Column(Modifier.align(Alignment.CenterEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(
                    onClick = { zoom = (zoom * 1.4).coerceAtMost(6.0) },
                    modifier = Modifier.testTag("globe_zoom_in").semantics { contentDescription = "Zoom in on globe" },
                    enabled = zoom < 6.0,
                ) { Text("+", fontSize = 22.sp) }
                FilledTonalIconButton(
                    onClick = { zoom = (zoom / 1.4).coerceAtLeast(0.6) },
                    modifier = Modifier.testTag("globe_zoom_out").semantics { contentDescription = "Zoom out on globe" },
                    enabled = zoom > 0.6,
                ) { Text("−", fontSize = 22.sp) }
            }
            Text(
                selectedViewStatus ?: "Drag to rotate · pinch to zoom",
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = OrbitColors.muted,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { observer?.let { latitude = it.latitudeDegrees; longitude = it.longitudeDegrees; zoom = 1.0 } },
                enabled = observer != null,
                modifier = Modifier.weight(1f).testTag("globe_focus_observer"),
            ) { Text("Observer", maxLines = 1) }
            OutlinedButton(
                onClick = { selected?.let { latitude = it.position.geocentricLatitudeDegrees; longitude = it.position.longitudeDegrees; zoom = 1.0 } },
                enabled = selected != null,
                modifier = Modifier.weight(1f).testTag("globe_focus_target"),
            ) { Text("Target", maxLines = 1) }
            OutlinedButton(onClick = { zoom = 1.0 }, modifier = Modifier.weight(1f).testTag("globe_fit_orbit")) { Text("Fit orbit", maxLines = 1) }
        }
        Text(
            "Amber: selected orbit · cyan: ground track and above horizon · blue: other satellites · white: observer. Altitudes use the same scale as Earth.",
            style = MaterialTheme.typography.labelSmall,
            color = OrbitColors.muted,
        )
        if (surface.failed) Text("Earth outlines could not be loaded.", style = MaterialTheme.typography.bodySmall, color = OrbitColors.amber)
    }
}

private fun GlobeProjection.offset(point: GlobeCameraPoint) = Offset(screenX(point).toFloat(), screenY(point).toFloat())

private fun globeGraticule(projection: GlobeProjection): Path = Path().apply {
    for (line in globeGraticuleCoordinates) {
        var previous = projection.camera.project(line.first())
        for (index in 1 until line.size) {
            val current = projection.camera.project(line[index])
            visibleGlobeSurfaceSegment(previous, current)?.let { (start, end) ->
                moveTo(projection.screenX(start).toFloat(), projection.screenY(start).toFloat())
                lineTo(projection.screenX(end).toFloat(), projection.screenY(end).toFloat())
            }
            previous = current
        }
    }
}

private val globeGraticuleCoordinates: List<List<EarthFixedPosition>> by lazy {
    buildList {
        for (latitude in -60..60 step 30) {
            add((-180..180 step 3).map { globeCoordinate(latitude.toDouble(), it.toDouble()) })
        }
        for (longitude in -180 until 180 step 30) {
            add((-90..90 step 3).map { globeCoordinate(it.toDouble(), longitude.toDouble()) })
        }
    }
}

private fun globeCoordinate(latitudeDegrees: Double, longitudeDegrees: Double): EarthFixedPosition {
    val latitude = latitudeDegrees * PI / 180.0
    val longitude = longitudeDegrees * PI / 180.0
    return EarthFixedPosition(EARTH_RADIUS_KM * cos(latitude) * cos(longitude), EARTH_RADIUS_KM * cos(latitude) * sin(longitude), EARTH_RADIUS_KM * sin(latitude))
}

private fun DrawScope.drawGlobeTrail(
    positions: List<EarthFixedPosition>,
    projection: GlobeProjection,
    color: Color,
    width: Float,
    surface: Boolean = false,
    dashed: Boolean = false,
) {
    if (positions.size < 2) return
    val path = Path()
    var previousEnd: Offset? = null
    for ((from, to) in positions.zipWithNext()) {
        val start = projection.camera.project(if (surface) globeSurfacePosition(from) else from)
        val end = projection.camera.project(if (surface) globeSurfacePosition(to) else to)
        val segments = if (surface) listOfNotNull(visibleGlobeSurfaceSegment(start, end)) else visibleGlobeSegments(start, end)
        if (segments.isEmpty()) previousEnd = null
        for ((visibleStart, visibleEnd) in segments) {
            val screenStart = projection.offset(visibleStart)
            val screenEnd = projection.offset(visibleEnd)
            if (previousEnd == null || (screenStart - previousEnd).getDistance() > 0.1f) path.moveTo(screenStart.x, screenStart.y)
            path.lineTo(screenEnd.x, screenEnd.y)
            previousEnd = screenEnd
        }
    }
    drawPath(path, color, style = Stroke(width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null))
}

private fun DrawScope.drawGlobeMarkerLabel(name: String, marker: Offset, paint: Paint) {
    if (marker.x !in 0f..size.width || marker.y !in 0f..size.height) return
    var label = name.take(36)
    val maximumWidth = size.width * 0.62f
    while (label.length > 1 && paint.measureText(label) > maximumWidth) label = label.dropLast(1)
    if (label != name) label += "…"
    val padding = 7.dp.toPx()
    val labelWidth = paint.measureText(label) + padding * 2f
    val labelHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent + padding * 1.6f
    val left = (marker.x + 12.dp.toPx()).coerceIn(8.dp.toPx(), (size.width - labelWidth - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()))
    val top = (marker.y - labelHeight - 8.dp.toPx()).coerceIn(32.dp.toPx(), (size.height - labelHeight - 30.dp.toPx()).coerceAtLeast(32.dp.toPx()))
    drawRoundRect(OrbitColors.background.copy(alpha = 0.88f), Offset(left, top), Size(labelWidth, labelHeight), CornerRadius(6.dp.toPx()))
    drawRoundRect(OrbitColors.amber.copy(alpha = 0.55f), Offset(left, top), Size(labelWidth, labelHeight), CornerRadius(6.dp.toPx()), style = Stroke(0.8.dp.toPx()))
    drawIntoCanvas { it.nativeCanvas.drawText(label, left + padding, top + padding * 0.8f - paint.fontMetrics.ascent, paint) }
}
