package org.satelliteeavesdropper.app.data

import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.SatelliteOrbit
import java.time.Instant

internal data class VerifiedReceptionSelection(
    val satellite: SatelliteRecord,
    val transmitter: TransmitterRecord,
)

/** Recheck the signed catalog and pass at the instant a foreground receiver is requested. */
internal fun verifyReceptionSelection(
    catalog: CatalogLoadResult,
    requestedSatellite: SatelliteRecord,
    requestedTransmitter: TransmitterRecord,
    observer: ObserverLocation,
    now: Instant,
): VerifiedReceptionSelection {
    require(catalog.source != CatalogSource.DEMO && isFreshWithin72Hours(catalog.manifest.sourceUpdatedAt, now)) {
        "A current signed catalog is required to receive."
    }
    val satellite = catalog.manifest.satellites.firstOrNull { it.noradId == requestedSatellite.noradId }
        ?: throw IllegalArgumentException("Target is not in the verified catalog; select it again.")
    require(!satellite.isKnownDecayedAt(now)) {
        "Reception is unavailable from the source-reported decay date; these elements are historical."
    }
    val elements = satellite.orbitElements()
        ?: throw IllegalArgumentException("Target orbital elements are unavailable.")
    require(isFreshWithin72Hours(elements.epoch, now)) { "Target orbital elements are not current." }
    require(requestedSatellite.orbitElements() == elements) { "Target orbit changed; select it again." }
    val transmitter = satellite.transmitters.firstOrNull { it.id == requestedTransmitter.id }
        ?: throw IllegalArgumentException("Downlink is not in the verified catalog; select it again.")
    require(transmitter == requestedTransmitter) { "Downlink changed; select it again." }
    require(transmitter.mayReceive && transmitter.status == "active" && transmitter.frequencyHz > 0 &&
        transmitter.captureRateSps in setOf(1_024_000, 2_400_000) &&
        transmitter.decoderId in setOf("AUDIO_NFM", "AX25_AFSK1200")) {
        "This downlink is not enabled for reception."
    }
    require(SatelliteOrbit(elements).lookFrom(observer, now).elevationDegrees >= 0.0) {
        "Target is below the horizon; wait for its predicted pass."
    }
    return VerifiedReceptionSelection(satellite, transmitter)
}
