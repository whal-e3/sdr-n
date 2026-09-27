package org.satelliteeavesdropper.app.globe

import java.time.Instant

internal const val PREVIEW_WINDOW_MILLIS = 24L * 60L * 60L * 1000L

internal enum class GlobePassPreviewStatus { AVAILABLE, PEAK_PASSED, BEYOND_WINDOW }

/** A pass button must select its advertised peak rather than clamp it to another instant. */
internal fun globePassPreviewStatus(peakAt: Instant, liveNow: Instant): GlobePassPreviewStatus = when {
    peakAt.isBefore(liveNow) -> GlobePassPreviewStatus.PEAK_PASSED
    peakAt.isAfter(liveNow.plusMillis(PREVIEW_WINDOW_MILLIS)) -> GlobePassPreviewStatus.BEYOND_WINDOW
    else -> GlobePassPreviewStatus.AVAILABLE
}
