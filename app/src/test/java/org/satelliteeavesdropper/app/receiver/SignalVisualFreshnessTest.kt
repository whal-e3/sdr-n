package org.satelliteeavesdropper.app.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalVisualFreshnessTest {
    private val samples = ReceptionSnapshot(
        state = "Receiving",
        sessionId = "session-1",
        processedSamples = 16_384,
        samplesUpdatedAtElapsedMs = 10_000,
    )

    @Test fun advancingSamplesAreLiveUntilTheFreshnessBoundaryThenBecomeStale() {
        assertEquals(SignalVisualFreshness.LIVE, signalVisualFreshness(samples, 10_000))
        assertEquals(SignalVisualFreshness.LIVE, signalVisualFreshness(samples, 11_500))
        assertEquals(SignalVisualFreshness.STALE, signalVisualFreshness(samples, 11_501))
    }

    @Test fun changedTuningAndObserverFieldsDoNotMakeFrozenSamplesLive() {
        val statusOnlyUpdate = samples.copy(
            predictedDopplerHz = 1_000.0,
            lookElevationDegrees = 40.0,
            observerFeedState = ObserverFeedState.AUTO_FOLLOWING,
        )
        assertEquals(SignalVisualFreshness.STALE, signalVisualFreshness(statusOnlyUpdate, 12_000))
        val newSamples = statusOnlyUpdate.copy(processedSamples = 32_768, samplesUpdatedAtElapsedMs = 12_000)
        assertEquals(SignalVisualFreshness.LIVE, signalVisualFreshness(newSamples, 12_000))
    }

    @Test fun unverifiedOrInvalidSampleTimesNeverClaimLive() {
        assertEquals(SignalVisualFreshness.STALE,
            signalVisualFreshness(samples.copy(samplesUpdatedAtElapsedMs = null), 10_000))
        assertEquals(SignalVisualFreshness.STALE,
            signalVisualFreshness(samples.copy(samplesUpdatedAtElapsedMs = -1), 10_000))
        assertEquals(SignalVisualFreshness.STALE, signalVisualFreshness(samples, 9_999))
    }

    @Test fun waitingAndStoppedAreDistinguishedEvenWhenTheSnapshotTimestampIsRecent() {
        assertEquals(SignalVisualFreshness.WAITING,
            signalVisualFreshness(samples.copy(state = "Starting", processedSamples = 0), 10_000))
        assertEquals(SignalVisualFreshness.STOPPED, signalVisualFreshness(samples.stopped(), 10_000))
        assertEquals(SignalVisualFreshness.STOPPED,
            signalVisualFreshness(samples.copy(state = "Stopped", processedSamples = 0), 10_000))
        assertEquals(SignalVisualFreshness.STALE,
            signalVisualFreshness(samples.copy(state = "Idle"), 10_000))
    }

    @Test fun hardwareAndSyntheticSourcesRequireFreshSamplesAndKeepTheirEndedIdentity() {
        listOf("SDR test", "Synthetic preview").forEach { state ->
            val active = samples.copy(state = state)
            assertEquals(SignalVisualFreshness.LIVE, signalVisualFreshness(active, 10_001))
            assertEquals(SignalVisualFreshness.STALE, signalVisualFreshness(active, 12_000))
        }
        assertEquals(SignalVisualFreshness.STOPPED,
            signalVisualFreshness(samples.copy(state = "Test stopped"), 10_001))
        assertEquals(SignalVisualFreshness.STOPPED,
            signalVisualFreshness(samples.copy(state = "Synthetic stopped"), 10_001))
        assertTrue(samples.copy(state = "Test stopped").isSdrTestVisualSession())
        assertTrue(samples.copy(state = "Synthetic stopped").isSyntheticVisualSession())
    }

    @Test fun hardwareTestHasNoOrbitDopplerAcquisitionOrDecoderClaims() {
        val test = samples.copy(state = "SDR test", rfCenterHz = 100_000_000)
        assertTrue(orbitTargetDetail(test).contains("no satellite, location or pass prediction"))
        assertTrue(tuningDetail(test).contains("no orbital Doppler correction"))
        assertFalse(orbitTargetDetail(test).contains("Waiting for orbital prediction"))
        assertTrue(acquisitionHeadline(test).contains("no satellite acquisition"))
        assertTrue(acquisitionDetail(test).contains("does not verify satellite reception"))
        assertEquals("Spectrum only · decoder off", decoderHeadline(test))
        assertEquals("Last RF tuning 100.000 MHz", tuningHeadline(test.copy(state = "Test stopped")))
    }

    @Test fun failureNeverAppearsWaitingLiveOrSuccessfullyStopped() {
        assertEquals(SignalVisualFreshness.FAILED,
            signalVisualFreshness(ReceptionSnapshot(error = "USB permission denied"), 10_000))
        assertEquals(SignalVisualFreshness.FAILED,
            signalVisualFreshness(samples.copy(error = "Sample stream failed"), 10_000))
        assertEquals(SignalVisualFreshness.FAILED,
            signalVisualFreshness(samples.copy(state = "Test stopped", error = "USB detached"), 10_000))
        assertEquals(SignalVisualFreshness.FAILED,
            signalVisualFreshness(samples.copy(state = "Failed"), 10_000))
    }
}
