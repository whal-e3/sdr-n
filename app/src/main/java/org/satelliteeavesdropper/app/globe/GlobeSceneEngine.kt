package org.satelliteeavesdropper.app.globe

import java.time.Duration
import java.time.Instant
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.receiverConfigurationIssue
import org.satelliteeavesdropper.orbit.EARTH_RADIUS_KM
import org.satelliteeavesdropper.orbit.EarthFixedPosition
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.OmmElements
import org.satelliteeavesdropper.orbit.SatelliteLook
import org.satelliteeavesdropper.orbit.SatelliteOrbit

internal const val MAX_GLOBE_CANDIDATES = 128
private const val PATH_STEPS = 96
private const val MAX_PATH_MINUTES = 48.0 * 60.0
private const val EARTH_POLAR_RADIUS_KM = EARTH_RADIUS_KM * (1.0 - 1.0 / 298.257223563)

internal data class GlobeCandidates(
    val records: List<SatelliteRecord>,
    /** All nonhistorical filter matches, including entries whose propagation may fail. */
    val matchingCount: Int,
    val historicalCount: Int,
)

internal fun matchesGlobeQuery(record: SatelliteRecord, query: String): Boolean {
    val text = query.trim()
    return text.isEmpty() || record.noradId.contains(text, ignoreCase = true) ||
        record.name.contains(text, ignoreCase = true) ||
        record.aliases.any { it.contains(text, ignoreCase = true) }
}

/**
 * Searches the entire catalog while retaining only a bounded set of record references. A
 * selected target can be added outside the filters; it does not change the matching count.
 * No OMM models or propagators are retained for the rest of the catalog.
 */
internal suspend fun selectGlobeCandidates(
    catalog: List<SatelliteRecord>,
    query: String,
    radioProfilesOnly: Boolean,
    selected: SatelliteRecord?,
    at: Instant,
    limit: Int = MAX_GLOBE_CANDIDATES,
): GlobeCandidates = withContext(Dispatchers.Default) {
    require(limit in 1..MAX_GLOBE_CANDIDATES)
    val context = currentCoroutineContext()
    val normalizedQuery = query.trim()
    val bestFirst = compareByDescending<RankedRecord> { it.hasRadioProfile }
        .thenBy { it.epochDistanceSeconds }
        .thenBy { it.index }
    val retained = PriorityQueue(limit, bestFirst.reversed())
    var matchingCount = 0
    var historicalCount = 0
    for ((index, record) in catalog.withIndex()) {
        context.ensureActive()
        if (!matchesGlobeQuery(record, normalizedQuery)) continue
        val hasRadioProfile = record.transmitters.any { receiverConfigurationIssue(it) == null }
        if (radioProfilesOnly && !hasRadioProfile) continue
        if (record.isKnownDecayedAt(at)) {
            historicalCount++
            continue
        }
        matchingCount++
        val ranked = RankedRecord(record, hasRadioProfile, epochDistanceSeconds(record, at), index)
        if (retained.size < limit) retained.add(ranked)
        else if (bestFirst.compare(ranked, retained.peek()) < 0) {
            retained.remove()
            retained.add(ranked)
        }
    }
    context.ensureActive()
    val records = retained.toList().sortedWith(bestFirst).mapTo(ArrayList(limit + 1)) { it.record }
    if (selected != null && records.none { it.noradId == selected.noradId }) records.add(selected)
    GlobeCandidates(records, matchingCount, historicalCount)
}

private data class RankedRecord(
    val record: SatelliteRecord,
    val hasRadioProfile: Boolean,
    val epochDistanceSeconds: Double,
    val index: Int,
)

private fun epochDistanceSeconds(record: SatelliteRecord, at: Instant): Double = try {
    val raw = record.omm.getString("EPOCH")
    val epoch = Instant.parse(if (raw.endsWith("Z")) raw else "${raw}Z")
    val difference = Duration.between(epoch, at)
    kotlin.math.abs(difference.seconds.toDouble() + difference.nano / 1e9)
} catch (_: Exception) {
    Double.POSITIVE_INFINITY
}

internal data class GlobeScene(
    val at: Instant,
    val markers: List<GlobeMarker> = emptyList(),
    val selectedLook: SatelliteLook? = null,
    val orbitPath: List<EarthFixedPosition> = emptyList(),
    val groundTrack: List<EarthFixedPosition> = emptyList(),
    val failedCount: Int = 0,
    val selectedError: String? = null,
    val pathDurationMinutes: Double = 0.0,
)

/**
 * Owns at most 129 models and reuses each SGP4 propagator for subsequent frames. Construction
 * and propagation run off the main thread. The mutex also protects a reused model from two
 * overlapping UI requests after cancellation or a rapid time-slider change.
 */
