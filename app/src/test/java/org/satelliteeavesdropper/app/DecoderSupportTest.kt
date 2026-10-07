package org.satelliteeavesdropper.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.TransmitterRecord
import java.time.Instant

class DecoderSupportTest {
    private val audio = transmitter("audio", "AUDIO_NFM")
    private val packet = transmitter("packet", "AX25_AFSK1200").copy(policy = "public", captureRateSps = 2_400_000)

    @Test fun recordsWithoutRadioMetadataAreDistinctFromUnsupportedDownlinks() {
        val missing = decoderSupport(emptyList())
        val unsupported = decoderSupport(listOf(audio.copy(decoderId = "METEOR_LRPT_80K")))

        assertFalse(missing.canDecode)
        assertFalse(missing.hasRadioMetadata)
        assertEquals("Tracking only · No radio metadata", missing.summaryLabel)
        assertEquals("No radio metadata is available for this satellite.", missing.explanation)
        assertFalse(unsupported.canDecode)
        assertTrue(unsupported.hasRadioMetadata)
        assertEquals("Tracking only · No supported downlink", unsupported.summaryLabel)
        assertTrue(unsupported.explanation.contains("LRPT decoding is not implemented"))
    }

    @Test fun fmAndAx25ConfiguredOutputsAreNamedWithoutClaimingReception() {
        val fm = decoderSupport(listOf(audio))
        val ax25 = decoderSupport(listOf(packet))

        assertTrue(fm.canDecode)
        assertEquals("Decodable", fm.badgeLabel)
        assertEquals("Decodable · FM audio", fm.summaryLabel)
        assertEquals("Decodable · AX.25 packets", ax25.summaryLabel)
        assertTrue(fm.explanation.contains("Configured for FM audio"))
        assertTrue(fm.explanation.contains("Reception still requires a current signed catalog"))
    }

    @Test fun mixedAndRepeatedDownlinksPreserveCapabilityAndUseDistinctStableOutputs() {
        val lrpt = audio.copy(id = "lrpt", decoderId = "METEOR_LRPT_80K")
        val support = decoderSupport(listOf(lrpt, packet, audio, audio.copy(id = "second-audio")))

        assertTrue(support.canDecode)
        assertEquals("FM audio + AX.25 packets", support.outputLabel)
        assertEquals("Decodable · FM audio + AX.25 packets", support.summaryLabel)
        assertEquals(listOf("packet", "audio", "second-audio"), support.supportedDownlinks.map { it.id })
        assertEquals(listOf("lrpt"), support.unsupportedDownlinks.map { it.transmitter.id })
        assertTrue(support.explanation.contains("1 other downlink is unavailable"))
        assertTrue(support.explanation.contains("LRPT decoding is not implemented"))
    }

    @Test fun policyInactiveFrequencyAndCaptureFailuresNeverBecomeDecodable() {
        val blocked = listOf(
            audio.copy(id = "restricted", policy = "restricted"),
            audio.copy(id = "inactive", status = "inactive"),
            audio.copy(id = "invalid-frequency", frequencyHz = 0),
            audio.copy(id = "invalid-rate", captureRateSps = 960_000),
        )

        blocked.forEach { transmitter ->
            val support = decoderSupport(listOf(transmitter))
            assertFalse(transmitter.id, support.canDecode)
            assertEquals(receiverConfigurationIssue(transmitter), support.unsupportedDownlinks.single().reason)
        }
    }

    @Test fun absentAndUnknownDecoderReasonsStayVisibleEvenWithMissingCaptureConfiguration() {
        val missing = audio.copy(id = "missing", decoderId = null, captureRateSps = null)
        val unknown = audio.copy(id = "unknown", decoderId = "BPSK_9600")
        val lrpt = audio.copy(id = "lrpt", decoderId = "METEOR_LRPT_80K", captureRateSps = null)

        assertTrue(decoderSupport(listOf(missing)).explanation.contains("No decoder is configured"))
        assertTrue(decoderSupport(listOf(missing)).explanation.contains("No supported IQ capture rate"))
        assertTrue(decoderSupport(listOf(unknown)).explanation.contains("Decoder BPSK_9600 is not implemented"))
        assertTrue(decoderSupport(listOf(lrpt)).explanation.contains("LRPT decoding is not implemented"))
        assertFalse(decoderSupport(listOf(missing, unknown, lrpt)).canDecode)
    }

    @Test fun capabilityClassificationAgreesWithReceiverConfigurationForEveryMixedEntry() {
        val transmitters = listOf(
            audio, packet,
            audio.copy(id = "restricted", policy = "restricted"),
            audio.copy(id = "inactive", status = "inactive"),
            audio.copy(id = "invalid-frequency", frequencyHz = -1),
            audio.copy(id = "invalid-rate", captureRateSps = null),
            audio.copy(id = "no-decoder", decoderId = null),
            audio.copy(id = "lrpt", decoderId = "METEOR_LRPT_80K"),
        )
        val support = decoderSupport(transmitters)

        assertEquals(transmitters.filter { receiverConfigurationIssue(it) == null }, support.supportedDownlinks)
        assertEquals(transmitters.filter { receiverConfigurationIssue(it) != null },
            support.unsupportedDownlinks.map { it.transmitter })
        assertEquals(groupTargetDownlinks(transmitters).receiverConfigured, support.supportedDownlinks)
    }

    @Test fun staleVerificationAndHistoricalOrbitDoNotChangeDecoderCapability() {
        val historical = SatelliteRecord(
            noradId = "1",
            name = "Historical demo satellite",
            aliases = emptyList(),
            omm = JSONObject().put("EPOCH", "1995-01-01T00:00:00Z").put("DECAY_DATE", "1996-01-01"),
            transmitters = listOf(audio.copy(verifiedAt = Instant.parse("1995-01-01T00:00:00Z"))),
        )

        assertTrue(historical.isKnownDecayedAt(Instant.parse("2026-09-30T00:00:00Z")))
        assertTrue(decoderSupport(historical).canDecode)
        assertEquals("Decodable · FM audio", decoderSupport(historical).summaryLabel)
    }

    private fun transmitter(id: String, decoderId: String) = TransmitterRecord(
        id = id,
        frequencyHz = 145_800_000,
        bandwidthHz = 25_000,
        mode = "FM",
        baud = null,
        status = "active",
        verifiedAt = null,
        decoderId = decoderId,
        captureRateSps = 1_024_000,
        antenna = null,
        policy = "amateur",
        evidenceUrls = emptyList(),
    )
}
