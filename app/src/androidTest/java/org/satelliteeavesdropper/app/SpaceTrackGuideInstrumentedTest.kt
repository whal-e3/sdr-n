package org.satelliteeavesdropper.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/** Exercises the offline guide and Android document picker without a Space-Track account. */
@RunWith(AndroidJUnit4::class)
class SpaceTrackGuideInstrumentedTest {
    @Test fun guideCopiesCsvLinkAndOpensCancelableDocumentPicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = ObserverPreferences(context)
        val previousMode = preferences.load().mode
        var activity: MainActivity? = null
        fun find(
            node: AccessibilityNodeInfo?,
            predicate: (AccessibilityNodeInfo) -> Boolean,
        ): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) {
                find(node.getChild(index), predicate)?.let { return it }
            }
            return null
        }
        fun findText(text: String): AccessibilityNodeInfo? =
            find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }
        fun scrollContainer(): AccessibilityNodeInfo? =
            find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable }
        fun waitForText(text: String, scroll: Boolean = false): AccessibilityNodeInfo {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var previousBounds: Rect? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val node = findText(text)
                val bounds = node?.let(::boundsOf)
                val containerBounds = if (scroll) scrollContainer()?.let(::boundsOf) else null
                val fullyVisible = bounds != null && !bounds.isEmpty &&
                    (containerBounds == null || containerBounds.contains(bounds))
                // Compose can expose a partially clipped child while a scroll is still moving.
                // Use two equal observations before tapping its physical coordinates.
                if (fullyVisible) {
                    if (bounds == previousBounds) return requireNotNull(node)
                    previousBounds = bounds
                    SystemClock.sleep(200)
                    continue
                }
                previousBounds = null
                if (containerBounds != null && !containerBounds.isEmpty) {
                    // A short physical swipe retains overlap between pages. Large accessibility
                    // page-scroll actions can skip the button or leave it behind the dialog edge.
                    val towardTop = bounds == null || bounds.bottom > containerBounds.bottom
                    val from = containerBounds.top + containerBounds.height() * (if (towardTop) 3 else 1) / 4
                    val to = containerBounds.top + containerBounds.height() * (if (towardTop) 1 else 3) / 4
                    shell("input swipe ${containerBounds.centerX()} $from ${containerBounds.centerX()} $to 300")
                    SystemClock.sleep(250)
                } else SystemClock.sleep(100)
            }
            throw AssertionError("Visible text missing or clipped: $text")
        }
        fun tap(text: String, scroll: Boolean = false) {
            val bounds = boundsOf(waitForText(text, scroll))
            assertTrue("Empty touch bounds: $text", !bounds.isEmpty)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)
        }
        try {
            // Do not depend on GPS, USB hardware, network access or a permission prompt.
            assertTrue(preferences.saveMode(LocationMode.MANUAL))
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            tap("Space-Track guide")
            waitForText("Get Space-Track orbit data")
            tap("Copy download link", scroll = true)
            waitForText("Download link copied.", scroll = true)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val link = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                ?: throw AssertionError("The guide did not copy a download link")
            val uri = Uri.parse(link)
            assertEquals("https", uri.scheme)
            assertEquals("www.space-track.org", uri.host)
            assertTrue("Copy must give an orbital-data CSV export", uri.path.orEmpty().contains("/class/gp/"))
            assertTrue("The browser export must use the supported CSV format", uri.path.orEmpty().contains("/format/csv"))
            tap("Close")
            tap("Space-Track guide")
            waitForText("Get Space-Track orbit data")
            tap("Import downloaded file")

            val pickerDeadline = SystemClock.elapsedRealtime() + 10_000
            var pickerVisible = false
            while (SystemClock.elapsedRealtime() < pickerDeadline && !pickerVisible) {
                pickerVisible = find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.packageName?.toString() in setOf("com.google.android.documentsui", "com.android.documentsui")
                } != null
                if (!pickerVisible) SystemClock.sleep(100)
            }
            assertTrue("Import must open Android's document picker", pickerVisible)
            shell("input keyevent 4")
            waitForText("Space-Track guide")
            assertFalse("Canceling import must leave the guide dismissed",
                findText("Get Space-Track orbit data") != null)
        } finally {
            // Also dismiss a system picker if an assertion failed while it was open.
            if (find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.packageName?.toString() in setOf("com.google.android.documentsui", "com.android.documentsui")
                } != null) shell("input keyevent 4")
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            assertTrue(preferences.saveMode(previousMode))
        }
    }
}
