package org.satelliteeavesdropper.app

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.satelliteeavesdropper.app.data.CatalogLoadResult
import org.satelliteeavesdropper.app.data.CatalogParser
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.TransmitterRecord
import org.satelliteeavesdropper.app.day.DayPassSchedule
import org.satelliteeavesdropper.app.day.DayScheduleCache
import org.satelliteeavesdropper.app.day.DayScheduleScreen
import org.satelliteeavesdropper.app.day.ScheduleCacheKey
import org.satelliteeavesdropper.app.day.ScheduleCatalogIdentity
import org.satelliteeavesdropper.app.day.ScheduledPass
import org.satelliteeavesdropper.app.day.TrackableSatellite
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Instant
import java.time.ZoneId

/**
 * Taps the real day-screen controls against four explicit synthetic records and cached passes.
 * Tests classification and browsing only: these passes are not propagation or reception evidence.
 * The fixture never replaces the installed catalog, imports an orbit, or starts an SDR stream.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalComposeUiApi::class)
class DecoderFilterInstrumentedTest {
    @Test fun satelliteFilterUsesDecoderSupportAndShowsDistinctCardBadges() = withFixture { ui ->
        ui.assertGroups("View", "Decoder support", "Pass time")
        ui.assertChecked("Passes")
        ui.assertChecked("Decodable")
        ui.tap("Satellites")
        ui.assertRecords(SUPPORTED_NAMES)

        ui.tap("All satellites")
        ui.assertChecked("All satellites")
        ui.assertRecords(ALL_NAMES)
        BADGES.forEach { (name, badge) -> ui.assertCardBadge(name, badge) }
        ui.assertNoOldControls()

        ui.tap("Decodable")
        ui.assertChecked("Decodable")
        ui.assertRecords(SUPPORTED_NAMES)
    }

    @Test fun passTimeAndDecoderFiltersRemainIndependentAcrossViewChanges() = withFixture { ui ->
        ui.tap("All satellites")
        ui.tap("Whole day")
        ui.assertChecked("Whole day")
        ui.assertRecords(ALL_NAMES)
        BADGES.forEach { (name, badge) -> ui.assertCardBadge(name, badge) }

        ui.tap("Now & next")
        ui.assertChecked("Now & next")
        ui.assertRecords(setOf(FM_NAME, PACKET_NAME, ORBIT_NAME))
        ui.tap("Above horizon now")
        ui.assertChecked("Above horizon now")
        ui.assertRecords(setOf(FM_NAME))

        ui.tap("Whole day")
        ui.tap("Decodable")
        ui.assertRecords(SUPPORTED_NAMES)
        ui.tap("Satellites")
        ui.assertChecked("Decodable")
        ui.assertRecords(SUPPORTED_NAMES)
        ui.tap("Passes")
        ui.assertChecked("Decodable")
        ui.assertChecked("Whole day")
        ui.assertRecords(SUPPORTED_NAMES)
        ui.assertGroups("View", "Decoder support", "Pass time")
        ui.assertNoOldControls()
    }

    @Test fun futureDateDisablesCurrentHorizonAndResetsItsSelection() = withFixture { ui ->
        ui.tap("All satellites")
        ui.tap("Above horizon now")
        ui.assertChecked("Above horizon now")
        ui.assertRecords(setOf(FM_NAME))

        ui.tap("Next day")
        ui.label("Tomorrow")
        ui.assertDisabled("Above horizon now")
        ui.assertChecked("Whole day")
        ui.assertUnchecked("Above horizon now")
        ui.assertRecords(ALL_NAMES)

        // A touch on the disabled chip must not create an empty current-horizon future view.
        ui.tap("Above horizon now", allowDisabled = true)
        ui.assertChecked("Whole day")
        ui.assertRecords(ALL_NAMES)
        ui.tap("Now & next")
        ui.assertChecked("Now & next")
        ui.assertRecords(ALL_NAMES)

        ui.tap("Previous day")
        ui.label("Today")
        ui.assertEnabled("Above horizon now")
        ui.tap("Above horizon now")
        ui.assertRecords(setOf(FM_NAME))
    }

    private fun withFixture(test: (Ui) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = ObserverPreferences(instrumentation.targetContext)
        val previousMode = preferences.load().mode
        var activity: MainActivity? = null
        try {
            // Prevent the normal Activity's initial composition from requesting a GPS fix.
            assertTrue(preferences.saveMode(LocationMode.MANUAL))
            val fixture = Fixture()
            activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            val current = activity
            instrumentation.runOnMainSync {
                current.setContent {
                    OrbitTheme {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            DayScheduleScreen(
                                catalog = fixture.catalog,
                                observer = fixture.observer,
                                onSatelliteSelected = {},
                                scheduleCache = fixture.cache,
                                modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(LIST_TAG),
                            )
                        }
                    }
                }
            }
            val ui = Ui()
            ui.awaitFixture()
            ui.label("Today")
            test(ui)
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            assertTrue(preferences.saveMode(previousMode))
        }
    }

    private class Fixture {
        private val now = Instant.now()
        private val zone = ZoneId.systemDefault()
        private val today = now.atZone(zone).toLocalDate()
        val observer = ObserverLocation(37.2411, 127.1776)
        private val sample = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("sample_catalog.json").bufferedReader().use(CatalogParser::parse)
        private val iss = sample.satellites.first { it.noradId == "25544" }
        private val lrpt = sample.satellites.first { record ->
            record.transmitters.any { it.decoderId == "METEOR_LRPT_80K" }
        }
        private val records = listOf(
            copyRecord(iss, "91001", FM_NAME, iss.transmitters.filter { it.decoderId == "AUDIO_NFM" }),
            copyRecord(iss, "91002", PACKET_NAME, iss.transmitters.filter { it.decoderId == "AX25_AFSK1200" }),
            copyRecord(lrpt, "91003", LRPT_NAME, lrpt.transmitters),
            copyRecord(iss, "91004", ORBIT_NAME, emptyList()),
        )
        val catalog = CatalogLoadResult(
            sample.copy(generatedAt = now, sourceUpdatedAt = now, satellites = records),
            CatalogSource.DEMO,
        )
        val cache = DayScheduleCache()

        init {
            val satellites = records.mapIndexed { index, record ->
                TrackableSatellite(record, requireNotNull(record.orbitElements()), index)
            }
            // Seed tomorrow first, then today, so the cache's active key matches the first render.
            // Fresh timestamps follow the actual device day rather than a hard-coded test date.
            for (offset in listOf(1L, 0L)) {
                val day = today.plusDays(offset)
                val key = ScheduleCacheKey(
                    ScheduleCatalogIdentity(catalog.manifest), catalog.manifest.sequence,
                    catalog.manifest.generatedAt, catalog.source, records.size, "", observer, day, zone,
                )
                val entries = if (offset == 0L) listOf(
                    pass(satellites[0], now.minusSeconds(600), now.plusSeconds(7200)),
                    pass(satellites[1], now.plusSeconds(7200), now.plusSeconds(7800)),
                    pass(satellites[2], now.minusSeconds(7800), now.minusSeconds(7200)),
                    pass(satellites[3], now.plusSeconds(3600), now.plusSeconds(4200)),
                ) else satellites.mapIndexed { index, satellite ->
                    val start = day.atStartOfDay(zone).toInstant().plusSeconds(36000L + index * 1200L)
                    pass(satellite, start, start.plusSeconds(600))
                }
                cache.prepare(key)
                cache.put(key, DayPassSchedule(
                    day, zone, records.size, satellites, entries.sortedBy { it.pass.aos },
                    satellites.size, emptySet(),
                ))
            }
        }

        private fun copyRecord(
            source: SatelliteRecord,
            id: String,
            name: String,
            transmitters: List<TransmitterRecord>,
        ): SatelliteRecord = source.copy(
            noradId = id, name = name, aliases = emptyList(), transmitters = transmitters,
            omm = JSONObject(source.omm.toString()).apply {
                put("NORAD_CAT_ID", id)
                put("OBJECT_NAME", name)
                put("EPOCH", now.toString())
            },
        )

        private fun pass(satellite: TrackableSatellite, start: Instant, end: Instant) = ScheduledPass(
            satellite,
            SatellitePass(start, start.plusSeconds((end.epochSecond - start.epochSecond) / 2), end,
                45.0, 10.0, 190.0, beganBeforeWindow = false, endsAfterWindow = false),
        )
    }

    private class Ui {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()

        fun awaitFixture() {
            val deadline = SystemClock.elapsedRealtime() + 12_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (find(root()) { matches(it, "4 / 4 orbits · 4 passes") } != null) {
                    settle()
                    return
                }
                SystemClock.sleep(100)
            }
            throw AssertionError("Synthetic day-screen fixture did not render\n${describe(root())}")
        }

        fun assertGroups(vararg labels: String) = labels.forEach { label(it) }

        fun assertChecked(text: String) {
            val node = control(text)
            assertTrue("Filter '$text' should be chosen", node.isChecked || node.isSelected)
        }

        fun assertUnchecked(text: String) {
            val node = control(text)
            assertFalse("Filter '$text' should not be chosen", node.isChecked || node.isSelected)
        }

        fun assertDisabled(text: String) = assertFalse("'$text' should be disabled", control(text).isEnabled)
        fun assertEnabled(text: String) = assertTrue("'$text' should be enabled", control(text).isEnabled)

        /** Scan the complete small LazyColumn; offscreen excluded cards cannot pass the assertion. */
        fun assertRecords(expected: Set<String>) {
            val found = linkedSetOf<String>()
            scanPage { root ->
                ALL_NAMES.filterTo(found) { name -> find(root) { matches(it, name) } != null }
            }
            assertEquals("Visible records after filtering the complete list", expected, found)
        }

        fun assertCardBadge(name: String, badge: String) {
            // Bring the badge itself into the viewport, then reacquire its named card from
            // one fresh tree. title.parent previously returned an old partially clipped card
            // even though the refreshed title had already moved to a different screen position.
            label(badge)
            val noradLabel = "NORAD ${NORAD_IDS.getValue(name)}"
            val deadline = SystemClock.elapsedRealtime() + 4_000
            var lastTree: AccessibilityNodeInfo? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val tree = root()
                lastTree = tree
                val viewport = find(tree) { it.viewIdResourceName == LIST_TAG }?.let(::bounds)
                val card = find(tree) { candidate ->
                    candidate.isClickable && !candidate.isCheckable &&
                        find(candidate, visibleOnly = false) {
                            matches(it, name) || matches(it, noradLabel)
                        } != null
                }
                if (viewport != null && card != null && card.refresh()) {
                    // A partially scrolled card can hide its title while its NORAD ID remains
                    // visible. Either unique fixture identity proves the same-card association.
                    val identity = find(card, visibleOnly = false) {
                        matches(it, name) || matches(it, noradLabel)
                    }
                    val output = find(card) { matches(it, badge) }
                    val cardBounds = bounds(card)
                    if (identity != null && output != null && viewport.contains(bounds(output)) &&
                        cardBounds.contains(bounds(output)) &&
                        (!identity.isVisibleToUser || cardBounds.contains(bounds(identity)))) return
                }
                SystemClock.sleep(100)
            }
            throw AssertionError("Visible card '$name' lacks its exact badge '$badge'\n${describe(lastTree)}")
        }

        fun assertNoOldControls() = scanPage { root ->
            OLD_CONTROLS.forEach { text ->
                assertTrue("Confusing former filter '$text' is still present",
                    find(root) { matches(it, text) } == null)
            }
        }

        fun tap(text: String, allowDisabled: Boolean = false) {
            val node = control(text)
            assertTrue("Cannot tap disabled '$text'", allowDisabled || node.isEnabled)
            val rectangle = bounds(node)
            val downTime = SystemClock.uptimeMillis()
            fun event(action: Int): MotionEvent = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply {
                    x = rectangle.exactCenterX(); y = rectangle.exactCenterY(); pressure = 1f; size = 1f
                }), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val motion = event(action)
                try { assertTrue("Touch injection failed", instrumentation.uiAutomation.injectInputEvent(motion, true)) }
                finally { motion.recycle() }
                SystemClock.sleep(60)
            }
            settle()
        }

        fun label(text: String): AccessibilityNodeInfo = seek(text)

        private fun seek(text: String, requireChip: Boolean = false): AccessibilityNodeInfo {
            scrollToTop()
            val deadline = SystemClock.elapsedRealtime() + 12_000
            var steps = 0
            var previousBounds: Rect? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val node = find(root()) { matches(it, text) && (!requireChip || touchTarget(it).isCheckable) }
                if (node != null && !bounds(node).isEmpty) {
                    val target = touchTarget(node)
                    // Use text bounds for reading a card title, and the complete chip/button
                    // bounds for touches. An offscreen ancestor can still expose a visible label.
                    val rectangle = bounds(if (requireChip || text in DATE_BUTTONS) target else node)
                    if (bounds(page()).contains(rectangle)) {
                        if (rectangle == previousBounds) return node
                        previousBounds = rectangle
                        SystemClock.sleep(150)
                        continue
                    }
                }
                previousBounds = null
                if (++steps <= 20 && scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) continue
                SystemClock.sleep(100)
            }
            throw AssertionError("Missing or clipped '$text'\n${describe(root())}")
        }

        private fun control(text: String): AccessibilityNodeInfo =
            touchTarget(seek(text, requireChip = text in FILTER_LABELS))

        private fun touchTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if ((current.isClickable || current.isCheckable) && !bounds(current).isEmpty) return current
                current = current.parent
            }
            return node
        }

        private fun scanPage(inspect: (AccessibilityNodeInfo?) -> Unit) {
            scrollToTop()
            repeat(24) {
                inspect(root())
                if (!scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
            }
            throw AssertionError("Fixture list did not reach the end within 24 scrolls")
        }

        private fun scrollToTop() {
            val list = page()
            val toPosition = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id
            if (list.actionList.any { it.id == toPosition }) {
                list.performAction(toPosition, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
                })
                settle()
            }
            repeat(24) {
                if (dateHeaderVisible()) return
                if (!scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    throw AssertionError("Scroll stopped before the date header reached the safe viewport\n${describe(root())}")
                }
            }
            throw AssertionError("Fixture list did not reach the start within 24 scrolls")
        }

        private fun dateHeaderVisible(): Boolean {
            val heading = find(root()) { matches(it, "Today") || matches(it, "Tomorrow") } ?: return false
            val next = find(root()) { matches(it, "Next day") } ?: return false
            val viewport = bounds(page())
            return viewport.contains(bounds(heading)) && viewport.contains(bounds(touchTarget(next)))
        }

        private fun scroll(action: Int): Boolean {
            val before = describe(root())
            val list = page()
            if (list.actionList.none { it.id == action } || !list.performAction(action)) return false
            // Accessibility scroll actions launch a Compose coroutine. The accepted action
            // precedes both scrolling and the accessibility-tree update; an immediate snapshot
            // falsely looked unchanged and truncated the previous scans after their first card.
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
                if (before != describe(root())) {
                    settle()
                    return true
                }
            }
            return false
        }

        private fun settle() {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
        }

        private fun page(): AccessibilityNodeInfo {
            // A LazyColumn can report isScrollable=false at a boundary. Always address the
            // actual list rather than substituting the non-scrollable active-window root.
            return find(root()) { it.viewIdResourceName == LIST_TAG }
                ?: throw AssertionError("Tagged day schedule is missing\n${describe(root())}")
        }

        private fun root(): AccessibilityNodeInfo? {
            // refresh() updates one node, while LazyColumn child IDs and parent snapshots can
            // remain cached from before a scroll. API 34 added the public whole-cache reset.
            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            return instrumentation.uiAutomation.rootInActiveWindow?.also { it.refresh() }
        }

        private fun find(
            node: AccessibilityNodeInfo?,
            visibleOnly: Boolean = true,
            predicate: (AccessibilityNodeInfo) -> Boolean,
        ): AccessibilityNodeInfo? {
            if (node == null) return null
            node.refresh()
            if ((!visibleOnly || node.isVisibleToUser) && predicate(node)) return node
            for (index in 0 until node.childCount) {
                find(node.getChild(index), visibleOnly, predicate)?.let { return it }
            }
            return null
        }

        private fun matches(node: AccessibilityNodeInfo, text: String): Boolean =
            listOf(node.text, node.contentDescription).any { value ->
                value?.toString()?.split('\n', '\r')?.any { it.trim() == text } == true
            }

        private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

        private fun describe(node: AccessibilityNodeInfo?): String = buildString {
            var count = 0
            fun visit(current: AccessibilityNodeInfo?) {
                if (current == null || count++ > 100) return
                append("${current.text ?: current.contentDescription} ")
                append("[visible=${current.isVisibleToUser}, clickable=${current.isClickable}, ")
                append("checked=${current.isChecked}, enabled=${current.isEnabled}, bounds=${bounds(current)}]\n")
                for (index in 0 until current.childCount) visit(current.getChild(index))
            }
            visit(node)
        }
    }

    private companion object {
        const val FM_NAME = "Fixture FM audio"
        const val PACKET_NAME = "Fixture AX.25 packets"
        const val LRPT_NAME = "Fixture unsupported LRPT"
        const val ORBIT_NAME = "Fixture orbit without radio metadata"
        val SUPPORTED_NAMES = setOf(FM_NAME, PACKET_NAME)
        val ALL_NAMES = SUPPORTED_NAMES + setOf(LRPT_NAME, ORBIT_NAME)
        val NORAD_IDS = mapOf(FM_NAME to "91001", PACKET_NAME to "91002",
            LRPT_NAME to "91003", ORBIT_NAME to "91004")
        val BADGES = mapOf(
            FM_NAME to "Decodable · FM audio",
            PACKET_NAME to "Decodable · AX.25 packets",
            LRPT_NAME to "Tracking only · No supported downlink",
            ORBIT_NAME to "Tracking only · No radio metadata",
        )
        val OLD_CONTROLS = setOf("Upcoming", "Day passes", "In sight", "All", "Downlinks", "Radio profiles", "All orbits")
        val FILTER_LABELS = setOf("Passes", "Satellites", "Decodable", "All satellites",
            "Now & next", "Whole day", "Above horizon now")
        val DATE_BUTTONS = setOf("Previous day", "Next day")
        const val LIST_TAG = "decoder-filter-fixture-list"
    }
}
