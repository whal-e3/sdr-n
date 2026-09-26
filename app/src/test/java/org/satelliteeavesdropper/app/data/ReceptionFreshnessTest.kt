package org.satelliteeavesdropper.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ReceptionFreshnessTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun acceptsExactly72HoursOld() {
        assertTrue(isFreshWithin72Hours(now.minus(Duration.ofHours(72)), now))
    }

    @Test
    fun rejectsEvenOneNanosecondBeyond72Hours() {
        assertFalse(isFreshWithin72Hours(now.minus(Duration.ofHours(72)).minusNanos(1), now))
    }

    @Test
    fun rejectsEvenOneNanosecondInTheFuture() {
        assertFalse(isFreshWithin72Hours(now.plusNanos(1), now))
    }

    @Test
    fun acceptsCurrentTimestamp() {
        assertTrue(isFreshWithin72Hours(now, now))
    }
}
