package org.satelliteeavesdropper.app.day

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.receiverConfigurationIssue
import org.satelliteeavesdropper.app.data.CatalogManifest
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.OmmElements
import org.satelliteeavesdropper.orbit.SatelliteOrbit
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** An orbital record is trackable even when the catalog has no supported downlink for it. */
data class TrackableSatellite(
    val record: SatelliteRecord,
    val elements: OmmElements,
    val index: Int,
) {
    val hasPublicDownlink: Boolean get() = record.transmitters.any { it.mayReceive }
    val hasConfiguredDownlink: Boolean get() = record.transmitters.any { receiverConfigurationIssue(it) == null }
}

data class ScheduledPass(val satellite: TrackableSatellite, val pass: SatellitePass)

internal enum class PassTimeView { UPCOMING, FULL_DAY, IN_SIGHT }

/** "Upcoming" includes a pass already underway and the whole of a future local day. */
internal fun passesForTimeView(
    passes: List<ScheduledPass>,
    view: PassTimeView,
    day: LocalDate,
    zone: ZoneId,
    now: Instant,
): List<ScheduledPass> = when (view) {
    PassTimeView.FULL_DAY -> passes
    PassTimeView.IN_SIGHT -> passes.filter { !now.isBefore(it.pass.aos) && now.isBefore(it.pass.los) }
    PassTimeView.UPCOMING -> when {
        day.isAfter(now.atZone(zone).toLocalDate()) -> passes
        day.isBefore(now.atZone(zone).toLocalDate()) -> emptyList()
        else -> passes.filter { now.isBefore(it.pass.los) }
    }
}

/** Keeps the observation-focused pass list small without hiding the full orbit catalog. */
internal fun passesForRadioView(passes: List<ScheduledPass>, radioProfilesOnly: Boolean): List<ScheduledPass> =
    if (radioProfilesOnly) passes.filter { it.satellite.hasConfiguredDownlink } else passes

/** A partial result remains useful while a large catalog is still being propagated. */
data class DayPassSchedule(
    val day: LocalDate,
    val zone: ZoneId,
    val catalogCount: Int,
    val satellites: List<TrackableSatellite>,
    val passes: List<ScheduledPass>,
    val processedCount: Int,
    val failedNoradIds: Set<String>,
) {
    val isComplete: Boolean get() = processedCount == satellites.size
    val orbitUnavailableCount: Int get() = catalogCount - satellites.size
    val intervalStart: Instant get() = day.atStartOfDay(zone).toInstant()
    val intervalEnd: Instant get() = day.plusDays(1).atStartOfDay(zone).toInstant()
}

internal fun interface PassPredictor {
    fun predict(
        elements: OmmElements,
        observer: ObserverLocation,
        start: Instant,
        end: Instant,
    ): List<SatellitePass>
}

private val sgp4Predictor = PassPredictor { elements, observer, start, end ->
    SatelliteOrbit(elements).predictPasses(observer, start, end, stepSeconds = 120)
}

private fun predictBeforeDecay(
    satellite: TrackableSatellite,
    observer: ObserverLocation,
    start: Instant,
    end: Instant,
    predictor: PassPredictor,
): List<SatellitePass> {
    val predictionEnd = satellite.record.predictionEndBeforeDecay(start, end) ?: return emptyList()
    return predictor.predict(satellite.elements, observer, start, predictionEnd)
}

/** The order is stable so a partial calculation can resume at [DayPassSchedule.processedCount]. */
internal fun trackableSatellites(
    manifest: CatalogManifest,
    checkActive: () -> Unit = {},
): List<TrackableSatellite> {
    checkActive()
    // Predict configured public/amateur downlinks in the first batch. The remaining catalog
    // still follows its source order and receives the same full-day propagation.
    val (radioProfiles, trackingOnly) = manifest.satellites.partition { record ->
        checkActive()
        record.transmitters.any { receiverConfigurationIssue(it) == null }
    }
    val trackable = ArrayList<TrackableSatellite>(manifest.satellites.size)
    for (records in listOf(radioProfiles, trackingOnly)) {
        for (record in records) {
            checkActive()
            record.orbitElements()?.let { elements ->
                trackable += TrackableSatellite(record, elements, trackable.size)
            }
        }
    }
    return trackable
}

private val dayPassComparator = compareBy<ScheduledPass> { it.pass.aos }
        .thenBy { it.satellite.record.noradId.toIntOrNull() ?: Int.MAX_VALUE }
        .thenBy { it.pass.tca }

internal fun sortDayPasses(passes: List<ScheduledPass>): List<ScheduledPass> =
    passes.sortedWith(dayPassComparator)

