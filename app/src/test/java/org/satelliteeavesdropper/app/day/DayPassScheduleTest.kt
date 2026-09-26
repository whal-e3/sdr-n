package org.satelliteeavesdropper.app.day

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.satelliteeavesdropper.app.data.CatalogManifest
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.TransmitterRecord
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DayPassScheduleTest {
    private val day = LocalDate.of(2026, 9, 24)
    private val zone = ZoneId.of("Asia/Seoul")
    private val observer = ObserverLocation(37.24, 127.17)

    @Test fun everyUsableOrbitIsTrackableEvenWithoutTransmitter() {
        val records = listOf(record(1), record(2), record(3, valid = false))
        val trackable = trackableSatellites(manifest(records))
        assertEquals(listOf("1", "2"), trackable.map { it.record.noradId })
        assertTrue(trackable.all { !it.hasPublicDownlink })
        assertEquals(listOf(0, 1), trackable.map { it.index })
    }

    @Test fun canceledTrackableConstructionStopsBeforeRemainingRecordsAreRead() {
        val records = (1..100).map(::record)
        var checks = 0
        try {
            trackableSatellites(manifest(records)) {
                if (++checks == 20) throw CancellationException("observer or catalog changed")
            }
            fail("Canceled construction should stop")
        } catch (_: CancellationException) {
            assertEquals(20, checks)
        }
    }

    @Test fun canceledPredictionStopsWithinBatchAndDoesNotPublishObsoletePasses() = runBlocking {
        val owner = Job(currentCoroutineContext()[Job])
        val updates = mutableListOf<DayPassSchedule>()
        var predictions = 0
        try {
            kotlinx.coroutines.withContext(owner) {
                calculateDaySchedule(manifest((1..40).map(::record)), observer, day, zone,
                    onUpdate = updates::add,
                    predictor = PassPredictor { _, _, start, _ ->
                        predictions++
                        owner.cancel()
                        listOf(passAt(start))
                    })
            }
            fail("Canceled calculation should stop")
        } catch (_: CancellationException) {
            assertEquals(1, predictions)
            assertEquals(listOf(0), updates.map { it.processedCount })
            assertTrue(updates.single().passes.isEmpty())
        }
    }

    @Test fun configuredRadioPassesAppearFirstAndAllOrbitsRemainBrowsable() {
        val validRadio = TransmitterRecord("valid", 145_825_000, 12_500, "NFM", 1200,
            "active", null, "AX25_AFSK1200", 1_024_000, null, "amateur", emptyList())
        val inactiveRadio = validRadio.copy(id = "inactive", status = "inactive")
        val trackable = trackableSatellites(manifest(listOf(
            record(1), record(2, transmitters = listOf(inactiveRadio)),
            record(3, transmitters = listOf(validRadio)),
        )))
        assertEquals(listOf("3", "1", "2"), trackable.map { it.record.noradId })
        val passes = trackable.map { ScheduledPass(it, passAt(day.atStartOfDay(zone).toInstant())) }
        assertEquals(listOf("3"), passesForRadioView(passes, true).map { it.satellite.record.noradId })
        assertEquals(listOf("3", "1", "2"), passesForRadioView(passes, false).map { it.satellite.record.noradId })
    }

    @Test fun passCalculationUsesLocalDayAndSortsAcrossSatellites() = runBlocking {
        val records = listOf(record(2), record(1), record(3, valid = false))
        val observedWindows = mutableListOf<Pair<Instant, Instant>>()
        val updates = mutableListOf<DayPassSchedule>()
        val schedule = calculateDaySchedule(manifest(records), observer, day, zone,
            onUpdate = updates::add,
            predictor = PassPredictor { elements, _, start, end ->
                observedWindows += start to end
                listOf(passAt(start.plusSeconds(if (elements.noradId == "1") 60 else 120)))
            })
        assertEquals(Instant.parse("2026-09-23T15:00:00Z"), schedule.intervalStart)
        assertEquals(Instant.parse("2026-09-24T15:00:00Z"), schedule.intervalEnd)
        assertTrue(observedWindows.all { it.first == schedule.intervalStart && it.second == schedule.intervalEnd })
        assertEquals(listOf("1", "2"), schedule.passes.map { it.satellite.record.noradId })
        assertEquals(1, schedule.orbitUnavailableCount)
        assertTrue(schedule.isComplete)
        assertEquals(0, updates.first().processedCount)
        assertEquals(2, updates.last().processedCount)
    }

    @Test fun staleOrbitsRemainBrowsableAndPredictableWhileKnownDecayedRecordsStayHistorical() = runBlocking {
        val stale = record(2).copy(omm = JSONObject(record(2).omm.toString()).put("EPOCH", "1995-01-01T00:00:00Z"))
        val decayed = record(3).copy(omm = JSONObject(record(3).omm.toString()).put("DECAY_DATE", "2026-09-23"))
        val calls = mutableListOf<String>()
        val result = calculateDaySchedule(manifest(listOf(record(1), stale, decayed)), observer, day, zone,
            onUpdate = {}, predictor = PassPredictor { elements, _, start, _ ->
                calls += elements.noradId
                listOf(passAt(start))
            })
        assertEquals(listOf("1", "2", "3"), result.satellites.map { it.record.noradId })
        assertEquals(listOf("1", "2"), calls)
        assertEquals(listOf("1", "2"), result.passes.map { it.satellite.record.noradId })
        assertTrue(result.isComplete)
        assertTrue(result.failedNoradIds.isEmpty())
    }

    @Test fun priorDayPredictionStopsAtSourceReportedDecayDateWithinLocalDay() = runBlocking {
        val historical = record(1).copy(omm = JSONObject(record(1).omm.toString()).put("DECAY_DATE", "2026-09-24"))
        var observedEnd: Instant? = null
        val result = calculateDaySchedule(manifest(listOf(historical)), observer, day, zone,
            onUpdate = {}, predictor = PassPredictor { _, _, start, end ->
                observedEnd = end
                listOf(passAt(start))
            })
        assertEquals(Instant.parse("2026-09-24T00:00:00Z"), observedEnd)
        assertEquals(1, result.passes.size)
    }

    @Test fun prioritySearchAlsoAvoidsPredictingKnownDecayedObjects() = runBlocking {
        val historical = record(1).copy(omm = JSONObject(record(1).omm.toString()).put("DECAY_DATE", "2026-09-23"))
        var predictions = 0
        var completed = emptySet<String>()
        calculatePriorityPasses(trackableSatellites(manifest(listOf(historical))), observer, day, zone,
            onUpdate = { passes, done, failed ->
                assertTrue(passes.isEmpty())
                assertTrue(failed.isEmpty())
                completed = done
            }, predictor = PassPredictor { _, _, start, _ ->
                predictions++
                listOf(passAt(start))
            })
        assertEquals(0, predictions)
        assertEquals(setOf("1"), completed)
    }

    @Test fun progressCanResumeAndOneBadPropagationDoesNotHideOtherSatellites() = runBlocking {
        val records = (1..40).map(::record)
        val updates = mutableListOf<DayPassSchedule>()
        val first = calculateDaySchedule(manifest(records), observer, day, zone,
            onUpdate = updates::add,
            predictor = PassPredictor { elements, _, start, _ ->
                if (elements.noradId == "7") error("bad orbit")
                listOf(passAt(start.plusSeconds((41 - elements.noradId.toInt()).toLong() * 60)))
            })
        assertEquals(listOf(0, 32, 40), updates.map { it.processedCount })
        assertEquals(39, first.passes.size)
        assertEquals(setOf("7"), first.failedNoradIds)
        assertEquals(first.passes.map { it.pass.aos }.sorted(), first.passes.map { it.pass.aos })

        var resumedCalls = 0
        val resumed = calculateDaySchedule(manifest(records), observer, day, zone,
            previous = updates[1], onUpdate = {},
            predictor = PassPredictor { _, _, start, _ ->
                resumedCalls++
                listOf(passAt(start.plusSeconds(60)))
            })
        assertEquals(8, resumedCalls)
        assertEquals(39, resumed.passes.size)
        assertFalse(resumed.failedNoradIds.isEmpty())
        assertTrue(resumed.isComplete)
    }

    @Test fun broadScheduleMergesProgressWithoutResortingEarlierPasses() = runBlocking {
        val records = (1..520).map(::record)
        val updates = mutableListOf<DayPassSchedule>()
        val predictor = PassPredictor { elements, _, start, _ ->
            // Later catalog records have earlier passes, so every update inserts
            // ahead of some passes that the user has already seen.
            listOf(passAt(start.plusSeconds((521 - elements.noradId.toInt()) * 60L)))
        }
        val schedule = calculateDaySchedule(manifest(records), observer, day, zone,
            onUpdate = updates::add, predictor = predictor)

        assertEquals(listOf(0, 32, 256, 512, 520), updates.map { it.processedCount })
        updates.forEach { update ->
            assertEquals(update.passes.map { it.pass.aos }.sorted(), update.passes.map { it.pass.aos })
            assertEquals(update.processedCount, update.passes.size)
        }
        assertEquals((520 downTo 1).map(Int::toString),
            schedule.passes.map { it.satellite.record.noradId })

        var resumedCalls = 0
        val resumed = calculateDaySchedule(manifest(records), observer, day, zone,
            previous = updates[2], onUpdate = {}, predictor = PassPredictor { elements, site, start, end ->
                resumedCalls++
                predictor.predict(elements, site, start, end)
            })
        assertEquals(264, resumedCalls)
        assertEquals(schedule.passes.map { it.pass.aos }, resumed.passes.map { it.pass.aos })
    }

    @Test fun upcomingIncludesAnActivePassButSkipsCompletedPasses() {
        val satellites = trackableSatellites(manifest((1..3).map(::record)))
        val now = Instant.parse("2026-09-24T10:00:00Z") // 19:00 in Seoul
        val passes = listOf(
            ScheduledPass(satellites[0], passAt(now.minusSeconds(180))),
            ScheduledPass(satellites[1], passAt(now.minusSeconds(60))),
            ScheduledPass(satellites[2], passAt(now.plusSeconds(60))),
        )
        fun ids(view: PassTimeView) = passesForTimeView(passes, view, day, zone, now)
            .map { it.satellite.record.noradId }

        assertEquals(listOf("2", "3"), ids(PassTimeView.UPCOMING))
        assertEquals(listOf("1", "2", "3"), ids(PassTimeView.FULL_DAY))
        assertEquals(listOf("2"), ids(PassTimeView.IN_SIGHT))
        assertTrue(passesForTimeView(passes, PassTimeView.UPCOMING, day, zone,
            now.plusSeconds(180)).isEmpty())
    }

    @Test fun upcomingUsesSelectedLocalDayAroundMidnight() {
        val satellite = trackableSatellites(manifest(listOf(record(1)))).single()
        val futureDay = day.plusDays(1)
        val now = futureDay.atStartOfDay(zone).toInstant().minusSeconds(1)
        val futurePasses = listOf(ScheduledPass(satellite, passAt(now.plusSeconds(2))))

        assertEquals(futurePasses, passesForTimeView(futurePasses,
            PassTimeView.UPCOMING, futureDay, zone, now))
        assertTrue(passesForTimeView(futurePasses,
            PassTimeView.UPCOMING, day, zone, now.plusSeconds(2)).isEmpty())
    }

    @Test fun dayBoundaryClipsAreNotPresentedAsRealRiseAndSetTimes() {
        val start = day.atStartOfDay(zone).toInstant()
        val clipped = SatellitePass(
            aos = start,
            tca = start.plusSeconds(10 * 3_600L),
            los = day.plusDays(1).atStartOfDay(zone).toInstant(),
            maximumElevationDegrees = 45.0,
            aosAzimuthDegrees = 90.0,
            losAzimuthDegrees = 270.0,
            beganBeforeWindow = true,
            endsAfterWindow = true,
        )
        assertEquals("In sight at day start · TCA 10:00 · In sight at day end",
            passWindowLabel(clipped, zone))

        val ordinary = passAt(start.plusSeconds(3_600))
        assertEquals("AOS 01:00 · TCA 01:01 · LOS 01:02", passWindowLabel(ordinary, zone))
    }

    private fun manifest(records: List<SatelliteRecord>): CatalogManifest = CatalogManifest(
        sequence = 1,
        generatedAt = Instant.parse("2026-09-24T00:00:00Z"),
        sourceUpdatedAt = Instant.parse("2026-09-24T00:00:00Z"),
        satellites = records,
        attribution = emptyList(),
    )

    private fun record(id: Int, valid: Boolean = true,
                       transmitters: List<TransmitterRecord> = emptyList()): SatelliteRecord = SatelliteRecord(
        noradId = id.toString(),
        name = "Satellite $id",
        aliases = emptyList(),
        omm = JSONObject().apply {
            put("EPOCH", "2026-09-24T00:00:00Z")
            put("MEAN_MOTION", if (valid) 15.0 else -1.0)
            put("ECCENTRICITY", 0.001)
            put("INCLINATION", 51.6)
            put("RA_OF_ASC_NODE", 10.0)
            put("ARG_OF_PERICENTER", 20.0)
            put("MEAN_ANOMALY", 30.0)
        },
        transmitters = transmitters,
    )

    private fun passAt(aos: Instant) = SatellitePass(
        aos = aos,
        tca = aos.plusSeconds(60),
        los = aos.plusSeconds(120),
        maximumElevationDegrees = 45.0,
        aosAzimuthDegrees = 90.0,
        losAzimuthDegrees = 270.0,
        beganBeforeWindow = false,
        endsAfterWindow = false,
    )
}
