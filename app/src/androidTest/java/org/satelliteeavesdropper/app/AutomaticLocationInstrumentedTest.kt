package org.satelliteeavesdropper.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the automatic observer stream and screen using a temporary mock GPS provider. */
@RunWith(AndroidJUnit4::class)
class AutomaticLocationInstrumentedTest {
    @Test
    fun freshMockGpsFixAndNearbyUpdateReachAutomaticObserverAndScreen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        assumeTrue(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        assumeTrue(manager.isLocationEnabled)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assumeTrue("Unlock the screen before this foreground location test", power.isInteractive && !keyguard.isKeyguardLocked)

        val packageName = context.packageName
        val previousAppOp = shell("appops get $packageName android:mock_location")
        val previousMode = previousAppOp.lineSequence()
            .firstOrNull { it.contains("MOCK_LOCATION", ignoreCase = true) || it.contains("mock_location", ignoreCase = true) }
            ?.let { Regex("\\b(allow|ignore|deny|default|foreground)\\b", RegexOption.IGNORE_CASE).find(it)?.value }
            ?: "default"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = ObserverPreferences(context)
        val previousLocationMode = preferences.load().mode
        var activity: MainActivity? = null
        var providerAdded = false
        try {
            // While-in-use location permission needs a visible Activity, not only an
            // instrumentation process. Keep that Activity's screen on until the fix.
            assertTrue(preferences.saveMode(LocationMode.AUTO))
            val foreground = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            activity = foreground
            instrumentation.runOnMainSync {
                foreground.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            assertTrue(
                "MainActivity did not resume; lifecycle=${foreground.lifecycle.currentState}",
                withTimeoutOrNull(5_000) {
                    while (!foreground.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) delay(100)
                    true
                } == true,
            )
            val tabClicked = withTimeoutOrNull(5_000) {
                while (!clickVisibleText(instrumentation.uiAutomation.rootInActiveWindow, "Location")) delay(100)
                true
            }
            assertTrue("Location tab could not be tapped; visible=${visibleTexts(instrumentation.uiAutomation.rootInActiveWindow)}",
                tabClicked == true)
            val opened = withTimeoutOrNull(5_000) {
                while (!hasVisibleText(instrumentation.uiAutomation.rootInActiveWindow, "Observer location")) delay(100)
                true
            }
            assertTrue("Location screen did not open; visible=${visibleTexts(instrumentation.uiAutomation.rootInActiveWindow)}",
                opened == true)
            assertTrue(
                "Saved AUTO mode was not shown; visible=${visibleTexts(instrumentation.uiAutomation.rootInActiveWindow)}",
                hasVisibleText(instrumentation.uiAutomation.rootInActiveWindow, "AUTOMATIC LOCATION"),
            )

            shell("appops set $packageName android:mock_location allow")
            @Suppress("DEPRECATION")
            manager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, true, false, false, true, false, false,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE,
            )
            providerAdded = true
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)

            val searching = CompletableDeferred<DeviceLocationState.Searching>()
            val observedStates = mutableListOf<String>()
            val result = async {
                withTimeoutOrNull(20_000) {
                    observeDeviceLocation(context).onEach { state ->
                        observedStates += state.toString()
                        if (state is DeviceLocationState.Searching && !searching.isCompleted) {
                            searching.complete(state)
                        }
                    }.first { state ->
                        state is DeviceLocationState.Fix &&
                            state.observer.latitudeDegrees == 35.12345 && !state.fromRecentCache
                    } as DeviceLocationState.Fix
                }
            }
            val initial = withTimeoutOrNull(5_000) { searching.await() }
                ?: throw AssertionError("Location stream did not emit Searching; states=$observedStates")
            assertTrue(initial.providers.contains(LocationManager.GPS_PROVIDER))
            if ("fused" in manager.getProviders(true)) {
                assertTrue("Enabled fused provider was not registered: ${initial.providers}",
                    initial.providers.contains("fused"))
            }

            // This Android build can accept a mock point before the newly registered
            // listener is ready to receive it. Repeat fresh updates until one arrives;
            // each point follows the same callback path as an Android GPS update.
            var injected = 0
            while (!result.isCompleted) {
                val mock = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 35.12345
                    longitude = 128.54321
                    altitude = 123.0
                    accuracy = 8f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, mock)
                injected++
                delay(1_000)
            }

