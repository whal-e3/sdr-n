package org.satelliteeavesdropper.app

import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.TransmitterRecord

internal data class UnsupportedDownlink(
    val transmitter: TransmitterRecord,
    val reason: String,
)

/**
 * Decoder capability describes catalog configuration, never a successful reception.
 * Catalog freshness/signature, orbital decay, visibility, and SDR readiness remain separate.
 */
internal data class DecoderSupport(
    val supportedDownlinks: List<TransmitterRecord>,
    val unsupportedDownlinks: List<UnsupportedDownlink>,
) {
    val canDecode: Boolean get() = supportedDownlinks.isNotEmpty()
    val hasRadioMetadata: Boolean get() = canDecode || unsupportedDownlinks.isNotEmpty()
    val badgeLabel: String get() = if (canDecode) "Decodable" else "Tracking only"

    // A stable order and distinct outputs keep a satellite with several radio profiles concise.
    val outputLabel: String
        get() = decoderOutputLabels.filterKeys { decoderId ->
            supportedDownlinks.any { it.decoderId == decoderId }
        }.values.joinToString(" + ")

    val summaryLabel: String
        get() = when {
            canDecode -> "$badgeLabel · $outputLabel"
            !hasRadioMetadata -> "$badgeLabel · No radio metadata"
            else -> "$badgeLabel · No supported downlink"
        }

    val explanation: String
        get() = when {
            canDecode -> buildString {
                append("Configured for $outputLabel.")
                if (unsupportedDownlinks.isNotEmpty()) {
                    val count = unsupportedDownlinks.size
                    append(" $count other downlink${if (count == 1) " is" else "s are"} unavailable: ")
                    append(unsupportedDownlinks.map { it.reason }.distinct().joinToString(" "))
                }
                append(" Reception still requires a current signed catalog, satellite visibility, and SDR hardware.")
            }
            !hasRadioMetadata -> "No radio metadata is available for this satellite."
            else -> "No downlink has a usable decoder configuration. " +
                unsupportedDownlinks.map { it.reason }.distinct().joinToString(" ")
        }
}

private val decoderOutputLabels = linkedMapOf(
    "AUDIO_NFM" to "FM audio",
    "AX25_AFSK1200" to "AX.25 packets",
)

internal fun decoderSupport(satellite: SatelliteRecord): DecoderSupport = decoderSupport(satellite.transmitters)

/** Use the reception configuration predicate so list badges and the Decodable filter agree. */
internal fun decoderSupport(transmitters: List<TransmitterRecord>): DecoderSupport {
    val supported = ArrayList<TransmitterRecord>()
    val unsupported = ArrayList<UnsupportedDownlink>()
    for (transmitter in transmitters) {
        val issue = receiverConfigurationIssue(transmitter)
        if (issue == null) {
            supported += transmitter
        } else {
            unsupported += UnsupportedDownlink(transmitter, decoderIssueExplanation(transmitter, issue))
        }
    }
    return DecoderSupport(supported, unsupported)
}

private fun decoderIssueExplanation(transmitter: TransmitterRecord, configurationIssue: String): String {
    val decoderIssue = when (transmitter.decoderId) {
        "AUDIO_NFM", "AX25_AFSK1200" -> null
        null -> "No decoder is configured for this downlink."
        "METEOR_LRPT_80K" -> "LRPT decoding is not implemented."
        else -> "Decoder ${transmitter.decoderId} is not implemented."
    }
    return when {
        decoderIssue == null -> configurationIssue
        configurationIssue == "No decoder is implemented for this downlink." -> decoderIssue
        // Explain missing decoder support even when an earlier configuration check also fails.
        else -> "$decoderIssue $configurationIssue"
    }
}
