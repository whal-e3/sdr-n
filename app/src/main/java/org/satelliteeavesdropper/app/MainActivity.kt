package org.satelliteeavesdropper.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.data.CatalogRepository
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.TransmitterRecord
import org.satelliteeavesdropper.app.data.verifyReceptionSelection
import org.satelliteeavesdropper.app.receiver.ReceptionService
import org.satelliteeavesdropper.app.receiver.ReceptionState
import org.satelliteeavesdropper.app.receiver.ReceiverSessionCoordinator
import org.satelliteeavesdropper.app.receiver.SdrTesterState
import org.satelliteeavesdropper.app.receiver.SdrTestConfig
import org.satelliteeavesdropper.app.receiver.NativeSdrTestBackend
import org.satelliteeavesdropper.app.receiver.parseSdrTestFrequencyHz
import org.satelliteeavesdropper.app.receiver.runSdrTest
import org.satelliteeavesdropper.app.receiver.isUsableReceiveFix
import org.satelliteeavesdropper.orbit.ObserverLocation
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal data class UsbChoice(val device: UsbDevice, val type: Int, val label: String)
private data class PendingReception(
    val choice: UsbChoice,
    val satellite: SatelliteRecord,
    val transmitter: TransmitterRecord,
    val observer: ObserverLocation,
    val automaticObserver: Boolean,
    val initialFixElapsedMs: Long?,
)

/** Explicit receiver keeps the USB grant PendingIntent valid on Android 14+. */
class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        onResult?.invoke(context, intent)
    }

    companion object {
        var onResult: ((Context, Intent) -> Unit)? = null
    }
}

