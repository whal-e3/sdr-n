package org.satelliteeavesdropper.orbit

import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeCoordinatesTest {
    private val j2000 = Instant.parse("2000-01-01T12:00:00Z")

    @Test fun greenwichRotationAtJ2000HasKnownAngleAndEastWestSign() {
        // Vallado's J2000 Greenwich mean sidereal angle: 280.460618375 degrees.
        assertEquals(280.460618375, Math.toDegrees(gmstRadians(j2000)), 1e-9)
        val inertialX = SatelliteOrbit(TemeStateProvider {
            TemeState(Vector3(7_000.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0))
        })
        val position = inertialX.earthFixedPosition(j2000)
        assertEquals(79.539381625, position.longitudeDegrees, 1e-9)
        assertEquals(0.0, position.geocentricLatitudeDegrees, 1e-9)
        assertEquals(7_000.0, position.radiusKm, 1e-9)
        assertEquals(621.863, position.altitudeKm, 1e-9)
    }

    @Test fun globePositionAndObserverLookUseConsistentFrames() {
        val observer = ObserverLocation(37.2411, 127.1776, 100.0)
        val observerPosition = earthSurfacePosition(observer)
        val satellitePosition = EarthFixedPosition(-5_100.0, 4_800.0, 3_800.0)
        val theta = gmstRadians(j2000)
        val orbit = SatelliteOrbit(TemeStateProvider {
            TemeState(
                Vector3(
                    cos(theta) * satellitePosition.xKm - sin(theta) * satellitePosition.yKm,
                    sin(theta) * satellitePosition.xKm + cos(theta) * satellitePosition.yKm,
                    satellitePosition.zKm,
                ),
                Vector3(0.0, 0.0, 0.0),
            )
        })
        val globePosition = orbit.earthFixedPosition(j2000)
        assertEquals(satellitePosition.xKm, globePosition.xKm, 1e-9)
        assertEquals(satellitePosition.yKm, globePosition.yKm, 1e-9)
        assertEquals(satellitePosition.zKm, globePosition.zKm, 1e-9)

        val latitude = Math.toRadians(observer.latitudeDegrees)
        val longitude = Math.toRadians(observer.longitudeDegrees)
        val dx = globePosition.xKm - observerPosition.xKm
        val dy = globePosition.yKm - observerPosition.yKm
        val dz = globePosition.zKm - observerPosition.zKm
        val east = -sin(longitude) * dx + cos(longitude) * dy
        val north = -sin(latitude) * cos(longitude) * dx -
            sin(latitude) * sin(longitude) * dy + cos(latitude) * dz
        val up = cos(latitude) * cos(longitude) * dx +
            cos(latitude) * sin(longitude) * dy + sin(latitude) * dz
        val look = orbit.lookFrom(observer, j2000)
        assertEquals(hypot(hypot(dx, dy), dz), look.slantRangeKm, 1e-9)
        assertEquals(Math.toDegrees(atan2(up, hypot(east, north))), look.elevationDegrees, 1e-9)
        assertEquals((Math.toDegrees(atan2(east, north)) + 360.0) % 360.0,
            look.azimuthDegrees, 1e-9)
    }

    @Test fun frozenRotationDrawsOrbitWhileVaryingRotationDrawsGroundTrack() {
        // A stationary inertial test target moves west as Earth rotates below it.
        val orbit = SatelliteOrbit(TemeStateProvider {
            TemeState(Vector3(7_000.0, 0.0, 100.0), Vector3(0.0, 0.0, 0.0))
        })
        val later = j2000.plusNanos(21_541_022_625_000L) // quarter of a sidereal day
        val initial = orbit.earthFixedPosition(j2000)
        val frozen = orbit.earthFixedPosition(later, rotationAt = j2000)
        val groundTrack = orbit.earthFixedPosition(later)
        assertEquals(initial, frozen)
        assertEquals(-90.0, groundTrack.longitudeDegrees - initial.longitudeDegrees, 0.00001)
        assertEquals(initial.radiusKm, groundTrack.radiusKm, 1e-9)
        assertEquals(initial.zKm, groundTrack.zKm, 0.0)
    }

    @Test fun frozenEarthOrientationStillPropagatesAtTheRequestedSatelliteTime() {
        val orbit = SatelliteOrbit(TemeStateProvider { at ->
            TemeState(
                Vector3(7_000.0, 0.0, (at.epochSecond - j2000.epochSecond).toDouble()),
                Vector3(0.0, 0.0, 1.0),
            )
        })
        val position = orbit.earthFixedPosition(j2000.plusSeconds(30), rotationAt = j2000)
        assertEquals(30.0, position.zKm, 0.0)
        assertEquals(79.539381625, position.longitudeDegrees, 1e-9)
    }

    @Test fun surfaceCoordinatesRespectWgs84AltitudePolesAndDateline() {
        val equator = earthSurfacePosition(ObserverLocation(0.0, 0.0, 500.0))
        assertEquals(EARTH_RADIUS_KM + 0.5, equator.xKm, 1e-9)
        assertEquals(0.0, equator.yKm, 1e-9)
        assertEquals(0.0, equator.zKm, 1e-9)

        val northPole = earthSurfacePosition(ObserverLocation(90.0, 25.0))
        val southPole = earthSurfacePosition(ObserverLocation(-90.0, -25.0))
        assertEquals(6_356.752314245, northPole.zKm, 1e-8)
        assertEquals(-6_356.752314245, southPole.zKm, 1e-8)
        assertEquals(90.0, northPole.geocentricLatitudeDegrees, 1e-9)
        assertEquals(-90.0, southPole.geocentricLatitudeDegrees, 1e-9)
        assertTrue(northPole.altitudeKm < 0.0) // The spherical radius is equatorial.

        val eastDateline = earthSurfacePosition(ObserverLocation(0.0, 180.0))
        val westDateline = earthSurfacePosition(ObserverLocation(0.0, -180.0))
        assertEquals(180.0, eastDateline.longitudeDegrees, 1e-9)
        assertEquals(-180.0, westDateline.longitudeDegrees, 1e-9)
        assertEquals(eastDateline.xKm, westDateline.xKm, 1e-9)
        assertEquals(eastDateline.zKm, westDateline.zKm, 0.0)
    }

    @Test fun realSgp4PositionRemainsFiniteNearPolesAndDateline() {
        val orbit = SatelliteOrbit(valladoOmm())
        for (offset in listOf(0L, 1_000L, 3_000L, 6_000L)) {
            val position = orbit.earthFixedPosition(valladoOmm().epoch.plusSeconds(offset))
            assertTrue(position.radiusKm > EARTH_RADIUS_KM)
            assertTrue(position.geocentricLatitudeDegrees in -90.0..90.0)
            assertTrue(position.longitudeDegrees in -180.0..180.0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonfiniteCoordinatesAreRejected() {
        EarthFixedPosition(Double.NaN, 0.0, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroRadiusIsRejected() {
        EarthFixedPosition(0.0, 0.0, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonfinitePropagationIsRejected() {
        SatelliteOrbit(TemeStateProvider {
            TemeState(Vector3(Double.POSITIVE_INFINITY, 0.0, 0.0), Vector3(0.0, 0.0, 0.0))
        }).earthFixedPosition(j2000)
    }
}
