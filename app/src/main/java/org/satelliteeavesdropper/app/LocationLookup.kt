package org.satelliteeavesdropper.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.satelliteeavesdropper.orbit.ObserverLocation

// Android exposed this provider before LocationManager.FUSED_PROVIDER became a public constant
// in API 31. Check that it is enabled before requesting it on older phones.
private const val FUSED_PROVIDER_NAME = "fused"

/** A foreground-only stream. The app never uploads the observer's location. */
sealed interface DeviceLocationState {
    data object PermissionRequired : DeviceLocationState
    data object ServicesDisabled : DeviceLocationState
    data class Searching(val providers: List<String>, val takingLonger: Boolean = false) : DeviceLocationState
    data class Fix(
        val observer: ObserverLocation,
        val provider: String,
        val accuracyMeters: Float?,
        val ageSeconds: Long,
        /** Android monotonic timestamp, retained so a delayed USB grant cannot refresh an old fix. */
        val fixElapsedRealtimeMillis: Long,
        val fromRecentCache: Boolean,
    ) : DeviceLocationState
    data class Unavailable(val reason: String) : DeviceLocationState
}

/**
 * Watches the phone's usable GPS, network, and fused providers. A fused fix can arrive on phones
 * where the separate network provider is disabled while GPS is still searching.
 * Calling code should collect this only while the location screen is active, after requesting
 * foreground location permission. Android owns the GPS/network provider choice.
 */
fun observeDeviceLocation(context: Context): Flow<DeviceLocationState> = callbackFlow {
    val app = context.applicationContext
    val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var lastFixElapsedNanos = Long.MIN_VALUE
    var searchNotice: Job? = null
    var activeProviders = emptyList<String>()

    fun publishFix(location: Location, cached: Boolean) {
        // Some providers deliver a cached point as the first update.
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        if (ageMs !in 0..300_000) return
        val observer = runCatching {
            ObserverLocation(
                location.latitude,
                location.longitude,
                location.altitude.takeIf { location.hasAltitude() } ?: 0.0,
            )
        }.getOrNull() ?: return
        if (location.elapsedRealtimeNanos < lastFixElapsedNanos) return
        lastFixElapsedNanos = location.elapsedRealtimeNanos
        searchNotice?.cancel()
        trySend(
            DeviceLocationState.Fix(
                observer = observer,
                provider = location.provider ?: "device",
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                ageSeconds = ageMs / 1_000,
                fixElapsedRealtimeMillis = location.elapsedRealtimeNanos / 1_000_000,
                fromRecentCache = cached,
            ),
        )
        searchNotice = launch {
            delay(300_000 - ageMs)
            trySend(DeviceLocationState.Searching(activeProviders, takingLonger = true))
        }
    }

    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = publishFix(location, cached = false)
    }

    fun configureProviders() {
        searchNotice?.cancel()
        runCatching { manager.removeUpdates(listener) }
        activeProviders = emptyList()

        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            trySend(DeviceLocationState.PermissionRequired)
            return
        }
        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { fine && runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) },
            LocationManager.NETWORK_PROVIDER.takeIf { (fine || coarse) && runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) },
            FUSED_PROVIDER_NAME.takeIf { (fine || coarse) && runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) },
        )
        if (providers.isEmpty()) {
            trySend(DeviceLocationState.ServicesDisabled)
            return
        }

        val registered = providers.filter { provider ->
            runCatching {
                // A fixed receiver still needs fresh fixes. A distance threshold can suppress
                // every update while stationary, so the current observer would expire.
                manager.requestLocationUpdates(provider, 5_000L, 0f, listener, Looper.getMainLooper())
            }.isSuccess
        }
        if (registered.isEmpty()) {
            trySend(DeviceLocationState.Unavailable("Android could not start location updates"))
            return
        }
        activeProviders = registered
        trySend(DeviceLocationState.Searching(registered))

        val recent = registered.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { location ->
            // Monotonic time remains meaningful even if the phone's wall clock changes.
            val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
            ageMs in 0..300_000
        }.maxWithOrNull(compareBy<Location> { it.elapsedRealtimeNanos }.thenBy { -it.accuracy })
        if (recent != null) publishFix(recent, cached = true)
        else searchNotice = launch {
            delay(20_000)
            trySend(DeviceLocationState.Searching(registered, takingLonger = true))
        }
    }

    val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION ||
                intent.action == LocationManager.MODE_CHANGED_ACTION) configureProviders()
        }
    }
    val filter = IntentFilter().apply {
        addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        addAction(LocationManager.MODE_CHANGED_ACTION)
    }
    if (Build.VERSION.SDK_INT >= 33) {
        app.registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        app.registerReceiver(changes, filter)
    }
    configureProviders()
    awaitClose {
        searchNotice?.cancel()
        runCatching { manager.removeUpdates(listener) }
        app.unregisterReceiver(changes)
    }
}.flowOn(Dispatchers.Main.immediate)

/** Compatibility helper for the existing Locate button. New UI should collect the stream. */
suspend fun locateObserver(context: Context): ObserverLocation? = withTimeoutOrNull(30_000) {
    when (val result = observeDeviceLocation(context).first {
        it is DeviceLocationState.Fix ||
            it is DeviceLocationState.PermissionRequired ||
            it is DeviceLocationState.ServicesDisabled ||
            it is DeviceLocationState.Unavailable
    }) {
        is DeviceLocationState.Fix -> result.observer
        else -> null
    }
}
