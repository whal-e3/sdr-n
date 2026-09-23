package org.satelliteeavesdropper.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OmmElementsTest {
    @Test fun parsesCelestrakNumbersStringsAndNineDigitIds() {
        val fields = mapOf<String, Any?>(
            "NORAD_CAT_ID" to "123456789",
            "EPOCH" to "2026-09-23T00:12:34.123456",
            "MEAN_MOTION" to 15.5,
            "ECCENTRICITY" to "0.0005",
            "INCLINATION" to "98.5",
            "RA_OF_ASC_NODE" to 120.0,
            "ARG_OF_PERICENTER" to "90.0",
            "MEAN_ANOMALY" to "12.0",
            "BSTAR" to "1.5e-5",
        )
        val omm = OmmElements.fromCelestrakFields(fields)
        assertEquals("123456789", omm.noradId)
        assertEquals("2026-09-23T00:12:34.123456Z", omm.epoch.toString())
        assertEquals(1.5e-5, omm.bStar, 1e-12)
        assertEquals(0.0, omm.meanMotionDot, 0.0)
        assertEquals("123456789", OmmElements.fromCelestrakFields(fields + ("NORAD_CAT_ID" to 123456789.0)).noradId)
    }

    @Test fun rejectsInvalidOrbitFields() {
        assertThrows(IllegalArgumentException::class.java) {
            valladoOmm().copy(eccentricity = 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valladoOmm().copy(noradId = "1000000000")
        }
        val valid = mapOf<String, Any?>(
            "NORAD_CAT_ID" to 5,
            "EPOCH" to "2000-06-27T18:50:19.733568",
            "MEAN_MOTION" to 10.82419157,
            "ECCENTRICITY" to 0.1859667,
            "INCLINATION" to 34.2682,
            "RA_OF_ASC_NODE" to 348.7242,
            "ARG_OF_PERICENTER" to 331.7664,
            "MEAN_ANOMALY" to 19.3264,
        )
        assertThrows(IllegalArgumentException::class.java) {
            OmmElements.fromCelestrakFields(valid + ("MEAN_ELEMENT_THEORY" to "SGP4-XP"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OmmElements.fromCelestrakFields(valid + ("BSTAR" to "unknown"))
        }
    }
}
