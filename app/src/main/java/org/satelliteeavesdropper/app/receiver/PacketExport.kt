package org.satelliteeavesdropper.app.receiver

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Portable decoded packets and the metadata captured with each frame, encoded as UTF-8 JSON. */
object PacketExport {
    const val MIME_TYPE = "application/json"
    const val SCHEMA_VERSION = 1

    fun toJson(packets: List<DecodedPacket>, exportedAtUtc: Instant = Instant.now()): String =
        JSONObject()
            .put("format", "orbitscope.decoded-packets")
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAtUtc", exportedAtUtc.toString())
            .put("dataType", "decoded_ax25_frames")
            .put("timestampBasis", "android_native_queue_dequeue_time_not_exact_rf_reception_time")
            .put("notes", "Decoded AX.25 frame bodies, not raw IQ recordings. The selected tracking target is not proof of the transmitting satellite. Observer coordinates are included.")
            .put("packetCount", packets.size)
            .put("packets", JSONArray().apply { packets.forEach { put(packetJson(it)) } })
            .toString(2)

    fun toUtf8(packets: List<DecodedPacket>, exportedAtUtc: Instant = Instant.now()): ByteArray =
        toJson(packets, exportedAtUtc).toByteArray(Charsets.UTF_8)

    private fun packetJson(packet: DecodedPacket): JSONObject {
        val metadata = packet.metadata
        return JSONObject()
            .put("packetId", packet.packetId)
            .put("sequence", packet.sequence)
            .put("sessionId", metadata.sessionId)
            .put("dequeuedAtUtc", packet.dequeuedAtUtc.toString())
            .put("decoderId", metadata.decoderId)
            .put("displayText", packet.displayText)
            .put("frame", JSONObject()
                .put("encoding", "hex")
                .put("bytes", packet.frameHex)
                .put("byteLength", packet.byteLength)
                .put("includesFcs", false))
            .put("verification", JSONObject()
                .put("crcValidatedByNativeDecoder", true)
                .put("ax25AddressesValidatedByNativeDecoder", true))
            .put("trackingTarget", JSONObject()
                .put("noradId", metadata.targetNoradId)
                .put("name", metadata.targetName)
                .put("identityBasis", "selected_tracking_target_not_verified_transmitter_identity"))
            .put("receiver", JSONObject()
                .put("device", metadata.device)
                .put("rfCenterHz", metadata.rfCenterHz)
                .put("sampleRateSps", metadata.sampleRateSps)
                .put("predictedDopplerHz", metadata.predictedDopplerHz)
                .put("afcTracking", metadata.afcTracking)
                .put("afcAppliedHz", metadata.afcAppliedHz)
                .put("afcLastResidualHz", metadata.afcLastResidualHz))
            .put("look", JSONObject()
                .put("azimuthDegrees", metadata.lookAzimuthDegrees ?: JSONObject.NULL)
                .put("elevationDegrees", metadata.lookElevationDegrees ?: JSONObject.NULL))
            .put("observer", JSONObject()
                .put("latitudeDegrees", metadata.observerLatitudeDegrees)
                .put("longitudeDegrees", metadata.observerLongitudeDegrees)
                .put("altitudeMeters", metadata.observerAltitudeMeters)
                .put("feedState", metadata.observerFeedState.name)
                .put("fixAgeSeconds", metadata.observerFixAgeSeconds ?: JSONObject.NULL))
    }
}
