package org.satelliteeavesdropper.orbit

import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** One satellite's SGP4 propagation and observer-relative pass calculations. */
class SatelliteOrbit internal constructor(private val source: TemeStateProvider) {
    constructor(omm: OmmElements) : this(OrekitSgp4(omm))

    fun lookFrom(observer: ObserverLocation, at: Instant): SatelliteLook = ObserverFrame(observer).look(at, source.at(at))

    /**
     * Propagates at [at] and rotates TEME into the globe's Earth-fixed orientation at [rotationAt].
     * Use the default for a ground track; hold [rotationAt] fixed to draw an orbit arc against the
     * Earth orientation of the current frame. UTC approximates UT1, as in [lookFrom].
     */
    fun earthFixedPosition(at: Instant, rotationAt: Instant = at): EarthFixedPosition {
        val position = source.at(at).position
        val theta = gmstRadians(rotationAt)
        val c = cos(theta)
        val s = sin(theta)
        return EarthFixedPosition(
            c * position.x + s * position.y,
            -s * position.x + c * position.y,
            position.z,
        )
    }

    /**
     * Finds intervals at or above [minElevationDegrees] in [start, end]. AOS and LOS are
     * refined to within one second; an interval crossing either window edge is marked.
     * Keep OMM data fresh before calling this method: stale SGP4 results are not rejected here.
     */
    fun predictPasses(
        observer: ObserverLocation,
        start: Instant,
        end: Instant,
        minElevationDegrees: Double = 0.0,
        stepSeconds: Int = 30,
    ): List<SatellitePass> {
        require(end.isAfter(start)) { "Search end must be after start" }
        require(minElevationDegrees.isFinite() && minElevationDegrees in -5.0..90.0)
        require(stepSeconds in 1..120)

        val frame = ObserverFrame(observer)
        fun look(time: Instant) = frame.look(time, source.at(time))
        fun above(sample: SatelliteLook) = sample.elevationDegrees >= minElevationDegrees

        val passes = mutableListOf<SatellitePass>()
        var previousTime = start
        var previousLook = look(start)
        var aos: Instant? = if (above(previousLook)) start else null
        var beganBeforeWindow = aos != null
        var sampledPeak: SatelliteLook? = if (aos != null) previousLook else null

        fun consume(time: Instant, sample: SatelliteLook) {
            if (!above(previousLook) && above(sample)) {
                aos = refineCrossing(previousTime, time, minElevationDegrees, true, ::look)
                beganBeforeWindow = false
                sampledPeak = look(aos!!)
            }
            if (aos != null && above(sample) && (sampledPeak == null ||
                        sample.elevationDegrees > sampledPeak!!.elevationDegrees)) {
                sampledPeak = sample
            }
            if (above(previousLook) && !above(sample)) {
                val los = refineCrossing(previousTime, time, minElevationDegrees, false, ::look)
                passes += makePass(aos ?: previousTime, los, sampledPeak ?: previousLook,
                    beganBeforeWindow, false, stepSeconds, ::look)
                aos = null
                sampledPeak = null
            }
            previousTime = time
            previousLook = sample
        }

        /**
         * A coarse interval can contain a short rise and set even when both endpoints are below
         * the horizon. Probe only intervals that can plausibly reach the threshold. For a bound
         * Earth orbit, 12 km/s exceeds satellite plus observer ground speed; the line-of-sight
         * range cannot shrink by more than that speed times half the interval. The resulting
         * angular bound keeps deep-below-horizon intervals at the inexpensive coarse step.
         */
        fun mayHidePass(first: SatelliteLook, last: SatelliteLook, elapsedMillis: Long): Boolean {
            if (elapsedMillis <= 10_000L || above(first) || above(last)) return false
            val halfTravelKm = 12.0 * elapsedMillis / 2_000.0
            val nearestRangeKm = minOf(first.slantRangeKm, last.slantRangeKm) - halfTravelKm
            if (nearestRangeKm <= 0.0) return true
            val angularBoundDegrees = Math.toDegrees(halfTravelKm / nearestRangeKm)
            return maxOf(first.elevationDegrees, last.elevationDegrees) + angularBoundDegrees >=
                minElevationDegrees
        }

        fun consumeInterval(fromTime: Instant, fromLook: SatelliteLook,
                            toTime: Instant, toLook: SatelliteLook) {
            val elapsedMillis = Duration.between(fromTime, toTime).toMillis()
            if (mayHidePass(fromLook, toLook, elapsedMillis)) {
                val midpoint = fromTime.plusMillis(elapsedMillis / 2)
                val middleLook = look(midpoint)
                consumeInterval(fromTime, fromLook, midpoint, middleLook)
                consumeInterval(midpoint, middleLook, toTime, toLook)
            } else {
                consume(toTime, toLook)
            }
        }

        while (previousTime.isBefore(end)) {
            val candidate = previousTime.plusSeconds(stepSeconds.toLong())
            val nextTime = if (candidate.isAfter(end)) end else candidate
            val nextLook = look(nextTime)
            consumeInterval(previousTime, previousLook, nextTime, nextLook)
        }

        aos?.let { activeAos ->
            passes += makePass(activeAos, end, sampledPeak ?: previousLook,
                beganBeforeWindow, true, stepSeconds, ::look)
        }
        return passes
    }
}

