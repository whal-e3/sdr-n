package org.satelliteeavesdropper.app

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.satelliteeavesdropper.app.data.CatalogRepository
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.app.day.calculateDaySchedule
import java.time.LocalDate
import java.time.ZoneId

/** Exercises a locally seeded signed catalog on the device; skips a clean install. */
@RunWith(AndroidJUnit4::class)
class SignedCatalogCacheInstrumentedTest {
    @Test fun verifiesAndParsesLargeSignedCacheOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.filesDir.resolve("catalog.cache").isFile)
        val started = SystemClock.elapsedRealtime()
        val result = CatalogRepository(context).load(refresh = false)
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i("SatelliteCatalogPerf", "${result.manifest.satellites.size} records verified and parsed in ${elapsed}ms")
        assertEquals(CatalogSource.CACHED, result.source)
        // Reopening / granting USB access reuses the authenticated graph, preventing
        // a second full OMM catalog allocation while the screen still holds the first.
        assertSame(result.manifest, CatalogRepository(context).load(refresh = false).manifest)
        val cacheFile = context.filesDir.resolve("catalog.cache")
        val original = cacheFile.readBytes()
        try {
            val altered = original.copyOf()
            altered[altered.lastIndex] = (altered.last().toInt() xor 1).toByte()
            cacheFile.writeBytes(altered)
            assertEquals("Changed disk bytes must not reuse the verified graph", CatalogSource.DEMO,
                CatalogRepository(context).load(refresh = false).source)
        } finally {
            cacheFile.writeBytes(original)
        }
        assertSame(result.manifest, CatalogRepository(context).load(refresh = false).manifest)
        assertTrue(result.manifest.satellites.size >= 1_000)
        InstrumentationRegistry.getArguments().getString("expectedCatalogCount")?.toIntOrNull()?.let { expected ->
            assertEquals(expected, result.manifest.satellites.size)
        }
        assertEquals(
            result.manifest.satellites.size,
            result.manifest.satellites.map { it.noradId }.toSet().size,
        )
    }

    /** Measures one representative chunk so the large day schedule has phone evidence. */
    @Test fun measuresDayPredictionChunkOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.filesDir.resolve("catalog.cache").isFile)
        val catalog = CatalogRepository(context).load(refresh = false)
        assertEquals(CatalogSource.CACHED, catalog.source)
        val records = catalog.manifest.satellites.take(128)
        val observer = ObserverLocation(37.32, 127.18) // Yongin area; benchmark only.
        val day = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val begun = SystemClock.elapsedRealtime()
        // Real unfiltered GP includes decades-old records with source decay dates.
        // Exercise the app's actual cutoffs and per-orbit failure reporting rather
        // than extrapolating those historical elements into the current day.
        val schedule = calculateDaySchedule(
            manifest = catalog.manifest.copy(satellites = records),
            observer = observer,
            day = day,
            zone = zone,
            onUpdate = {},
        )
        val elapsed = SystemClock.elapsedRealtime() - begun
        Log.i("SatelliteSchedulePerf", "128 records, ${schedule.passes.size} passes, " +
            "${schedule.failedNoradIds.size} reported failures, ${elapsed}ms on device")
        assertEquals(128, records.size)
        assertEquals(128, schedule.catalogCount)
        assertEquals(128, schedule.processedCount)
        assertTrue(schedule.isComplete)
        assertTrue(schedule.passes.isNotEmpty())
        assertTrue(schedule.passes.none { it.satellite.record.isKnownDecayedAt(schedule.intervalStart) })
        assertTrue(records.filter { it.isKnownDecayedAt(schedule.intervalStart) }
            .none { it.noradId in schedule.failedNoradIds })
    }
}
