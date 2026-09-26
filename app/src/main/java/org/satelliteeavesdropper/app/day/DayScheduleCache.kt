package org.satelliteeavesdropper.app.day

import org.satelliteeavesdropper.app.data.CatalogManifest
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.orbit.ObserverLocation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Catalog metadata may stay unchanged when a refresh creates an entirely new record graph. */
internal class ScheduleCatalogIdentity(val manifest: CatalogManifest) {
    override fun equals(other: Any?): Boolean =
        other is ScheduleCatalogIdentity && manifest === other.manifest

    override fun hashCode(): Int = System.identityHashCode(manifest)
}

internal data class ScheduleCacheKey(
    val catalog: ScheduleCatalogIdentity,
    val sequence: Long,
    val generatedAt: Instant,
    val source: CatalogSource,
    val catalogCount: Int,
    val supplementalRevision: String,
    val observer: ObserverLocation?,
    val day: LocalDate,
    val zone: ZoneId,
)

/** Owned by the parent screen: survives tab navigation, but never retains a destroyed activity. */
internal class DayScheduleCache {
    private val values = LinkedHashMap<ScheduleCacheKey, DayPassSchedule>(4, 0.75f, true)
    private var activeKey: ScheduleCacheKey? = null

    /**
     * Reserve space before allocating a replacement day. Reuse only the orbit list from
     * the same manifest; keeping the old DayPassSchedule here would retain all its passes.
     */
    @Synchronized fun prepare(key: ScheduleCacheKey): DayPassSchedule? {
        if (activeKey?.catalog != key.catalog) values.clear()
        activeKey = key
        values[key]?.let { return it }

        val satellites = values.values.lastOrNull()?.satellites
        val capacity = if (key.catalogCount >= 40_000) 1 else 2
        while (values.size >= capacity) {
            val eldest = values.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        return satellites?.let {
            DayPassSchedule(key.day, key.zone, key.catalogCount, it, emptyList(), 0, emptySet())
                .also { seed -> values[key] = seed }
        }
    }

    @Synchronized fun get(key: ScheduleCacheKey): DayPassSchedule? = values[key]

    @Synchronized fun put(key: ScheduleCacheKey, value: DayPassSchedule) {
        // A canceled calculation cannot restore an old catalog/day after the next key starts.
        if (key != activeKey) return
        values[key] = value
    }

    @Synchronized fun clear() {
        values.clear()
        activeKey = null
    }
}
