package org.satelliteeavesdropper.app

import org.satelliteeavesdropper.app.data.CatalogLoadResult
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.TransmitterRecord
import org.satelliteeavesdropper.app.data.isFreshWithin72Hours
import java.time.Instant

internal data class DiagnosticTuning(val satellite: SatelliteRecord, val transmitter: TransmitterRecord)

/** Select a current configured downlink for a receive-only USB sample check, without requiring a pass. */
internal fun selectUsbDiagnosticTuning(catalog: CatalogLoadResult?, now: Instant): DiagnosticTuning? {
    if (catalog == null || catalog.source == CatalogSource.DEMO ||
        !isFreshWithin72Hours(catalog.manifest.sourceUpdatedAt, now)) return null

    var fallback: DiagnosticTuning? = null
    for (satellite in catalog.manifest.satellites) {
        if (satellite.isKnownDecayedAt(now) || satellite.transmitters.isEmpty()) continue
        val transmitters = satellite.transmitters.filter { receiverConfigurationIssue(it) == null }
        if (transmitters.isEmpty()) continue
        val epoch = satellite.orbitElements()?.epoch ?: continue
        if (!isFreshWithin72Hours(epoch, now)) continue

        for (transmitter in transmitters) {
            val tuning = DiagnosticTuning(satellite, transmitter)
            if (fallback == null) fallback = tuning
            if (satellite.noradId == "25544" && transmitter.frequencyHz == 145_825_000L &&
                transmitter.decoderId == "AX25_AFSK1200") return tuning
        }
    }
    return fallback
}
