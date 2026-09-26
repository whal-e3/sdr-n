package org.satelliteeavesdropper.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.satelliteeavesdropper.app.data.TransmitterRecord

class TargetDownlinkGroupsTest {
    private val audio = transmitter("audio", "amateur", "AUDIO_NFM", 1_024_000)
    private val packet = transmitter("packet", "public", "AX25_AFSK1200", 2_400_000)

    @Test fun receiverConfiguredDownlinksComeFirstWhileEachGroupKeepsCatalogOrder() {
        val restricted = audio.copy(id = "restricted", policy = "restricted")
        val inactive = audio.copy(id = "inactive", status = "inactive")
        val unsupportedDecoder = audio.copy(id = "unsupported-decoder", decoderId = "METEOR_LRPT_80K")
        val unsupportedRate = audio.copy(id = "unsupported-rate", captureRateSps = 960_000)
        val invalidFrequency = audio.copy(id = "invalid-frequency", frequencyHz = 0)
        val groups = groupTargetDownlinks(listOf(
            restricted, packet, inactive, audio, unsupportedDecoder, unsupportedRate, invalidFrequency,
        ))

        assertEquals(listOf("packet", "audio"), groups.receiverConfigured.map { it.id })
        assertEquals(listOf("restricted", "inactive", "unsupported-decoder", "unsupported-rate", "invalid-frequency"),
            groups.referenceOnly.map { it.id })
        assertNull(receiverConfigurationIssue(audio))
        assertNull(receiverConfigurationIssue(packet))
        groups.referenceOnly.forEach { assertNotNull(receiverConfigurationIssue(it)) }
    }

    private fun transmitter(id: String, policy: String, decoderId: String, rate: Int) = TransmitterRecord(
        id = id,
        frequencyHz = 145_800_000,
        bandwidthHz = 25_000,
        mode = "FM",
        baud = null,
        status = "active",
        verifiedAt = null,
        decoderId = decoderId,
        captureRateSps = rate,
        antenna = null,
        policy = policy,
        evidenceUrls = emptyList(),
    )
}
