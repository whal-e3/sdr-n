package org.satelliteeavesdropper.app.receiver

/** Ages CRC-verified frames using a monotonic clock; receiving IQ alone never refreshes it. */
internal class FrameEvidence {
    private var previousCount = 0L
    private var latestAtMs: Long? = null

    fun latestFrameAgeSeconds(verifiedCount: Long, elapsedRealtimeMs: Long): Long? {
        if (verifiedCount <= 0L) {
            previousCount = 0L
            latestAtMs = null
            return null
        }
        if (verifiedCount != previousCount) {
            previousCount = verifiedCount
            latestAtMs = elapsedRealtimeMs
        }
        return latestAtMs?.let { ((elapsedRealtimeMs - it).coerceAtLeast(0L)) / 1_000L }
    }
}
