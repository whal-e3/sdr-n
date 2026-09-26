package org.satelliteeavesdropper.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class OrbitLookupRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun keepsTrackOnlyOrbitAndPersistsTwoHourRequestLimit() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-24T09:00:00Z"))
        var calls = 0
        val fetcher = OrbitLookupFetcher {
            calls++
            OrbitLookupHttpResponse(200, response("27386", "ENVISAT"))
        }
        val directory = temp.newFolder()
        val repository = OrbitLookupRepository(directory, fetcher, clock)

        val first = repository.lookupNoradId("027386") as OrbitLookupResult.Found
        assertFalse(first.fromCache)
        assertEquals("27386", first.satellite.noradId)
        assertEquals("ENVISAT", first.satellite.name)
        assertNotNull(first.satellite.orbitElements())
        assertTrue(first.satellite.transmitters.isEmpty())
        assertTrue(first.provenance.contains("tracking only"))
        assertEquals(1, calls)

        val reloaded = OrbitLookupRepository(directory, fetcher, clock)
        assertEquals("ENVISAT", reloaded.cachedRecords().single().satellite.name)
        clock.time = clock.time.plusSeconds(60 * 60)
        val cached = reloaded.lookupNoradId("27386") as OrbitLookupResult.Found
        assertTrue(cached.fromCache)
        assertEquals(first.fetchedAt, cached.fetchedAt)
        assertEquals(1, calls)

        clock.time = first.checkedAt.plusSeconds(2 * 60 * 60)
        val refreshed = reloaded.lookupNoradId("27386") as OrbitLookupResult.Found
        assertFalse(refreshed.fromCache)
        assertEquals(2, calls)
        assertEquals(clock.time, refreshed.fetchedAt)
    }

    @Test
    fun non200StopsAndCannotBeRepeatedInsideTwoHours() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-24T09:00:00Z"))
        var calls = 0
        val fetcher = OrbitLookupFetcher {
            calls++
            if (calls == 1) OrbitLookupHttpResponse(403, ByteArray(0))
            else OrbitLookupHttpResponse(200, response("27386", "ENVISAT"))
        }
        val directory = temp.newFolder()
        val repository = OrbitLookupRepository(directory, fetcher, clock)

        val blocked = repository.lookupNoradId("27386") as OrbitLookupResult.Unavailable
        assertEquals(403, blocked.httpStatus)
        assertTrue(blocked.message.contains("without retrying"))
        assertEquals(1, calls)

        val second = OrbitLookupRepository(directory, fetcher, clock).lookupNoradId("27386")
        assertTrue(second.fromCache)
        assertEquals(1, calls)

        clock.time = clock.time.plusSeconds(2 * 60 * 60)
        val requestedAgain = repository.lookupNoradId("27386") as OrbitLookupResult.Found
        assertFalse(requestedAgain.fromCache)
        assertEquals(2, calls)
    }

    @Test
    fun rejectsMismatchedOrUnusableOmmAndInvalidIds() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-24T09:00:00Z"))
        var calls = 0
        val repository = OrbitLookupRepository(temp.newFolder(), OrbitLookupFetcher {
            calls++
            OrbitLookupHttpResponse(200, response("25544", "ISS"))
        }, clock)

        val invalidId = repository.lookupNoradId("27386/other") as OrbitLookupResult.Unavailable
        assertTrue(invalidId.message.contains("NORAD ID"))
        assertEquals(0, calls)

        val mismatch = repository.lookupNoradId("27386") as OrbitLookupResult.Unavailable
        assertTrue(mismatch.message.contains("NORAD 25544"))
        assertEquals(1, calls)
        assertTrue(repository.lookupNoradId("27386").fromCache)
        assertEquals(1, calls)
        assertTrue(repository.cachedRecords().isEmpty())
    }

    @Test
    fun preservesLastGoodOrbitWhenLaterRefreshFails() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-24T09:00:00Z"))
        var calls = 0
        val directory = temp.newFolder()
        val repository = OrbitLookupRepository(directory, OrbitLookupFetcher {
            calls++
            if (calls == 1) OrbitLookupHttpResponse(200, response("27386", "ENVISAT"))
            else OrbitLookupHttpResponse(503, ByteArray(0))
        }, clock)

        val first = repository.lookupNoradId("27386") as OrbitLookupResult.Found
        clock.time = clock.time.plusSeconds(2 * 60 * 60)
        val retained = repository.lookupNoradId("27386") as OrbitLookupResult.Found
        assertEquals(first.fetchedAt, retained.fetchedAt)
        assertTrue(retained.warning.orEmpty().contains("HTTP 503"))
        assertEquals(2, calls)
        val cached = repository.cachedRecords().single()
        assertTrue(cached.warning.orEmpty().contains("HTTP 503"))
    }

    @Test
    fun keepsNewerCachedOrbitWhenARefreshReturnsOlderElements() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-09-24T09:00:00Z"))
        var calls = 0
        val repository = OrbitLookupRepository(temp.newFolder(), OrbitLookupFetcher {
            calls++
            val current = response("27386", "NEW ELEMENTS")
            if (calls == 1) OrbitLookupHttpResponse(200, current)
            else OrbitLookupHttpResponse(200, current.toString(Charsets.UTF_8)
                .replace("NEW ELEMENTS", "OLD ELEMENTS")
                .replace("2026-09-23T20:10:17.645664", "2026-09-20T20:10:17.645664")
                .toByteArray())
        }, clock)

        val first = repository.lookupNoradId("27386") as OrbitLookupResult.Found
        clock.time = clock.time.plusSeconds(2 * 60 * 60)
        val refreshed = repository.lookupNoradId("27386") as OrbitLookupResult.Found
        assertEquals(2, calls)
        assertEquals(first.fetchedAt, refreshed.fetchedAt)
        assertEquals("NEW ELEMENTS", refreshed.satellite.name)
        assertTrue(refreshed.warning.orEmpty().contains("older elements"))
        assertEquals("NEW ELEMENTS", repository.cachedRecords().single().satellite.name)
    }

    private fun response(id: String, name: String): ByteArray = """[{"OBJECT_NAME":"$name","OBJECT_ID":"2002-009A","EPOCH":"2026-09-23T20:10:17.645664","MEAN_MOTION":14.3908367,"ECCENTRICITY":0.00012376,"INCLINATION":98.3936,"RA_OF_ASC_NODE":216.0015,"ARG_OF_PERICENTER":90.5489,"MEAN_ANOMALY":323.9034,"EPHEMERIS_TYPE":0,"CLASSIFICATION_TYPE":"U","NORAD_CAT_ID":$id,"ELEMENT_SET_NO":999,"REV_AT_EPOCH":28767,"BSTAR":2.8865136e-5,"MEAN_MOTION_DOT":4.6e-7,"MEAN_MOTION_DDOT":0}]""".toByteArray()

    private class MutableClock(var time: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = time
    }
}
