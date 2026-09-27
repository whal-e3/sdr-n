package org.satelliteeavesdropper.app.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.satelliteeavesdropper.orbit.EARTH_RADIUS_KM
import org.satelliteeavesdropper.orbit.EarthFixedPosition
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.earthSurfacePosition

class GlobeGeometryTest {
    @Test fun cameraAxesAndInversePreserveTheEarthFixedPosition() {
        val position = EarthFixedPosition(6_500.0, -1_300.0, 3_400.0)
        for (latitude in listOf(-90.0, -37.0, 0.0, 37.0, 90.0)) {
            for (longitude in listOf(-180.0, -127.0, 0.0, 127.0, 180.0)) {
                val camera = GlobeCamera(latitude, longitude)
                val actual = camera.earthFixed(camera.project(position))
                assertEquals(position.xKm, actual.xKm, 1e-9)
                assertEquals(position.yKm, actual.yKm, 1e-9)
                assertEquals(position.zKm, actual.zKm, 1e-9)
            }
        }
        val equator = GlobeCamera(0.0, 0.0)
        assertEquals(6_500.0, equator.project(EarthFixedPosition(6_500.0, 0.0, 0.0)).z, 1e-9)
        assertEquals(6_500.0, equator.project(EarthFixedPosition(0.0, 6_500.0, 0.0)).x, 1e-9)
        assertEquals(6_500.0, equator.project(EarthFixedPosition(0.0, 0.0, 6_500.0)).y, 1e-9)
    }

    @Test fun earthOccludesRearMarkersButNotThoseOutsideTheLimb() {
        assertTrue(globePointVisible(GlobeCameraPoint(0.0, 0.0, 12.0), 10.0))
        assertFalse(globePointVisible(GlobeCameraPoint(0.0, 0.0, -12.0), 10.0))
        assertFalse(globePointVisible(GlobeCameraPoint(0.0, 0.0, 5.0), 10.0))
        assertTrue(globePointVisible(GlobeCameraPoint(12.0, 0.0, -12.0), 10.0))
        assertTrue(globePointVisible(GlobeCameraPoint(10.0, 0.0, -12.0), 10.0))
    }

    @Test fun rearOrbitSegmentIsSplitAtBothSidesOfTheEarthLimb() {
        val segments = visibleGlobeSegments(GlobeCameraPoint(-20.0, 0.0, -20.0), GlobeCameraPoint(20.0, 0.0, -20.0), 10.0)
        assertEquals(2, segments.size)
        assertEquals(-20.0, segments[0].first.x, 1e-9)
        assertEquals(-10.0, segments[0].second.x, 1e-9)
        assertEquals(10.0, segments[1].first.x, 1e-9)
        assertEquals(20.0, segments[1].second.x, 1e-9)
    }

    @Test fun frontOrbitEnteringTheEarthIsClippedAtTheSurface() {
        val segments = visibleGlobeSegments(GlobeCameraPoint(0.0, 0.0, 20.0), GlobeCameraPoint(0.0, 0.0, -20.0), 10.0)
        assertEquals(1, segments.size)
        assertEquals(20.0, segments[0].first.z, 1e-9)
        assertEquals(10.0, segments[0].second.z, 1e-9)
    }

    @Test fun groundTrackClipKeepsTheFrontChordAndStopsAtTheHorizon() {
        val segment = visibleGlobeSurfaceSegment(GlobeCameraPoint(2.0, 4.0, 5.0), GlobeCameraPoint(6.0, 8.0, -5.0))
        assertNotNull(segment)
        assertEquals(4.0, segment!!.second.x, 1e-9)
        assertEquals(6.0, segment.second.y, 1e-9)
        assertEquals(0.0, segment.second.z, 1e-9)
        assertNull(visibleGlobeSurfaceSegment(GlobeCameraPoint(0.0, 0.0, -1.0), GlobeCameraPoint(1.0, 0.0, -2.0)))
    }

    @Test fun polarObserverIsPlacedOnTheDisplaySurface() {
        val actual = earthSurfacePosition(ObserverLocation(90.0, 127.0))
        assertTrue(actual.radiusKm < EARTH_RADIUS_KM)
        val display = globeSurfacePosition(actual)
        assertEquals(EARTH_RADIUS_KM, display.radiusKm, 1e-9)
        assertTrue(globePointVisible(GlobeCamera(90.0, 127.0).project(display)))
    }

    @Test fun tapSelectionIgnoresHiddenMarkersAndUsesTheClosestVisibleMarker() {
        val markers = listOf(
            GlobeMarker("1", "Hidden", EarthFixedPosition(-7_000.0, 0.0, 0.0)),
            GlobeMarker("2", "Near", EarthFixedPosition(7_000.0, 0.0, 0.0)),
            GlobeMarker("3", "Offset", EarthFixedPosition(7_000.0, 50.0, 0.0)),
        )
        val projection = GlobeProjection(GlobeCamera(0.0, 0.0), 200.0, 200.0, 0.1)
        assertEquals("2", globeMarkerAt(markers, projection, 200.0, 200.0, 20.0))
        assertEquals("3", globeMarkerAt(markers, projection, 205.0, 200.0, 20.0))
        assertNull(globeMarkerAt(markers.take(1), projection, 200.0, 200.0, 20.0))
        assertNull(globeMarkerAt(markers, projection, 300.0, 300.0, 20.0))
    }

    @Test fun fitPreservesGeostationaryAltitudeInsteadOfCompressingIt() {
        val geostationary = EarthFixedPosition(42_164.0, 0.0, 0.0)
        val farArc = EarthFixedPosition(0.0, 43_000.0, 0.0)
        assertEquals(43_000.0 * 1.12, globeExtentKm(geostationary, listOf(farArc)), 1e-9)
        assertEquals(EARTH_RADIUS_KM * 1.28, globeExtentKm(null, emptyList()), 1e-9)
    }

    @Test fun stableFramingRoundsOutwardWithoutChangingTheAltitudeScale() {
        assertEquals(48_165.0, stableGlobeExtentKm(48_160.1), 1e-9)
        assertEquals(48_165.0, stableGlobeExtentKm(48_161.7), 1e-9)
        assertEquals(48_165.0, stableGlobeExtentKm(48_165.0), 1e-9)
        assertTrue(stableGlobeExtentKm(48_160.1) >= 48_160.1)
        assertTrue(stableGlobeExtentKm(48_160.1) - 48_160.1 < 5.0)
    }
}
