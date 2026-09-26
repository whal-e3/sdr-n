package org.satelliteeavesdropper.app.day

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.satelliteeavesdropper.app.data.CatalogManifest
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DayScheduleCacheTest {
    private val day = LocalDate.of(2026, 9, 26)
    private val zone = ZoneId.of("Asia/Seoul")
    private val observer = ObserverLocation(37.24, 127.17)
    private val epoch = Instant.parse("2026-09-24T00:00:00Z")

    @Test fun broadDayRolloverDropsPassesBeforeReplacementAndReusesOrbitList() {
        val cache = DayScheduleCache()
        val manifest = manifest(broad = true)
        val firstKey = key(manifest)
        val first = completed(manifest)
        assertNull(cache.prepare(firstKey))
        cache.put(firstKey, first)

        val nextKey = key(manifest, day = day.plusDays(1))
        val next = requireNotNull(cache.prepare(nextKey))
        assertNull(cache.get(firstKey))
        assertSame(first.satellites, next.satellites)
        assertTrue(next.passes.isEmpty())
        assertEquals(0, next.processedCount)
        assertTrue(next.failedNoradIds.isEmpty())
        assertEquals(day.plusDays(1), next.day)
        assertFalse(next.isComplete)

        // Publishing a late batch from the old calculation cannot restore its large day.
        cache.put(firstKey, first)
        assertNull(cache.get(firstKey))
        assertSame(next, cache.get(nextKey))
    }

    @Test fun observerChangeReusesOnlyTheSameManifestOrbitList() {
        val cache = DayScheduleCache()
        val manifest = manifest(broad = true)
        val firstKey = key(manifest)
        val first = completed(manifest)
        cache.prepare(firstKey)
        cache.put(firstKey, first)

        val noObserver = key(manifest, observer = null)
        val seed = requireNotNull(cache.prepare(noObserver))
        assertSame(first.satellites, seed.satellites)
        assertTrue(seed.passes.isEmpty())
        assertNull(cache.get(firstKey))
    }

    @Test fun equivalentMetadataWithDifferentManifestDropsOldRecordGraph() {
        val cache = DayScheduleCache()
        val firstManifest = manifest()
        val firstKey = key(firstManifest)
        val first = completed(firstManifest)
        cache.prepare(firstKey)
        cache.put(firstKey, first)

        // Identical metadata and list contents do not prove record-graph identity.
        val secondManifest = firstManifest.copy(satellites = firstManifest.satellites.toList())
        assertEquals(firstManifest, secondManifest)
        val secondKey = key(secondManifest)
        assertNotEquals(firstKey, secondKey)
        assertNull(cache.prepare(secondKey))
        assertNull(cache.get(firstKey))
        cache.put(firstKey, first)
        assertNull(cache.get(firstKey))
    }

    @Test fun tabNavigationResumesTheSamePartialScheduleWithoutCopying() {
        val cache = DayScheduleCache()
        val manifest = manifest()
        val originalKey = key(manifest)
        val partial = completed(manifest).copy(processedCount = 0)
        cache.prepare(originalKey)
        cache.put(originalKey, partial)

        assertSame(partial, cache.prepare(key(manifest)))
        assertSame(partial, cache.get(originalKey))
    }

    @Test fun narrowCatalogRetainsTwoDaysButEvictsBeforeThirdDayStarts() {
        val cache = DayScheduleCache()
        val manifest = manifest()
        val firstKey = key(manifest)
        val first = completed(manifest)
        cache.prepare(firstKey)
        cache.put(firstKey, first)
        val secondKey = key(manifest, day = day.plusDays(1))
        val second = requireNotNull(cache.prepare(secondKey))
        cache.put(secondKey, second.copy(processedCount = second.satellites.size))
        assertSame(first, cache.get(firstKey))

        val thirdKey = key(manifest, day = day.plusDays(2))
        cache.prepare(thirdKey)
        assertNull(cache.get(secondKey)) // Reading firstKey made the first day most recent.
        assertSame(first, cache.get(firstKey))
        assertTrue(requireNotNull(cache.get(thirdKey)).passes.isEmpty())
    }

    @Test fun disposalAndReloadClearEveryCachedReferenceAndRejectLateUpdates() {
        val cache = DayScheduleCache()
        val manifest = manifest(broad = true)
        val oldKey = key(manifest)
        val schedule = completed(manifest)
        cache.prepare(oldKey)
        cache.put(oldKey, schedule)
        cache.clear()
        cache.put(oldKey, schedule)
        assertNull(cache.get(oldKey))
        assertNull(cache.prepare(oldKey))
    }

    private fun key(manifest: CatalogManifest, day: LocalDate = this.day,
                    observer: ObserverLocation? = this.observer) = ScheduleCacheKey(
        ScheduleCatalogIdentity(manifest), manifest.sequence, manifest.generatedAt,
        CatalogSource.CACHED, manifest.satellites.size, "", observer, day, zone,
    )

    private fun manifest(broad: Boolean = false): CatalogManifest {
        val record = SatelliteRecord("1", "Satellite", emptyList(), JSONObject().apply {
            put("EPOCH", epoch.toString())
            put("MEAN_MOTION", 15.0)
            put("ECCENTRICITY", 0.001)
            put("INCLINATION", 51.6)
            put("RA_OF_ASC_NODE", 10.0)
            put("ARG_OF_PERICENTER", 20.0)
            put("MEAN_ANOMALY", 30.0)
        }, emptyList())
        // Cache policy depends on source count. Repeated immutable fixture records avoid
        // allocating 40k unrelated OMM trees in this retention test.
        val records = if (broad) List(40_000) { record } else listOf(record)
        return CatalogManifest(1, epoch, epoch, records, emptyList())
    }

    private fun completed(manifest: CatalogManifest): DayPassSchedule {
        val record = manifest.satellites.first()
        val satellite = TrackableSatellite(record, requireNotNull(record.orbitElements()), 0)
        val start = day.atStartOfDay(zone).toInstant()
        val pass = SatellitePass(start, start.plusSeconds(60), start.plusSeconds(120),
            45.0, 90.0, 270.0, false, false)
        return DayPassSchedule(day, zone, manifest.satellites.size, listOf(satellite),
            listOf(ScheduledPass(satellite, pass)), 1, setOf("bad-orbit"))
    }
}
