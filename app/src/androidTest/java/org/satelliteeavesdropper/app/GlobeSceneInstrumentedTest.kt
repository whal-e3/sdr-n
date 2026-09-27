package org.satelliteeavesdropper.app

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.satelliteeavesdropper.app.data.CatalogRepository
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.app.globe.GlobeScene
import org.satelliteeavesdropper.app.globe.GlobeSceneEngine
import org.satelliteeavesdropper.app.globe.selectGlobeCandidates
import org.satelliteeavesdropper.orbit.EARTH_RADIUS_KM
import org.satelliteeavesdropper.orbit.EarthFixedPosition
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.SatelliteOrbit

/** Read-only real-catalog geometry and timing checks; a clean install has no signed fixture. */
@RunWith(AndroidJUnit4::class)
class GlobeSceneInstrumentedTest {
    @Test fun measuresBoundedGlobeScenesFromFullSignedCatalogOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheFile = context.filesDir.resolve("catalog.cache")
        assumeTrue("Seed a locally signed catalog before this device benchmark", cacheFile.isFile)
        val originalCacheDigest = digest(cacheFile)
        val catalog = CatalogRepository(context).load(refresh = false)
        assertEquals(CatalogSource.CACHED, catalog.source)
        val records = catalog.manifest.satellites
        val originalCount = records.size
        InstrumentationRegistry.getArguments().getString("expectedCatalogCount")?.toIntOrNull()?.let {
            assertEquals("The full installed catalog must remain available", it, originalCount)
        }
        assertTrue("This benchmark needs a full catalog", originalCount > 128)
        val selected = requireNotNull(records.firstOrNull { it.noradId == "25544" }) {
            "The signed fixture must contain ISS (25544)"
        }
        val observer = ObserverLocation(37.2411, 127.1776) // Yongin; benchmark coordinates.
        val start = Instant.now()
        val selectionStarted = SystemClock.elapsedRealtimeNanos()
        val candidates = selectGlobeCandidates(records, query = "", radioProfilesOnly = false,
            selected = selected, at = start, limit = 128)
        val selectionNanos = SystemClock.elapsedRealtimeNanos() - selectionStarted
        assertTrue(candidates.records.size <= 129)
        assertTrue(candidates.records.any { it.noradId == selected.noradId })
        assertEquals(originalCount, candidates.matchingCount + candidates.historicalCount)
        val engine = GlobeSceneEngine(candidates)
        // Independent models check horizon classification; their work is excluded from timings.
        val independentOrbits = HashMap<String, SatelliteOrbit>()
        fun verify(scene: GlobeScene, requestedAt: Instant) {
            assertEquals("A scene must describe its actual propagation time", requestedAt, scene.at)
            assertTrue(scene.markers.size <= 129)
            assertEquals(scene.markers.size, scene.markers.map { it.noradId }.toSet().size)
            assertNull("ISS position and orbit preview must be available", scene.selectedError)
            val marker = requireNotNull(scene.markers.firstOrNull { it.noradId == selected.noradId })
            assertFinite(marker.position)
            val selectedLook = requireNotNull(scene.selectedLook)
            assertEquals(requestedAt, selectedLook.time)
            assertEquals(selectedLook.elevationDegrees >= 0.0, marker.aboveHorizon)
            for (current in scene.markers) {
                assertFinite(current.position)
                val record = requireNotNull(candidates.records.firstOrNull { it.noradId == current.noradId })
                assertFalse(record.isKnownDecayedAt(requestedAt))
                val orbit = independentOrbits.getOrPut(record.noradId) {
                    SatelliteOrbit(requireNotNull(record.orbitElements()))
                }
                assertEquals("Horizon classification for ${record.noradId}",
                    orbit.lookFrom(observer, requestedAt).elevationDegrees >= 0.0, current.aboveHorizon)
            }
            assertTrue(scene.orbitPath.size > 1)
            assertEquals(scene.orbitPath.size, scene.groundTrack.size)
            assertTrue(scene.pathDurationMinutes.isFinite() && scene.pathDurationMinutes > 0.0)
            scene.orbitPath.forEach(::assertFinite)
            scene.groundTrack.forEach { point ->
                assertFinite(point)
                assertEquals(EARTH_RADIUS_KM, point.radiusKm, 1e-6)
            }
            val pathStart = scene.orbitPath.first()
            assertEquals(marker.position.xKm, pathStart.xKm, 1e-6)
            assertEquals(marker.position.yKm, pathStart.yKm, 1e-6)
            assertEquals(marker.position.zKm, pathStart.zKm, 1e-6)
            assertEquals("Globe rendering must not trim catalog records", originalCount, records.size)
        }

        val firstStarted = SystemClock.elapsedRealtimeNanos()
        val first = engine.snapshot(start, observer, selected.noradId, includePaths = true)
        val firstNanos = SystemClock.elapsedRealtimeNanos() - firstStarted
        verify(first, start)
        Log.i("SatelliteGlobePerf", "catalog=$originalCount matching=${candidates.matchingCount} " +
            "historical=${candidates.historicalCount} candidates=${candidates.records.size} " +
            "markers=${first.markers.size} paths=${first.orbitPath.size} failures=${first.failedCount} " +
            "selection=${milliseconds(selectionNanos)}ms first=${milliseconds(firstNanos)}ms")

        for (mode in listOf("live", "preview60x")) {
            val timings = ArrayList<Long>(8)
            var lastScene = first
            repeat(8) { index ->
                // Live has a one-second cadence. Preview advances 15 seconds per 250 ms UI tick.
                val offsetSeconds = (index + 1L) * if (mode == "live") 1L else 15L
                val requestedAt = start.plusSeconds(offsetSeconds)
                val begun = SystemClock.elapsedRealtimeNanos()
                val scene = engine.snapshot(requestedAt, observer, selected.noradId, includePaths = true)
                timings += SystemClock.elapsedRealtimeNanos() - begun
                verify(scene, requestedAt)
                lastScene = scene
            }
            val sorted = timings.sorted()
            val medianNanos = (sorted[3] + sorted[4]) / 2
            val p95Nanos = sorted[ceil(sorted.size * 0.95).toInt() - 1]
            Log.i("SatelliteGlobePerf", "mode=$mode samples=${timings.size} " +
                "markers=${lastScene.markers.size} paths=${lastScene.orbitPath.size} " +
                "failures=${lastScene.failedCount} median=${milliseconds(medianNanos)}ms " +
                "p95=${milliseconds(p95Nanos)}ms max=${milliseconds(sorted.last())}ms")
        }

        assertTrue("The benchmark must leave the signed cache unchanged",
            originalCacheDigest.contentEquals(digest(cacheFile)))
        val reloaded = CatalogRepository(context).load(refresh = false)
        assertEquals(CatalogSource.CACHED, reloaded.source)
        assertEquals(originalCount, reloaded.manifest.satellites.size)
    }

    private fun assertFinite(position: EarthFixedPosition) {
        assertTrue(position.xKm.isFinite() && position.yKm.isFinite() && position.zKm.isFinite())
        assertTrue(position.radiusKm.isFinite() && position.radiusKm > 0.0)
        assertTrue(position.geocentricLatitudeDegrees in -90.0..90.0)
        assertTrue(position.longitudeDegrees in -180.0..180.0)
    }

    private fun milliseconds(nanos: Long): String = String.format(Locale.US, "%.2f", nanos / 1_000_000.0)

    private fun digest(file: File): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha256.update(buffer, 0, read)
            }
        }
        return sha256.digest()
    }
}
