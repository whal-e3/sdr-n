package org.satelliteeavesdropper.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.SatelliteOrbit
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONObject

class ReceptionSelectionTest {
    private val manifest = CatalogParser.parse(File("src/main/assets/sample_catalog.json").readText())
    private val satellite = manifest.satellites.first { it.noradId == "25544" }
    private val transmitter = satellite.transmitters.first { it.decoderId == "AX25_AFSK1200" }
    private val observer = ObserverLocation(37.2411, 127.1776)
    private val elements = requireNotNull(satellite.orbitElements())
    private val duringPass = SatelliteOrbit(elements).predictPasses(
        observer, elements.epoch, elements.epoch.plus(Duration.ofHours(24)),
    ).first().tca
    private val signed = CatalogLoadResult(manifest.copy(sourceUpdatedAt = duringPass), CatalogSource.CACHED)

    @Test fun acceptsCurrentSignedSelectionDuringPass() {
        val allowed = verifyReceptionSelection(signed, satellite, transmitter, observer, duringPass)
        assertEquals(satellite.noradId, allowed.satellite.noradId)
        assertEquals(transmitter.id, allowed.transmitter.id)
    }

    @Test fun rejectsDemoAndExpiredCatalogEvenWithVisibleTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            verifyReceptionSelection(signed.copy(source = CatalogSource.DEMO), satellite, transmitter, observer, duringPass)
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyReceptionSelection(signed.copy(manifest = manifest.copy(sourceUpdatedAt = duringPass.minus(Duration.ofHours(73)))),
                satellite, transmitter, observer, duringPass)
        }
    }

    @Test fun rejectsSourceReportedDecayEvenWithFreshElementsAndSignedCatalog() {
        val changed = satellite.copy(omm = JSONObject(satellite.omm.toString()).put("DECAY_DATE",
            LocalDate.ofInstant(duringPass, ZoneOffset.UTC).toString()))
        val current = signed.copy(manifest = signed.manifest.copy(satellites = listOf(changed)))
        val error = assertThrows(IllegalArgumentException::class.java) {
            verifyReceptionSelection(current, satellite, transmitter, observer, duringPass)
        }
        org.junit.Assert.assertEquals(
            "Reception is unavailable from the source-reported decay date; these elements are historical.",
            error.message,
        )
    }

    @Test fun rejectsDownlinkChangedAfterTargetWasShown() {
        val changed = transmitter.copy(frequencyHz = transmitter.frequencyHz + 5_000)
        val updatedSatellite = satellite.copy(transmitters = satellite.transmitters.map {
            if (it.id == transmitter.id) changed else it
        })
        val updated = signed.copy(manifest = signed.manifest.copy(satellites = listOf(updatedSatellite)))
        assertThrows(IllegalArgumentException::class.java) {
            verifyReceptionSelection(updated, satellite, transmitter, observer, duringPass)
        }
    }
}
