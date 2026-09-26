package org.satelliteeavesdropper.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.satelliteeavesdropper.app.receiver.isUsableReceiveFix

class ReceiveFixFreshnessTest {
    @Test fun delayedUsbGrantCannotReuseAnExpiredAutomaticGpsFix() {
        val fixAt = 1_000_000L
        assertTrue(isUsableReceiveFix(fixAt, fixAt + 10_000))
        assertTrue(isUsableReceiveFix(fixAt, fixAt + 300_000))
        assertFalse(isUsableReceiveFix(fixAt, fixAt + 300_001))
        assertFalse(isUsableReceiveFix(fixAt, fixAt - 1))
        assertFalse(isUsableReceiveFix(null, fixAt))
    }
}
