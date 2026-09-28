package org.satelliteeavesdropper.app.receiver

import java.time.Instant

const val MAX_RETAINED_DECODED_PACKETS = 20

/** Values captured when Android dequeues a verified frame, rather than at export time. */
data class PacketMetadata(
    val sessionId: String,
    val decoderId: String,
    /** The selected tracking target; a decoded frame alone does not establish its transmitter. */
    val targetNoradId: String,
    val targetName: String,
    val device: String,
    val rfCenterHz: Long,
    val sampleRateSps: Int,
    /** Predicted correction currently applied to the native baseband receiver. */
    val predictedDopplerHz: Double,
    val afcTracking: Boolean,
    val afcAppliedHz: Double,
    val afcLastResidualHz: Double,
    val lookAzimuthDegrees: Double?,
    val lookElevationDegrees: Double?,
    val observerLatitudeDegrees: Double,
    val observerLongitudeDegrees: Double,
    val observerAltitudeMeters: Double,
    val observerFeedState: ObserverFeedState,
    val observerFixAgeSeconds: Long?,
)

/**
 * An immutable copy of the native decoder's CRC/address-validated AX.25 frame body.
 * [dequeuedAtUtc] is Android's queue-read time, not an exact RF reception timestamp.
 * The native decoder removes the two transmitted FCS octets before returning [frame].
 */
class DecodedPacket(
    val sequence: Long,
    val dequeuedAtUtc: Instant,
    frame: ByteArray,
    val metadata: PacketMetadata,
) {
    private val bytes = frame.copyOf()
    val packetId: String = "${metadata.sessionId}:$sequence"
    val displayText: String = Ax25Formatter.format(bytes)
    val byteLength: Int get() = bytes.size
    /** Exact full frame, including binary information bytes; human display may be truncated. */
    val frameHex: String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    /** A defensive copy protects older snapshots and pending exports from later mutation. */
    val rawFrameBytes: ByteArray get() = bytes.copyOf()

    init {
        require(metadata.sessionId.isNotBlank()) { "Packet session must not be blank" }
        require(sequence > 0) { "Packet sequence must be positive" }
    }
}

/** Bounded session history. Older snapshots and selected exports never change on new frames. */
internal class DecodedPacketBuffer(
    private val sessionId: String,
    private val capacity: Int = MAX_RETAINED_DECODED_PACKETS,
) {
    private val packets = ArrayDeque<DecodedPacket>()
    private var nextSequence = 1L
    var omittedPacketCount: Long = 0
        private set

    init {
        require(sessionId.isNotBlank())
        require(capacity > 0)
    }

    fun add(frame: ByteArray, dequeuedAtUtc: Instant, metadata: PacketMetadata): DecodedPacket {
        require(metadata.sessionId == sessionId) { "Packet belongs to a different receive session" }
        val packet = DecodedPacket(nextSequence++, dequeuedAtUtc, frame, metadata)
        packets.addLast(packet)
        if (packets.size > capacity) {
            packets.removeFirst()
            omittedPacketCount++
        }
        return packet
    }

    fun snapshot(): List<DecodedPacket> = packets.toList()
}
