package org.satelliteeavesdropper.app.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceptionStatusTextTest {
    @Test fun firstAndLastTuningStatusDistinguishLiveFromHistoricalCorrection() {
        val live = ReceptionSnapshot(
            state = "Starting",
            rfCenterHz = 145_825_000,
            predictedDopplerHz = 1_234.0,
        )

        assertTrue(tuningHeadline(live).startsWith("RF tuner "))
        assertTrue(tuningDetail(live).contains("+1234 Hz"))

        val stopped = live.stopped()
        assertTrue(tuningHeadline(stopped).startsWith("Last RF tuning "))
        assertTrue(tuningDetail(stopped).startsWith("Last predicted signal "))
        assertTrue(tuningDetail(stopped).contains("+1234 Hz"))
        assertFalse(tuningHeadline(stopped).startsWith("RF tuner "))
    }

    @Test fun endedPacketSearchDoesNotClaimCurrentSearchOrFrameSync() {
        val live = ReceptionSnapshot(
            state = "Receiving",
            decoderId = "AX25_AFSK1200",
            processedSamples = 500_000,
            hdlcFlagCandidates = 3,
            failedFrameCrc = 2,
        )

        assertTrue(decoderHeadline(live).startsWith("Searching for AX.25"))
        val stopped = live.stopped()
        assertTrue(decoderHeadline(stopped).contains("search ended · no valid frames"))
        assertTrue(decoderDetail(stopped).contains("candidates alone are not sync"))
        assertFalse(decoderHeadline(stopped).contains("Searching"))
    }

    @Test fun validFrameRemainsPastEvidenceWithUnverifiedSource() {
        val stopped = ReceptionSnapshot(
            state = "Stopped",
            decoderId = "AX25_AFSK1200",
            processedSamples = 500_000,
            verifiedFrameCount = 1,
        )

        assertTrue(decoderHeadline(stopped).startsWith("Last session · 1 CRC-valid"))
        assertTrue(decoderDetail(stopped).contains("transmitter identity is unverified"))
    }

    @Test fun liveFrameEvidenceAgesInsteadOfClaimingContinuousSync() {
        val recent = ReceptionSnapshot(
            state = "Receiving",
            decoderId = "AX25_AFSK1200",
            processedSamples = 500_000,
            verifiedFrameCount = 1,
            latestVerifiedFrameAgeSeconds = 2,
        )

        assertTrue(decoderHeadline(recent).contains("Frame verified in last 5 s"))
        assertTrue(decoderDetail(recent).contains("continuous sync are unverified"))

        val oldFrame = recent.copy(processedSamples = 5_000_000,
            latestVerifiedFrameAgeSeconds = 90)
        assertTrue(decoderHeadline(oldFrame).contains("1 min ago"))
        assertFalse(decoderHeadline(oldFrame).contains("last 5 s"))
        assertTrue(decoderDetail(oldFrame).contains("transmitter identity"))

        val stopped = oldFrame.stopped()
        assertTrue(decoderHeadline(stopped).startsWith("Last session"))
        assertFalse(decoderHeadline(stopped).contains("ago"))
    }

    @Test fun endedAudioSessionDoesNotClaimCurrentPlayback() {
        val live = ReceptionSnapshot(
            state = "Receiving",
            decoderId = "AUDIO_NFM",
            processedSamples = 500_000,
            audioFramesPlayed = 48_000,
        )

        assertTrue(decoderHeadline(live).contains("output active"))
        val stopped = live.stopped()
        assertTrue(decoderHeadline(stopped).startsWith("Last session · NFM audio was written"))
        assertTrue(decoderDetail(stopped).contains("PCM samples written in last session"))
        assertFalse(decoderHeadline(stopped).contains("active"))
    }

    @Test fun afcStatusDistinguishesEstimateFromVerifiedSourceAndPastSession() {
        val live = ReceptionSnapshot(
            state = "Receiving",
            decoderId = "AUDIO_NFM",
            processedSamples = 500_000,
            afcTracking = true,
            afcAppliedHz = 1_900.0,
            afcLastResidualHz = 45.0,
        )

        assertTrue(afcDescription(live).contains("estimated FM-like component"))
        assertTrue(afcDescription(live).contains("does not verify satellite identity or packet sync"))
        assertTrue(acquisitionHeadline(live).contains("Estimated FM component tracked"))
        assertTrue(afcDescription(live.stopped()).startsWith("Last session:"))
        assertTrue(acquisitionHeadline(live.stopped()).startsWith("Last session ·"))
        val noEstimate = live.copy(afcTracking = false, afcAppliedHz = 0.0).stopped()
        assertTrue(afcDescription(noEstimate).contains("AFC did not acquire"))
        assertTrue(acquisitionHeadline(noEstimate).contains("no FM estimate"))
        val synthetic = ReceptionSnapshot(state = "Synthetic preview", processedSamples = 16_384)
        assertTrue(acquisitionHeadline(synthetic).contains("No RF signal acquisition"))
        assertTrue(acquisitionDetail(synthetic).contains("no carrier or satellite signal"))
    }

    @Test fun observerStatusSeparatesFollowingPausedAndPastPrediction() {
        val live = ReceptionSnapshot(
            state = "Receiving",
            targetName = "ISS",
            targetNoradId = "25544",
            lookAzimuthDegrees = 120.0,
            lookElevationDegrees = 30.0,
            observerFeedState = ObserverFeedState.AUTO_FOLLOWING,
            observerFixAgeSeconds = 3,
        )
        assertTrue(orbitTargetDetail(live).contains("Auto GPS following latest fix"))
        assertTrue(orbitTargetDetail(live).contains("fix 3 s old"))
        assertTrue(orbitTargetDetail(live).contains("RF source unverified"))

        val paused = live.copy(observerFeedState = ObserverFeedState.AUTO_PAUSED)
        assertTrue(orbitTargetDetail(paused).contains("updates paused; last observer held"))
        assertFalse(orbitTargetDetail(paused).contains("Auto GPS following"))

        val ended = live.stopped()
        assertTrue(orbitTargetDetail(ended).contains("Last predicted azimuth"))
        assertTrue(orbitTargetDetail(ended).contains("Auto GPS followed the latest fix during the session"))
        assertFalse(orbitTargetDetail(ended).contains("Auto GPS following latest fix"))
    }
}
