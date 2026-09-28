package org.satelliteeavesdropper.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.satelliteeavesdropper.app.receiver.DecodedPacket
import org.satelliteeavesdropper.app.receiver.ObserverFeedState
import org.satelliteeavesdropper.app.receiver.PacketMetadata
import org.satelliteeavesdropper.app.receiver.ReceptionSnapshot
import org.satelliteeavesdropper.app.receiver.ReceptionState
import java.io.FileInputStream
import java.time.Instant

/**
 * Uses explicit synthetic packet fixtures in a stopped snapshot to exercise export UI only.
 * CRC/address validation is assumed for the fixture; this is not a native-decoding or RF test.
 * No receiver service, native stream, location, network, or USB permission is started here.
 */
@RunWith(AndroidJUnit4::class)
class PacketExportInstrumentedTest {
    private val firstFrame = frame(ByteArray(640) { it.toByte() })
    private val secondFrame = frame(byteArrayOf(0, 0x80.toByte(), 0xff.toByte(), 13, 10))
    private val packets = listOf(
        DecodedPacket(1, Instant.parse("2026-09-28T01:02:03.456Z"), firstFrame, metadata()),
        DecodedPacket(2, Instant.parse("2026-09-28T01:02:04.456Z"), secondFrame,
            metadata().copy(observerLatitudeDegrees = 36.0, observerFixAgeSeconds = 4)),
    )
    private val snapshot = ReceptionSnapshot(
        state = "Stopped", sessionId = "synthetic-export-test", device = "Synthetic fixture",
        decoderId = "AX25_AFSK1200", verifiedFrameCount = 2,
        packets = packets.map { it.displayText }, decodedPackets = packets,
    )

    @Test fun copiesBatchAndIndividualPacketsWithLosslessFramesAndMetadata() = withFixture { ui ->
        ui.tap("Signal")
        ui.tap("Copy packets", scroll = true)
        ui.waitForText("Packet JSON copied.", scroll = true)
        val batch = clipboardJson()
        assertEquals(2, batch.getInt("packetCount"))
        assertPacket(batch, 0, packets[0], firstFrame)
        assertPacket(batch, 1, packets[1], secondFrame)

        ui.tap("View packets", scroll = true)
        ui.waitForText("Decoded packets")
        ui.tap("Copy packet 2", scroll = true)
        val single = clipboardJson()
        assertEquals(1, single.getInt("packetCount"))
        assertPacket(single, 0, packets[1], secondFrame)
        ui.tap("Close")
        ui.waitForText("Copy packets", scroll = true)
    }

    @Test fun savesSelectedBatchThroughAndroidPickerAndCancelsWithoutCreatingFile() = withFixture { ui ->
        val stamp = SystemClock.elapsedRealtime()
        val savedName = "orbitscope-export-test-$stamp.json"
        val canceledName = "orbitscope-export-canceled-$stamp.json"
        val savedPath = "/sdcard/Download/$savedName"
        val canceledPath = "/sdcard/Download/$canceledName"
        try {
            ui.tap("Signal")
            ui.tap("Save packets", scroll = true)
            ui.waitForPicker()
            ui.chooseDownloads()
            ui.setDocumentName(savedName)
            // New packets arriving while the picker is open must not alter the selected batch.
            ReceptionState.publish(snapshot.copy(
                decodedPackets = listOf(packets[1]), packets = listOf(packets[1].displayText),
            ))
            ui.saveDocument()
            ui.waitForText("Packet JSON saved.", scroll = true)
            val saved = JSONObject(ui.shellBytes("cat $savedPath").toString(Charsets.UTF_8))
            assertEquals(2, saved.getInt("packetCount"))
            assertPacket(saved, 0, packets[0], firstFrame)
            assertPacket(saved, 1, packets[1], secondFrame)

            ReceptionState.publish(snapshot)
            ui.tap("Save packets", scroll = true)
            ui.waitForPicker()
            ui.chooseDownloads()
            ui.setDocumentName(canceledName)
            ui.shell("input keyevent 4")
            ui.waitForText("Save canceled. No packet file was created.", scroll = true)
            assertFalse("Canceled CreateDocument must not leave a file",
                ui.shellBytes("find /sdcard/Download -maxdepth 1 -name $canceledName")
                    .toString(Charsets.UTF_8).contains(canceledName))
        } finally {
            ui.dismissPicker()
            ui.shell("rm -f $savedPath $canceledPath")
        }
    }

