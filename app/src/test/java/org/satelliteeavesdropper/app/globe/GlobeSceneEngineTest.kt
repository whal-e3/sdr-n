package org.satelliteeavesdropper.app.globe

import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.TransmitterRecord
import org.satelliteeavesdropper.orbit.EARTH_RADIUS_KM
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.SatelliteOrbit
import org.satelliteeavesdropper.orbit.TemeState
import org.satelliteeavesdropper.orbit.TemeStateProvider
import org.satelliteeavesdropper.orbit.Vector3
import org.satelliteeavesdropper.orbit.gmstRadians

class GlobeSceneEngineTest {
    private val now = Instant.parse("2026-09-27T00:00:00Z")
    private val observer = ObserverLocation(0.0, 0.0)

    @Test fun searchFindsNamesAliasesAndIdsBeyondRenderingLimit() = runBlocking {
        val records = (1..700).map { record(it) }.toMutableList()
        records[699] = record(700).copy(name = "Distant Explorer", aliases = listOf("Hidden callsign"))
        for (query in listOf("700", " distant EXPLORER ", "CALLSIGN")) {
            val candidates = selectGlobeCandidates(records, query, false, null, now)
            assertEquals(1, candidates.matchingCount)
            assertEquals(listOf("700"), candidates.records.map { it.noradId })
        }
        assertEquals(700, selectGlobeCandidates(records, "", false, null, now).matchingCount)
    }

    @Test fun selectionIsBoundedPrioritizesConfiguredRadioAndFreshElements() = runBlocking {
        val records = (1..200).map { record(it, epoch = now.minusSeconds(it * 86_400L)) }.toMutableList()
        records.add(record(201, transmitters = listOf(radioProfile), epoch = now.minusSeconds(999 * 86_400L)))
        val selected = record(999)
        val candidates = selectGlobeCandidates(records, "", false, selected, now, limit = 3)
        assertEquals(201, candidates.matchingCount)
        assertEquals(listOf("201", "1", "2", "999"), candidates.records.map { it.noradId })
        val filtered = selectGlobeCandidates(records, "", true, selected, now, limit = 3)
        assertEquals(1, filtered.matchingCount)
        assertEquals(listOf("201", "999"), filtered.records.map { it.noradId })
    }

    @Test fun selectedFilterExceptionDoesNotIncreaseMatchingCount() = runBlocking {
        val selected = record(1)
        val candidates = selectGlobeCandidates(listOf(selected, record(2)), "absent", true, selected, now)
        assertEquals(0, candidates.matchingCount)
        assertEquals(listOf(selected), candidates.records)
    }

    @Test fun historicalMatchesAreCountedSeparatelyAndSelectedHistoricalHasNoGeometry() = runBlocking {
        val historical = record(1, decayDate = "2026-09-27")
        val candidates = selectGlobeCandidates(listOf(historical, record(2)), "", false, historical, now)
        assertEquals(1, candidates.matchingCount)
        assertEquals(1, candidates.historicalCount)
        val scene = GlobeSceneEngine(candidates) { fixedOrbit() }.snapshot(now, observer, "1")
        assertEquals(listOf("2"), scene.markers.map { it.noradId })
        assertNotNull(scene.selectedError)
        assertNull(scene.selectedLook)
        assertTrue(scene.orbitPath.isEmpty())
        assertEquals(0, scene.failedCount)
    }

    @Test fun constructorsAreBoundedAndReusedAcrossSnapshots() = runBlocking {
        val candidates = selectGlobeCandidates((1..500).map { record(it) }, "", false, record(999), now)
        assertEquals(129, candidates.records.size)
        var constructions = 0
        val engine = GlobeSceneEngine(candidates) {
            constructions++
            fixedOrbit()
        }
        val first = engine.snapshot(now, null, null, includePaths = false)
        val second = engine.snapshot(now.plusSeconds(1), null, null, includePaths = false)
        assertEquals(129, constructions)
        assertEquals(129, first.markers.size)
        assertEquals(129, second.markers.size)
        assertEquals(0, first.failedCount)
    }