            val fix = result.await() ?: throw AssertionError(
                "No callback after $injected injected fixes; " +
                    "lastKnown=${manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)}; states=$observedStates",
            )
            assertEquals(35.12345, fix.observer.latitudeDegrees, 0.000001)
            assertEquals(128.54321, fix.observer.longitudeDegrees, 0.000001)
            assertEquals(123.0, fix.observer.altitudeMeters, 0.001)
            assertEquals(LocationManager.GPS_PROVIDER, fix.provider)
            assertEquals(8f, fix.accuracyMeters ?: Float.NaN, 0.01f)
            assertTrue("The injected location must be fresh", fix.ageSeconds <= 5)
            assertFalse("The callback should deliver the injected fix, not a cache hit", fix.fromRecentCache)
            val rendered = withTimeoutOrNull(10_000) {
                while (!hasVisibleText(instrumentation.uiAutomation.rootInActiveWindow,
                        "35.12345°, 128.54321°") ||
                    // Modern Android may forward the injected GPS fix through
                    // fused to the UI's independent subscription. Coordinates
                    // must still match exactly; either registered source is valid.
                    !(hasVisibleText(instrumentation.uiAutomation.rootInActiveWindow, "Automatic · gps") ||
                      hasVisibleText(instrumentation.uiAutomation.rootInActiveWindow, "Automatic · fused"))) {
                    delay(200)
                }
                true
            }
            assertTrue("AUTO fix callback arrived but UI did not show it; visible=${visibleTexts(instrumentation.uiAutomation.rootInActiveWindow)}",
                rendered == true)

            // A nearby new fix must replace the active observer even though the
            // all-orbit day schedule does not need to restart for this move.
            val nearbyRendered = withTimeoutOrNull(20_000) {
                while (!hasVisibleText(instrumentation.uiAutomation.rootInActiveWindow,
                        "35.12400°, 128.54321°")) {
                    manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,
                        Location(LocationManager.GPS_PROVIDER).apply {
                            latitude = 35.12400 // About 61 m from the first fix.
                            longitude = 128.54321
                            altitude = 123.0
                            accuracy = 8f
                            time = System.currentTimeMillis()
                            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        })
                    delay(1_000)
                }
                true
            }
            assertTrue("A fresh nearby fix did not update CURRENT POSITION; visible=${visibleTexts(instrumentation.uiAutomation.rootInActiveWindow)}",
                nearbyRendered == true)
        } finally {
            try {
                if (providerAdded) manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            } finally {
                try {
                    shell("appops set $packageName android:mock_location $previousMode")
                } finally {
                    activity?.let { foreground ->
                        instrumentation.runOnMainSync { foreground.finish() }
                    }
                    assertTrue("Could not restore the saved location mode", preferences.saveMode(previousLocationMode))
                }
            }
        }
    }

    private fun clickVisibleText(root: AccessibilityNodeInfo?, label: String): Boolean {
        fun findClickable(node: AccessibilityNodeInfo, ancestor: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            val candidate = if (node.isClickable) node else ancestor
            if (node.text?.toString() == label) return candidate
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val target = findClickable(child, candidate)
                if (target != null) return target
            }
            return null
        }
        val clickable = root?.let { findClickable(it, null) } ?: return false
        // On this phone ACTION_CLICK returns true during launch yet leaves the
        // Sky tab selected. Inject a real tap at the item bounds instead.
        val bounds = Rect()
        clickable.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        return true
    }

    private fun hasVisibleText(root: AccessibilityNodeInfo?, text: String): Boolean {
        if (root == null) return false
        if (root.text?.toString() == text) return true
        for (index in 0 until root.childCount) {
            if (hasVisibleText(root.getChild(index), text)) return true
        }
        return false
    }

    private fun visibleTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val texts = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo) {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        walk(root)
        return texts
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