/** Merge one newly predicted batch into the already sorted partial schedule. */
internal fun mergeDayPasses(sorted: List<ScheduledPass>, additional: List<ScheduledPass>): List<ScheduledPass> {
    if (additional.isEmpty()) return sorted
    if (sorted.isEmpty()) return sortDayPasses(additional)
    val batch = sortDayPasses(additional)
    val merged = ArrayList<ScheduledPass>(sorted.size + batch.size)
    var previousIndex = 0
    var batchIndex = 0
    while (previousIndex < sorted.size && batchIndex < batch.size) {
        if (dayPassComparator.compare(sorted[previousIndex], batch[batchIndex]) <= 0) {
            merged += sorted[previousIndex++]
        } else {
            merged += batch[batchIndex++]
        }
    }
    if (previousIndex < sorted.size) merged.addAll(sorted.subList(previousIndex, sorted.size))
    if (batchIndex < batch.size) merged.addAll(batch.subList(batchIndex, batch.size))
    return merged
}

/**
 * Propagate on a worker thread in small batches. UI receives an initial trackable-satellite
 * list immediately, then increasingly complete chronological pass results. One failed OMM
 * propagation does not hide the remaining satellites. A saved partial result can be resumed.
 */
internal suspend fun calculateDaySchedule(
    manifest: CatalogManifest,
    observer: ObserverLocation?,
    day: LocalDate,
    zone: ZoneId,
    previous: DayPassSchedule? = null,
    onUpdate: (DayPassSchedule) -> Unit,
    predictor: PassPredictor = sgp4Predictor,
): DayPassSchedule {
    val satellites = previous?.satellites ?: withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        trackableSatellites(manifest) { context.ensureActive() }
    }
    val initial = previous ?: DayPassSchedule(
        day = day,
        zone = zone,
        catalogCount = manifest.satellites.size,
        satellites = satellites,
        passes = emptyList(),
        processedCount = 0,
        failedNoradIds = emptySet(),
    )
    require(initial.day == day && initial.zone == zone && initial.catalogCount == manifest.satellites.size)
    require(initial.processedCount in 0..satellites.size)
    currentCoroutineContext().ensureActive()
    onUpdate(initial)
    if (observer == null || initial.isComplete) return initial

    val start = initial.intervalStart
    val end = initial.intervalEnd
    var sorted = initial.passes
    val pending = ArrayList<ScheduledPass>()
    val failures = initial.failedNoradIds.toMutableSet()
    var processed = initial.processedCount
    while (processed < satellites.size) {
        currentCoroutineContext().ensureActive()
        val batchEnd = minOf(processed + 32, satellites.size)
        val batch = withContext(Dispatchers.Default) {
            satellites.subList(processed, batchEnd).map { satellite ->
                currentCoroutineContext().ensureActive()
                val passes = try {
                    Result.success(predictBeforeDecay(satellite, observer, start, end, predictor))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Result.failure(error)
                }
                satellite to passes
            }
        }
        batch.forEach { (satellite, outcome) ->
            outcome.fold(
                onSuccess = { passes ->
                    pending += passes.map { ScheduledPass(satellite, it) }
                },
                onFailure = { failures += satellite.record.noradId },
            )
        }
        processed = batchEnd
        // Merge only new passes; sorting the entire growing day on every update is costly
        // for broad orbital catalogs and delays the next visible batch.
        if (processed == satellites.size || processed <= 32 || processed % 256 == 0) {
            sorted = withContext(Dispatchers.Default) { mergeDayPasses(sorted, pending) }
            pending.clear()
            currentCoroutineContext().ensureActive()
            onUpdate(initial.copy(
                passes = sorted,
                processedCount = processed,
                failedNoradIds = failures.toSet(),
            ))
        }
    }
    return initial.copy(
        passes = sorted,
        processedCount = processed,
        failedNoradIds = failures.toSet(),
    )
}

/** Prioritize a small search result without waiting for the full catalog calculation. */
internal suspend fun calculatePriorityPasses(
    satellites: List<TrackableSatellite>,
    observer: ObserverLocation,
    day: LocalDate,
    zone: ZoneId,
    onUpdate: (List<ScheduledPass>, Set<String>, Set<String>) -> Unit,
    predictor: PassPredictor = sgp4Predictor,
) {
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    val passes = mutableListOf<ScheduledPass>()
    val completed = mutableSetOf<String>()
    val failed = mutableSetOf<String>()
    satellites.chunked(8).forEach { batch ->
        currentCoroutineContext().ensureActive()
        val calculated = withContext(Dispatchers.Default) {
            batch.map { satellite ->
                currentCoroutineContext().ensureActive()
                val result = try {
                    Result.success(predictBeforeDecay(satellite, observer, start, end, predictor))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Result.failure(error)
                }
                satellite to result
            }
        }
        calculated.forEach { (satellite, result) ->
            completed += satellite.record.noradId
            result.fold(
                onSuccess = { found -> passes += found.map { ScheduledPass(satellite, it) } },
                onFailure = { failed += satellite.record.noradId },
            )
        }
        currentCoroutineContext().ensureActive()
        onUpdate(sortDayPasses(passes), completed.toSet(), failed.toSet())
    }
}
