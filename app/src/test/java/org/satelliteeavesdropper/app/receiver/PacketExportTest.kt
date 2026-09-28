package org.satelliteeavesdropper.app.receiver

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PacketExportTest {
    private val dequeuedAt = Instant.parse("2026-09-28T01:02:03.456Z")
    private val exportedAt = Instant.parse("2026-09-28T02:03:04Z")

    @Test fun roundTripsFullBinaryFrameWithoutLossOrFabricatedFcs() {
        val frame = packetTestFrame(ByteArray(768) { it.toByte() })
        val packet = DecodedPacket(1, dequeuedAt, frame, packetTestMetadata())
        val document = JSONObject(PacketExport.toJson(listOf(packet), exportedAt))
        val json = document.getJSONArray("packets").getJSONObject(0)
        val jsonFrame = json.getJSONObject("frame")
        val restored = jsonFrame.getString("bytes").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertArrayEquals(frame, restored)
        assertEquals(frame.size, jsonFrame.getInt("byteLength"))
        assertEquals("hex", jsonFrame.getString("encoding"))
        assertFalse(jsonFrame.getBoolean("includesFcs"))
        assertTrue(json.getJSONObject("verification").getBoolean("crcValidatedByNativeDecoder"))
        assertTrue(json.getJSONObject("verification").getBoolean("ax25AddressesValidatedByNativeDecoder"))
    }

    @Test fun labelsSchemaTimingAndSelectedTargetEvidenceAccurately() {
        val packet = DecodedPacket(1, dequeuedAt, packetTestFrame(), packetTestMetadata())
        val document = JSONObject(PacketExport.toJson(listOf(packet), exportedAt))
        val json = document.getJSONArray("packets").getJSONObject(0)

        assertEquals("orbitscope.decoded-packets", document.getString("format"))
        assertEquals(1, document.getInt("schemaVersion"))
        assertEquals("decoded_ax25_frames", document.getString("dataType"))
        assertEquals(exportedAt.toString(), document.getString("exportedAtUtc"))
        assertEquals(dequeuedAt.toString(), json.getString("dequeuedAtUtc"))
        assertEquals("android_native_queue_dequeue_time_not_exact_rf_reception_time", document.getString("timestampBasis"))
        assertEquals("selected_tracking_target_not_verified_transmitter_identity", json.getJSONObject("trackingTarget").getString("identityBasis"))
        assertEquals("session-a:1", json.getString("packetId"))
        assertEquals("session-a", json.getString("sessionId"))
        assertEquals("AX25_AFSK1200", json.getString("decoderId"))
    }

    @Test fun keepsAllReceiverObserverAndLookMetadataFromEachPacket() {
        val metadata = packetTestMetadata()
        val packet = DecodedPacket(1, dequeuedAt, packetTestFrame(), metadata)
        val json = JSONObject(PacketExport.toJson(listOf(packet), exportedAt))
            .getJSONArray("packets").getJSONObject(0)
        val receiver = json.getJSONObject("receiver")
        val observer = json.getJSONObject("observer")
        val look = json.getJSONObject("look")

        assertEquals("RTL-SDR", receiver.getString("device"))
        assertEquals(145_825_000L, receiver.getLong("rfCenterHz"))
        assertEquals(1_024_000, receiver.getInt("sampleRateSps"))
        assertEquals(1000.0, receiver.getDouble("predictedDopplerHz"), 0.0)
        assertTrue(receiver.getBoolean("afcTracking"))
        assertEquals(120.0, receiver.getDouble("afcAppliedHz"), 0.0)
        assertEquals(-15.0, receiver.getDouble("afcLastResidualHz"), 0.0)
        assertEquals(37.2411, observer.getDouble("latitudeDegrees"), 0.0)
        assertEquals(127.1776, observer.getDouble("longitudeDegrees"), 0.0)
        assertEquals(100.0, observer.getDouble("altitudeMeters"), 0.0)
        assertEquals("AUTO_FOLLOWING", observer.getString("feedState"))
        assertEquals(3L, observer.getLong("fixAgeSeconds"))
        assertEquals(45.0, look.getDouble("azimuthDegrees"), 0.0)
        assertEquals(30.0, look.getDouble("elevationDegrees"), 0.0)
        assertEquals("25544", json.getJSONObject("trackingTarget").getString("noradId"))
        assertEquals("ISS (ZARYA)", json.getJSONObject("trackingTarget").getString("name"))
    }

    @Test fun representsUnknownFixAgeAndLookAsNull() {
        val metadata = packetTestMetadata().copy(
            observerFeedState = ObserverFeedState.FIXED,
            observerFixAgeSeconds = null,
            lookAzimuthDegrees = null,
            lookElevationDegrees = null,
        )
        val packet = DecodedPacket(1, dequeuedAt, packetTestFrame(), metadata)
        val json = JSONObject(PacketExport.toJson(listOf(packet), exportedAt))
            .getJSONArray("packets").getJSONObject(0)

        assertTrue(json.getJSONObject("observer").isNull("fixAgeSeconds"))
        assertTrue(json.getJSONObject("look").isNull("azimuthDegrees"))
        assertTrue(json.getJSONObject("look").isNull("elevationDegrees"))
    }

    @Test fun utf8BatchSeparatesSessionsAndRetainsUnicodeNames() {
        val first = DecodedPacket(1, dequeuedAt, packetTestFrame(), packetTestMetadata())
        val second = DecodedPacket(1, dequeuedAt.plusSeconds(1), packetTestFrame(),
            packetTestMetadata("session-b").copy(targetName = "위성 🛰"))
        val bytes = PacketExport.toUtf8(listOf(first, second), exportedAt)
        val document = JSONObject(bytes.toString(Charsets.UTF_8))

        assertEquals(2, document.getInt("packetCount"))
        assertEquals(2, document.getJSONArray("packets").length())
        assertEquals("session-a:1", document.getJSONArray("packets").getJSONObject(0).getString("packetId"))
        assertEquals("session-b:1", document.getJSONArray("packets").getJSONObject(1).getString("packetId"))
        assertEquals("위성 🛰", document.getJSONArray("packets").getJSONObject(1)
            .getJSONObject("trackingTarget").getString("name"))
        assertEquals("application/json", PacketExport.MIME_TYPE)
    }
}
