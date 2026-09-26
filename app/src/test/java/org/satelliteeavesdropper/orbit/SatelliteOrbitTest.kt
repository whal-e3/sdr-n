package org.satelliteeavesdropper.orbit

import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteOrbitTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val observer = ObserverLocation(0.0, 0.0)
    private val earthRadiusKm = 6_378.137

    @Test fun zenithRangeAndDopplerHaveExpectedSigns() {
        val orbit = SatelliteOrbit(TemeStateProvider { time ->
            ecefAsTeme(time, Vector3(earthRadiusKm + 500.0, 0.0, 0.0), Vector3(1.0, 0.0, 0.0))
        })
        val look = orbit.lookFrom(observer, start)
        assertEquals(90.0, look.elevationDegrees, 1e-8)
        assertEquals(500.0, look.slantRangeKm, 1e-6)
        assertEquals(1.0, look.rangeRateKmPerSecond, 1e-9)
        assertEquals(-484.999, look.dopplerShiftHz(145_400_000.0), 0.01)
    }

    @Test fun passBoundariesPeakAndWindowClipping() {
        // An eastward synthetic target has one complete 0..300 s pass and
        // another pass beginning at 600 s that extends beyond the window.
        val orbit = SatelliteOrbit(TemeStateProvider { time ->
            val seconds = (time.epochSecond - start.epochSecond) + time.nano / 1e9
            val height = 1_000.0 * sin(2.0 * PI * seconds / 600.0)
            ecefAsTeme(time, Vector3(earthRadiusKm + height, 1_000.0, 0.0))
        })
        val passes = orbit.predictPasses(observer, start.minusSeconds(60), start.plusSeconds(660))
        assertEquals(2, passes.size)
        assertFalse(passes[0].beganBeforeWindow)
        assertFalse(passes[0].endsAfterWindow)
        assertEquals(0.0, (passes[0].aos.epochSecond - start.epochSecond).toDouble(), 1.0)
        assertEquals(150.0, (passes[0].tca.epochSecond - start.epochSecond).toDouble(), 1.0)
        assertEquals(300.0, (passes[0].los.epochSecond - start.epochSecond).toDouble(), 1.0)
        assertEquals(45.0, passes[0].maximumElevationDegrees, 0.01)
        assertEquals(600.0, (passes[1].aos.epochSecond - start.epochSecond).toDouble(), 1.0)
        assertTrue(passes[1].endsAfterWindow)

        val clipped = orbit.predictPasses(observer, start.plusSeconds(60), start.plusSeconds(240))
        assertEquals(1, clipped.size)
        assertTrue(clipped.single().beganBeforeWindow)
        assertTrue(clipped.single().endsAfterWindow)
    }

    @Test fun shortGrazingPassBetweenCoarseSamplesIsFound() {
        // The 20-second horizon crossing at t=25..45 s is below the horizon at
        // both 120-second samples and even at their 60-second midpoint.
        val orbit = SatelliteOrbit(TemeStateProvider { time ->
            val seconds = (time.epochSecond - start.epochSecond) + time.nano / 1e9
            val elevation = 1.0 - 0.1 * abs(seconds - 35.0)
            val angle = Math.toRadians(elevation)
            val range = 6_200.0
            ecefAsTeme(time, Vector3(
                earthRadiusKm + range * sin(angle),
                range * cos(angle),
                0.0,
            ))
        })
        for (offset in listOf(0L, 60L, 120L)) {
            assertTrue(orbit.lookFrom(observer, start.plusSeconds(offset)).elevationDegrees < 0.0)
        }

        val passes = orbit.predictPasses(observer, start, start.plusSeconds(120), stepSeconds = 120)
        assertEquals(1, passes.size)
        assertEquals(25.0, secondsAfterStart(passes.single().aos), 1.0)
        assertEquals(35.0, secondsAfterStart(passes.single().tca), 1.0)
        assertEquals(45.0, secondsAfterStart(passes.single().los), 1.0)
        assertEquals(1.0, passes.single().maximumElevationDegrees, 0.05)
    }

    @Test fun deepBelowHorizonUsesOnlyCoarseSamples() {
        var propagationCount = 0
        val orbit = SatelliteOrbit(TemeStateProvider { time ->
            propagationCount++
            ecefAsTeme(time, Vector3(-earthRadiusKm - 500.0, 0.0, 0.0))
        })
        val passes = orbit.predictPasses(observer, start, start.plusSeconds(86_400), stepSeconds = 120)
        assertTrue(passes.isEmpty())
        assertEquals(721, propagationCount)
    }

    @Test fun adaptiveSearchKeepsRepresentativeDayBounded() {
        val elements = valladoOmm()
        val propagator = OrekitSgp4(elements)
        var propagationCount = 0
        val orbit = SatelliteOrbit(TemeStateProvider { time ->
            propagationCount++
            propagator.at(time)
        })
        orbit.predictPasses(observer, elements.epoch, elements.epoch.plusSeconds(86_400), stepSeconds = 120)
        assertTrue("A day used $propagationCount propagations", propagationCount < 2_000)
    }

    @Test fun polarAndDatelineObserversProduceFiniteLookAngles() {
        val orbit = SatelliteOrbit(valladoOmm())
        for (location in listOf(ObserverLocation(89.9, 179.9), ObserverLocation(-89.9, -179.9))) {
            val look = orbit.lookFrom(location, valladoOmm().epoch)
            assertTrue(look.azimuthDegrees in 0.0..360.0)
            assertTrue(look.elevationDegrees in -90.0..90.0)
            assertTrue(look.slantRangeKm.isFinite() && look.slantRangeKm > 0.0)
            assertTrue(look.rangeRateKmPerSecond.isFinite())
        }
    }

    private fun ecefAsTeme(time: Instant, position: Vector3, velocity: Vector3 = Vector3(0.0, 0.0, 0.0)): TemeState {
        val theta = gmstRadians(time)
        val c = cos(theta)
        val s = sin(theta)
        val omega = 7.2921150e-5
        val inertialVx = velocity.x - omega * position.y
        val inertialVy = velocity.y + omega * position.x
        return TemeState(
            Vector3(c * position.x - s * position.y, s * position.x + c * position.y, position.z),
            Vector3(c * inertialVx - s * inertialVy, s * inertialVx + c * inertialVy, velocity.z),
        )
    }

    private fun secondsAfterStart(time: Instant): Double =
        (time.epochSecond - start.epochSecond) + time.nano / 1e9
}

internal fun valladoOmm() = OmmElements(
    noradId = "5",
    epoch = Instant.parse("2000-06-27T18:50:19.733568Z"),
    meanMotionRevolutionsPerDay = 10.82419157,
    eccentricity = 0.1859667,
    inclinationDegrees = 34.2682,
    raanDegrees = 348.7242,
    argumentOfPerigeeDegrees = 331.7664,
    meanAnomalyDegrees = 19.3264,
    bStar = 2.8098e-5,
    objectId = "1958-002B",
)
