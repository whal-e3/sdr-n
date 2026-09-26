package org.satelliteeavesdropper.app

import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Instant

/** The first predicted pass may already be underway when Target is opened. */
internal fun targetPassLabel(pass: SatellitePass?, now: Instant, format: (Instant) -> String): String = when {
    pass == null -> "Next pass: none predicted in 24 hours"
    now.isBefore(pass.aos) -> "Next pass: ${format(pass.aos)}"
    now.isBefore(pass.los) -> "Pass in progress · predicted LOS ${format(pass.los)}"
    else -> "Pass ended · updating prediction…"
}