    private fun clipboardJson(): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val json = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            ?: throw AssertionError("Packet export did not populate the clipboard")
        return JSONObject(json)
    }

    private fun assertPacket(document: JSONObject, index: Int, packet: DecodedPacket, bytes: ByteArray) {
        assertEquals(1, document.getInt("schemaVersion"))
        val json = document.getJSONArray("packets").getJSONObject(index)
        assertEquals(packet.packetId, json.getString("packetId"))
        assertEquals(packet.dequeuedAtUtc.toString(), json.getString("dequeuedAtUtc"))
        assertEquals(packet.metadata.sessionId, json.getString("sessionId"))
        assertEquals("AX25_AFSK1200", json.getString("decoderId"))
        val encoded = json.getJSONObject("frame")
        assertEquals(bytes.size, encoded.getInt("byteLength"))
        assertFalse(encoded.getBoolean("includesFcs"))
        assertArrayEquals(bytes, encoded.getString("bytes").chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val receiver = json.getJSONObject("receiver")
        assertEquals("Synthetic fixture", receiver.getString("device"))
        assertEquals(145_825_000L, receiver.getLong("rfCenterHz"))
        assertEquals(1_024_000, receiver.getInt("sampleRateSps"))
        assertEquals(1000.0, receiver.getDouble("predictedDopplerHz"), 0.0)
        assertEquals(120.0, receiver.getDouble("afcAppliedHz"), 0.0)
        assertEquals(packet.metadata.observerLatitudeDegrees,
            json.getJSONObject("observer").getDouble("latitudeDegrees"), 0.0)
        assertEquals("AUTO_FOLLOWING", json.getJSONObject("observer").getString("feedState"))
        assertEquals(packet.metadata.observerFixAgeSeconds,
            json.getJSONObject("observer").getLong("fixAgeSeconds"))
        assertEquals("Synthetic tracking target 🛰", json.getJSONObject("trackingTarget").getString("name"))
        assertEquals("selected_tracking_target_not_verified_transmitter_identity",
            json.getJSONObject("trackingTarget").getString("identityBasis"))
    }

    private fun withFixture(test: (Ui) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = ObserverPreferences(context)
        val previousMode = preferences.load().mode
        val previousSnapshot = ReceptionState.snapshots.value
        val ui = Ui()
        var activity: MainActivity? = null
        try {
            assertTrue(preferences.saveMode(LocationMode.MANUAL))
            ReceptionState.publish(snapshot)
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            test(ui)
        } finally {
            ui.dismissPicker()
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            ReceptionState.publish(previousSnapshot)
            assertTrue(preferences.saveMode(previousMode))
        }
    }

    private class Ui {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val pickerPackages = setOf("com.google.android.documentsui", "com.android.documentsui")

        private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            node.refresh()
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
            return null
        }

        private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)

        private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

        private fun scrollContainer(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            var ancestor = node?.parent
            while (ancestor != null) {
                if (ancestor.isScrollable && ancestor.isVisibleToUser) return ancestor
                ancestor = ancestor.parent
            }
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            fun collect(current: AccessibilityNodeInfo?) {
                if (current == null) return
                if (current.isScrollable && current.isVisibleToUser) candidates += current
                for (index in 0 until current.childCount) collect(current.getChild(index))
            }
            collect(instrumentation.uiAutomation.rootInActiveWindow)
            // A wide window also has a scrollable navigation rail and horizontal chip rows.
            // Scroll the page/dialog body when the desired item has not entered its viewport yet.
            return candidates.maxByOrNull { bounds(it).let { rectangle -> rectangle.width().toLong() * rectangle.height() } }
        }

        /** Compose navigation text can have empty bounds while its clickable parent is drawn. */
        private fun touchTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            var candidate: AccessibilityNodeInfo? = node
            while (candidate != null) {
                candidate.refresh()
                if (candidate.isClickable && !bounds(candidate).isEmpty) return candidate
                candidate = candidate.parent
            }
            return node
        }

        fun shellBytes(command: String): ByteArray = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }

        fun shell(command: String) { shellBytes(command) }

        fun waitForText(text: String, scroll: Boolean = false): AccessibilityNodeInfo =
            waitForNode(text, scroll) { it.text?.toString() == text }

        private fun waitForNode(description: String, scroll: Boolean = false,
            predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            val deadline = SystemClock.elapsedRealtime() + 12_000
            var previous: Rect? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val node = find(predicate)
                val rect = node?.let { bounds(touchTarget(it)) }
                val container = if (scroll) scrollContainer(node)?.let(::bounds) else null
                if (rect != null && !rect.isEmpty && (container == null || container.contains(rect))) {
                    if (previous == rect) return requireNotNull(node)
                    previous = rect
                    SystemClock.sleep(200)
                    continue
                }
                previous = null
                if (container != null && !container.isEmpty) {
                    val upward = rect == null || rect.bottom > container.bottom
                    val from = container.top + container.height() * (if (upward) 3 else 1) / 4
                    val to = container.top + container.height() * (if (upward) 1 else 3) / 4
                    shell("input swipe ${container.centerX()} $from ${container.centerX()} $to 300")
                    SystemClock.sleep(250)
                } else SystemClock.sleep(100)
            }
            val visible = mutableListOf<String>()
            fun describe(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) {
                    visible += "${node.text ?: node.contentDescription} [visible=${node.isVisibleToUser}, bounds=${bounds(node)}, package=${node.packageName}]"
                }
                for (index in 0 until node.childCount) describe(node.getChild(index))
            }
            describe(instrumentation.uiAutomation.rootInActiveWindow)
            throw AssertionError("Visible item missing or clipped: $description\n${visible.joinToString("\n")}")
        }

        private fun tapNode(node: AccessibilityNodeInfo) {
            val target = touchTarget(node)
            val rect = bounds(target)
            assertTrue("Tap target is empty", !rect.isEmpty)
            assertTrue("Control is not enabled", target.isEnabled)
            assertTrue("Control click was rejected", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)
        }

        fun tap(text: String, scroll: Boolean = false) = tapNode(waitForText(text, scroll))
        fun tapAny(texts: Set<String>) = tapNode(waitForNode(texts.joinToString(" / ")) {
            it.text?.toString() in texts && touchTarget(it).isClickable && touchTarget(it).isEnabled
        })

        fun waitForPicker() {
            waitForNode("Android document picker") { it.packageName?.toString() in pickerPackages }
        }

        fun chooseDownloads() {
            val drawer = waitForNode("Document picker storage menu") {
                it.contentDescription?.toString() in setOf("Show roots", "Open navigation drawer", "루트 표시", "탐색 창 열기")
            }
            tapNode(drawer)
            tapAny(setOf("Downloads", "Download", "다운로드"))
        }

        fun setDocumentName(name: String) {
            val input = waitForNode("Document filename") { it.className?.toString() == "android.widget.EditText" }
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) }
            assertTrue("Document filename could not be set", input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            instrumentation.waitForIdleSync()
        }

        fun saveDocument() {
            val button = touchTarget(waitForNode("Enabled document Save button") {
                it.packageName?.toString() in pickerPackages &&
                    it.text?.toString() in setOf("Save", "SAVE", "저장") &&
                    touchTarget(it).isClickable && touchTarget(it).isEnabled
            })
            // Android's native picker exposes ACTION_CLICK directly. This avoids a shell tap
            // racing filename/IME relayout in a resized emulator; the actual picker saves the file.
            assertTrue("Document Save action was rejected", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }

        fun dismissPicker() {
            repeat(4) {
                if (find { it.packageName?.toString() in pickerPackages } == null) return
                // Back may first close the storage drawer or IME, then cancel the picker.
                shell("input keyevent 4")
                SystemClock.sleep(300)
            }
        }
    }

    private fun frame(payload: ByteArray): ByteArray {
        fun address(call: String, last: Boolean) = call.padEnd(6).map { (it.code shl 1).toByte() }
            .toByteArray() + byteArrayOf(if (last) 0x61 else 0x60)
        return address("APRS", false) + address("TEST", true) + byteArrayOf(0x03, 0xf0.toByte()) + payload
    }

    private fun metadata() = PacketMetadata(
        sessionId = "synthetic-export-test", decoderId = "AX25_AFSK1200",
        targetNoradId = "25544", targetName = "Synthetic tracking target 🛰", device = "Synthetic fixture",
        rfCenterHz = 145_825_000, sampleRateSps = 1_024_000, predictedDopplerHz = 1000.0,
        afcTracking = true, afcAppliedHz = 120.0, afcLastResidualHz = -15.0,
        lookAzimuthDegrees = 45.0, lookElevationDegrees = 30.0,
        observerLatitudeDegrees = 37.2411, observerLongitudeDegrees = 127.1776, observerAltitudeMeters = 100.0,
        observerFeedState = ObserverFeedState.AUTO_FOLLOWING, observerFixAgeSeconds = 3,
    )
}
