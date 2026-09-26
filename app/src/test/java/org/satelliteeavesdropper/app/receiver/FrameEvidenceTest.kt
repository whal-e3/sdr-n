package org.satelliteeavesdropper.app.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameEvidenceTest {
    @Test fun iqProgressDoesNotRefreshLastVerifiedFrame() {
        val evidence = FrameEvidence()

        assertNull(evidence.latestFrameAgeSeconds(0, 1_000))
        assertEquals(0L, evidence.latestFrameAgeSeconds(1, 2_000))
        assertEquals(8L, evidence.latestFrameAgeSeconds(1, 10_500))
        assertEquals(0L, evidence.latestFrameAgeSeconds(2, 11_000))
        assertEquals(3L, evidence.latestFrameAgeSeconds(2, 14_000))
    }

    @Test fun decoderCounterResetClearsPreviousFrameAge() {
        val evidence = FrameEvidence()

        assertEquals(0L, evidence.latestFrameAgeSeconds(1, 2_000))
        assertNull(evidence.latestFrameAgeSeconds(0, 3_000))
        assertEquals(0L, evidence.latestFrameAgeSeconds(1, 9_000))
    }
}