    @Test fun malformedAndInsideEarthPredictionsAreWithheldAndCountedOnce() = runBlocking {
        val malformed = record(1).also { it.omm.put("ECCENTRICITY", 2.0) }
        val belowEarth = record(2)
        val valid = record(3)
        val engine = GlobeSceneEngine(GlobeCandidates(listOf(malformed, belowEarth, valid), 3, 0)) {
            if (it.noradId == "2") fixedOrbit(radiusKm = 6_000.0) else fixedOrbit()
        }
        val scene = engine.snapshot(now, observer, "2")
        assertEquals(listOf("3"), scene.markers.map { it.noradId })
        assertEquals(2, scene.failedCount)
        assertTrue(scene.selectedError!!.contains("inside Earth"))
        assertNull(scene.selectedLook)
        assertTrue(scene.orbitPath.isEmpty())
    }

    @Test fun selectedTargetRemainsVisibleWhenBelowHorizonFilterIsEnabled() = runBlocking {
        val records = listOf(record(1), record(2), record(3))
        val engine = GlobeSceneEngine(GlobeCandidates(records, 3, 0)) {
            fixedOrbit(negativeX = it.noradId != "3")
        }
        val scene = engine.snapshot(now, observer, "1", aboveHorizonOnly = true, includePaths = false)
        assertEquals(listOf("1", "3"), scene.markers.map { it.noradId })
        assertFalse(scene.markers[0].aboveHorizon)
        assertTrue(scene.markers[1].aboveHorizon)
        assertTrue(scene.selectedLook!!.elevationDegrees < 0.0)
    }

    @Test fun physicalCheckRejectsBuriedEquatorialOrbitButAcceptsValidPolarRadius() = runBlocking {
        val polarRadiusKm = EARTH_RADIUS_KM * (1.0 - 1.0 / 298.257223563)
        val engine = GlobeSceneEngine(GlobeCandidates(listOf(record(1), record(2)), 2, 0)) {
            if (it.noradId == "1") fixedOrbit(radiusKm = EARTH_RADIUS_KM - 1.0)
            else SatelliteOrbit(TemeStateProvider {
                TemeState(Vector3(0.0, 0.0, polarRadiusKm + 1.0), Vector3(0.0, 0.0, 0.0))
            })
        }
        val scene = engine.snapshot(now, null, "1", includePaths = false)
        assertEquals(listOf("2"), scene.markers.map { it.noradId })
        assertEquals(1, scene.failedCount)
        assertTrue(scene.selectedError!!.contains("inside Earth"))
        assertTrue(scene.markers.single().position.radiusKm < EARTH_RADIUS_KM)
    }

    @Test fun orbitArcUsesFrozenEarthRotationAndGroundTrackUsesSurfaceProjection() = runBlocking {
        val engine = GlobeSceneEngine(GlobeCandidates(listOf(record(1)), 1, 0)) { fixedOrbit() }
        val scene = engine.snapshot(now, observer, "1")
        assertEquals(97, scene.orbitPath.size)
        assertEquals(97, scene.groundTrack.size)
        assertEquals(96.0, scene.pathDurationMinutes, 1e-8)
        assertNotNull(scene.selectedLook)
        assertNull(scene.selectedError)
        assertTrue(scene.orbitPath.last().yKm > 1_000.0)
        for (point in scene.groundTrack) {
            assertEquals(EARTH_RADIUS_KM, point.radiusKm, 1e-8)
            assertEquals(EARTH_RADIUS_KM, point.xKm, 1e-7)
            assertEquals(0.0, point.yKm, 1e-7)
        }
    }

    @Test fun pathStopsBeforeSourceHistoricalCutoffAndPreviewRechecksThatCutoff() = runBlocking {
        val before = Instant.parse("2026-09-26T23:30:00Z")
        val target = record(1, decayDate = "2026-09-27")
        val sampled = mutableListOf<Instant>()
        val engine = GlobeSceneEngine(GlobeCandidates(listOf(target), 1, 0)) { fixedOrbit(onSample = sampled::add) }
        val scene = engine.snapshot(before, null, "1")
        assertEquals(30.0, scene.pathDurationMinutes, 1e-7)
        assertTrue(sampled.all { it.isBefore(now) })
        assertEquals(97, scene.orbitPath.size)
        val historicalScene = engine.snapshot(now, null, "1")
        assertTrue(historicalScene.markers.isEmpty())
        assertTrue(historicalScene.orbitPath.isEmpty())
        assertNotNull(historicalScene.selectedError)
    }

