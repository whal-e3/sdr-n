package org.satelliteeavesdropper.app

import android.Manifest
import android.content.Context
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.Settings
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.data.CatalogLoadResult
import org.satelliteeavesdropper.app.data.CatalogRepository
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.app.data.OrbitLookupRepository
import org.satelliteeavesdropper.app.data.OrbitLookupResult
import org.satelliteeavesdropper.app.data.OrbitImportRepository
import org.satelliteeavesdropper.app.data.OrbitImportSnapshot
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.TransmitterRecord
import org.satelliteeavesdropper.app.data.isFreshWithin72Hours
import org.satelliteeavesdropper.app.data.mergeTrackingCatalog
import org.satelliteeavesdropper.app.day.DayScheduleScreen
import org.satelliteeavesdropper.app.day.DayScheduleCache
import org.satelliteeavesdropper.app.globe.GlobeScreen
import org.satelliteeavesdropper.app.receiver.ReceptionState
import org.satelliteeavesdropper.app.receiver.ReceptionSnapshot
import org.satelliteeavesdropper.app.receiver.SignalVisuals
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.SatelliteLook
import org.satelliteeavesdropper.orbit.SatelliteOrbit
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private enum class AppTab(val label: String, val symbol: String) {
    SKY("Sky", "◎"), TARGET("Target", "◈"), SIGNAL("Signal", "≋"), LOCATION("Location", "⌖"),
}

private enum class SignalSource(val label: String) {
    RECEIVER("Receiver"), SDR_TESTER("SDR tester"), TEST_TONE("Test tone"),
}

/**
 * Sky contains the full orbital catalog. Target shows one orbit and its reception eligibility.
 * Signal separates USB samples, the signal display, and validated decoder output.
 */
