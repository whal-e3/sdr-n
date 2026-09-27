package org.satelliteeavesdropper.app.globe

import org.satelliteeavesdropper.orbit.EARTH_RADIUS_KM
import org.satelliteeavesdropper.orbit.EarthFixedPosition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Coordinates in the orthographic camera: right, up, and toward the viewer, in kilometres. */
internal data class GlobeCameraPoint(val x: Double, val y: Double, val z: Double) {
    fun interpolated(other: GlobeCameraPoint, fraction: Double) = GlobeCameraPoint(
        x + (other.x - x) * fraction,
        y + (other.y - y) * fraction,
        z + (other.z - z) * fraction,
    )
}

internal data class GlobeCamera(val latitudeDegrees: Double, val longitudeDegrees: Double) {
    private val latitude = latitudeDegrees * PI / 180.0
    private val longitude = longitudeDegrees * PI / 180.0
    private val sinLatitude = sin(latitude)
    private val cosLatitude = cos(latitude)
    private val sinLongitude = sin(longitude)
    private val cosLongitude = cos(longitude)

    fun project(position: EarthFixedPosition) = GlobeCameraPoint(
        x = -sinLongitude * position.xKm + cosLongitude * position.yKm,
        y = -sinLatitude * cosLongitude * position.xKm - sinLatitude * sinLongitude * position.yKm + cosLatitude * position.zKm,
        z = cosLatitude * cosLongitude * position.xKm + cosLatitude * sinLongitude * position.yKm + sinLatitude * position.zKm,
    )

    fun earthFixed(point: GlobeCameraPoint) = EarthFixedPosition(
        xKm = earthFixedX(point.x, point.y, point.z),
        yKm = earthFixedY(point.x, point.y, point.z),
        zKm = earthFixedZ(point.y, point.z),
    )

    // The renderer also rotates dimensionless mesh coordinates. These dot products avoid
    // allocating/validating an orbit position and calculating its radius for every mesh vertex.
    fun earthFixedX(x: Double, y: Double, z: Double): Double =
        -sinLongitude * x - sinLatitude * cosLongitude * y + cosLatitude * cosLongitude * z

    fun earthFixedY(x: Double, y: Double, z: Double): Double =
        cosLongitude * x - sinLatitude * sinLongitude * y + cosLatitude * sinLongitude * z

    fun earthFixedZ(y: Double, z: Double): Double = cosLatitude * y + sinLatitude * z
}

internal data class GlobeProjection(
    val camera: GlobeCamera,
    val centerX: Double,
    val centerY: Double,
    val pixelsPerKm: Double,
) {
    fun screenX(point: GlobeCameraPoint): Double = centerX + point.x * pixelsPerKm
    fun screenY(point: GlobeCameraPoint): Double = centerY - point.y * pixelsPerKm
}

/** The Earth hides a point only when it is inside the limb and behind the front surface. */
internal fun globePointVisible(point: GlobeCameraPoint, earthRadiusKm: Double = EARTH_RADIUS_KM): Boolean {
    val projectedRadiusSquared = point.x * point.x + point.y * point.y
    val radiusSquared = earthRadiusKm * earthRadiusKm
    if (projectedRadiusSquared >= radiusSquared) return true
    val frontSurface = sqrt(max(0.0, radiusSquared - projectedRadiusSquared))
    return point.z >= frontSurface - 1e-7
}

/**
 * Split a 3D line at sphere/shadow boundaries before drawing it. A rear orbit may be visible
 * outside the limb even though the middle of its projected segment is hidden by the Earth.
 */
