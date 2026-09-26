package org.satelliteeavesdropper.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TrackingCatalogTest {
    @Test fun sourceReportedReentryCanUpdateTheFinalElementsWithoutANewerEpoch() {
        val original = record("5", "LATEST FIVE", "2026-09-22T00:00:00Z", true)
        val historical = original.copy(omm = JSONObject(original.omm.toString()).put("DECAY_DATE", "2026-09-23"))
        val merged = mergeTrackingCatalog(manifest(listOf(original)), listOf(historical))
        assertTrue(merged.satellites.single().isKnownDecayedAt(Instant.parse("2026-09-24T00:00:00Z")))
        assertTrue(merged.satellites.single().transmitters.isEmpty())
        assertEquals(null, original.recordedDecayDate)
        assertEquals(1, original.transmitters.size)
    }

    @Test fun newerSupplementalElementsReplaceSignedOrbitForTrackingOnly() {
        val signed = manifest(listOf(
            record("5", "SIGNED FIVE", "2026-09-20T00:00:00Z", true),
            record("7", "SIGNED SEVEN", "2026-09-22T00:00:00Z", true),
            record("8", "SIGNED EIGHT", "2026-09-22T00:00:00Z", true),
        ))
        val merged = mergeTrackingCatalog(signed, listOf(
            record("5", "NEW TLE FIVE", "2026-09-23T00:00:00Z", true),
            record("6", "NEW TLE SIX", "2026-09-23T00:00:00Z", true),
            record("7", "OLD TLE SEVEN", "2026-09-21T00:00:00Z", true),
            record("8", "SAME EPOCH EIGHT", "2026-09-22T00:00:00Z", true),
        ))

        assertEquals(listOf("5", "7", "8", "6"), merged.satellites.map { it.noradId })
        assertEquals("NEW TLE FIVE", merged.satellites[0].name)
        assertTrue(merged.satellites[0].transmitters.isEmpty())
        assertEquals("NEW TLE SIX", merged.satellites[3].name)
        assertTrue(merged.satellites[3].transmitters.isEmpty())
        assertEquals("SIGNED SEVEN", merged.satellites[1].name)
        assertEquals("SIGNED EIGHT", merged.satellites[2].name)
        assertEquals(1, merged.satellites[1].transmitters.size)
        assertEquals(1, merged.satellites[2].transmitters.size)
        assertEquals("SIGNED FIVE", signed.satellites[0].name)
        assertEquals(1, signed.satellites[0].transmitters.size)
    }

    private fun manifest(records: List<SatelliteRecord>) = CatalogManifest(
        4, Instant.parse("2026-09-23T12:00:00Z"), Instant.parse("2026-09-23T12:00:00Z"),
        records, emptyList(),
    )

    private fun record(id: String, name: String, epoch: String, withTransmitter: Boolean): SatelliteRecord {
        val omm = JSONObject()
            .put("NORAD_CAT_ID", id)
            .put("OBJECT_NAME", name)
            .put("EPOCH", epoch)
            .put("MEAN_MOTION", 14.2)
            .put("ECCENTRICITY", 0.001)
            .put("INCLINATION", 98.0)
            .put("RA_OF_ASC_NODE", 100.0)
            .put("ARG_OF_PERICENTER", 200.0)
            .put("MEAN_ANOMALY", 50.0)
        val transmitters = if (withTransmitter) listOf(TransmitterRecord(
            "tx-$id", 145_800_000, 12_500, "NFM", null, "active", null,
            "AUDIO_NFM", 1_024_000, null, "amateur", emptyList(),
        )) else emptyList()
        return SatelliteRecord(id, name, emptyList(), omm, transmitters)
    }
}