private fun refineCrossing(
    start: Instant,
    end: Instant,
    threshold: Double,
    ascending: Boolean,
    look: (Instant) -> SatelliteLook,
): Instant {
    var low = start
    var high = end
    while (Duration.between(low, high).toMillis() > 1_000) {
        val middle = low.plusMillis(Duration.between(low, high).toMillis() / 2)
        val above = look(middle).elevationDegrees >= threshold
        if (above == ascending) high = middle else low = middle
    }
    return high
}

private fun makePass(
    aos: Instant,
    los: Instant,
    sampledPeak: SatelliteLook,
    beganBeforeWindow: Boolean,
    endsAfterWindow: Boolean,
    stepSeconds: Int,
    look: (Instant) -> SatelliteLook,
): SatellitePass {
    var left = maxOf(aos, sampledPeak.time.minusSeconds(stepSeconds.toLong()))
    var right = minOf(los, sampledPeak.time.plusSeconds(stepSeconds.toLong()))
    while (Duration.between(left, right).toMillis() > 1_000) {
        val third = Duration.between(left, right).toMillis() / 3
        val a = left.plusMillis(third)
        val b = right.minusMillis(third)
        if (look(a).elevationDegrees < look(b).elevationDegrees) left = a else right = b
    }
    val candidates = listOf(sampledPeak, look(aos), look(left), look(right), look(los))
    val peak = candidates.maxByOrNull { it.elevationDegrees } ?: sampledPeak
    return SatellitePass(
        aos = aos,
        tca = peak.time,
        los = los,
        maximumElevationDegrees = peak.elevationDegrees,
        aosAzimuthDegrees = look(aos).azimuthDegrees,
        losAzimuthDegrees = look(los).azimuthDegrees,
        beganBeforeWindow = beganBeforeWindow,
        endsAfterWindow = endsAfterWindow,
    )
}

private class ObserverFrame(observer: ObserverLocation) {
    private val latitude = Math.toRadians(observer.latitudeDegrees)
    private val longitude = Math.toRadians(observer.longitudeDegrees)
    private val sinLat = sin(latitude)
    private val cosLat = cos(latitude)
    private val sinLon = sin(longitude)
    private val cosLon = cos(longitude)
    private val position = earthSurfacePosition(observer)

    fun look(time: Instant, state: TemeState): SatelliteLook {
        // TEME -> pseudo Earth-fixed using Vallado GMST. UT1 is approximated by UTC;
        // polar motion and nutation are below the accuracy of normal fresh GP data.
        val theta = gmstRadians(time)
        val c = cos(theta)
        val s = sin(theta)
        val x = c * state.position.x + s * state.position.y
        val y = -s * state.position.x + c * state.position.y
        val z = state.position.z
        val omega = 7.2921150e-5 // rad/s
        val vx = c * state.velocity.x + s * state.velocity.y + omega * y
        val vy = -s * state.velocity.x + c * state.velocity.y - omega * x
        val vz = state.velocity.z

        val dx = x - position.xKm
        val dy = y - position.yKm
        val dz = z - position.zKm
        val range = sqrt(dx * dx + dy * dy + dz * dz)
        require(range > 0.0 && range.isFinite()) { "Invalid propagated satellite range" }
        val east = -sinLon * dx + cosLon * dy
        val north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz
        val up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz
        val azimuth = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
        val elevation = Math.toDegrees(atan2(up, hypot(east, north)))
        val rangeRate = (dx * vx + dy * vy + dz * vz) / range
        return SatelliteLook(time, azimuth, elevation, range, rangeRate)
    }
}

/** Greenwich mean sidereal angle in radians, using UTC as the UT1 approximation. */
internal fun gmstRadians(time: Instant): Double {
    val julianDate = 2_440_587.5 + time.epochSecond / 86_400.0 + time.nano / 86_400_000_000_000.0
    val centuries = (julianDate - 2_451_545.0) / 36_525.0
    val seconds = 67_310.54841 +
        (876_600.0 * 3_600.0 + 8_640_184.812866) * centuries +
        0.093104 * centuries * centuries -
        6.2e-6 * centuries * centuries * centuries
    val normalizedSeconds = ((seconds % 86_400.0) + 86_400.0) % 86_400.0
    return normalizedSeconds * 2.0 * PI / 86_400.0
}
