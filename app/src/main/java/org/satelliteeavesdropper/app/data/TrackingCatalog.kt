package org.satelliteeavesdropper.app.data

/**
 * Use newer user-supplied or directly looked-up elements for tracking, including when an ID
 * already exists in the signed catalog. Supplemental records never carry receiver profiles.
 * The original signed manifest must remain separate for reception authorization.
 */
internal fun mergeTrackingCatalog(
    signed: CatalogManifest,
    supplemental: Collection<SatelliteRecord>,
): CatalogManifest {
    if (supplemental.isEmpty()) return signed
    val records = signed.satellites.toMutableList()
    val indices = records.withIndex().associateTo(HashMap(records.size + supplemental.size)) {
        it.value.noradId to it.index
    }
    for (candidate in supplemental) {
        val epoch = candidate.orbitElements()?.epoch ?: continue
        val trackingOnly = candidate.copy(transmitters = emptyList())
        val index = indices[candidate.noradId]
        if (index == null) {
            indices[candidate.noradId] = records.size
            records += trackingOnly
        } else {
            val current = records[index]
            val currentEpoch = current.orbitElements()?.epoch
            // Reentry can be reported after the final element set: its epoch may
            // remain unchanged while the imported metadata gains a decay date.
            val addsDecayMetadata = epoch == currentEpoch && current.recordedDecayDate == null &&
                candidate.recordedDecayDate != null
            if (currentEpoch == null || epoch.isAfter(currentEpoch) || addsDecayMetadata) records[index] = trackingOnly
        }
    }
    return signed.copy(satellites = records)
}
