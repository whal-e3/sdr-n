package org.satelliteeavesdropper.app.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.satelliteeavesdropper.orbit.ObserverLocation

class LiveObserverSessionTest {
    private val start = ObserverLocation(37.2411, 127.1776)
    private val moved = ObserverLocation(37.2511, 127.1876)

    @Test fun matchingFreshAutoFixChangesOnlyObserverAndRecoversAfterPause() {
        val session = LiveObserverSession("session-a", "25544", true, start, 98_000, 100_000)
        assertEquals(ObserverFeedState.AUTO_WAITING, session.current(100_000).feedState)
        assertEquals(2L, session.current(100_000).fixAgeSeconds)

        assertFalse(session.update("session-old", "25544", moved, 101_000, 101_000))
        assertFalse(session.update("session-a", "33591", moved, 101_000, 101_000))
        assertEquals(start, session.current(101_000).location)

        assertTrue(session.update("session-a", "25544", moved, 101_000, 102_000))
        assertEquals(moved, session.current(102_000).location)
        assertEquals(ObserverFeedState.AUTO_FOLLOWING, session.current(102_000).feedState)
        assertEquals(1L, session.current(102_000).fixAgeSeconds)

        assertFalse(session.pause("session-old", "25544", unavailable = false))
        assertTrue(session.pause("session-a", "25544", unavailable = false))
        assertEquals(ObserverFeedState.AUTO_PAUSED, session.current(103_000).feedState)
        assertEquals(moved, session.current(103_000).location)
        assertFalse(session.update("session-a", "25544", start, 101_000, 103_000))
        assertEquals(ObserverFeedState.AUTO_PAUSED, session.current(103_000).feedState)

        assertTrue(session.update("session-a", "25544", start, 104_000, 104_000))
        assertEquals(start, session.current(104_000).location)
        assertEquals(ObserverFeedState.AUTO_FOLLOWING, session.current(104_000).feedState)
        assertEquals(ObserverFeedState.AUTO_STALE, session.current(405_000).feedState)
    }

    @Test fun staleOrOutOfOrderFixCannotRetuneAndManualModeCannotFollowGps() {
        val auto = LiveObserverSession("session-a", "25544", true, start, 1_000_000, 1_000_000)
        assertFalse(auto.update("session-a", "25544", moved, 600_000, 1_001_000))
        assertTrue(auto.update("session-a", "25544", moved, 1_002_000, 1_002_000))
        assertFalse(auto.update("session-a", "25544", start, 999_000, 1_003_000))
        assertEquals(moved, auto.current(1_003_000).location)
        assertTrue(auto.pause("session-a", "25544", unavailable = true))
        assertEquals(ObserverFeedState.AUTO_UNAVAILABLE, auto.current(1_003_000).feedState)

        val manual = LiveObserverSession("session-m", "25544", false, start, null, 1_000_000)
        assertFalse(manual.update("session-m", "25544", moved, 1_002_000, 1_002_000))
        assertFalse(manual.pause("session-m", "25544", unavailable = true))
        assertEquals(start, manual.current(1_405_000).location)
        assertEquals(ObserverFeedState.FIXED, manual.current(1_405_000).feedState)
    }

    @Test fun finishedSessionCannotClearOrUpdateItsReplacement() {
        val old = LiveObserverSession("old", "25544", true, start, 100_000, 100_000)
        val replacement = LiveObserverSession("new", "33591", true, start, 100_000, 100_000)
        try {
            ReceptionState.setActivityVisible(true)
            ReceptionState.beginObserverSession(old)
            ReceptionState.beginObserverSession(replacement)
            ReceptionState.endObserverSession(old)
            assertFalse(ReceptionState.updateObserver("old", "25544", moved, 101_000, 101_000))
            assertFalse(ReceptionState.updateObserver("new", "25544", moved, 101_000, 101_000))
            assertTrue(ReceptionState.updateObserver("new", "33591", moved, 101_000, 101_000))
            ReceptionState.setActivityVisible(false)
            assertEquals(ObserverFeedState.AUTO_PAUSED, replacement.current(101_000).feedState)
            assertEquals(moved, replacement.current(101_000).location)

            val startedWhileHidden = LiveObserverSession("hidden", "99999", true, start, 101_000, 101_000)
            ReceptionState.beginObserverSession(startedWhileHidden)
            assertEquals(ObserverFeedState.AUTO_PAUSED, startedWhileHidden.current(101_000).feedState)
            assertFalse(ReceptionState.updateObserver("hidden", "99999", moved, 102_000, 102_000))
            ReceptionState.endObserverSession(startedWhileHidden)
        } finally {
            ReceptionState.setActivityVisible(false)
            ReceptionState.endObserverSession(replacement)
            ReceptionState.endObserverSession(old)
        }
    }
}
