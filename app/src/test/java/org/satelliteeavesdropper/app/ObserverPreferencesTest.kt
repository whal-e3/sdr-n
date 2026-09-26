package org.satelliteeavesdropper.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObserverPreferencesTest {
    @Test fun savedManualAndMapCoordinatesBecomeActiveAgain() {
        for (mode in listOf(LocationMode.MANUAL, LocationMode.MAP)) {
            val preference = ObserverPreferenceCodec.decode(
                mode.name, "37.241258941", "127.177904312", "126.5",
            )
            assertEquals(mode, preference.mode)
            assertEquals(37.241258941, preference.activeObserver!!.latitudeDegrees, 1e-12)
            assertEquals(127.177904312, preference.activeObserver!!.longitudeDegrees, 1e-12)
            assertEquals(126.5, preference.activeObserver!!.altitudeMeters, 1e-12)
        }
    }

    @Test fun automaticModeNeverUsesStoredUserCoordinatesAsCurrentFix() {
        val preference = ObserverPreferenceCodec.decode(
            LocationMode.AUTO.name, "37.241258941", "127.177904312", "0.0",
        )
        assertEquals(LocationMode.AUTO, preference.mode)
        assertNull(preference.activeObserver)
        assertEquals(37.241258941, preference.selectedObserver!!.latitudeDegrees, 1e-12)
    }

    @Test fun malformedStoredLocationIsNotUsedForPasses() {
        val preference = ObserverPreferenceCodec.decode(
            LocationMode.MANUAL.name, "91.0", "127.17", "0.0",
        )
        assertEquals(LocationMode.MANUAL, preference.mode)
        assertNull(preference.activeObserver)
        assertNull(ObserverPreferenceCodec.decode("unknown", "37.24", "127.17", null).activeObserver)
    }
}