internal fun visibleGlobeSegments(
    start: GlobeCameraPoint,
    end: GlobeCameraPoint,
    earthRadiusKm: Double = EARTH_RADIUS_KM,
): List<Pair<GlobeCameraPoint, GlobeCameraPoint>> {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val dz = end.z - start.z
    val cuts = mutableListOf(0.0, 1.0)
    fun addQuadraticRoots(a: Double, b: Double, c: Double) {
        if (abs(a) < 1e-14) {
            if (abs(b) > 1e-14) {
                val root = -c / b
                if (root > 0.0 && root < 1.0) cuts.add(root)
            }
            return
        }
        val discriminant = b * b - 4.0 * a * c
        if (discriminant < 0.0) return
        val root = sqrt(discriminant)
        for (value in listOf((-b - root) / (2.0 * a), (-b + root) / (2.0 * a))) {
            if (value > 0.0 && value < 1.0) cuts.add(value)
        }
    }
    val radiusSquared = earthRadiusKm * earthRadiusKm
    addQuadraticRoots(
        dx * dx + dy * dy,
        2.0 * (start.x * dx + start.y * dy),
        start.x * start.x + start.y * start.y - radiusSquared,
    )
    addQuadraticRoots(
        dx * dx + dy * dy + dz * dz,
        2.0 * (start.x * dx + start.y * dy + start.z * dz),
        start.x * start.x + start.y * start.y + start.z * start.z - radiusSquared,
    )
    if (abs(dz) > 1e-14) {
        val root = -start.z / dz
        if (root > 0.0 && root < 1.0) cuts.add(root)
    }
    return cuts.distinct().sorted().zipWithNext().mapNotNull { (from, to) ->
        if (to - from < 1e-10 || !globePointVisible(start.interpolated(end, (from + to) / 2.0), earthRadiusKm)) null
        else start.interpolated(end, from) to start.interpolated(end, to)
    }
}

/** Surface lines use hemisphere clipping, so their short chords are not mistaken for buried points. */
internal fun visibleGlobeSurfaceSegment(
    start: GlobeCameraPoint,
    end: GlobeCameraPoint,
): Pair<GlobeCameraPoint, GlobeCameraPoint>? {
    if (start.z < 0.0 && end.z < 0.0) return null
    if (start.z >= 0.0 && end.z >= 0.0) return start to end
    val crossing = start.interpolated(end, start.z / (start.z - end.z))
    return if (start.z >= 0.0) start to crossing else crossing to end
}

internal fun globeExtentKm(selected: EarthFixedPosition?, orbit: List<EarthFixedPosition>): Double =
    max(EARTH_RADIUS_KM * 1.28, max(selected?.radiusKm ?: 0.0, orbit.maxOfOrNull { it.radiusKm } ?: 0.0) * 1.12)

/** Round only the camera's framing outward; no orbit coordinate or Earth radius is changed. */
internal fun stableGlobeExtentKm(exactExtentKm: Double): Double = ceil(exactExtentKm / 5.0) * 5.0

/** Snap only the display observer/ground track to the reference sphere, including WGS84 poles. */
internal fun globeSurfacePosition(position: EarthFixedPosition): EarthFixedPosition {
    val multiplier = EARTH_RADIUS_KM / position.radiusKm
    return EarthFixedPosition(position.xKm * multiplier, position.yKm * multiplier, position.zKm * multiplier)
}

internal fun globeMarkerAt(
    markers: List<GlobeMarker>,
    projection: GlobeProjection,
    xPixels: Double,
    yPixels: Double,
    tolerancePixels: Double,
): String? = markers.asSequence().mapNotNull { marker ->
    val point = projection.camera.project(marker.position)
    if (!globePointVisible(point)) return@mapNotNull null
    val dx = projection.screenX(point) - xPixels
    val dy = projection.screenY(point) - yPixels
    val distanceSquared = dx * dx + dy * dy
    if (distanceSquared > tolerancePixels * tolerancePixels) null else Triple(marker.noradId, distanceSquared, point.z)
}.sortedWith(compareBy<Triple<String, Double, Double>> { it.second }.thenByDescending { it.third })
    .firstOrNull()?.first

internal fun wrapGlobeLongitude(longitude: Double): Double = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
