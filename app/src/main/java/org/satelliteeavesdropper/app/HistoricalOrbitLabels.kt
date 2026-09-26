package org.satelliteeavesdropper.app

import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A source-reported decay date does not establish the event's cause or exact time. */
internal fun SatelliteRecord.orbitHistoryLabel(now: Instant): String? {
    val date = recordedDecayDate ?: return null
    return if (isKnownDecayedAt(now)) "Historical orbit · source-reported decay date: $date (UTC)"
        else "Source-reported decay date: $date (UTC)"
}

/** Historical records stay searchable without implying that they still have current passes. */
internal fun SatelliteRecord.dayPassStatus(
    day: LocalDate,
    zone: ZoneId,
    calculated: Boolean,
    failed: Boolean,
    passCount: Int,
): String {
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    return when {
        predictionEndBeforeDecay(start, end) == null ->
            "No predictions on or after source-reported decay date"
        failed -> "Pass calculation failed"
        !calculated -> "Passes pending"
        passCount == 0 -> "No pass on this day"
        else -> "$passCount ${if (passCount == 1) "pass" else "passes"} on this day"
    }
}

internal fun SatelliteRecord.dayPredictionStopsAtDecay(day: LocalDate, zone: ZoneId): Boolean {
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    val cutoff = recordedDecayDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant() ?: return false
    return predictionEndBeforeDecay(start, end) == cutoff
}

/** A date-limited prediction end is not a predicted loss of sight. */
internal fun SatelliteRecord.targetPredictionPassLabel(
    pass: SatellitePass?,
    now: Instant,
    format: (Instant) -> String,
): String {
    val windowEnd = now.plus(Duration.ofHours(24))
    val end = predictionEndBeforeDecay(now, windowEnd)
    val cutoff = recordedDecayDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
    val stopsAtDecay = cutoff != null && end == cutoff
    return when {
        end == null -> "Current pass prediction disabled from source-reported decay date"
        pass == null && stopsAtDecay ->
            "No pass predicted before source-reported decay date"
        pass != null && pass.endsAfterWindow && pass.los == end &&
            stopsAtDecay && !now.isBefore(pass.aos) ->
            "Pass in progress · prediction stops at source-reported decay date"
        else -> targetPassLabel(pass, now, format)
    }
}
