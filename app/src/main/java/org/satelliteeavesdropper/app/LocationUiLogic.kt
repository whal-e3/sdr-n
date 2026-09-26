package org.satelliteeavesdropper.app

import org.satelliteeavesdropper.orbit.ObserverLocation
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val DAY_SCHEDULE_MOVEMENT_METERS = 100.0
private const val EARTH_RADIUS_METERS = 6_371_000.0

internal data class AutomaticObserverUpdate(
    val active: ObserverLocation,
    val daySchedule: ObserverLocation,
)

/** Every fix is active; the full-catalog day schedule only restarts after about 100 m of movement. */
internal fun automaticObserverUpdate(
    previousDaySchedule: ObserverLocation?,
    latest: ObserverLocation,
): AutomaticObserverUpdate {
    if (previousDaySchedule == null) return AutomaticObserverUpdate(latest, latest)
    val latitudeDelta = Math.toRadians(latest.latitudeDegrees - previousDaySchedule.latitudeDegrees)
    val longitudeDelta = Math.toRadians(latest.longitudeDegrees - previousDaySchedule.longitudeDegrees)
    val halfChord = sin(latitudeDelta / 2).let { it * it } +
        cos(Math.toRadians(previousDaySchedule.latitudeDegrees)) * cos(Math.toRadians(latest.latitudeDegrees)) *
        sin(longitudeDelta / 2).let { it * it }
    val surfaceMeters = 2 * EARTH_RADIUS_METERS * asin(sqrt(halfChord.coerceIn(0.0, 1.0)))
    val movedMeters = hypot(surfaceMeters, latest.altitudeMeters - previousDaySchedule.altitudeMeters)
    val schedule = if (movedMeters >= DAY_SCHEDULE_MOVEMENT_METERS) latest else previousDaySchedule
    return AutomaticObserverUpdate(latest, schedule)
}

internal enum class LocationPermissionAction { REQUEST, WAIT, SETTINGS }

/** Before the first request Android also reports no rationale, so request history matters. */
internal fun locationPermissionAction(
    requestedBefore: Boolean,
    requestInFlight: Boolean,
    shouldShowRationale: Boolean,
): LocationPermissionAction = when {
    requestInFlight -> LocationPermissionAction.WAIT
    requestedBefore && !shouldShowRationale -> LocationPermissionAction.SETTINGS
    else -> LocationPermissionAction.REQUEST
}
