package org.satelliteeavesdropper.app.receiver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class DecodedPacketTest {
    private val time = Instant.parse("2026-09-28T01:02:03.456Z")

    @Test fun copiesNativeBytesAndDoesNotExposeMutableFrameStorage() {
        val frame = packetTestFrame(byteArrayOf(0, 0x80.toByte(), 0xff.toByte()))
        val original = frame.copyOf()
        val packet = DecodedPacket(1, time, frame, packetTestMetadata())
        frame.fill(0)
        val returned = packet.rawFrameBytes
        returned.fill(0)

        assertArrayEquals(original, packet.rawFrameBytes)
        assertEquals(original.joinToString("") { "%02x".format(it.toInt() and 0xff) }, packet.frameHex)
        assertEquals("ISS>APRS:···", packet.displayText)
    }

    @Test fun keepsFullBinaryInformationWhenDisplayIsTruncated() {
        val frame = packetTestFrame(ByteArray(768) { it.toByte() })
        val packet = DecodedPacket(1, time, frame, packetTestMetadata())

        assertEquals(784, packet.byteLength)
        assertEquals(1_568, packet.frameHex.length)
        assertArrayEquals(frame, packet.rawFrameBytes)
        assertEquals("ISS>APRS:".length + 512, packet.displayText.length)
    }

    @Test fun assignsStableSequenceAndSeparatesReceiveSessions() {
        val firstSession = DecodedPacketBuffer("first-session")
        val secondSession = DecodedPacketBuffer("second-session")
        val frame = packetTestFrame()
        val first = firstSession.add(frame, time, packetTestMetadata("first-session"))
        val next = firstSession.add(frame, time.plusSeconds(1), packetTestMetadata("first-session"))
        val second = secondSession.add(frame, time, packetTestMetadata("second-session"))

        assertEquals("first-session:1", first.packetId)
        assertEquals("first-session:2", next.packetId)
        assertEquals("second-session:1", second.packetId)
        assertNotEquals(first.packetId, second.packetId)
    }

    @Test fun boundsHistoryWithoutMutatingOlderSnapshots() {
        val history = DecodedPacketBuffer("session-a", capacity = 2)
        val first = history.add(packetTestFrame(), time, packetTestMetadata())
        val selectedForExport = history.snapshot()
        history.add(packetTestFrame(), time.plusSeconds(1), packetTestMetadata())
        history.add(packetTestFrame(), time.plusSeconds(2), packetTestMetadata())

        assertEquals(1L, history.omittedPacketCount)
        assertEquals(listOf(2L, 3L), history.snapshot().map { it.sequence })
        assertEquals(listOf(first), selectedForExport)
        assertArrayEquals(packetTestFrame(), selectedForExport.single().rawFrameBytes)
    }

    @Test fun capturesMetadataForEachPacketBeforeLaterReceiverChanges() {
        val history = DecodedPacketBuffer("session-a")
        val initialMetadata = packetTestMetadata()
        val first = history.add(packetTestFrame(), time, initialMetadata)
        val laterMetadata = initialMetadata.copy(
            predictedDopplerHz = -3200.0,
            afcTracking = false,
            afcAppliedHz = -150.0,
            afcLastResidualHz = 30.0,
            observerLatitudeDegrees = 36.0,
            observerLongitudeDegrees = 128.0,
            observerAltitudeMeters = 200.0,
            observerFeedState = ObserverFeedState.AUTO_STALE,
            observerFixAgeSeconds = 301,
            lookAzimuthDegrees = 230.0,
            lookElevationDegrees = 15.0,
        )
        val second = history.add(packetTestFrame(), time.plusSeconds(300), laterMetadata)

        assertEquals(initialMetadata, first.metadata)
        assertEquals(time, first.dequeuedAtUtc)
        assertEquals(37.2411, first.metadata.observerLatitudeDegrees, 0.0)
        assertEquals(1000.0, first.metadata.predictedDopplerHz, 0.0)
        assertEquals(ObserverFeedState.AUTO_FOLLOWING, first.metadata.observerFeedState)
        assertEquals(3L, first.metadata.observerFixAgeSeconds)
        assertEquals(laterMetadata, second.metadata)
        assertNotEquals(first.dequeuedAtUtc, second.dequeuedAtUtc)
    }

    @Test fun cannotAppendMetadataFromADifferentSession() {
        val history = DecodedPacketBuffer("session-a")
        assertThrows(IllegalArgumentException::class.java) {
            history.add(packetTestFrame(), time, packetTestMetadata("other-session"))
        }
        assertEquals(emptyList<DecodedPacket>(), history.snapshot())
    }

    @Test fun stoppedReceptionKeepsExportablePacketsAndEvictionCount() {
        val packet = DecodedPacket(4, time, packetTestFrame(), packetTestMetadata())
        val snapshot = ReceptionSnapshot(
            state = "Receiving", sessionId = "session-a", packets = listOf(packet.displayText),
            decodedPackets = listOf(packet), omittedPacketCount = 3,
        )
        val stopped = snapshot.stopped()

        assertEquals("Stopped", stopped.state)
        assertSame(packet, stopped.decodedPackets.single())
        assertEquals(3L, stopped.omittedPacketCount)
        assertEquals(snapshot.packets, stopped.packets)
    }
}

internal fun packetTestFrame(payload: ByteArray = "Hello".toByteArray()): ByteArray {
    fun address(call: String, last: Boolean) = call.padEnd(6).map { (it.code shl 1).toByte() }
        .toByteArray() + byteArrayOf(if (last) 0x61 else 0x60)
    return address("APRS", false) + address("ISS", true) + byteArrayOf(0x03, 0xf0.toByte()) + payload
}

internal fun packetTestMetadata(sessionId: String = "session-a") = PacketMetadata(
    sessionId = sessionId,
    decoderId = "AX25_AFSK1200",
    targetNoradId = "25544",
    targetName = "ISS (ZARYA)",
    device = "RTL-SDR",
    rfCenterHz = 145_825_000,
    sampleRateSps = 1_024_000,
    predictedDopplerHz = 1000.0,
    afcTracking = true,
    afcAppliedHz = 120.0,
    afcLastResidualHz = -15.0,
    lookAzimuthDegrees = 45.0,
    lookElevationDegrees = 30.0,
    observerLatitudeDegrees = 37.2411,
    observerLongitudeDegrees = 127.1776,
    observerAltitudeMeters = 100.0,
    observerFeedState = ObserverFeedState.AUTO_FOLLOWING,
    observerFixAgeSeconds = 3,
)
