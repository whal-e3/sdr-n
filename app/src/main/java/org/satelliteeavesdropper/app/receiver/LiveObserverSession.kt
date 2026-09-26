package org.satelliteeavesdropper.app.receiver

import org.satelliteeavesdropper.orbit.ObserverLocation

private const val MAX_FIX_AGE_MS = 300_000L

enum class ObserverFeedState {
    FIXED,
    AUTO_WAITING,
    AUTO_FOLLOWING,
    AUTO_PAUSED,
    AUTO_UNAVAILABLE,
    AUTO_STALE,
}

internal data class ObserverTracking(
    val location: ObserverLocation,
    val feedState: ObserverFeedState,
    val fixAgeSeconds: Long?,
)

/** A monotonic GPS fix may be used to start or update automatic tuning for five minutes. */
internal fun isUsableReceiveFix(fixElapsedMs: Long?, nowElapsedMs: Long): Boolean =
    fixElapsedMs != null && fixElapsedMs >= 0 && fixElapsedMs <= nowElapsedMs &&
        nowElapsedMs - fixElapsedMs <= MAX_FIX_AGE_MS

/**
 * The receiver keeps its satellite and RF downlink for the whole session. Only a matching,
 * fresh automatic observer fix may change its predicted look angle and Doppler correction.
 */
internal class LiveObserverSession(
    val sessionId: String,
    val targetNoradId: String,
    private val automatic: Boolean,
    initial: ObserverLocation,
    initialFixElapsedMs: Long?,
    startedAtElapsedMs: Long,
) {
    private var location = initial
    private var lastFixAtElapsedMs = initialFixElapsedMs?.takeIf {
        automatic && isUsableReceiveFix(it, startedAtElapsedMs)
    }
    private var following = false
    private var pauseReason: ObserverFeedState? = null

    @Synchronized
    fun update(
        expectedSessionId: String,
        expectedTargetNoradId: String,
        observer: ObserverLocation,
        fixElapsedMs: Long,
        nowElapsedMs: Long,
    ): Boolean {
        if (!matches(expectedSessionId, expectedTargetNoradId) || !automatic ||
            !isUsableReceiveFix(fixElapsedMs, nowElapsedMs)) return false
        if (lastFixAtElapsedMs != null && fixElapsedMs <= lastFixAtElapsedMs!!) return false
        location = observer
        lastFixAtElapsedMs = fixElapsedMs
        following = true
        pauseReason = null
        return true
    }

    @Synchronized
    fun pause(
        expectedSessionId: String,
        expectedTargetNoradId: String,
        unavailable: Boolean,
    ): Boolean {
        if (!matches(expectedSessionId, expectedTargetNoradId) || !automatic) return false
        pauseReason = if (unavailable) ObserverFeedState.AUTO_UNAVAILABLE else ObserverFeedState.AUTO_PAUSED
        return true
    }

    @Synchronized
    fun current(nowElapsedMs: Long): ObserverTracking {
        val ageSeconds = lastFixAtElapsedMs?.let { ((nowElapsedMs - it).coerceAtLeast(0)) / 1_000L }
        val state = when {
            !automatic -> ObserverFeedState.FIXED
            pauseReason != null -> pauseReason!!
            lastFixAtElapsedMs != null && nowElapsedMs - lastFixAtElapsedMs!! > MAX_FIX_AGE_MS ->
                ObserverFeedState.AUTO_STALE
            following -> ObserverFeedState.AUTO_FOLLOWING
            else -> ObserverFeedState.AUTO_WAITING
        }
        return ObserverTracking(location, state, ageSeconds)
    }

    private fun matches(expectedSessionId: String, expectedTargetNoradId: String): Boolean =
        expectedSessionId.isNotBlank() && expectedSessionId == sessionId &&
            expectedTargetNoradId.isNotBlank() && expectedTargetNoradId == targetNoradId

}
