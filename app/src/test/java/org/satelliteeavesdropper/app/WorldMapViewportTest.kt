package org.satelliteeavesdropper.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WorldMapViewportTest {
    @Test fun screenCoordinatesMapToLatitudeAndLongitude() {
        val map = WorldMapViewport()
        val center = map.coordinateAt(180.0, 90.0, 360.0, 180.0)
        assertEquals(0.0, center.latitudeDegrees, 1e-9)
        assertEquals(0.0, center.longitudeDegrees, 1e-9)

        val northeast = map.coordinateAt(270.0, 45.0, 360.0, 180.0)
        assertEquals(45.0, northeast.latitudeDegrees, 1e-9)
        assertEquals(90.0, northeast.longitudeDegrees, 1e-9)
    }

    @Test fun panningEastMovesTheCenterWest() {
        val map = WorldMapViewport(zoom = 2.0)
        val dragged = map.transformed(180.0, 90.0, 36.0, 0.0, 1.0, 360.0, 180.0)
        assertEquals(-18.0, dragged.centerLongitude, 1e-9)
    }

    @Test fun pinchZoomKeepsTheGeographicPointUnderTheFingers() {
        val map = WorldMapViewport(centerLatitude = 20.0, centerLongitude = 100.0, zoom = 2.0)
        val before = map.coordinateAt(250.0, 80.0, 360.0, 180.0)
        val zoomed = map.transformed(250.0, 80.0, 0.0, 0.0, 2.0, 360.0, 180.0)
        val after = zoomed.coordinateAt(250.0, 80.0, 360.0, 180.0)
        assertEquals(before.latitudeDegrees, after.latitudeDegrees, 1e-9)
        assertEquals(before.longitudeDegrees, after.longitudeDegrees, 1e-9)
    }

    @Test fun longitudeWrapsAcrossTheDateline() {
        val map = WorldMapViewport(centerLongitude = 170.0, zoom = 2.0)
        val east = map.coordinateAt(220.0, 90.0, 360.0, 180.0)
        assertEquals(-170.0, east.longitudeDegrees, 1e-9)
    }
}
