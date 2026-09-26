package org.satelliteeavesdropper.app.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceptionSnapshotTest {
    @Test fun stoppedSessionRetainsCaptureAndVerifiedFrameEvidence() {
        val receiving = ReceptionSnapshot(
            state = "Receiving",
            spectrum = List(256) { -80f },
            iqSamples = List(512) { 0.25f },
            spectrogram = listOf(List(128) { -80f }),
            acceptedSamples = 1_024,
            processedSamples = 1_024,
            decoderId = "AX25_AFSK1200",
            verifiedFrameCount = 1,
            packets = listOf("TEST>APRS:Hello satellite"),
        )

        val stopped = receiving.stopped()

        assertEquals("Stopped", stopped.state)
        assertEquals(receiving.spectrum, stopped.spectrum)
        assertEquals(receiving.iqSamples, stopped.iqSamples)
        assertEquals(receiving.spectrogram, stopped.spectrogram)
        assertEquals(receiving.processedSamples, stopped.processedSamples)
        assertEquals(receiving.verifiedFrameCount, stopped.verifiedFrameCount)
        assertEquals(receiving.packets, stopped.packets)
        assertEquals("Idle", ReceptionSnapshot().stopped().state)
    }
}
