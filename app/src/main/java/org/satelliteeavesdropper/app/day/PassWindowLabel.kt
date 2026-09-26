package org.satelliteeavesdropper.app.day

import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val passClock = DateTimeFormatter.ofPattern("HH:mm")

/** A day-boundary clip is not an actual acquisition or loss of sight. */
internal fun passWindowLabel(
    pass: SatellitePass,
    zone: ZoneId,
    predictionStopsAtDecay: Boolean = false,
): String {
    val start = if (pass.beganBeforeWindow) "In sight at day start"
        else "AOS ${pass.aos.atZone(zone).format(passClock)}"
    val end = when {
        pass.endsAfterWindow && predictionStopsAtDecay -> "Prediction ends at source-reported decay date"
        pass.endsAfterWindow -> "In sight at day end"
        else -> "LOS ${pass.los.atZone(zone).format(passClock)}"
    }
    return "$start · TCA ${pass.tca.atZone(zone).format(passClock)} · $end"
}
