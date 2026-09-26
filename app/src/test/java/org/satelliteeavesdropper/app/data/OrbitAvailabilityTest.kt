package org.satelliteeavesdropper.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class OrbitAvailabilityTest {
    private fun record(date: Any?) = SatelliteRecord("5", "Historical object", emptyList(),
        JSONObject().put("DECAY_DATE", date ?: JSONObject.NULL), emptyList())

    @Test fun sourceDateUsesUtcCalendarCutoffWithoutInventingExactEventTime() {
        val satellite = record("2026-09-24")
        assertEquals(LocalDate.of(2026, 9, 24), satellite.recordedDecayDate)
        assertFalse(satellite.isKnownDecayedAt(Instant.parse("2026-09-23T23:59:59Z")))
        assertTrue(satellite.isKnownDecayedAt(Instant.parse("2026-09-24T00:00:00Z")))
    }

    @Test fun predictionWindowClipsAtConservativeSourceDateAndStopsAfterward() {
        val satellite = record("2026-09-24")
        val cutoff = Instant.parse("2026-09-24T00:00:00Z")
        assertEquals(cutoff, satellite.predictionEndBeforeDecay(
            Instant.parse("2026-09-23T15:00:00Z"), Instant.parse("2026-09-24T15:00:00Z")))
        assertNull(satellite.predictionEndBeforeDecay(cutoff, cutoff.plusSeconds(86_400)))
        val earlierEnd = Instant.parse("2026-09-23T23:00:00Z")
        assertEquals(earlierEnd, satellite.predictionEndBeforeDecay(earlierEnd.minusSeconds(60), earlierEnd))
    }

    @Test fun absentAndInvalidOptionalDatesDoNotInventAReentryEvent() {
        val start = Instant.parse("2026-09-24T00:00:00Z")
        val end = start.plusSeconds(86_400)
        for (value in listOf(null, "", "null", "2026-02-29", "not a date", 123)) {
            val satellite = record(value)
            assertNull(satellite.recordedDecayDate)
            assertFalse(satellite.isKnownDecayedAt(start))
            assertEquals(end, satellite.predictionEndBeforeDecay(start, end))
        }
    }
}