    @Test fun longPeriodPreviewIsCappedAt48Hours() = runBlocking {
        val target = record(1).also { it.omm.put("MEAN_MOTION", 0.1) }
        val scene = GlobeSceneEngine(GlobeCandidates(listOf(target), 1, 0)) { fixedOrbit() }
            .snapshot(now, null, "1")
        assertEquals(48.0 * 60.0, scene.pathDurationMinutes, 1e-8)
        assertEquals(97, scene.orbitPath.size)
    }

    @Test fun failedFutureArcDoesNotFabricateOrJoinPartialPaths() = runBlocking {
        val engine = GlobeSceneEngine(GlobeCandidates(listOf(record(1)), 1, 0)) {
            fixedOrbit(onSample = { if (it.isAfter(now.plusSeconds(60))) error("Propagation failed") })
        }
        val scene = engine.snapshot(now, null, "1")
        assertEquals(1, scene.markers.size)
        assertEquals(1, scene.failedCount)
        assertTrue(scene.orbitPath.isEmpty())
        assertTrue(scene.groundTrack.isEmpty())
        assertEquals(0.0, scene.pathDurationMinutes, 0.0)
        assertTrue(scene.selectedError!!.contains("Orbit preview unavailable"))
    }

    @Test fun cancellationFromPropagationRemainsCancellation() {
        var cancelled = false
        try {
            runBlocking {
                val engine = GlobeSceneEngine(GlobeCandidates(listOf(record(1)), 1, 0)) {
                    fixedOrbit(onSample = { throw CancellationException("cancelled propagation") })
                }
                engine.snapshot(now, null, "1")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun realSgp4SnapshotProducesFiniteGeometry() = runBlocking {
        val scene = GlobeSceneEngine(GlobeCandidates(listOf(record(1)), 1, 0)).snapshot(now, observer, "1")
        assertEquals(1, scene.markers.size)
        assertEquals(0, scene.failedCount)
        assertEquals(97, scene.orbitPath.size)
        assertTrue(scene.markers.single().position.radiusKm > EARTH_RADIUS_KM)
        assertNotNull(scene.selectedLook)
    }

    private fun record(
        id: Int,
        epoch: Instant = now,
        decayDate: String? = null,
        transmitters: List<TransmitterRecord> = emptyList(),
    ) = SatelliteRecord(
        noradId = id.toString(),
        name = "Satellite $id",
        aliases = emptyList(),
        omm = JSONObject().apply {
            put("EPOCH", epoch.toString())
            put("MEAN_MOTION", 15.0)
            put("ECCENTRICITY", 0.001)
            put("INCLINATION", 51.6)
            put("RA_OF_ASC_NODE", 10.0)
            put("ARG_OF_PERICENTER", 20.0)
            put("MEAN_ANOMALY", 30.0)
            decayDate?.let { put("DECAY_DATE", it) }
        },
        transmitters = transmitters,
    )

    private fun fixedOrbit(
        radiusKm: Double = EARTH_RADIUS_KM + 500.0,
        negativeX: Boolean = false,
        onSample: (Instant) -> Unit = {},
    ): SatelliteOrbit = SatelliteOrbit(TemeStateProvider { at ->
        onSample(at)
        val theta = gmstRadians(at)
        val x = if (negativeX) -radiusKm else radiusKm
        TemeState(Vector3(cos(theta) * x, sin(theta) * x, 0.0), Vector3(0.0, 0.0, 0.0))
    })

    private val radioProfile = TransmitterRecord(
        "valid", 145_825_000, 12_500, "NFM", 1200, "active", null,
        "AX25_AFSK1200", 1_024_000, null, "amateur", emptyList(),
    )
}
