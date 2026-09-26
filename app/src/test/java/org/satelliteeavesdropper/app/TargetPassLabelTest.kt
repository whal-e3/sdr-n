package org.satelliteeavesdropper.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Instant

class TargetPassLabelTest {
    private val aos = Instant.parse("2026-09-24T11:35:00Z")
    private val tca = aos.plusSeconds(300)
    private val los = aos.plusSeconds(600)
    private val pass = SatellitePass(aos, tca, los, 42.0, 90.0, 270.0, false, false)
    private val format: (Instant) -> String = { it.toString() }

    @Test fun distinguishesAnUpcomingPassFromOneAlreadyUnderway() {
        assertEquals("Next pass: $aos", targetPassLabel(pass, aos.minusSeconds(1), format))
        assertEquals("Pass in progress · predicted LOS $los", targetPassLabel(pass, aos, format))
        assertEquals("Pass in progress · predicted LOS $los", targetPassLabel(pass, tca, format))
        assertEquals("Pass ended · updating prediction…", targetPassLabel(pass, los, format))
    }

    @Test fun reportsNoPredictedPass() {
        assertEquals("Next pass: none predicted in 24 hours", targetPassLabel(null, aos, format))
    }
}
