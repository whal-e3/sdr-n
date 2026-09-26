package org.satelliteeavesdropper.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HistoricalOrbitLabelsTest {
    private val day = LocalDate.of(2026, 9, 26)
    private val utc = ZoneId.of("UTC")
    private val seoul = ZoneId.of("Asia/Seoul")
    private val cutoff = Instant.parse("2026-09-26T00:00:00Z")

    @Test fun historicalLabelStartsAtSourceReportedUtcDateWithoutClaimingPhysicalReentry() {
        val record = record("2026-09-26")
        assertEquals("Source-reported decay date: 2026-09-26 (UTC)",
            record.orbitHistoryLabel(cutoff.minusSeconds(1)))
        assertEquals("Historical orbit · source-reported decay date: 2026-09-26 (UTC)",
            record.orbitHistoryLabel(cutoff))
        assertNull(record(null).orbitHistoryLabel(cutoff))
    }

    @Test fun sourceDecayDayOverridesPendingFailedAndZeroPassLabelsAfterCutoff() {
        val record = record("2026-09-26")
        val expected = "No predictions on or after source-reported decay date"
        assertEquals(expected, record.dayPassStatus(day, utc, false, false, 0))
        assertEquals(expected, record.dayPassStatus(day, utc, true, true, 0))
        assertEquals(expected, record.dayPassStatus(day.plusDays(1), utc, true, false, 0))
    }

    @Test fun historicalRecordKeepsPassStatusForEarlierDays() {
        val record = record("2026-09-26")
        assertTrue(record.isKnownDecayedAt(cutoff.plusSeconds(60)))
        assertEquals("2 passes on this day", record.dayPassStatus(day.minusDays(1), utc, true, false, 2))
        assertEquals("Passes pending", record.dayPassStatus(day.minusDays(1), utc, false, false, 0))
        assertEquals("No pass on this day", record.dayPassStatus(day.minusDays(1), utc, true, false, 0))
    }

    @Test fun localDayCanHavePredictionsOnlyBeforeSourceDecayUtcDate() {
        val record = record("2026-09-26")
        assertTrue(record.dayPredictionStopsAtDecay(day, seoul))
        assertFalse(record.dayPredictionStopsAtDecay(day.minusDays(1), seoul))
        assertFalse(record.dayPredictionStopsAtDecay(day.plusDays(1), seoul))
        assertTrue(record.dayPredictionStopsAtDecay(day.minusDays(1), utc))
        assertEquals("1 pass on this day", record.dayPassStatus(day, seoul, true, false, 1))
    }

    @Test fun ordinaryAndOldUnmarkedOrbitsKeepTheirPassStatus() {
        val record = record(null)
        assertFalse(record.dayPredictionStopsAtDecay(day, seoul))
        assertEquals("Pass calculation failed", record.dayPassStatus(day, seoul, true, true, 0))
        assertEquals("Passes pending", record.dayPassStatus(day, seoul, false, false, 0))
        assertEquals("No pass on this day", record.dayPassStatus(day, seoul, true, false, 0))
        assertEquals("1 pass on this day", record.dayPassStatus(day, seoul, true, false, 1))
    }

    @Test fun targetPredictionDoesNotCallASourceDecayCutoffLossOfSight() {
        val record = record("2026-09-26")
        val now = cutoff.minusSeconds(60)
        val pass = SatellitePass(now.minusSeconds(60), now, cutoff, 40.0, 90.0, 270.0, false, true)
        val format: (Instant) -> String = { it.toString() }
        assertEquals("Pass in progress · prediction stops at source-reported decay date",
            record.targetPredictionPassLabel(pass, now, format))
        assertEquals("No pass predicted before source-reported decay date",
            record.targetPredictionPassLabel(null, now, format))
        assertEquals("Current pass prediction disabled from source-reported decay date",
            record.targetPredictionPassLabel(pass, cutoff, format))
        val wholeDayStart = cutoff.minusSeconds(24 * 60 * 60)
        val wholeDayPass = pass.copy(aos = wholeDayStart, tca = wholeDayStart.plusSeconds(300))
        assertEquals("Pass in progress · prediction stops at source-reported decay date",
            record.targetPredictionPassLabel(wholeDayPass, wholeDayStart, format))
        assertEquals("Next pass: none predicted in 24 hours",
            record(null).targetPredictionPassLabel(null, now, format))
    }

    private fun record(decayDate: String?): SatelliteRecord = SatelliteRecord(
        "1", "Historical test satellite", emptyList(),
        JSONObject().apply { if (decayDate != null) put("DECAY_DATE", decayDate) }, emptyList(),
    )
}
