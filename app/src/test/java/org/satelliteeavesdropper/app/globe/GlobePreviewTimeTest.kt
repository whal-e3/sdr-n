package org.satelliteeavesdropper.app.globe

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class GlobePreviewTimeTest {
    private val now = Instant.parse("2026-09-27T03:00:00Z")

    @Test fun currentAndExactlyTwentyFourHourPeaksRemainSelectable() {
        assertEquals(GlobePassPreviewStatus.AVAILABLE, globePassPreviewStatus(now, now))
        assertEquals(GlobePassPreviewStatus.AVAILABLE,
            globePassPreviewStatus(now.plusMillis(PREVIEW_WINDOW_MILLIS), now))
    }

    @Test fun peaksOutsideEitherBoundaryCannotSilentlyPreviewAnotherTime() {
        assertEquals(GlobePassPreviewStatus.PEAK_PASSED,
            globePassPreviewStatus(now.minusNanos(1), now))
        assertEquals(GlobePassPreviewStatus.BEYOND_WINDOW,
            globePassPreviewStatus(now.plusMillis(PREVIEW_WINDOW_MILLIS).plusNanos(1), now))
    }

    @Test fun cachedPassPeakBecomesUnavailableWhenLiveTimeAdvancesPastIt() {
        val peak = now.plusSeconds(20)
        assertEquals(GlobePassPreviewStatus.AVAILABLE, globePassPreviewStatus(peak, now))
        assertEquals(GlobePassPreviewStatus.AVAILABLE, globePassPreviewStatus(peak, peak))
        assertEquals(GlobePassPreviewStatus.PEAK_PASSED,
            globePassPreviewStatus(peak, now.plusSeconds(21)))
    }
}
