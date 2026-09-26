package org.satelliteeavesdropper.orbit

import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import org.orekit.frames.FramesFactory
import org.orekit.propagation.analytical.tle.TLE
import org.orekit.propagation.analytical.tle.TLEPropagator
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory

internal data class Vector3(val x: Double, val y: Double, val z: Double)

/** Position (km) and velocity (km/s) in the SGP4 TEME frame. */
internal data class TemeState(val position: Vector3, val velocity: Vector3)

internal fun interface TemeStateProvider {
    fun at(time: Instant): TemeState
}

private val LAUNCH_ID_PATTERN = Regex("^(\\d{4})-(\\d{3})([A-Z]{1,3})$")

/** SGP4/SDP4 propagation without an Orekit data archive or a TLE text conversion. */
internal class OrekitSgp4(omm: OmmElements) : TemeStateProvider {
    private val epoch = omm.epoch
    private val timeScale = TimeScalesFactory.getTAI()

    // We use TAI only as a data-free, uniform calendar bearing UTC's civil labels.
    // SGP4 uses elapsed minutes and epoch calendar fields, not physical TAI here.
    // We never request an Orekit frame transform; the returned coordinates are TEME.
    private val epochDate = AbsoluteDate(
        1970, 1, 1, 0, 0, 0.0, timeScale,
    ).shiftedBy(epoch.epochSecond.toDouble() + epoch.nano / 1e9)

    private val propagator: TLEPropagator

    init {
        val launch = LAUNCH_ID_PATTERN.matchEntire(omm.objectId.orEmpty())
        val revolutionsToRadians = 2.0 * PI
        val secondsPerDay = 86_400.0
        val tle = TLE(
            omm.noradId.toInt(),
            'U',
            launch?.groupValues?.get(1)?.toInt() ?: 0,
            launch?.groupValues?.get(2)?.toInt() ?: 0,
            launch?.groupValues?.get(3) ?: "",
            TLE.DEFAULT,
            0,
            epochDate,
            omm.meanMotionRevolutionsPerDay * revolutionsToRadians / secondsPerDay,
            2.0 * omm.meanMotionDot * revolutionsToRadians / (secondsPerDay * secondsPerDay),
            6.0 * omm.meanMotionDdot * revolutionsToRadians / (secondsPerDay * secondsPerDay * secondsPerDay),
            omm.eccentricity,
            Math.toRadians(omm.inclinationDegrees),
            Math.toRadians(omm.argumentOfPerigeeDegrees),
            Math.toRadians(omm.raanDegrees),
            Math.toRadians(omm.meanAnomalyDegrees),
            0,
            omm.bStar,
            timeScale,
        )
        // GCRF is used only as an inertial carrier object. No GCRF/TEME transform occurs.
        propagator = TLEPropagator.selectExtrapolator(tle, FramesFactory.getGCRF())
    }

    @Synchronized
    override fun at(time: Instant): TemeState {
        val delta = Duration.between(epoch, time)
        val date = epochDate.shiftedBy(delta.seconds.toDouble() + delta.nano / 1e9)
        val pv = propagator.getPVCoordinates(date)
        return TemeState(
            position = Vector3(pv.position.x / 1_000.0, pv.position.y / 1_000.0, pv.position.z / 1_000.0),
            velocity = Vector3(pv.velocity.x / 1_000.0, pv.velocity.y / 1_000.0, pv.velocity.z / 1_000.0),
        )
    }
}
