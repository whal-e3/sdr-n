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

        while (previousTime.isBefore(end)) {
            val candidate = previousTime.plusSeconds(stepSeconds.toLong())
            val nextTime = if (candidate.isAfter(end)) end else candidate
            val nextLook = look(nextTime)

            if (!above(previousLook) && above(nextLook)) {
                aos = refineCrossing(previousTime, nextTime, minElevationDegrees, true, ::look)
                beganBeforeWindow = false
                sampledPeak = look(aos)
            }
            if (aos != null && above(nextLook) && (sampledPeak == null ||
                        nextLook.elevationDegrees > sampledPeak.elevationDegrees)) {
                sampledPeak = nextLook
            }
            if (above(previousLook) && !above(nextLook)) {
                val los = refineCrossing(previousTime, nextTime, minElevationDegrees, false, ::look)
                passes += makePass(aos ?: previousTime, los, sampledPeak ?: previousLook,
                    beganBeforeWindow, false, stepSeconds, ::look)
                aos = null
                sampledPeak = null
            }

            previousTime = nextTime
            previousLook = nextLook
        }

        if (aos != null) {
            passes += makePass(aos, end, sampledPeak ?: previousLook,
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
    private val position: Vector3

    init {
        val a = 6_378.137 // WGS84 equatorial radius, km
        val flattening = 1.0 / 298.257223563
        val eccentricitySquared = flattening * (2.0 - flattening)
        val primeVerticalRadius = a / sqrt(1.0 - eccentricitySquared * sinLat * sinLat)
        val altitudeKm = observer.altitudeMeters / 1_000.0
        position = Vector3(
            (primeVerticalRadius + altitudeKm) * cosLat * cosLon,
            (primeVerticalRadius + altitudeKm) * cosLat * sinLon,
            (primeVerticalRadius * (1.0 - eccentricitySquared) + altitudeKm) * sinLat,
        )
    }

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

        val dx = x - position.x
        val dy = y - position.y
        val dz = z - position.z
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