@Composable
internal fun SatelliteScreen(
    usbDevices: () -> List<UsbChoice>,
    usbMessage: String?,
    diagnosticDevices: List<UsbChoice>?,
    diagnosticMessage: String?,
    diagnosticCaptureRunning: Boolean,
    diagnosticSnapshot: ReceptionSnapshot?,
    scanUsbSdr: () -> Unit,
    requestUsbAccess: (UsbChoice) -> Unit,
    captureUsbIq: (UsbChoice, String) -> Unit,
    stopUsbTest: () -> Unit,
    startReception: (UsbChoice, SatelliteRecord, TransmitterRecord, ObserverLocation, Boolean, Long?) -> Unit,
    stopReception: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as MainActivity
    val lifecycle = activity.lifecycle
    val scope = rememberCoroutineScope()
    val repository = remember(context) { CatalogRepository(context) }
    val dayScheduleCache = remember { DayScheduleCache() }
    DisposableEffect(dayScheduleCache) {
        onDispose { dayScheduleCache.clear() }
    }
    val orbitLookup = remember(context) { OrbitLookupRepository(context) }
    val orbitImport = remember(context) { OrbitImportRepository(context) }
    val observerPreferences = remember(context) { ObserverPreferences(context) }
    val savedObserver = remember(observerPreferences) { observerPreferences.load() }
    var tab by rememberSaveable { mutableStateOf(AppTab.SKY) }
    var skyGlobe by rememberSaveable { mutableStateOf(false) }
    var globeSelectedNoradId by rememberSaveable { mutableStateOf<String?>(null) }
    var signedCatalog by remember { mutableStateOf<CatalogLoadResult?>(null) }
    var supplemental by remember { mutableStateOf<List<OrbitLookupResult.Found>>(emptyList()) }
    var imported by remember { mutableStateOf<OrbitImportSnapshot?>(null) }
    val supplementalRecords = remember(supplemental, imported) {
        val records = LinkedHashMap<String, SatelliteRecord>()
        supplemental.forEach { records[it.noradId] = it.satellite }
        imported?.records.orEmpty().forEach { record ->
            val previous = records[record.noradId]
            if (previous == null ||
                (record.orbitElements()?.epoch?.isAfter(previous.orbitElements()?.epoch) == true)) {
                records[record.noradId] = record
            }
        }
        records
    }
    val catalog = remember(signedCatalog, supplementalRecords) {
        signedCatalog?.let { base ->
            base.copy(manifest = mergeTrackingCatalog(base.manifest, supplementalRecords.values))
        }
    }
    val supplementalRevision = remember(supplemental, imported) {
        supplemental.sortedBy { it.noradId }
            .joinToString("|") { "${it.noradId}:${it.fetchedAt}:${it.satellite.orbitElements()?.epoch}" } +
            "|imports:" + imported?.sources.orEmpty()
                .joinToString("|") { "${it.sourceName}:${it.importedAt}:${it.savedRecords}" }
    }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var catalogLoading by remember { mutableStateOf(false) }
    var lookupRunning by remember { mutableStateOf(false) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }
    var importRunning by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var selectedNoradId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = remember(selectedNoradId, catalog) {
        catalog?.manifest?.satellites?.firstOrNull { it.noradId == selectedNoradId }
    }
    var recommended by remember { mutableStateOf<SatelliteRecord?>(null) }
    var userSelectedObserver by remember { mutableStateOf(savedObserver.selectedObserver) }
    var observer by remember { mutableStateOf(savedObserver.activeObserver) }
    var dayScheduleObserver by remember { mutableStateOf(savedObserver.activeObserver) }
    var locationMode by rememberSaveable { mutableStateOf(savedObserver.mode) }
    var latitude by rememberSaveable {
        mutableStateOf(savedObserver.selectedObserver?.latitudeDegrees?.toString().orEmpty())
    }
    var longitude by rememberSaveable {
        mutableStateOf(savedObserver.selectedObserver?.longitudeDegrees?.toString().orEmpty())
    }
    var locationState by remember { mutableStateOf<DeviceLocationState>(DeviceLocationState.Searching(emptyList())) }
    var locationMessage by remember {
        mutableStateOf<String?>(if (savedObserver.activeObserver != null) "Saved observer location" else null)
    }
    var locationPermissionGranted by remember {
        mutableStateOf(context.hasLocationPermission())
    }
    var preciseLocationGranted by remember {
        mutableStateOf(context.hasPreciseLocationPermission())
    }
    var locationPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var locationPermissionRequestInFlight by remember { mutableStateOf(false) }
    var targetLook by remember { mutableStateOf<SatelliteLook?>(null) }
    var nextPass by remember { mutableStateOf<SatellitePass?>(null) }
    var targetPredictionError by remember { mutableStateOf<String?>(null) }
    var targetNow by remember { mutableStateOf(Instant.now()) }
    var signalSource by rememberSaveable { mutableStateOf(SignalSource.RECEIVER) }
    var testFrequencyMHz by rememberSaveable { mutableStateOf("100.000") }
    var demoToneHz by rememberSaveable { mutableStateOf(32_000f) }
    var demoRunning by remember { mutableStateOf(false) }
    var demoSnapshot by remember { mutableStateOf<ReceptionSnapshot?>(null) }
    var demoError by remember { mutableStateOf<String?>(null) }
    val reception by ReceptionState.snapshots.collectAsState()
    val receiving = reception.state == "Starting" || reception.state == "Receiving"
    LaunchedEffect(receiving, diagnosticCaptureRunning) {
        if (receiving || diagnosticCaptureRunning) demoRunning = false
    }

    // Automatic fixes update only the matching active receive session. Manual/map selection
    // during a session leaves its target and last observer in place until it is restarted.
    LaunchedEffect(locationMode, locationState, observer, reception.sessionId,
        reception.targetNoradId, receiving) {
        if (!receiving || reception.sessionId.isBlank()) return@LaunchedEffect
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            ReceptionState.pauseObserver(reception.sessionId, reception.targetNoradId, false)
            return@LaunchedEffect
        }
        val currentLocationState = locationState
        val fix = currentLocationState as? DeviceLocationState.Fix
        when {
            locationMode != LocationMode.AUTO ->
                ReceptionState.pauseObserver(reception.sessionId, reception.targetNoradId, false)
            observer != null && fix != null ->
                ReceptionState.updateObserver(reception.sessionId, reception.targetNoradId,
                    fix.observer, fix.fixElapsedRealtimeMillis, SystemClock.elapsedRealtime())
            currentLocationState is DeviceLocationState.Searching &&
                !currentLocationState.takingLonger -> Unit
            else -> ReceptionState.pauseObserver(reception.sessionId, reception.targetNoradId, true)
        }
    }

    fun setObserver(location: ObserverLocation, source: String, scheduleLocation: ObserverLocation = location) {
        observer = location
        dayScheduleObserver = scheduleLocation
        latitude = "%.5f".format(Locale.US, location.latitudeDegrees)
        longitude = "%.5f".format(Locale.US, location.longitudeDegrees)
        locationMessage = source
    }

    fun chooseLocationMode(mode: LocationMode) {
        if (locationMode == mode) return
        val saved = observerPreferences.saveMode(mode)
        locationMode = mode
        observer = if (mode == LocationMode.AUTO) null else userSelectedObserver
        dayScheduleObserver = observer
        recommended = null
        if (mode != LocationMode.AUTO) {
            latitude = userSelectedObserver?.latitudeDegrees?.toString().orEmpty()
            longitude = userSelectedObserver?.longitudeDegrees?.toString().orEmpty()
        }
        locationMessage = when {
            !saved -> "Location mode changed for this session, but could not be saved."
            mode == LocationMode.AUTO -> "Waiting for a current device location fix."
            observer != null -> "Saved observer location"
            else -> "Choose a location on the map or enter coordinates."
        }
    }

    fun selectUserObserver(location: ObserverLocation, mode: LocationMode, source: String) {
        userSelectedObserver = location
        setObserver(location, source)
        if (!observerPreferences.saveUserSelection(mode, location)) {
            locationMessage = "$source · Could not save this location for next launch."
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        locationPermissionRequestInFlight = false
        locationPermissionGranted = context.hasLocationPermission()
        preciseLocationGranted = context.hasPreciseLocationPermission()
        if (!locationPermissionGranted) locationState = DeviceLocationState.PermissionRequired
    }
    fun requestLocationPermission() {
        locationPermissionRequested = true
        locationPermissionRequestInFlight = true
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun openAppPermissionSettings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)))
    }
    val missingLocationAction = locationPermissionAction(
        locationPermissionRequested,
        locationPermissionRequestInFlight,
        activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
            activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION),
    )
    val preciseLocationAction = locationPermissionAction(
        locationPermissionRequested,
        locationPermissionRequestInFlight,
        activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION),
    )
    DisposableEffect(lifecycle, context) {
        val permissionObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                // A grant can change in Android Settings while this activity is paused.
                locationPermissionGranted = context.hasLocationPermission()
                preciseLocationGranted = context.hasPreciseLocationPermission()
            }
        }
        lifecycle.addObserver(permissionObserver)
        onDispose { lifecycle.removeObserver(permissionObserver) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importRunning) scope.launch {
            importRunning = true
            try {
                val snapshot = orbitImport.importFromUri(uri)
                imported = snapshot
                val report = snapshot.summary
                importMessage = "Imported ${report.sourceName}: ${report.addedRecords} added, " +
                    "${report.replacedRecords} updated, ${report.rejectedRecords} rejected; " +
                    "${report.savedRecords} saved track-only orbits."
            } catch (error: Exception) {
                importMessage = "Orbit import failed: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                importRunning = false
            }
        }
    }

    suspend fun loadCatalog() {
        if (catalogLoading) return
        catalogLoading = true
        dayScheduleCache.clear()
        val replacingCatalog = signedCatalog != null
        signedCatalog = null
        recommended = null
        try {
            // Dispose old schedule/prediction effects before parsing a replacement graph.
            if (replacingCatalog) {
                withFrameNanos { }
                withFrameNanos { }
            }
            val loaded = repository.load()
            signedCatalog = loaded
            selectedNoradId = selectedNoradId?.takeIf { id ->
                loaded.manifest.satellites.any { it.noradId == id } ||
                    supplemental.any { it.noradId == id } ||
                    imported?.records?.any { it.noradId == id } == true
            }
            catalogError = null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            catalogError = error.message ?: "Catalog could not be loaded"
        } finally {
            catalogLoading = false
        }
    }

    LaunchedEffect(Unit) {
        supplemental = orbitLookup.cachedRecords()
        imported = runCatching { orbitImport.load() }.getOrNull()
        loadCatalog()
    }
    LaunchedEffect(locationMode) {
        // Loading a large signed catalog must not delay the observer permission prompt.
        if (locationMode == LocationMode.AUTO && !context.hasLocationPermission() &&
            missingLocationAction == LocationPermissionAction.REQUEST) {
            requestLocationPermission()
        }
    }

    fun lookUpMissingOrbit(id: String) {
        if (lookupRunning) return
        scope.launch {
            lookupRunning = true
            try {
                when (val result = orbitLookup.lookupNoradId(id)) {
                    is OrbitLookupResult.Found -> {
                        val previous = catalog?.manifest?.satellites?.firstOrNull { it.noradId == result.noradId }
                        val previousEpoch = previous?.orbitElements()?.epoch
                        val fetchedEpoch = result.satellite.orbitElements()?.epoch
                        supplemental = supplemental.filterNot { it.noradId == result.noradId } + result
                        lookupMessage = (if (previous == null) "Added ${result.satellite.name} for orbit tracking"
                            else if (previousEpoch == null || fetchedEpoch?.isAfter(previousEpoch) == true)
                                "Updated tracking orbit for ${result.satellite.name}"
                            else "No newer orbit for ${result.satellite.name}; kept the current tracking orbit") +
                            " · ${result.provenance}" +
                            (result.warning?.let { " · $it" } ?: "")
                    }
                    is OrbitLookupResult.Unavailable -> {
                        lookupMessage = result.message +
                            (result.retryAfter?.let { " Retry after ${formatOrbitTime(it)}." } ?: "")
                    }
                }
            } catch (error: Exception) {
                lookupMessage = error.message ?: "Orbit lookup failed"
            } finally {
                lookupRunning = false
            }
        }
    }
    LaunchedEffect(locationMode, locationPermissionGranted, preciseLocationGranted) {
        if (locationMode != LocationMode.AUTO) return@LaunchedEffect
        observer = null
        dayScheduleObserver = null
        recommended = null
        if (!locationPermissionGranted) {
            locationState = DeviceLocationState.PermissionRequired
            locationMessage = "Location permission is needed for automatic positioning."
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            observer = null
            dayScheduleObserver = null
            observeDeviceLocation(context).collect { state ->
                if (locationMode != LocationMode.AUTO) return@collect
                locationState = state
                if (state is DeviceLocationState.Fix) {
                    val update = automaticObserverUpdate(dayScheduleObserver, state.observer)
                    setObserver(update.active, "Automatic · ${state.provider}", update.daySchedule)
                } else if (state is DeviceLocationState.Searching) {
                    // A restarted or expired search has no current fix; clear earlier failure text.
                    observer = null
                    dayScheduleObserver = null
                    locationMessage = if (state.takingLonger)
                        "Still waiting for a current device location fix."
                    else "Searching for a current device location fix."
                } else {
                    // Never keep using an unavailable automatic fix for tuning.
                    observer = null
                    dayScheduleObserver = null
                    locationMessage = "Automatic location unavailable; choose a map or manual observer."
                }
            }
        }
    }
    LaunchedEffect(catalog, dayScheduleObserver) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val loaded = catalog
                val site = dayScheduleObserver
                recommended = if (loaded == null || site == null || loaded.source == CatalogSource.DEMO ||
                    !isFreshWithin72Hours(loaded.manifest.sourceUpdatedAt, Instant.now())) null
                else withContext(Dispatchers.Default) {
                    val now = Instant.now()
                    loaded.manifest.satellites.asSequence()
                        .filterNot { record -> record.isKnownDecayedAt(now) }
                        .filter { record -> record.transmitters.any { receiverConfigurationIssue(it) == null } }
                        .mapNotNull { record ->
                            val elements = record.orbitElements() ?: return@mapNotNull null
                            if (!isFreshWithin72Hours(elements.epoch, now)) return@mapNotNull null
                            val look = runCatching { SatelliteOrbit(elements).lookFrom(site, now) }.getOrNull()
                            if (look != null && look.elevationDegrees >= 0) record to look.elevationDegrees else null
                        }
                        .maxByOrNull { it.second }?.first
                }
                delay(30_000)
            }
        }
    }
    val currentRecommendation = recommended?.takeUnless { it.isKnownDecayedAt(targetNow) }
    val target = remember(selected, currentRecommendation, catalog) {
        selected?.let { chosen ->
            catalog?.manifest?.satellites?.firstOrNull { it.noradId == chosen.noradId } ?: chosen
        } ?: currentRecommendation
    }
    val targetIsHistorical = target?.isKnownDecayedAt(targetNow) == true
    val targetDownlinks = remember(target) { target?.transmitters?.let(::groupTargetDownlinks) }
    val signedTarget = remember(signedCatalog, target) {
        signedCatalog?.manifest?.satellites?.firstOrNull { it.noradId == target?.noradId }
    }
    var showReferenceDownlinks by rememberSaveable(target?.noradId) { mutableStateOf(false) }
    LaunchedEffect(target, observer) {
        targetLook = null
        nextPass = null
        targetPredictionError = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val record = target
                val site = observer
                val now = Instant.now()
                targetNow = now
                if (record != null && site != null && !record.isKnownDecayedAt(now)) {
                    val result = withContext(Dispatchers.Default) {
                        runCatching {
                            val orbit = SatelliteOrbit(record.orbitElements() ?: error("No orbital elements"))
                            val end = record.predictionEndBeforeDecay(now, now.plus(Duration.ofHours(24)))
                                ?: error("Prediction unavailable from source-reported decay date")
                            orbit.lookFrom(site, now) to
                                orbit.predictPasses(site, now, end).firstOrNull()
                        }
                    }
                    targetLook = result.getOrNull()?.first
                    nextPass = result.getOrNull()?.second
                    targetPredictionError = result.exceptionOrNull()?.let {
                        if (it is CancellationException) throw it
                        "This orbit could not be propagated; look angle and pass times are unavailable."
                    }
                } else {
                    targetLook = null
                    nextPass = null
                    targetPredictionError = null
                }
                delay(30_000)
            }
        }
    }
    LaunchedEffect(demoRunning) {
        if (!demoRunning) {
            demoSnapshot = demoSnapshot?.copy(state = "Synthetic stopped")
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var handle = 0L
            val sessionId = UUID.randomUUID().toString()
            val waterfall = ArrayDeque<List<Float>>()
            var lastProcessed = 0L
            var samplesUpdatedAtElapsedMs: Long? = null
            demoSnapshot = ReceptionSnapshot(state = "Synthetic preview", sessionId = sessionId,
                device = "Generated IQ · no USB", sampleRateSps = 1_024_000)
            try {
                handle = NativeReceiver.nativeCreate(1_024_000)
                check(handle != 0L) { "Native test receiver could not start" }
                while (isActive) {
                    check(NativeReceiver.nativeGenerateTestTone(handle, demoToneHz.toDouble(), 16_384) >= 0) {
                        "Could not generate test IQ"
                    }
                    delay(100)
                    val spectrum = NativeReceiver.nativeSpectrum(handle).toList()
                    val stats = NativeReceiver.nativeStats(handle)
                    val processed = stats.getOrElse(2) { 0 }
                    if (processed > lastProcessed) {
                        samplesUpdatedAtElapsedMs = SystemClock.elapsedRealtime()
                        if (spectrum.size == 256) {
                            waterfall.addLast(List(128) { index ->
                                maxOf(spectrum[2 * index], spectrum[2 * index + 1])
                            })
                            while (waterfall.size > 48) waterfall.removeFirst()
                        }
                    }
                    lastProcessed = processed
                    demoSnapshot = ReceptionSnapshot(
                        state = "Synthetic preview", sessionId = sessionId,
                        device = "Generated IQ · no USB", spectrum = spectrum,
                        iqSamples = NativeReceiver.nativeIqSnapshot(handle).toList(),
                        spectrogram = waterfall.toList(), acceptedSamples = stats.getOrElse(0) { 0 },
                        droppedSamples = stats.getOrElse(1) { 0 }, processedSamples = processed,
                        samplesUpdatedAtElapsedMs = samplesUpdatedAtElapsedMs, sampleRateSps = 1_024_000,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                demoError = error.message ?: "Native receiver unavailable"
                demoSnapshot = demoSnapshot?.copy(state = "Synthetic stopped", error = demoError)
                demoRunning = false
            } finally {
                if (demoSnapshot?.sessionId == sessionId) {
                    demoSnapshot = demoSnapshot?.copy(state = "Synthetic stopped")
                }
                if (handle != 0L) NativeReceiver.nativeDestroy(handle)
            }
        }
    }

    Scaffold(
        topBar = {
            Surface(color = OrbitColors.background) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(stringResource(R.string.app_name), color = OrbitColors.cyan,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(tab.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text(when (catalog?.source) {
                            CatalogSource.LIVE -> if (supplementalRecords.isEmpty()) "SIGNED LIVE" else "SIGNED + EXTRA"
                            CatalogSource.CACHED -> if (supplementalRecords.isEmpty()) "SIGNED CACHE" else "SIGNED + EXTRA"
                            CatalogSource.DEMO -> "DEMO ONLY"
                            null -> "LOADING"
                        }, color = if (catalog?.source == CatalogSource.DEMO) OrbitColors.amber else OrbitColors.cyan,
                            style = MaterialTheme.typography.labelSmall)
                        Text("${catalog?.manifest?.satellites?.size ?: 0} orbits",
                            color = OrbitColors.muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = OrbitColors.surface) {
                AppTab.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = tab == destination,
                        onClick = { tab = destination },
                        icon = { Text(destination.symbol) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        when (tab) {
            AppTab.SKY -> Column(Modifier.fillMaxSize().padding(contentPadding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !skyGlobe, onClick = { skyGlobe = false },
                        label = { Text("List") })
                    FilterChip(selected = skyGlobe, onClick = { skyGlobe = true },
                        label = { Text("Globe") })
                }
                if (skyGlobe) GlobeScreen(
                    catalog = catalog,
                    observer = observer,
                    selectedNoradId = globeSelectedNoradId,
                    onSelectionChanged = { globeSelectedNoradId = it },
                    onTrack = { selectedNoradId = it.noradId; tab = AppTab.TARGET },
                    modifier = Modifier.weight(1f),
                ) else DayScheduleScreen(
                catalog = catalog,
                observer = dayScheduleObserver,
                onSatelliteSelected = { selectedNoradId = it.noradId; tab = AppTab.TARGET },
                scheduleCache = dayScheduleCache,
                modifier = Modifier.weight(1f),
                onMissingNoradLookup = ::lookUpMissingOrbit,
                lookupRunning = lookupRunning,
                lookupMessage = lookupMessage,
                supplementalRevision = supplementalRevision,
                onImportFile = { importFile.launch(arrayOf("application/json", "text/csv", "text/plain", "application/octet-stream", "*/*")) },
                importRunning = importRunning,
                importMessage = importMessage,
                importedCount = imported?.records?.size ?: 0,
                )
            }
            AppTab.TARGET -> LazyColumn(
                Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Track & tune", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Orbit prediction selects a target. A USB SDR and an active public downlink are needed to receive.",
                            color = OrbitColors.muted)
                    }
                }
                if (currentRecommendation != null) item {
                    OrbitPanel {
                        Text("AUTOMATIC SUGGESTION", color = OrbitColors.cyan,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(currentRecommendation.name, style = MaterialTheme.typography.titleLarge)
                        Text("Highest visible, configured public downlink in the current catalog.",
                            color = OrbitColors.muted)
                        if (selected != null) OutlinedButton(onClick = { selectedNoradId = null }) { Text("Track suggested target") }
                    }
                }
                if (target == null) item {
                    OrbitPanel {
                        Text("No target selected", style = MaterialTheme.typography.titleLarge)
                        Text("Choose a satellite in Sky, or wait for an eligible downlink to rise. Tracking records can be viewed even without a receiver.")
                        OutlinedButton(onClick = { tab = AppTab.SKY }) { Text("Browse satellites") }
                    }
                }
                if (target != null) {
                    item {
                        OrbitPanel {
                            Text(if (selected == null) "AUTO TARGET" else "SELECTED TARGET",
                                color = OrbitColors.cyan, style = MaterialTheme.typography.labelMedium)
                            Text(target.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("NORAD ${target.noradId} · ${target.transmitters.size} cataloged downlinks")
                            OutlinedButton(onClick = {
                                globeSelectedNoradId = target.noradId
                                skyGlobe = true
                                tab = AppTab.SKY
                            }) { Text("View on globe") }
                            target.orbitHistoryLabel(targetNow)?.let { Text(it, color = OrbitColors.amber) }
                            if (target.transmitters.isNotEmpty()) {
                                Text("${targetDownlinks?.receiverConfigured?.size ?: 0} configured for this receiver · " +
                                    "${targetDownlinks?.referenceOnly?.size ?: 0} reference entries",
                                    color = OrbitColors.muted)
                            }
                            if (signedTarget?.orbitElements() != target.orbitElements() && target.transmitters.isEmpty()) {
                                val importedRecord = imported?.records?.find { it.noradId == target.noradId }
                                if (importedRecord?.orbitElements() == target.orbitElements()) {
                                    Text(imported?.sourceFor(target.noradId)?.provenance.orEmpty(),
                                        color = OrbitColors.amber)
                                } else supplemental.find { it.noradId == target.noradId &&
                                    it.satellite.orbitElements() == target.orbitElements() }?.let { extra ->
                                    Text(extra.provenance, color = OrbitColors.amber)
                                    extra.warning?.let { Text(it, color = OrbitColors.amber) }
                                }
                            }
                            if (!targetIsHistorical && target.orbitElements()?.epoch?.let { isFreshWithin72Hours(it, targetNow) } != true) {
                                Text("Orbital elements are older than 72 h; predicted passes may be inaccurate.",
                                    color = OrbitColors.amber)
                            }
                            if (targetIsHistorical) {
                                Text("Current position, pass prediction and reception are disabled from the source-reported decay date. The source supplies a UTC calendar date without an exact event time or cause.",
                                    color = OrbitColors.amber)
                            }
                            else if (observer == null) Text("Set location to calculate elevation and passes.", color = OrbitColors.amber)
                            else {
                                if (targetPredictionError != null) {
                                    Text(targetPredictionError.orEmpty(), color = OrbitColors.amber)
                                } else {
                                    val look = targetLook
                                    Text(if (look == null) "Calculating look angle…" else
                                        "${"%.1f".format(Locale.US, look.elevationDegrees)}° elevation · ${"%.0f".format(Locale.US, look.azimuthDegrees)}° azimuth",
                                        color = if (look?.elevationDegrees ?: -1.0 >= 0) OrbitColors.cyan else OrbitColors.muted)
                                    Text(target.targetPredictionPassLabel(nextPass, targetNow, ::formatOrbitTime))
                                }
                            }
                            Text("Orbit prediction does not confirm a transmitted signal.", style = MaterialTheme.typography.bodySmall,
                                color = OrbitColors.muted)
                        }
                    }
                    if (target.transmitters.isEmpty()) item {
                        OrbitPanel {
                            Text(if (targetIsHistorical) "Historical record" else "Tracking only", style = MaterialTheme.typography.titleMedium)
                            Text(if (targetIsHistorical)
                                "No transmitter is configured for this historical record. Its orbital elements remain available in Sky."
                            else "No transmitter is configured for this orbital record. Orbit tracking requires usable elements.")
                        }
                    }
                    if (targetDownlinks?.receiverConfigured?.isNotEmpty() == true) item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${if (targetIsHistorical) "HISTORICAL RECEIVER PROFILES" else "CONFIGURED FOR RECEPTION"} · ${targetDownlinks.receiverConfigured.size}",
                                color = OrbitColors.cyan, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold)
                            Text(if (targetIsHistorical) "Cataloged receiver profiles retained for reference. Current reception is disabled."
                            else "Curated public or amateur downlinks with a supported decoder. Reception also needs a current signed catalog and a visible pass.",
                                color = OrbitColors.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (targetDownlinks?.receiverConfigured?.isEmpty() == true && target.transmitters.isNotEmpty()) item {
                        OrbitPanel {
                            Text("No receiver-configured downlink", style = MaterialTheme.typography.titleMedium)
                            Text(if (targetIsHistorical) "None of this historical target's downlinks is configured for reception in this app."
                            else "This target can be tracked, but none of its cataloged downlinks is configured for reception in this app.",
                                color = OrbitColors.muted)
                        }
                    }
                    items(targetDownlinks?.receiverConfigured.orEmpty(), key = { "downlink:${it.id}" }) { tx ->
                        val now = Instant.now()
                        val fresh = catalog?.source != CatalogSource.DEMO &&
                            catalog?.manifest?.sourceUpdatedAt?.let { isFreshWithin72Hours(it, now) } == true &&
                            target.orbitElements()?.epoch?.let { isFreshWithin72Hours(it, now) } == true
                        val signedSelection = signedTarget?.orbitElements() == target.orbitElements() &&
                            signedTarget?.transmitters?.contains(tx) == true
                        val historical = target.isKnownDecayedAt(now)
                        val eligible = !historical && signedSelection && fresh && observer != null &&
                            (targetLook?.elevationDegrees ?: -90.0) >= 0
                        OrbitPanel {
                            Text(if (historical) "HISTORICAL RECEIVER PROFILE" else "RECEIVER CONFIGURED", color = OrbitColors.cyan,
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("${"%.6f".format(Locale.US, tx.frequencyHz / 1_000_000.0)} MHz",
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${tx.mode} · ${tx.decoderId ?: "no supported decoder"} · ${tx.status}")
                            Text(tx.policyAndAntennaLabel, color = OrbitColors.muted)
                            if (!eligible) Text(when {
                                historical -> "Reception disabled from source-reported decay date."
                                !signedSelection -> "Select this downlink from the current signed catalog."
                                !fresh -> "A fresh signed catalog and orbital elements are required."
                                observer == null -> "Set your observer location first."
                                else -> "Below the horizon. Wait for the next predicted pass."
                            }, color = OrbitColors.amber)
                            if (eligible) {
                                val devices = usbDevices()
                                if (devices.isEmpty()) Text("Connect RTL-SDR or HackRF One using phone USB host/OTG.",
                                    color = OrbitColors.amber)
                                devices.forEach { choice ->
                                    Button(onClick = { startReception(choice, target, tx, observer!!,
                                        locationMode == LocationMode.AUTO,
                                        (locationState as? DeviceLocationState.Fix)?.fixElapsedRealtimeMillis
                                            .takeIf { locationMode == LocationMode.AUTO }) }) {
                                        Text("Receive with ${choice.label.substringBefore('·').trim()}")
                                    }
                                }
                            }
                        }
                    }
                    if (targetDownlinks?.referenceOnly?.isNotEmpty() == true) item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("OTHER CATALOGED DOWNLINKS · ${targetDownlinks.referenceOnly.size}",
                                color = OrbitColors.muted, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold)
                            Text("Reference frequencies. Reception is unavailable for these entries in this app.",
                                color = OrbitColors.muted, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { showReferenceDownlinks = !showReferenceDownlinks }) {
                                Text(if (showReferenceDownlinks) "Hide reference downlinks" else
                                    "Show ${targetDownlinks.referenceOnly.size} reference downlinks")
                            }
                        }
                    }
                    if (showReferenceDownlinks) items(targetDownlinks?.referenceOnly.orEmpty(),
                        key = { "reference:${it.id}" }) { tx ->
                        OrbitPanel {
                            Text("REFERENCE ONLY", color = OrbitColors.muted,
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("${"%.6f".format(Locale.US, tx.frequencyHz / 1_000_000.0)} MHz",
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${tx.mode} · ${tx.status}")
                            Text(tx.policyAndAntennaLabel, color = OrbitColors.muted)
                            Text(receiverConfigurationIssue(tx) ?: "Reception is unavailable for this entry.",
                                color = OrbitColors.amber)
                        }
                    }
                }
                item { Text("Receive starts manually and keeps its target and downlink. In automatic location mode, fresh GPS fixes update pointing and Doppler while the app is visible; in the background the last observer is held. Restart after choosing another target or manual/map location. Decoder output requires valid frames.",
                    modifier = Modifier.padding(bottom = 20.dp), color = OrbitColors.muted,
                    style = MaterialTheme.typography.bodySmall) }
            }
            AppTab.SIGNAL -> LazyColumn(
                Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Signal lab", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SignalSource.entries.forEach { source ->
                                FilterChip(selected = signalSource == source, onClick = {
                                    signalSource = source
                                    if (source != SignalSource.TEST_TONE) demoRunning = false
                                }, label = { Text(source.label) })
                            }
                        }
                    }
                }
                when (signalSource) {
                    SignalSource.RECEIVER -> {
                        item {
                            OrbitPanel {
                                Text("SATELLITE RECEIVER", color = OrbitColors.cyan, style = MaterialTheme.typography.labelMedium)
                                Text(reception.error ?: reception.state, style = MaterialTheme.typography.titleLarge,
                                    color = if (reception.error == null) OrbitColors.white else OrbitColors.red)
                                if (reception.device.isNotEmpty()) Text(reception.device)
                                if (reception.state in setOf("Starting", "Receiving")) {
                                    Text("${reception.processedSamples} complex samples processed · ${reception.droppedSamples} dropped")
                                    OutlinedButton(onClick = stopReception) { Text("Stop receiver") }
                                } else if (reception.state == "Idle") {
                                    Text("Select a visible satellite and configured downlink in Target to start receiving.")
                                }
                                usbMessage?.let { Text(it, color = OrbitColors.amber) }
                            }
                        }
                        item { SignalVisuals(reception) }
                        if (reception.packets.isNotEmpty()) item {
                            OrbitPanel {
                                Text("CRC-CHECKED AX.25 FRAMES", color = OrbitColors.cyan,
                                    style = MaterialTheme.typography.labelMedium)
                                reception.packets.takeLast(10).forEach { Text(it) }
                            }
                        }
                    }
                    SignalSource.SDR_TESTER -> {
                        item {
                            OrbitPanel {
                                Text("SDR HARDWARE TEST", color = OrbitColors.cyan, style = MaterialTheme.typography.labelMedium)
                                Text("Check USB access and sample streaming without a satellite signal, location or catalog.")
                                Text("Runs for 10 seconds, receive only, decoder off. Noise is enough for this check; it does not prove antenna performance or decoding.",
                                    color = OrbitColors.muted, style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(testFrequencyMHz, { testFrequencyMHz = it },
                                    label = { Text("Test frequency (MHz)") }, singleLine = true,
                                    enabled = !diagnosticCaptureRunning, modifier = Modifier.fillMaxWidth())
                                Text("Use a frequency supported by your SDR. Default: 100 MHz.",
                                    color = OrbitColors.muted, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = scanUsbSdr, enabled = !diagnosticCaptureRunning) { Text("Scan USB SDR") }
                                if (diagnosticDevices.isNullOrEmpty()) {
                                    Text("Connect an RTL-SDR or HackRF One through a working USB OTG adapter, then scan.")
                                }
                                diagnosticDevices?.forEach { choice ->
                                    Text(choice.label)
                                    Button(onClick = {
                                        demoRunning = false
                                        captureUsbIq(choice, testFrequencyMHz)
                                    }, enabled = !diagnosticCaptureRunning && reception.state !in setOf("Starting", "Receiving")) {
                                        Text("Test SDR for 10 seconds")
                                    }
                                    OutlinedButton(onClick = { requestUsbAccess(choice) }, enabled = !diagnosticCaptureRunning) {
                                        Text("Request USB access")
                                    }
                                }
                                if (reception.state in setOf("Starting", "Receiving")) {
                                    Text("Stop the satellite receiver before testing the SDR.", color = OrbitColors.amber)
                                }
                                if (diagnosticCaptureRunning) {
                                    OutlinedButton(onClick = stopUsbTest) { Text("Stop SDR test") }
                                }
                                diagnosticMessage?.let { Text(it, color = OrbitColors.amber) }
                            }
                        }
                        if (diagnosticSnapshot != null) item { SignalVisuals(diagnosticSnapshot) }
                    }
                    SignalSource.TEST_TONE -> {
                        item {
                            OrbitPanel {
                                Text("GENERATED TEST TONE", color = OrbitColors.cyan, style = MaterialTheme.typography.labelMedium)
                                Text("Exercises the plots using locally generated IQ. This does not test an attached SDR.")
                                Text("Tone: ${"%.1f".format(Locale.US, demoToneHz / 1_000f)} kHz")
                                Slider(value = demoToneHz, onValueChange = { demoToneHz = it }, valueRange = 1_000f..100_000f)
                                Button(onClick = { demoRunning = !demoRunning; demoError = null },
                                    enabled = demoRunning || (!diagnosticCaptureRunning && !receiving)) {
                                    Text(if (demoRunning) "Stop test tone" else "Start test tone")
                                }
                                if (diagnosticCaptureRunning || reception.state in setOf("Starting", "Receiving")) {
                                    Text("Stop the active SDR stream before starting a generated tone.", color = OrbitColors.amber)
                                }
                                demoError?.let { Text(it, color = OrbitColors.red) }
                            }
                        }
                        if (demoSnapshot != null) item { SignalVisuals(demoSnapshot!!) }
                    }
                }
                item { Text("Plots show captured IQ; they do not confirm a satellite signal or decoding.",
                    modifier = Modifier.padding(bottom = 20.dp), color = OrbitColors.muted,
                    style = MaterialTheme.typography.bodySmall) }
            }
            AppTab.LOCATION -> LazyColumn(
                Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Observer location", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Your position sets horizon visibility, pass times and Doppler correction.",
                            color = OrbitColors.muted)
                    }
                }
                item {
                    OrbitPanel {
                        Text("CURRENT POSITION", color = OrbitColors.cyan, style = MaterialTheme.typography.labelMedium)
                        Text(observer?.let {
                            "${"%.5f".format(Locale.US, it.latitudeDegrees)}°, ${"%.5f".format(Locale.US, it.longitudeDegrees)}°"
                        } ?: "No location set", style = MaterialTheme.typography.titleLarge)
                        locationMessage?.let { Text(it, color = OrbitColors.muted) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LocationMode.entries.forEach { mode ->
                                val label = when (mode) {
                                    LocationMode.AUTO -> "GPS"
                                    LocationMode.MAP -> "Map"
                                    LocationMode.MANUAL -> "Manual"
                                }
                                if (locationMode == mode) Button(onClick = { chooseLocationMode(mode) }) { Text(label) }
                                else OutlinedButton(onClick = { chooseLocationMode(mode) }) { Text(label) }
                            }
                        }
                    }
                }
                when (locationMode) {
                    LocationMode.AUTO -> item {
                        OrbitPanel {
                            Text("AUTOMATIC LOCATION", color = OrbitColors.cyan, style = MaterialTheme.typography.labelMedium)
                            Text(when (val state = locationState) {
                                DeviceLocationState.PermissionRequired -> "Allow location permission to use the phone's location providers."
                                DeviceLocationState.ServicesDisabled -> "Android location services are off."
                                is DeviceLocationState.Searching -> if (state.takingLonger) "Still searching for a location fix. Move outdoors or use the map." else "Searching the phone's location providers…"
                                is DeviceLocationState.Fix -> "${state.provider} fix · ${state.accuracyMeters?.let { "${it.toInt()} m accuracy · " } ?: ""}${state.ageSeconds} s old${if (state.fromRecentCache) " · recent cached fix" else ""}"
                                is DeviceLocationState.Unavailable -> state.reason
                            })
                            if (!locationPermissionGranted) when (missingLocationAction) {
                                LocationPermissionAction.REQUEST -> Button(onClick = ::requestLocationPermission) {
                                    Text("Allow device location")
                                }
                                LocationPermissionAction.WAIT -> Text("Waiting for Android's location permission choice…")
                                LocationPermissionAction.SETTINGS -> {
                                    Text("Android will not show another location permission prompt. Enable location access in app settings, then return.",
                                        color = OrbitColors.amber)
                                    OutlinedButton(onClick = ::openAppPermissionSettings) { Text("Open app settings") }
                                }
                            }
                            if (locationPermissionGranted && !preciseLocationGranted) {
                                Text("Approximate location is available; precise GPS location needs permission.",
                                    color = OrbitColors.amber)
                                when (preciseLocationAction) {
                                    LocationPermissionAction.REQUEST -> OutlinedButton(onClick = ::requestLocationPermission) {
                                        Text("Allow precise GPS")
                                    }
                                    LocationPermissionAction.WAIT -> Text("Waiting for Android's location permission choice…")
                                    LocationPermissionAction.SETTINGS -> {
                                        Text("Enable precise location in app settings, then return.", color = OrbitColors.amber)
                                        OutlinedButton(onClick = ::openAppPermissionSettings) { Text("Open app settings") }
                                    }
                                }
                            }
                            if (observer != null) Text("The Sky day schedule refreshes after about 100 m of movement. Current position and target pointing use every fix.",
                                style = MaterialTheme.typography.bodySmall, color = OrbitColors.muted)
                            if (locationState is DeviceLocationState.ServicesDisabled) {
                                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                                    Text("Open location settings")
                                }
                            }
                        }
                    }
                    LocationMode.MAP -> item {
                        OrbitPanel {
                            WorldMapPicker(currentLocation = observer, onLocationChosen = {
                                selectUserObserver(it, LocationMode.MAP, "Selected on map")
                            })
                        }
                    }
                    LocationMode.MANUAL -> item {
                        OrbitPanel {
                            Text("MANUAL COORDINATES", color = OrbitColors.cyan, style = MaterialTheme.typography.labelMedium)
                            Text("Use decimal degrees. Latitude −90…90; longitude −180…180.")
                            OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                val location = runCatching {
                                    ObserverLocation(latitude.toDouble(), longitude.toDouble())
                                }.getOrNull()
                                if (location == null) locationMessage = "Enter valid latitude and longitude."
                                else selectUserObserver(location, LocationMode.MANUAL, "Manual coordinates")
                            }) { Text("Use coordinates") }
                        }
                    }
                }
                item {
                    OrbitPanel {
                        Text("ORBITAL CATALOG", color = OrbitColors.cyan, style = MaterialTheme.typography.labelMedium)
                        Text(when (catalog?.source) {
                            CatalogSource.LIVE -> "Signed live catalog · ${signedCatalog?.manifest?.satellites?.size} records"
                            CatalogSource.CACHED -> "Verified signed cache · ${signedCatalog?.manifest?.satellites?.size} records"
                            CatalogSource.DEMO -> "Historical demo · hardware reception disabled"
                            null -> "Loading catalog…"
                        })
                        catalog?.warning?.let { Text(it, color = OrbitColors.amber) }
                        catalogError?.let { Text(it, color = OrbitColors.red) }
                        if (supplemental.isNotEmpty()) Text("${supplemental.size} direct CelesTrak orbit lookup(s) added for tracking only.",
                            color = OrbitColors.amber)
                        if (imported != null) Text("${imported!!.records.size} user-supplied orbital record(s) saved for tracking only.",
                            color = OrbitColors.amber)
                        OutlinedButton(onClick = { scope.launch { loadCatalog() } }, enabled = !catalogLoading) {
                            Text("Refresh catalog")
                        }
                    }
                }
                item { Text("Location stays on the phone and is used for local pass and Doppler calculations.",
                    modifier = Modifier.padding(bottom = 20.dp), color = OrbitColors.muted,
                    style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun Context.hasLocationPermission(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun Context.hasPreciseLocationPermission(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
private fun OrbitPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = OrbitColors.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, OrbitColors.surfaceRaised),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun formatOrbitTime(time: Instant): String =
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(time)
