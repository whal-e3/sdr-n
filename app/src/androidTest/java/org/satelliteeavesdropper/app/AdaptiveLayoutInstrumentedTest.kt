package org.satelliteeavesdropper.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature as TestFoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Injects real WindowManager updates; the app uses its normal Activity, UI and click handlers. */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutInstrumentedTest {
    @get:Rule val publisher = WindowLayoutInfoPublisherRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun verticalSeparatingCreaseKeepsHeaderNavigationAndAboutClear() = withActivity { activity ->
        val hinge = publishFold(activity, FoldingFeature.Orientation.VERTICAL, size = 0)
        waitUntil("About clear of vertical hinge $hinge") { controlClear("About", hinge, vertical = true) }
        listOf("Sky" to "Space-Track guide", "Target" to "Track & tune",
            "Location" to "Observer location", "Signal" to "Signal lab").forEach { (tab, heading) ->
            val navigation = waitForNavigation(tab)
            assertClear(bounds(navigation), hinge, vertical = true, "Navigation $tab")
            tap(navigation)
            waitFor(heading)
            assertClear(bounds(waitFor("About", clickable = true)), hinge, true, "About button")
        }
        tap(waitFor("About", clickable = true))
        waitFor("About OrbitScope")
        assertVisibleDialogTextClear(hinge, vertical = true)
        tap(waitFor("Close", clickable = true))
        waitFor("Signal lab")
    }

    @Test fun liveTabletopAndBookUpdatesRetainSignalChoiceAndMoveOpenDialog() = withActivity { activity ->
        tap(waitForNavigation("Signal"))
        waitFor("Signal lab")
        tap(waitFor("Test tone", clickable = true))
        waitFor("GENERATED TEST TONE")

        val verticalHinge = publishFold(activity, FoldingFeature.Orientation.VERTICAL, size = 20)
        waitUntil("About clear of book hinge $verticalHinge") { controlClear("About", verticalHinge, vertical = true) }
        assertTrue("Signal tab lost during book posture", waitForNavigation("Signal").isSelected)
        tap(waitFor("About", clickable = true))
        waitFor("About OrbitScope")
        assertVisibleDialogTextClear(verticalHinge, vertical = true)

        val horizontalHinge = publishFold(activity, FoldingFeature.Orientation.HORIZONTAL, size = 20)
        waitUntil("Dialog clear of tabletop hinge $horizontalHinge") { controlClear("Close", horizontalHinge, vertical = false) &&
            controlClear("About OrbitScope", horizontalHinge, vertical = false, clickable = false) }
        assertVisibleDialogTextClear(horizontalHinge, vertical = false)
        tap(waitFor("Close", clickable = true))
        waitUntil("About clear of tabletop hinge $horizontalHinge") { controlClear("About", horizontalHinge, vertical = false) }
        listOf("Sky", "Target", "Signal", "Location").forEach { tab ->
            assertClear(bounds(waitForNavigation(tab)), horizontalHinge, false, "Navigation $tab")
        }
        assertTrue("Signal tab lost during tabletop posture", waitForNavigation("Signal").isSelected)
        publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
        waitFor("GENERATED TEST TONE")
        assertTrue("Test-tone source lost after unfolding", hasCheckedState(waitFor("Test tone", clickable = true)))
    }

    private fun withActivity(test: (MainActivity) -> Unit) {
        val preferences = ObserverPreferences(instrumentation.targetContext)
        val previousMode = preferences.load().mode
        var activity: MainActivity? = null
        try {
            assertTrue(preferences.saveMode(LocationMode.MANUAL))
            activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            waitFor("About", clickable = true)
            test(activity)
        } finally {
            publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            assertTrue(preferences.saveMode(previousMode))
        }
    }

    private fun publishFold(activity: MainActivity, orientation: FoldingFeature.Orientation, size: Int): Rect {
        var screenBounds: Rect? = null
        instrumentation.runOnMainSync {
            val feature = TestFoldingFeature(activity = activity, state = FoldingFeature.State.HALF_OPENED,
                orientation = orientation, size = size)
            val screenOrigin = IntArray(2)
            val windowOrigin = IntArray(2)
            activity.window.decorView.getLocationOnScreen(screenOrigin)
            activity.window.decorView.getLocationInWindow(windowOrigin)
            screenBounds = Rect(feature.bounds).apply {
                offset(screenOrigin[0] - windowOrigin[0], screenOrigin[1] - windowOrigin[1])
            }
            publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(feature)))
        }
        return checkNotNull(screenBounds)
    }

    private fun assertVisibleDialogTextClear(hinge: Rect, vertical: Boolean) {
        fun allVisibleTextClear(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return true
            if (node.isVisibleToUser && !node.text.isNullOrBlank()) {
                val rectangle = bounds(node)
                if (!rectangle.isEmpty && !isClear(rectangle, hinge, vertical)) return false
            }
            for (index in 0 until node.childCount) {
                if (!allVisibleTextClear(node.getChild(index))) return false
            }
            return true
        }
        // A newly opened dialog can initially span the window before its fold flow emits.
        // Title and Close may each clear opposite sides, so require every text rectangle to
        // be clear together before asserting the settled popup placement.
        waitUntil("All About dialog text/buttons clear of hinge $hinge", onTimeout = ::captureFoldScreenshot) {
            val root = currentRoot()
            root != null && controlClear("About OrbitScope", hinge, vertical, clickable = false) &&
                controlClear("Close", hinge, vertical) && allVisibleTextClear(root)
        }
        fun inspect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser && !node.text.isNullOrBlank()) {
                val rectangle = bounds(node)
                if (!rectangle.isEmpty) assertClear(rectangle, hinge, vertical, "Dialog text ${node.text}")
            }
            for (index in 0 until node.childCount) inspect(node.getChild(index))
        }
        inspect(currentRoot())
    }

    private fun controlClear(label: String, hinge: Rect, vertical: Boolean, clickable: Boolean = true): Boolean {
        val node = find(currentRoot(), label, clickable) ?: return false
        val rectangle = bounds(node)
        return !rectangle.isEmpty && isClear(rectangle, hinge, vertical)
    }

    private fun isClear(bounds: Rect, hinge: Rect, vertical: Boolean): Boolean = if (vertical)
        bounds.right < hinge.left || bounds.left > hinge.right
    else bounds.bottom < hinge.top || bounds.top > hinge.bottom

    private fun assertClear(bounds: Rect, hinge: Rect, vertical: Boolean, label: String) {
        assertTrue("$label crosses hinge: control=$bounds hinge=$hinge", !bounds.isEmpty && isClear(bounds, hinge, vertical))
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    /** FilterChip exposes its chosen state as a checked checkbox rather than a selected tab. */
    private fun hasCheckedState(node: AccessibilityNodeInfo): Boolean {
        if (node.isChecked) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (hasCheckedState(child)) return true
        }
        return false
    }

    private fun find(
        node: AccessibilityNodeInfo?,
        label: String,
        clickable: Boolean?,
        allowSelectedNavigation: Boolean = false,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        fun matchesLabel(value: CharSequence?): Boolean = value?.toString()?.trim()?.let { valueText ->
            valueText == label || valueText.split('\n', '\r', ',').any { it.trim() == label }
        } == true
        val matches = matchesLabel(node.text) || matchesLabel(node.contentDescription)
        if (matches && node.refresh() && (matchesLabel(node.text) || matchesLabel(node.contentDescription))) {
            var target: AccessibilityNodeInfo = node
            if (clickable == true) {
                while (!target.isClickable && !(allowSelectedNavigation && target.isSelected)) {
                    target = target.parent ?: break
                }
            }
            if (target.refresh() && target.isVisibleToUser &&
                (clickable == null || target.isClickable == clickable || (allowSelectedNavigation && target.isSelected)) &&
                !bounds(target).isEmpty) return target
        }
        for (index in 0 until node.childCount) {
            find(node.getChild(index), label, clickable, allowSelectedNavigation)?.let { return it }
        }
        return null
    }

    /** Material exposes a selected bottom destination without a redundant accessibility click. */
    private fun findNavigation(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? =
        find(node, label, clickable = true, allowSelectedNavigation = true)

    private fun waitForNavigation(label: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil("visible navigation '$label', either selected or clickable") {
            found = findNavigation(currentRoot(), label)
            found != null
        }
        return checkNotNull(found)
    }

    private fun waitFor(label: String, clickable: Boolean? = null): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil("label '$label' with clickable=$clickable") {
            found = find(currentRoot(), label, clickable)
            found != null
        }
        return checkNotNull(found)
    }

    private fun currentRoot(): AccessibilityNodeInfo? =
        instrumentation.uiAutomation.rootInActiveWindow?.also { it.refresh() }

    private fun waitUntil(expected: String, onTimeout: (() -> Unit)? = null, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        val diagnostics = accessibilityDiagnostics()
        Log.e("OrbitAdaptiveTest", "Timed out waiting for $expected\n$diagnostics")
        onTimeout?.invoke()
        throw AssertionError("Adaptive UI did not reach $expected\n$diagnostics")
    }

    private fun captureFoldScreenshot() {
        try {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
                ?: throw IllegalStateException("UiAutomation screenshot unavailable")
            val file = File(instrumentation.targetContext.cacheDir,
                "adaptive-dialog-fold-failure-${SystemClock.elapsedRealtime()}.png")
            try {
                file.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Screenshot compression failed" }
                }
            } finally {
                bitmap.recycle()
            }
            Log.e("OrbitAdaptiveTest", "Fold-dialog screenshot saved: ${file.absolutePath}")
        } catch (error: Exception) {
            Log.e("OrbitAdaptiveTest", "Could not capture fold-dialog screenshot", error)
        }
    }

    /** Include hidden text and its ancestors: a visible clickable parent can own a hidden label. */
    private fun accessibilityDiagnostics(): String = buildString {
        var visited = 0
        fun inspect(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || visited >= 200 || length >= 24_000) return
            visited++
            append(" ".repeat(depth.coerceAtMost(16)))
            append("class=${node.className} package=${node.packageName} ")
            append("text=${node.text?.toString()?.take(240)} contentDescription=${node.contentDescription?.toString()?.take(240)} ")
            append("clickable=${node.isClickable} visible=${node.isVisibleToUser} selected=${node.isSelected} ")
            append("bounds=${bounds(node)} resourceId=${node.viewIdResourceName}\n")
            for (index in 0 until node.childCount) inspect(node.getChild(index), depth + 1)
        }
        val root = currentRoot()
        if (root == null) append("Active accessibility window is null") else inspect(root, 0)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val rectangle = bounds(node)
        assertTrue("Empty click bounds for ${node.text}", !rectangle.isEmpty)
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            x = rectangle.exactCenterX(); y = rectangle.exactCenterY(); pressure = 1f; size = 1f
        })
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int): MotionEvent = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            1, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        event(MotionEvent.ACTION_DOWN).useMotion { assertTrue(instrumentation.uiAutomation.injectInputEvent(it, true)) }
        SystemClock.sleep(60)
        event(MotionEvent.ACTION_UP).useMotion { assertTrue(instrumentation.uiAutomation.injectInputEvent(it, true)) }
    }

    private fun MotionEvent.useMotion(block: (MotionEvent) -> Unit) {
        try { block(this) } finally { recycle() }
    }
}
