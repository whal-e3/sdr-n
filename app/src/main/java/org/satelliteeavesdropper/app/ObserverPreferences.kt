package org.satelliteeavesdropper.app

import android.content.Context
import org.satelliteeavesdropper.orbit.ObserverLocation

internal enum class LocationMode { AUTO, MAP, MANUAL }

/** A user-selected observer is retained for map/manual use, but never treated as a GPS fix. */
internal data class ObserverPreference(
    val mode: LocationMode,
    val selectedObserver: ObserverLocation?,
) {
    val activeObserver: ObserverLocation?
        get() = selectedObserver.takeIf { mode != LocationMode.AUTO }
}

internal object ObserverPreferenceCodec {
    fun decode(mode: String?, latitude: String?, longitude: String?, altitude: String?): ObserverPreference {
        val savedMode = LocationMode.entries.firstOrNull { it.name == mode } ?: LocationMode.AUTO
        val selected = runCatching {
            ObserverLocation(
                latitude?.toDoubleOrNull() ?: return@runCatching null,
                longitude?.toDoubleOrNull() ?: return@runCatching null,
                altitude?.toDoubleOrNull() ?: 0.0,
            )
        }.getOrNull()
        return ObserverPreference(savedMode, selected)
    }
}

internal class ObserverPreferences(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences("observer_selection", Context.MODE_PRIVATE)

    fun load(): ObserverPreference = ObserverPreferenceCodec.decode(
        storage.getString("mode", null),
        storage.getString("latitude", null),
        storage.getString("longitude", null),
        storage.getString("altitude", null),
    )

    /** Explicit user actions are small; commit ensures a prompt process stop cannot lose them. */
    fun saveMode(mode: LocationMode): Boolean =
        storage.edit().putString("mode", mode.name).commit()

    fun saveUserSelection(mode: LocationMode, location: ObserverLocation): Boolean {
        require(mode != LocationMode.AUTO)
        return storage.edit()
            .putString("mode", mode.name)
            .putString("latitude", location.latitudeDegrees.toString())
            .putString("longitude", location.longitudeDegrees.toString())
            .putString("altitude", location.altitudeMeters.toString())
            .commit()
    }
}
