package org.satelliteeavesdropper.app.data

import java.time.Duration
import java.time.Instant

/** A catalog timestamp or orbit epoch is usable from its instant through the next 72 hours. */
internal fun isFreshWithin72Hours(timestamp: Instant, now: Instant): Boolean {
    val age = Duration.between(timestamp, now)
    return !age.isNegative && age <= Duration.ofHours(72)
}
