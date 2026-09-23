package org.satelliteeavesdropper.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.satelliteeavesdropper.orbit.ObserverLocation
import kotlin.coroutines.resume

/** Location is requested only when the user taps Locate; it is never uploaded. */
suspend fun locateObserver(context: Context): ObserverLocation? {
    val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return null
    val fix = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        ?.takeIf { System.currentTimeMillis() - it.time < 5 * 60_000 }
        ?: withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                if (Build.VERSION.SDK_INT >= 30) {
                    manager.getCurrentLocation(provider, null, context.mainExecutor) { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (continuation.isActive) continuation.resume(location)
                        }
                    }
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                }
            }
        }
    return fix?.let { ObserverLocation(it.latitude, it.longitude, it.altitude) }
}
