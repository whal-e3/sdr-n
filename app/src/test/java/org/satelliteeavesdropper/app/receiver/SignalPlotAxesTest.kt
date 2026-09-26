package org.satelliteeavesdropper.app.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalPlotAxesTest {
    @Test fun timeDomainEndpointsMatchTheSamplesActuallyDrawn() {
        assertEquals(
            Triple("0 ms", "0.125 ms", "0.249 ms"),
            timeDomainAxisLabels(1_024_000),
        )
        assertEquals(
            Triple("0 ms", "0.053 ms", "0.106 ms"),
            timeDomainAxisLabels(2_400_000),
        )
    }

    @Test fun unknownRateUsesSampleIndicesInsteadOfInfiniteTime() {
        assertEquals(
            Triple("Sample 0", "Sample 128", "Sample 255"),
            timeDomainAxisLabels(0),
        )
    }
}
