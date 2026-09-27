package org.satelliteeavesdropper.orbit

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 equatorial radius; also the reference radius of the globe's spherical Earth. */
const val EARTH_RADIUS_KM = 6_378.137

/**
 * Position relative to Earth's centre in kilometres. X points to longitude 0°, Y to 90° east,
 * and Z to the north pole. Satellite positions use the same approximate Earth-fixed frame as
 * [SatelliteOrbit.lookFrom]. Geocentric latitude differs slightly from geodetic map latitude.
 */
data class EarthFixedPosition(val xKm: Double, val yKm: Double, val zKm: Double) {
    init {
        require(xKm.isFinite() && yKm.isFinite() && zKm.isFinite()) {
            "Invalid Earth-fixed position"
        }
        require(radiusKm.isFinite() && radiusKm > 0.0) { "Invalid Earth-fixed radius" }
    }

    val radiusKm: Double get() = hypot(hypot(xKm, yKm), zKm)
    val geocentricLatitudeDegrees: Double get() = Math.toDegrees(atan2(zKm, hypot(xKm, yKm)))
    val longitudeDegrees: Double get() = Math.toDegrees(atan2(yKm, xKm))

    /** Height above the spherical reference; this is not WGS84 geodetic altitude. */
    val altitudeKm: Double get() = radiusKm - EARTH_RADIUS_KM
}

/** Observer position on the WGS84 ellipsoid, including the supplied altitude. */
fun earthSurfacePosition(observer: ObserverLocation): EarthFixedPosition {
    val latitude = Math.toRadians(observer.latitudeDegrees)
    val longitude = Math.toRadians(observer.longitudeDegrees)
    val sinLatitude = sin(latitude)
    val cosLatitude = cos(latitude)
    val flattening = 1.0 / 298.257223563
    val eccentricitySquared = flattening * (2.0 - flattening)
    val primeVerticalRadius = EARTH_RADIUS_KM / sqrt(1.0 - eccentricitySquared * sinLatitude * sinLatitude)
    val altitudeKm = observer.altitudeMeters / 1_000.0
    return EarthFixedPosition(
        (primeVerticalRadius + altitudeKm) * cosLatitude * cos(longitude),
        (primeVerticalRadius + altitudeKm) * cosLatitude * sin(longitude),
        (primeVerticalRadius * (1.0 - eccentricitySquared) + altitudeKm) * sinLatitude,
    )
}
