package org.satelliteeavesdropper.app.receiver

/** Sample progress, rather than a changed tuning/status field, determines whether a plot is live. */
internal enum class SignalVisualFreshness { WAITING, LIVE, STALE, STOPPED, FAILED }

internal const val LIVE_SAMPLE_MAX_AGE_MS = 1_500L

internal fun ReceptionSnapshot.isSyntheticVisualSession(): Boolean =
    state == "Synthetic preview" || state == "Synthetic stopped"

internal fun ReceptionSnapshot.isSdrTestVisualSession(): Boolean =
    state == "SDR test" || state == "Test stopped"

internal fun ReceptionSnapshot.isEndedVisualSession(): Boolean =
    state == "Stopped" || state == "Test stopped" || state == "Synthetic stopped"

internal fun ReceptionSnapshot.hasActiveVisualSession(): Boolean =
    state == "Starting" || state == "Receiving" || state == "Synthetic preview" || state == "SDR test"

/** Invalid/missing/future timestamps never establish current sample delivery. */
internal fun signalVisualFreshness(snapshot: ReceptionSnapshot, nowElapsedMs: Long): SignalVisualFreshness {
    if (!snapshot.error.isNullOrBlank() || snapshot.state == "Failed") return SignalVisualFreshness.FAILED
    if (snapshot.isEndedVisualSession()) return SignalVisualFreshness.STOPPED
    if (snapshot.processedSamples <= 0L) return SignalVisualFreshness.WAITING
    if (!snapshot.hasActiveVisualSession()) return SignalVisualFreshness.STALE
    val updatedAt = snapshot.samplesUpdatedAtElapsedMs ?: return SignalVisualFreshness.STALE
    if (updatedAt < 0L || nowElapsedMs < updatedAt) return SignalVisualFreshness.STALE
    return if (nowElapsedMs - updatedAt <= LIVE_SAMPLE_MAX_AGE_MS) SignalVisualFreshness.LIVE
    else SignalVisualFreshness.STALE
}
