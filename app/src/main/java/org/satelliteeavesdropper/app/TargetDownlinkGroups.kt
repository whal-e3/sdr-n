package org.satelliteeavesdropper.app

import org.satelliteeavesdropper.app.data.TransmitterRecord

internal data class TargetDownlinkGroups(
    val receiverConfigured: List<TransmitterRecord>,
    val referenceOnly: List<TransmitterRecord>,
)

/** Configuration required by the signed-catalog reception gate; pass and freshness are checked separately. */
internal fun receiverConfigurationIssue(transmitter: TransmitterRecord): String? = when {
    !transmitter.mayReceive -> "Catalog policy is ${transmitter.policy}; reception is unavailable in this app."
    transmitter.status != "active" -> "Transmitter is not listed as active."
    transmitter.frequencyHz <= 0 -> "A valid downlink frequency is unavailable."
    transmitter.captureRateSps !in setOf(1_024_000, 2_400_000) ->
        "No supported IQ capture rate is configured."
    transmitter.decoderId !in setOf("AUDIO_NFM", "AX25_AFSK1200") ->
        "No decoder is implemented for this downlink."
    else -> null
}

internal fun groupTargetDownlinks(transmitters: List<TransmitterRecord>): TargetDownlinkGroups {
    val (receiverConfigured, referenceOnly) = transmitters.partition { receiverConfigurationIssue(it) == null }
    return TargetDownlinkGroups(receiverConfigured, referenceOnly)
}
