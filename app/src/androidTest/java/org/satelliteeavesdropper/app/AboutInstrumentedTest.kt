package org.satelliteeavesdropper.app

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/** Real taps guard against a header rendered behind Android's status bar. */
@RunWith(AndroidJUnit4::class)
class AboutInstrumentedTest {
    @Test fun headerOpensOfflinePrivacyWithoutLocationOrUsb() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = ObserverPreferences(context)
        val previousMode = preferences.load().mode
        var activity: MainActivity? = null
        try {
            assertTrue(preferences.saveMode(LocationMode.MANUAL))
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString()?.contains(text) == true) return node
                for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
                return null
            }
            fun waitFor(text: String): AccessibilityNodeInfo {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
                    SystemClock.sleep(100)
                }
                throw AssertionError("Visible text missing: $text")
            }
            fun tap(text: String) {
                val bounds = Rect()
                waitFor(text).getBoundsInScreen(bounds)
                assertTrue("Empty touch bounds: $text", !bounds.isEmpty)
                instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { fd ->
                    FileInputStream(fd.fileDescriptor).use { it.readBytes() }
                }
            }
            tap("About")
            waitFor("About OrbitScope")
            tap("Privacy policy")
            waitFor("Location")
            // The full policy is local content, not a browser/network dependency.
            waitFor("OrbitScope uses location supplied by Android")
            tap("Close")
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            assertTrue(preferences.saveMode(previousMode))
        }
    }
}