internal class GlobeSceneEngine(
    candidates: GlobeCandidates,
    private val orbitFactory: (OmmElements) -> SatelliteOrbit = { SatelliteOrbit(it) },
) {
    private val records = candidates.records
    private val mutex = Mutex()
    private var prepared: List<PreparedOrbit>? = null

    init {
        require(records.size <= MAX_GLOBE_CANDIDATES + 1)
    }

    suspend fun snapshot(
        at: Instant,
        observer: ObserverLocation?,
        selectedNoradId: String?,
        aboveHorizonOnly: Boolean = false,
        includePaths: Boolean = true,
    ): GlobeScene = withContext(Dispatchers.Default) {
        mutex.withLock {
            val context = currentCoroutineContext()
            context.ensureActive()
            val entries = prepared ?: records.map { record ->
                context.ensureActive()
                try {
                    val elements = record.orbitElements()
                        ?: throw IllegalArgumentException("Orbital elements are unavailable or invalid.")
                    PreparedOrbit(record, elements, orbitFactory(elements), null)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    PreparedOrbit(record, null, null, failureMessage(error))
                }
            }.also { prepared = it }
            val markers = ArrayList<GlobeMarker>(entries.size)
            var selectedLook: SatelliteLook? = null
            var selectedError: String? = null
            var selectedEntry: PreparedOrbit? = null
            val failures = HashSet<String>()
            for (entry in entries) {
                context.ensureActive()
                val record = entry.record
                val isSelected = record.noradId == selectedNoradId
                if (record.isKnownDecayedAt(at)) {
                    if (isSelected) selectedError = "Source marks this orbit historical at the displayed time."
                    continue
                }
                val orbit = entry.orbit
                if (orbit == null) {
                    failures.add(record.noradId)
                    if (isSelected) selectedError = entry.error
                    continue
                }
                try {
                    val position = orbit.earthFixedPosition(at).also(::requirePhysicalOrbit)
                    val look = observer?.let { orbit.lookFrom(it, at).also(::requireValidLook) }
                    val aboveHorizon = look?.elevationDegrees?.let { it >= 0.0 } ?: false
                    if (!aboveHorizonOnly || aboveHorizon || isSelected) {
                        markers.add(GlobeMarker(record.noradId, record.name, position, aboveHorizon))
                    }
                    if (isSelected) {
                        selectedLook = look
                        selectedEntry = entry
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failures.add(record.noradId)
                    if (isSelected) selectedError = failureMessage(error)
                }
            }
            var orbitPath: List<EarthFixedPosition> = emptyList()
            var groundTrack: List<EarthFixedPosition> = emptyList()
            var pathDurationMinutes = 0.0
            if (includePaths && selectedEntry != null) {
                val entry = selectedEntry
                try {
                    val periodMinutes = 1_440.0 / requireNotNull(entry.elements).meanMotionRevolutionsPerDay
                    val requestedMillis = (minOf(periodMinutes, MAX_PATH_MINUTES) * 60_000.0).toLong()
                    require(requestedMillis > 0) { "Orbital period is too short to display." }
                    val requestedEnd = at.plusMillis(requestedMillis)
                    val boundary = entry.record.predictionEndBeforeDecay(at, requestedEnd)
                        ?: throw IllegalArgumentException("No orbit preview before the source historical cutoff.")
                    // Never sample the cutoff itself, even when an orbit arc crosses midnight.
                    val end = if (entry.record.isKnownDecayedAt(boundary)) boundary.minusNanos(1) else boundary
                    val duration = Duration.between(at, end)
                    val durationNanos = duration.toNanos()
                    require(durationNanos > 0) { "No orbit preview before the source historical cutoff." }
                    val path = ArrayList<EarthFixedPosition>(PATH_STEPS + 1)
                    val track = ArrayList<EarthFixedPosition>(PATH_STEPS + 1)
                    val orbit = requireNotNull(entry.orbit)
                    for (step in 0..PATH_STEPS) {
                        context.ensureActive()
                        val sampleAt = at.plusNanos(durationNanos * step / PATH_STEPS)
                        val arcPoint = orbit.earthFixedPosition(sampleAt, rotationAt = at).also(::requirePhysicalOrbit)
                        val fixedPoint = orbit.earthFixedPosition(sampleAt).also(::requirePhysicalOrbit)
                        val scale = EARTH_RADIUS_KM / fixedPoint.radiusKm
                        path.add(arcPoint)
                        track.add(EarthFixedPosition(fixedPoint.xKm * scale, fixedPoint.yKm * scale, fixedPoint.zKm * scale))
                    }
                    orbitPath = path
                    groundTrack = track
                    pathDurationMinutes = durationNanos / 60_000_000_000.0
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failures.add(entry.record.noradId)
                    selectedError = "Orbit preview unavailable: ${failureMessage(error)}"
                }
            }
            context.ensureActive()
            GlobeScene(at, markers, selectedLook, orbitPath, groundTrack, failures.size,
                selectedError, pathDurationMinutes)
        }
    }
}

private data class PreparedOrbit(
    val record: SatelliteRecord,
    val elements: OmmElements?,
    val orbit: SatelliteOrbit?,
    val error: String?,
)

private fun requirePhysicalOrbit(position: EarthFixedPosition) {
    val equatorialSquared = EARTH_RADIUS_KM * EARTH_RADIUS_KM
    val polarSquared = EARTH_POLAR_RADIUS_KM * EARTH_POLAR_RADIUS_KM
    val ellipsoidRadiusSquared = (position.xKm * position.xKm + position.yKm * position.yKm) /
        equatorialSquared + position.zKm * position.zKm / polarSquared
    require(ellipsoidRadiusSquared.isFinite() && ellipsoidRadiusSquared >= 1.0 - 1e-12) {
        "Predicted position is inside Earth; geometry withheld."
    }
}

private fun requireValidLook(look: SatelliteLook) {
    require(look.azimuthDegrees.isFinite() && look.azimuthDegrees in 0.0..360.0 &&
        look.elevationDegrees.isFinite() && look.elevationDegrees in -90.0..90.0 &&
        look.slantRangeKm.isFinite() && look.slantRangeKm > 0.0 && look.rangeRateKmPerSecond.isFinite()) {
        "Predicted pointing is invalid; geometry withheld."
    }
}

private fun failureMessage(error: Exception): String =
    error.message?.take(180)?.takeIf { it.isNotBlank() } ?: "Orbit prediction is unavailable."
