package org.satelliteeavesdropper.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.satelliteeavesdropper.orbit.ObserverLocation

class LocationUiLogicTest {
    @Test fun nearbyFreshFixUpdatesActiveObserverWithoutRestartingDaySchedule() {
        val previous = ObserverLocation(35.12345, 128.54321)
        val latest = ObserverLocation(35.12400, 128.54321) // About 61 m north.

        val update = automaticObserverUpdate(previous, latest)

        assertSame(latest, update.active)
        assertSame(previous, update.daySchedule)
    }

    @Test fun dayScheduleFollowsMeaningfulMovement() {
        val previous = ObserverLocation(35.12345, 128.54321)
        val latest = ObserverLocation(35.12500, 128.54321) // About 172 m north.

        val update = automaticObserverUpdate(previous, latest)

        assertSame(latest, update.active)
        assertSame(latest, update.daySchedule)
        assertSame(latest, automaticObserverUpdate(null, latest).daySchedule)
    }

    @Test fun permissionActionKeepsNormalRetryAndOffersSettingsAfterPermanentDenial() {
        assertEquals(LocationPermissionAction.REQUEST, locationPermissionAction(false, false, false))
        assertEquals(LocationPermissionAction.REQUEST, locationPermissionAction(true, false, true))
        assertEquals(LocationPermissionAction.WAIT, locationPermissionAction(true, true, false))
        assertEquals(LocationPermissionAction.SETTINGS, locationPermissionAction(true, false, false))
    }
}