class MainActivity : ComponentActivity() {
    private var pendingReception: PendingReception? = null
    private var usbMessage by mutableStateOf<String?>(null)
    private var diagnosticDevices by mutableStateOf<List<UsbChoice>?>(null)
    private var diagnosticMessage: String?
        get() = SdrTesterState.message
        set(value) { SdrTesterState.message = value }
    private val diagnosticCaptureRunning get() = SdrTesterState.running
    private val diagnosticSessions by lazy { ReceiverSessionCoordinator(lifecycleScope) }
    private var diagnosticJob: Job? = null
    private var receptionLaunchRunning = false
    private var pendingDiagnosticFrequencyHz: Long? = null
    private var pendingDiagnosticDevice: UsbChoice? = null
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private val usbPermissionCallback: (Context, Intent) -> Unit = usbPermissionCallback@{ _, intent ->
            if (intent.action == ACTION_USB_DIAGNOSTIC_PERMISSION) {
                val pending = pendingDiagnosticDevice ?: return@usbPermissionCallback
                pendingDiagnosticDevice = null
                val frequencyHz = pendingDiagnosticFrequencyHz
                pendingDiagnosticFrequencyHz = null
                val returnedDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (returnedDevice?.deviceName != pending.device.deviceName ||
                    returnedDevice.vendorId != pending.device.vendorId ||
                    returnedDevice.productId != pending.device.productId) {
                    diagnosticMessage = "USB access result did not match the requested SDR. Scan again."
                    Log.w(TAG, "USB diagnostic permission result did not match ${pending.device.deviceName}")
                    return@usbPermissionCallback
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                    usbManager.hasPermission(pending.device)
                diagnosticMessage = "USB access ${if (granted) "granted" else "denied"} for ${pending.label}."
                Log.i(TAG, "USB diagnostic permission ${if (granted) "granted" else "denied"} for ${pending.device.deviceName}")
                if (granted && frequencyHz != null) startDiagnosticTest(pending, frequencyHz)
                return@usbPermissionCallback
            }
            if (intent.action != ACTION_USB_PERMISSION) return@usbPermissionCallback
            val pending = pendingReception ?: return@usbPermissionCallback
            pendingReception = null
            if (usbManager.hasPermission(pending.choice.device)) {
                launchReception(pending)
            } else {
                usbMessage = "USB permission was not granted"
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 19, 31)
        window.navigationBarColor = Color.rgb(16, 36, 52)
        window.decorView.systemUiVisibility = 0
        UsbPermissionReceiver.onResult = usbPermissionCallback
        setContent {
            OrbitTheme {
                SatelliteScreen(
                    usbDevices = ::supportedUsbDevices,
                    usbMessage = usbMessage,
                    diagnosticDevices = diagnosticDevices,
                    diagnosticMessage = diagnosticMessage,
                    diagnosticCaptureRunning = diagnosticCaptureRunning,
                    diagnosticSnapshot = SdrTesterState.snapshot,
                    scanUsbSdr = ::scanUsbSdr,
                    requestUsbAccess = ::requestDiagnosticUsbAccess,
                    captureUsbIq = ::captureDiagnosticUsbIq,
                    stopUsbTest = ::stopDiagnosticTest,
                    startReception = ::requestReception,
                    stopReception = {
                        startService(Intent(this, ReceptionService::class.java).setAction(ReceptionService.ACTION_STOP))
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        if (UsbPermissionReceiver.onResult === usbPermissionCallback) UsbPermissionReceiver.onResult = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        ReceptionState.setActivityVisible(true)
    }

    override fun onStop() {
        // The receiver may keep running in the foreground, but this activity no longer supplies GPS fixes.
        ReceptionState.setActivityVisible(false)
        pendingDiagnosticFrequencyHz = null
        stopDiagnosticTest()
        super.onStop()
    }

    private fun supportedUsbDevices(): List<UsbChoice> = usbManager.deviceList.values.mapNotNull { device ->
        val type = when {
            device.vendorId == 0x1d50 && device.productId == 0x6089 -> 2
            device.vendorId == 0x0bda && device.productId in listOf(0x2832, 0x2838) -> 1
            device.productName?.contains("RTL2832", ignoreCase = true) == true -> 1
            else -> null
        } ?: return@mapNotNull null
        UsbChoice(device, type, "${if (type == 1) "RTL-SDR" else "HackRF One"} · ${device.productName ?: device.deviceName}")
    }

    private fun scanUsbSdr() {
        val attached = usbManager.deviceList.values
        val supported = supportedUsbDevices()
        diagnosticDevices = supported
        diagnosticMessage = "Found ${supported.size} supported SDR(s) among ${attached.size} USB device(s)."
        Log.i(TAG, "USB scan: ${attached.joinToString { device ->
            "%04x:%04x (%s)".format(Locale.US, device.vendorId, device.productId, device.deviceName)
        }}; supported=${supported.size}")
    }

    private fun requestDiagnosticUsbAccess(choice: UsbChoice) {
        if (pendingDiagnosticDevice != null) {
            diagnosticMessage = "Wait for the current USB access decision before requesting another."
            return
        }
        val attached = usbManager.deviceList[choice.device.deviceName]
        if (attached == null) {
            pendingDiagnosticFrequencyHz = null
            diagnosticMessage = "SDR disconnected. Scan USB SDR again."
            Log.i(TAG, "USB diagnostic device disconnected: ${choice.device.deviceName}")
            return
        }
        if (usbManager.hasPermission(attached)) {
            diagnosticMessage = "USB access already granted for ${choice.label}. Receiver not started."
            Log.i(TAG, "USB diagnostic permission already granted for ${choice.device.deviceName}")
            val frequencyHz = pendingDiagnosticFrequencyHz
            pendingDiagnosticFrequencyHz = null
            if (frequencyHz != null) startDiagnosticTest(choice, frequencyHz)
            return
        }
        pendingDiagnosticDevice = choice
        val intent = Intent(this, UsbPermissionReceiver::class.java).setAction(ACTION_USB_DIAGNOSTIC_PERMISSION)
        val grant = PendingIntent.getBroadcast(
            this, 1, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            diagnosticMessage = "Waiting for Android USB access decision for ${choice.label}."
            Log.i(TAG, "USB diagnostic permission requested for ${choice.device.deviceName}")
            usbManager.requestPermission(attached, grant)
        } catch (error: Exception) {
            pendingDiagnosticDevice = null
            pendingDiagnosticFrequencyHz = null
            diagnosticMessage = "Could not request USB access: ${error.message ?: error.javaClass.simpleName}"
            Log.e(TAG, "USB diagnostic permission request failed for ${choice.device.deviceName}", error)
        }
    }

    private fun captureDiagnosticUsbIq(choice: UsbChoice, frequencyMHz: String) {
        val frequencyHz = parseSdrTestFrequencyHz(frequencyMHz)
        if (frequencyHz == null) {
            diagnosticMessage = "Enter a test frequency from 1 to 6000 MHz. The SDR must support that frequency."
            return
        }
        if (diagnosticCaptureRunning) return
        if (pendingDiagnosticDevice != null) {
            diagnosticMessage = "Wait for the current Android USB access decision."
            return
        }
        if (!usbManager.hasPermission(choice.device)) {
            pendingDiagnosticFrequencyHz = frequencyHz
            requestDiagnosticUsbAccess(choice)
            return
        }
        startDiagnosticTest(choice, frequencyHz)
    }

    private fun startDiagnosticTest(choice: UsbChoice, frequencyHz: Long) {
        if (diagnosticCaptureRunning) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            diagnosticMessage = "Return to the app and start the SDR test again."
            return
        }
        if (ReceptionState.snapshots.value.state in setOf("Starting", "Receiving") ||
            pendingReception != null || receptionLaunchRunning) {
            diagnosticMessage = "Stop the satellite receiver before testing the SDR."
            return
        }
        val attached = supportedUsbDevices().firstOrNull {
            it.device.deviceName == choice.device.deviceName &&
                it.device.vendorId == choice.device.vendorId &&
                it.device.productId == choice.device.productId && it.type == choice.type
        }
        if (attached == null) {
            diagnosticMessage = "SDR disconnected. Scan USB SDR again."
            return
        }
        if (!usbManager.hasPermission(attached.device)) {
            diagnosticMessage = "Android USB access was not granted. Start the test again to request it."
            return
        }
        SdrTesterState.running = true
        SdrTesterState.snapshot = null
        diagnosticMessage = "Testing USB samples for 10 seconds at ${"%.6f".format(Locale.US, frequencyHz / 1_000_000.0)} MHz…"
        diagnosticJob = diagnosticSessions.replace {
            try {
                val result = withContext(Dispatchers.IO) {
                    val connection = usbManager.openDevice(attached.device)
                        ?: error("Could not open the granted USB device")
                    val backend = NativeSdrTestBackend(connection.fileDescriptor, attached.type) { connection.close() }
                    runSdrTest(backend, SdrTestConfig(deviceLabel = attached.label, frequencyHz = frequencyHz),
                        publish = { SdrTesterState.snapshot = it })
                }
                diagnosticMessage = result.summary
            } catch (error: CancellationException) {
                diagnosticMessage = "SDR test stopped. Last samples are retained; no current stream is implied."
                throw error
            } catch (error: Exception) {
                diagnosticMessage = "SDR test failed: ${error.message ?: error.javaClass.simpleName}"
                Log.e(TAG, "SDR test failed", error)
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    SdrTesterState.snapshot = SdrTesterState.snapshot?.copy(state = "Test stopped")
                    diagnosticMessage = "SDR test stopped. Last samples are retained; no current stream is implied."
                }
                SdrTesterState.running = false
            }
        }
    }

    private fun stopDiagnosticTest() {
        diagnosticJob?.cancel()
    }

    private fun requestReception(
        choice: UsbChoice,
        satellite: SatelliteRecord,
        transmitter: TransmitterRecord,
        observer: ObserverLocation,
        automaticObserver: Boolean,
        initialFixElapsedMs: Long?,
    ) {
        if (diagnosticCaptureRunning) {
            usbMessage = "Stop the SDR test before starting satellite reception."
            return
        }
        if (!transmitter.mayReceive || transmitter.frequencyHz <= 0 || transmitter.captureRateSps == null ||
            transmitter.decoderId !in setOf("AUDIO_NFM", "AX25_AFSK1200")) {
            usbMessage = "This transmitter is not enabled for reception"
            return
        }
        if (automaticObserver && !isUsableReceiveFix(initialFixElapsedMs, SystemClock.elapsedRealtime())) {
            usbMessage = "Automatic GPS fix expired. Wait for a fresh fix before receiving."
            return
        }
        val pending = PendingReception(choice, satellite, transmitter, observer,
            automaticObserver, initialFixElapsedMs)
        if (usbManager.hasPermission(choice.device)) {
            launchReception(pending)
        } else {
            pendingReception = pending
            val intent = Intent(this, UsbPermissionReceiver::class.java).setAction(ACTION_USB_PERMISSION)
            val grant = PendingIntent.getBroadcast(
                this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(choice.device, grant)
            usbMessage = "Waiting for USB permission"
        }
    }

    private fun launchReception(pending: PendingReception) {
        if (diagnosticCaptureRunning) {
            usbMessage = "Stop the SDR test before starting satellite reception."
            return
        }
        receptionLaunchRunning = true
        lifecycleScope.launch {
            try {
                require(!pending.automaticObserver ||
                    isUsableReceiveFix(pending.initialFixElapsedMs, SystemClock.elapsedRealtime())) {
                    "Automatic GPS fix expired while waiting for USB access. Wait for a new fix."
                }
                // A target or catalog may have aged out while the Android USB grant was open.
                val verified = withContext(Dispatchers.IO) {
                    val catalog = CatalogRepository(this@MainActivity).load(refresh = false)
                    verifyReceptionSelection(
                        catalog, pending.satellite, pending.transmitter, pending.observer, Instant.now(),
                    )
                }
                require(!pending.automaticObserver ||
                    isUsableReceiveFix(pending.initialFixElapsedMs, SystemClock.elapsedRealtime())) {
                    "Automatic GPS fix expired before reception could start. Wait for a new fix."
                }
                val request = Intent(this@MainActivity, ReceptionService::class.java).apply {
                    action = ReceptionService.ACTION_START
                    putExtra(ReceptionService.EXTRA_DEVICE_NAME, pending.choice.device.deviceName)
                    putExtra(ReceptionService.EXTRA_DEVICE_TYPE, pending.choice.type)
                    putExtra(ReceptionService.EXTRA_FREQUENCY_HZ, verified.transmitter.frequencyHz)
                    putExtra(ReceptionService.EXTRA_SAMPLE_RATE, verified.transmitter.captureRateSps!!)
                    putExtra(ReceptionService.EXTRA_OMM_JSON, verified.satellite.omm.toString())
                    putExtra(ReceptionService.EXTRA_LATITUDE, pending.observer.latitudeDegrees)
                    putExtra(ReceptionService.EXTRA_LONGITUDE, pending.observer.longitudeDegrees)
                    putExtra(ReceptionService.EXTRA_ALTITUDE, pending.observer.altitudeMeters)
                    putExtra(ReceptionService.EXTRA_DECODER_ID, verified.transmitter.decoderId)
                    putExtra(ReceptionService.EXTRA_TARGET_NORAD, verified.satellite.noradId)
                    putExtra(ReceptionService.EXTRA_TARGET_NAME, verified.satellite.name)
                    putExtra(ReceptionService.EXTRA_SESSION_ID, UUID.randomUUID().toString())
                    putExtra(ReceptionService.EXTRA_AUTOMATIC_OBSERVER, pending.automaticObserver)
                    pending.initialFixElapsedMs?.let {
                        putExtra(ReceptionService.EXTRA_INITIAL_FIX_ELAPSED_MS, it)
                    }
                }
                require(!diagnosticCaptureRunning) { "Stop the SDR test before starting satellite reception." }
                startForegroundService(request)
                usbMessage = "Starting ${pending.choice.label}"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                usbMessage = error.message ?: "Could not start USB receiver"
            } finally {
                receptionLaunchRunning = false
            }
        }
    }

    companion object {
        private const val TAG = "SatelliteUsbDiagnostic"
        private const val ACTION_USB_PERMISSION = "org.satelliteeavesdropper.app.USB_PERMISSION"
        private const val ACTION_USB_DIAGNOSTIC_PERMISSION = "org.satelliteeavesdropper.app.USB_DIAGNOSTIC_PERMISSION"
    }
}
