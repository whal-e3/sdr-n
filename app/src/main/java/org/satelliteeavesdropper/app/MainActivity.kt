package org.satelliteeavesdropper.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.data.CatalogLoadResult
import org.satelliteeavesdropper.app.data.CatalogRepository
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.TransmitterRecord
import org.satelliteeavesdropper.app.receiver.ReceptionService
import org.satelliteeavesdropper.app.receiver.ReceptionState
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.SatelliteLook
import org.satelliteeavesdropper.orbit.SatelliteOrbit
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class VisibleSatellite(val record: SatelliteRecord, val look: SatelliteLook)
private data class UsbChoice(val device: UsbDevice, val type: Int, val label: String)
private data class PendingReception(
    val choice: UsbChoice,
    val satellite: SatelliteRecord,
    val transmitter: TransmitterRecord,
    val observer: ObserverLocation,
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
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private val usbPermissionCallback: (Context, Intent) -> Unit = usbPermissionCallback@{ _, intent ->
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
        UsbPermissionReceiver.onResult = usbPermissionCallback
        setContent {
            MaterialTheme {
                SatelliteScreen(
                    usbDevices = ::supportedUsbDevices,
                    usbMessage = usbMessage,
                    startReception = ::requestReception,
                    stopReception = {
                        startService(Intent(this, ReceptionService::class.java).setAction(ReceptionService.ACTION_STOP))
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        UsbPermissionReceiver.onResult = null
        super.onDestroy()
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

    private fun requestReception(
        choice: UsbChoice,
        satellite: SatelliteRecord,
        transmitter: TransmitterRecord,
        observer: ObserverLocation,
    ) {
        if (!transmitter.mayReceive || transmitter.frequencyHz <= 0 || transmitter.captureRateSps == null) {
            usbMessage = "This transmitter is not enabled for reception"
            return
        }
        val pending = PendingReception(choice, satellite, transmitter, observer)
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
        val request = Intent(this, ReceptionService::class.java).apply {
            action = ReceptionService.ACTION_START
            putExtra(ReceptionService.EXTRA_DEVICE_NAME, pending.choice.device.deviceName)
            putExtra(ReceptionService.EXTRA_DEVICE_TYPE, pending.choice.type)
            putExtra(ReceptionService.EXTRA_FREQUENCY_HZ, pending.transmitter.frequencyHz)
            putExtra(ReceptionService.EXTRA_SAMPLE_RATE, pending.transmitter.captureRateSps!!)
            putExtra(ReceptionService.EXTRA_OMM_JSON, pending.satellite.omm.toString())
            putExtra(ReceptionService.EXTRA_LATITUDE, pending.observer.latitudeDegrees)
            putExtra(ReceptionService.EXTRA_LONGITUDE, pending.observer.longitudeDegrees)
            putExtra(ReceptionService.EXTRA_ALTITUDE, pending.observer.altitudeMeters)
            putExtra(ReceptionService.EXTRA_DECODER_ID, pending.transmitter.decoderId)
        }
        try {
            startForegroundService(request)
            usbMessage = "Starting ${pending.choice.label}"
        } catch (error: Exception) {
            usbMessage = error.message ?: "Could not start USB receiver"
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "org.satelliteeavesdropper.app.USB_PERMISSION"
    }
}

@Composable
private fun SatelliteScreen(
    usbDevices: () -> List<UsbChoice>,
    usbMessage: String?,
    startReception: (UsbChoice, SatelliteRecord, TransmitterRecord, ObserverLocation) -> Unit,
    stopReception: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CatalogRepository(context) }
    var catalog by remember { mutableStateOf<CatalogLoadResult?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var observer by remember { mutableStateOf<ObserverLocation?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf<List<VisibleSatellite>>(emptyList()) }
    var selected by remember { mutableStateOf<SatelliteRecord?>(null) }
    var nextPass by remember { mutableStateOf<SatellitePass?>(null) }
    var demoRunning by remember { mutableStateOf(false) }
    var demoSpectrum by remember { mutableStateOf<List<Float>>(emptyList()) }
    var demoError by remember { mutableStateOf<String?>(null) }
    val reception by ReceptionState.snapshots.collectAsState()

    suspend fun updateVisibility(load: CatalogLoadResult?, location: ObserverLocation?) {
        if (load == null || location == null) return
        val now = Instant.now()
        visible = withContext(Dispatchers.Default) {
            load.manifest.satellites.mapNotNull { record ->
                if (record.transmitters.none { it.mayReceive }) return@mapNotNull null
                val elements = record.orbitElements() ?: return@mapNotNull null
                runCatching { VisibleSatellite(record, SatelliteOrbit(elements).lookFrom(location, now)) }.getOrNull()
            }.filter { it.look.elevationDegrees >= 0.0 }
                .sortedByDescending { it.look.elevationDegrees }
        }
    }

    suspend fun loadCatalog() {
        loading = true
        try {
            catalog = repository.load()
            catalogError = null
            selected = null
            updateVisibility(catalog, observer)
        } catch (error: Exception) {
            catalogError = error.message ?: "Catalog could not be loaded"
        } finally {
            loading = false
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            scope.launch {
                val fix = locateObserver(context)
                if (fix == null) locationMessage = "No location fix; enter coordinates manually"
                else {
                    latitude = "%.5f".format(Locale.US, fix.latitudeDegrees)
                    longitude = "%.5f".format(Locale.US, fix.longitudeDegrees)
                    observer = fix
                    locationMessage = "Using device location"
                    updateVisibility(catalog, fix)
                }
            }
        } else locationMessage = "Location permission denied; enter coordinates manually"
    }

    LaunchedEffect(Unit) { loadCatalog() }
    LaunchedEffect(catalog, observer) {
        while (isActive) {
            updateVisibility(catalog, observer)
            delay(30_000)
        }
    }
    LaunchedEffect(selected, observer) {
        val record = selected
        val site = observer
        while (isActive) {
            nextPass = if (record != null && site != null) withContext(Dispatchers.Default) {
                runCatching {
                    val orbit = SatelliteOrbit(record.orbitElements() ?: return@runCatching null)
                    orbit.predictPasses(site, Instant.now(), Instant.now().plus(Duration.ofHours(24))).firstOrNull()
                }.getOrNull()
            } else null
            delay(60_000)
        }
    }
    LaunchedEffect(demoRunning) {
        if (!demoRunning) return@LaunchedEffect
        var handle = 0L
        try {
            handle = NativeReceiver.nativeCreate(1_024_000)
            check(handle != 0L) { "Native test receiver could not start" }
            while (isActive) {
                NativeReceiver.nativeGenerateTestTone(handle, 32_000.0, 16_384)
                demoSpectrum = NativeReceiver.nativeSpectrum(handle).toList()
                delay(400)
            }
        } catch (error: Throwable) {
            demoError = error.message ?: "Native receiver unavailable"
            demoRunning = false
        } finally {
            if (handle != 0L) NativeReceiver.nativeDestroy(handle)
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Satellite Eavesdropper", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Find public satellite downlinks above your location, then receive with a USB SDR.")
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Catalog", style = MaterialTheme.typography.titleMedium)
                        Text(when (catalog?.source) {
                            CatalogSource.LIVE -> "Signed live catalog · ${catalog?.manifest?.satellites?.size} satellites"
                            CatalogSource.CACHED -> "Verified cached catalog · ${catalog?.manifest?.satellites?.size} satellites"
                            CatalogSource.DEMO -> "Unsigned demo data · illustrative positions only"
                            null -> "Loading catalog…"
                        })
                        catalog?.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedButton(onClick = { scope.launch { loadCatalog() } }, enabled = !loading) { Text("Refresh catalog") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Observer location", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val lat = latitude.toDoubleOrNull()
                                val lon = longitude.toDoubleOrNull()
                                val location = runCatching { ObserverLocation(lat ?: Double.NaN, lon ?: Double.NaN) }.getOrNull()
                                if (location == null) locationMessage = "Enter valid latitude and longitude"
                                else {
                                    observer = location
                                    locationMessage = "Using manual location"
                                    scope.launch { updateVisibility(catalog, location) }
                                }
                            }) { Text("Use coordinates") }
                            OutlinedButton(onClick = {
                                val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                if (granted) scope.launch {
                                    val fix = locateObserver(context)
                                    if (fix == null) locationMessage = "No location fix; enter coordinates manually"
                                    else {
                                        latitude = "%.5f".format(Locale.US, fix.latitudeDegrees)
                                        longitude = "%.5f".format(Locale.US, fix.longitudeDegrees)
                                        observer = fix
                                        locationMessage = "Using device location"
                                        updateVisibility(catalog, fix)
                                    }
                                } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }) { Text("Locate") }
                        }
                        locationMessage?.let { Text(it) }
                    }
                }
            }
            item {
                Text("In sight now (${visible.size})", style = MaterialTheme.typography.titleLarge)
                if (observer == null) Text("Set a location to calculate visibility.")
                else if (visible.isEmpty()) Text("No cataloged public downlinks are above the horizon right now.")
            }
            items(visible, key = { it.record.noradId }) { item ->
                Card(onClick = { selected = item.record }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.record.name, fontWeight = FontWeight.Bold)
                        Text("${"%.1f".format(Locale.US, item.look.elevationDegrees)}° elevation · ${"%.0f".format(Locale.US, item.look.azimuthDegrees)}° azimuth")
                        Text("${item.record.transmitters.count { it.mayReceive }} public/amateur downlinks")
                    }
                }
            }
            if (catalog?.source == CatalogSource.DEMO) {
                item {
                    Text("Browse demo catalog", style = MaterialTheme.typography.titleMedium)
                    Text("These entries are for testing the interface, not current reception guidance.")
                }
                items(catalog!!.manifest.satellites, key = { "demo-${it.noradId}" }) { record ->
                    Card(onClick = { selected = record }, modifier = Modifier.fillMaxWidth()) {
                        Text(record.name, modifier = Modifier.padding(14.dp))
                    }
                }
            }
            selected?.let { record ->
                item {
                    HorizontalDivider()
                    Text(record.name, style = MaterialTheme.typography.titleLarge)
                    Text("NORAD ${record.noradId} · next pass ${nextPass?.let { formatTime(it.aos) } ?: "calculating / none in 24 h"}")
                    Text("NFM voice plays audio; AX.25 packets appear below. Meteor imagery is not implemented yet.")
                }
                items(record.transmitters, key = { it.id }) { tx ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${"%.6f".format(Locale.US, tx.frequencyHz / 1_000_000.0)} MHz · ${tx.mode}", fontWeight = FontWeight.Bold)
                            Text("Policy: ${tx.policy} · decoder: ${tx.decoderId ?: "not published"}")
                            if (!tx.mayReceive) Text("Reception disabled: not a curated public/amateur downlink.")
                            else if (tx.captureRateSps == null) Text("No supported capture configuration in catalog.")
                            else if (observer == null) Text("Set your location before starting reception and Doppler tracking.")
                            else if (catalog?.source == CatalogSource.DEMO) {
                                Text("Hardware reception is disabled for unsigned demo data. Configure a signed catalog first.")
                            } else if (catalog != null && (
                                    Duration.between(catalog!!.manifest.sourceUpdatedAt, Instant.now()).toHours() !in 0L..72L ||
                                    record.orbitElements()?.let { Duration.between(it.epoch, Instant.now()).toHours() !in 0L..72L } != false
                                )) {
                                Text("Catalog or orbital elements are stale. Refresh before receiving.")
                            } else {
                                val devices = usbDevices()
                                if (devices.isEmpty()) Text("Connect an RTL-SDR or HackRF One with USB OTG.")
                                devices.forEach { choice ->
                                    Button(onClick = { startReception(choice, record, tx, observer!!) }) { Text("Receive with ${choice.label}") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("Receiver status", style = MaterialTheme.typography.titleLarge)
                Text(reception.error ?: "${reception.state}${if (reception.device.isNotEmpty()) " · ${reception.device}" else ""}")
                if (reception.state == "Receiving") {
                    Text("${reception.acceptedSamples} samples · ${reception.droppedSamples} dropped")
                    SpectrumPlot(reception.spectrum)
                    if (reception.packets.isNotEmpty()) {
                        Text("CRC-checked AX.25 packets", style = MaterialTheme.typography.titleMedium)
                        reception.packets.takeLast(10).forEach { packet -> Text(packet) }
                    }
                    OutlinedButton(onClick = stopReception) { Text("Stop receiver") }
                }
                usbMessage?.let { Text(it) }
            }
            item {
                HorizontalDivider()
                Text("Test without hardware", style = MaterialTheme.typography.titleMedium)
                Text("Synthetic IQ verifies the native spectrum pipeline. It is not a satellite signal.")
                Button(onClick = { demoRunning = !demoRunning; demoError = null }) { Text(if (demoRunning) "Stop test tone" else "Start test tone") }
                demoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (demoRunning) SpectrumPlot(demoSpectrum)
            }
            item { Text("Only receive transmissions you are authorized to monitor. A phone antenna cannot be used as a general SDR antenna.", modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun SpectrumPlot(bins: List<Float>) {
    if (bins.size < 2) return
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        val min = bins.minOrNull() ?: 0f
        val max = bins.maxOrNull() ?: 1f
        val span = (max - min).coerceAtLeast(0.001f)
        val path = Path()
        bins.forEachIndexed { index, value ->
            val x = index.toFloat() / (bins.size - 1) * size.width
            val y = size.height - ((value - min) / span) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFF009688), style = Stroke(width = 2.dp.toPx()))
        drawLine(Color.LightGray, Offset(0f, size.height), Offset(size.width, size.height))
    }
}

private fun formatTime(time: Instant): String =
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(time)
