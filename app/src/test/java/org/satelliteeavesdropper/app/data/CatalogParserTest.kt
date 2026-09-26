package org.satelliteeavesdropper.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class CatalogParserTest {
    private fun sampleJson(): String = File("src/main/assets/sample_catalog.json").readText()

    @Test
    fun parsesBundledCatalogAndPublicProfiles() {
        val catalog = CatalogParser.parse(sampleJson())

        assertEquals(1L, catalog.sequence)
        assertEquals(Instant.parse("2026-09-23T11:35:00Z"), catalog.generatedAt)
        assertEquals(3, catalog.satellites.size)
        assertTrue(catalog.attribution.any { it.contains("SatNOGS") })

        val iss = catalog.satellites.first { it.noradId == "25544" }
        assertEquals("ISS", iss.name)
        assertEquals(listOf("ISS (ZARYA)"), iss.aliases)
        assertEquals(2, iss.transmitters.size)
        val packet = iss.transmitters.first { it.id == "ZJxCeQmih9zDfYNVrB4wRN" }
        assertEquals(145_825_000L, packet.frequencyHz)
        assertEquals("AX25_AFSK1200", packet.decoderId)
        assertTrue(packet.mayReceive)
        val voice = iss.transmitters.first { it.decoderId == "AUDIO_NFM" }
        assertEquals(145_800_000L, voice.frequencyHz)
        assertEquals("amateur · VHF satellite antenna for 145.800 MHz", voice.policyAndAntennaLabel)
        assertTrue(voice.mayReceive)

        val meteor = catalog.satellites.first { it.noradId == "59051" }
        assertEquals("METEOR_LRPT_80K", meteor.transmitters.single().decoderId)
        assertTrue(meteor.transmitters.single().mayReceive)
    }

    @Test
    fun convertsOmmToOrbitElements() {
        val iss = CatalogParser.parse(sampleJson()).satellites.first { it.noradId == "25544" }
        val elements = iss.orbitElements()

        assertNotNull(elements)
        elements!!
        assertEquals("25544", elements.noradId)
        assertEquals(Instant.parse("2026-09-22T20:26:37.026816Z"), elements.epoch)
        assertEquals(15.49234213, elements.meanMotionRevolutionsPerDay, 1e-10)
        assertEquals(51.6316, elements.inclinationDegrees, 1e-6)
        assertEquals(0.00014639046, elements.bStar, 1e-12)
        assertEquals("1998-067A", elements.objectId)
    }

    @Test
    fun handlesNullOptionalFieldsAndDefaultsUnknownPolicyToRestricted() {
        val root = JSONObject(sampleJson())
        val tx = root.getJSONArray("satellites").getJSONObject(0)
            .getJSONArray("transmitters").getJSONObject(0)
        tx.put("bandwidthHz", JSONObject.NULL)
        tx.remove("baud")
        tx.put("verifiedAt", JSONObject.NULL)
        tx.put("decoderId", JSONObject.NULL)
        tx.put("captureRateSps", JSONObject.NULL)
        tx.put("antenna", "external 2 m antenna")
        tx.remove("evidenceUrls")
        tx.remove("policy")

        val parsed = CatalogParser.parse(root.toString()).satellites.first().transmitters
            .first { it.id == "PjfcFc4PZ8M8n3thuyA6x9" }
        assertNull(parsed.bandwidthHz)
        assertNull(parsed.baud)
        assertNull(parsed.verifiedAt)
        assertNull(parsed.decoderId)
        assertNull(parsed.captureRateSps)
        assertEquals("external 2 m antenna", parsed.antenna)
        assertEquals("restricted · external 2 m antenna", parsed.policyAndAntennaLabel)
        assertTrue(parsed.evidenceUrls.isEmpty())
        assertEquals("restricted", parsed.policy)
        assertFalse(parsed.mayReceive)
    }

    @Test
    fun truncatesFractionalNumericBaudFromSatnogs() {
        val root = JSONObject(sampleJson())
        val tx = root.getJSONArray("satellites").getJSONObject(0)
            .getJSONArray("transmitters").getJSONObject(0)
        tx.put("baud", 977.52)

        val parsed = CatalogParser.parse(root.toString()).satellites.first().transmitters.first()

        assertEquals(977, parsed.baud)
    }

    @Test
    fun omitsUnknownAntennaFromTransmitterLabel() {
        val root = JSONObject(sampleJson())
        val tx = root.getJSONArray("satellites").getJSONObject(0)
            .getJSONArray("transmitters").getJSONObject(0)

        tx.put("antenna", JSONObject.NULL)
        val nullAntenna = CatalogParser.parse(root.toString()).satellites.first().transmitters.first()
        assertNull(nullAntenna.antenna)
        assertEquals("amateur", nullAntenna.policyAndAntennaLabel)

        tx.remove("antenna")
        val missingAntenna = CatalogParser.parse(root.toString()).satellites.first().transmitters.first()
        assertNull(missingAntenna.antenna)
        assertEquals("amateur", missingAntenna.policyAndAntennaLabel)

        tx.put("antenna", JSONObject().put("band", "2m"))
        val missingDescription = CatalogParser.parse(root.toString()).satellites.first().transmitters.first()
        assertNull(missingDescription.antenna)
        assertEquals("amateur", missingDescription.policyAndAntennaLabel)
    }

    @Test
    fun rejectsUnknownSchemaAndReturnsNullForUnusableOmm() {
        val root = JSONObject(sampleJson())
        root.put("schemaVersion", 2)
        try {
            CatalogParser.parse(root.toString())
            throw AssertionError("Unknown schema must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("schema"))
        }

        root.put("schemaVersion", 1)
        root.getJSONArray("satellites").getJSONObject(0).getJSONObject("omm")
            .remove("MEAN_MOTION")
        val iss = CatalogParser.parse(root.toString()).satellites.first()
        assertNull(iss.orbitElements())
    }

    @Test
    fun rejectsManifestMissingRequiredSatelliteArray() {
        val root = JSONObject(sampleJson())
        root.remove("satellites")

        try {
            CatalogParser.parse(root.toString())
            throw AssertionError("Missing satellites array must be rejected")
        } catch (expected: org.json.JSONException) {
            assertTrue(expected.message.orEmpty().contains("satellites"))
        }
    }
}
