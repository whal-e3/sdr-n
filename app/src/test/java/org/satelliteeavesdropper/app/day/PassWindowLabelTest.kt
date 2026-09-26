package org.satelliteeavesdropper.app.day

import org.junit.Assert.assertEquals
import org.junit.Test
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Instant
import java.time.ZoneId

class PassWindowLabelTest {
    private val cutoff = Instant.parse("2026-09-26T00:00:00Z")
    private val zone = ZoneId.of("Asia/Seoul")

    @Test fun sourceReportedDecayClipIsNotPresentedAsTheLocalDayEnd() {
        val pass = SatellitePass(cutoff.minusSeconds(600), cutoff.minusSeconds(300), cutoff,
            40.0, 90.0, 270.0, false, true)
        assertEquals("AOS 08:50 · TCA 08:55 · Prediction ends at source-reported decay date",
            passWindowLabel(pass, zone, predictionStopsAtDecay = true))
        assertEquals("AOS 08:50 · TCA 08:55 · In sight at day end", passWindowLabel(pass, zone))
    }

    @Test fun ordinaryLosRemainsARealCrossingEvenInsideADateLimitedWindow() {
        val pass = SatellitePass(cutoff.minusSeconds(600), cutoff.minusSeconds(300), cutoff.minusSeconds(60),
            40.0, 90.0, 270.0, false, false)
        assertEquals("AOS 08:50 · TCA 08:55 · LOS 08:59",
            passWindowLabel(pass, zone, predictionStopsAtDecay = true))
    }
}
