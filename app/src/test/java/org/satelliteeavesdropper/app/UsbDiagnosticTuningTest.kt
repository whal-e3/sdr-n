package org.satelliteeavesdropper.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.satelliteeavesdropper.app.data.CatalogLoadResult
import org.satelliteeavesdropper.app.data.CatalogParser
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.app.data.SatelliteRecord
import java.io.File
import java.time.Duration
import java.time.Instant

class UsbDiagnosticTuningTest {
    private val now = Instant.parse("2026-09-26T00:00:00Z")
    private val manifest = CatalogParser.parse(File("src/main/assets/sample_catalog.json").readText())
    private val fixture = manifest.satellites.first { it.noradId == "25544" }
    private val packet = fixture.transmitters.first { it.decoderId == "AX25_AFSK1200" }
    private val iss = fixture.copy(
        omm = JSONObject(fixture.omm.toString()).put("EPOCH", now.toString()),
        transmitters = listOf(packet),
    )

    @Test fun prefersCurrentIssPacketDownlinkOverAnEarlierValidFallback() {
        val fallback = iss.copy(noradId = "12345", transmitters = listOf(packet.copy(id = "fallback")))
        val tuning = selectUsbDiagnosticTuning(catalog(fallback, iss), now)
        assertEquals("25544", tuning?.satellite?.noradId)
        assertEquals(packet, tuning?.transmitter)
    }

    @Test fun preservesCatalogOrderWhenPreferredDownlinkIsAbsent() {
        val first = iss.copy(noradId = "12345", transmitters = listOf(packet.copy(id = "first")))
        val second = iss.copy(noradId = "23456", transmitters = listOf(packet.copy(id = "second")))
        assertEquals("first", selectUsbDiagnosticTuning(catalog(first, second), now)?.transmitter?.id)
    }

    @Test fun excludesHistoricalPreferredSatelliteAtItsUtcCutoffAndUsesFallback() {
        val historical = iss.copy(omm = JSONObject(iss.omm.toString()).put("DECAY_DATE", "2026-09-26"))
        val fallback = iss.copy(noradId = "12345")
        assertEquals("12345", selectUsbDiagnosticTuning(catalog(historical, fallback), now)?.satellite?.noradId)
        assertNull(selectUsbDiagnosticTuning(catalog(historical), now))
    }

    @Test fun permitsSourceReportedFutureDecayDateOnlyBeforeItsUtcCutoff() {
        val bounded = iss.copy(omm = JSONObject(iss.omm.toString()).put("DECAY_DATE", "2026-09-27"))
        val cutoff = now.plus(Duration.ofDays(1))
        assertNotNull(selectUsbDiagnosticTuning(catalog(bounded), cutoff.minusSeconds(1)))
        assertNull(selectUsbDiagnosticTuning(catalog(bounded), cutoff))
    }

    @Test fun rejectsEveryUnsupportedReceiverConfigurationInsteadOfTuningIt() {
        val invalid = listOf(
            packet.copy(policy = "restricted"),
            packet.copy(status = "inactive"),
            packet.copy(frequencyHz = 0),
            packet.copy(captureRateSps = null),
            packet.copy(captureRateSps = 960_000),
            packet.copy(decoderId = null),
            packet.copy(decoderId = "METEOR_LRPT_80K"),
        )
        invalid.forEach { transmitter ->
            assertNull("Unexpected diagnostic tuning for $transmitter",
                selectUsbDiagnosticTuning(catalog(iss.copy(transmitters = listOf(transmitter))), now))
        }
        val fallback = iss.copy(noradId = "12345", transmitters = listOf(packet.copy(decoderId = "AUDIO_NFM")))
        assertEquals("12345", selectUsbDiagnosticTuning(catalog(
            iss.copy(transmitters = listOf(packet.copy(decoderId = "METEOR_LRPT_80K"))), fallback,
        ), now)?.satellite?.noradId)
    }

    @Test fun rejectsMissingStaleAndFutureOrbitEpochs() {
        val invalidOrbits = listOf(
            JSONObject(iss.omm.toString()).apply { remove("EPOCH") },
            JSONObject(iss.omm.toString()).put("EPOCH", now.minus(Duration.ofHours(73)).toString()),
            JSONObject(iss.omm.toString()).put("EPOCH", now.plusSeconds(1).toString()),
        )
        invalidOrbits.forEach { assertNull(selectUsbDiagnosticTuning(catalog(iss.copy(omm = it)), now)) }
        val boundary = iss.copy(omm = JSONObject(iss.omm.toString())
            .put("EPOCH", now.minus(Duration.ofHours(72)).toString()))
        assertNotNull(selectUsbDiagnosticTuning(catalog(boundary), now))
    }

    @Test fun requiresCurrentSignedCatalogForTheDiagnosticEvenWithoutAnObserver() {
        val current = catalog(iss)
        assertNotNull(selectUsbDiagnosticTuning(current, now))
        assertNotNull(selectUsbDiagnosticTuning(current.copy(source = CatalogSource.LIVE), now))
        assertNull(selectUsbDiagnosticTuning(null, now))
        assertNull(selectUsbDiagnosticTuning(current.copy(source = CatalogSource.DEMO), now))
        assertNull(selectUsbDiagnosticTuning(current.copy(manifest = current.manifest.copy(
            sourceUpdatedAt = now.minus(Duration.ofHours(73)),
        )), now))
        assertNull(selectUsbDiagnosticTuning(current.copy(manifest = current.manifest.copy(
            sourceUpdatedAt = now.plusSeconds(1),
        )), now))
    }

    private fun catalog(vararg satellites: SatelliteRecord) = CatalogLoadResult(
        manifest.copy(sourceUpdatedAt = now, satellites = satellites.toList()), CatalogSource.CACHED,
    )
}
