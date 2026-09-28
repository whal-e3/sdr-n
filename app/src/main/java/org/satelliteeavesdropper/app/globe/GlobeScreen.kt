package org.satelliteeavesdropper.app.globe

import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.OrbitColors
import org.satelliteeavesdropper.app.data.CatalogLoadResult
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.isFreshWithin72Hours
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.SatelliteOrbit
import org.satelliteeavesdropper.orbit.SatellitePass
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The globe owns its preview clock. Only explicit Track navigation reaches the live Target. */
@Composable
internal fun GlobeScreen(
    catalog: CatalogLoadResult?,
    observer: ObserverLocation?,
    selectedNoradId: String?,
    onSelectionChanged: (String) -> Unit,
    onTrack: (SatelliteRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val records = catalog?.manifest?.satellites.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var radioProfilesOnly by rememberSaveable { mutableStateOf(true) }
    var aboveHorizonOnly by rememberSaveable { mutableStateOf(false) }
    var liveNow by remember { mutableStateOf(Instant.now()) }
    var previewAnchorMillis by rememberSaveable { mutableStateOf(Instant.now().toEpochMilli()) }
    var previewMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
    val isPreview = previewMillis != null
    val at = previewMillis?.let(Instant::ofEpochMilli) ?: liveNow
    val selectedRecord = remember(records, selectedNoradId) {
        records.firstOrNull { it.noradId == selectedNoradId }
    }

    // Resume from the shown preview instant; time spent behind another screen is not played.
    LaunchedEffect(lifecycle, playing, isPreview) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastTick = SystemClock.elapsedRealtime()
            while (isActive) {
                val tick = SystemClock.elapsedRealtime()
                liveNow = Instant.now()
                if (playing && previewMillis != null) {
                    val end = previewAnchorMillis + PREVIEW_WINDOW_MILLIS
                    previewMillis = (previewMillis!! + (tick - lastTick) * 60L).coerceAtMost(end)
                    if (previewMillis == end) playing = false
                }
                lastTick = tick
                delay(if (playing) 250L else 1_000L)
            }
        }
    }

    var candidates by remember { mutableStateOf<GlobeCandidates?>(null) }
    var engine by remember { mutableStateOf<GlobeSceneEngine?>(null) }
    var scene by remember { mutableStateOf<GlobeScene?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchResults by remember { mutableStateOf<List<SatelliteRecord>>(emptyList()) }
    var searchCount by remember { mutableStateOf(0) }
    // Source historical cutoffs are UTC dates. Rebuild counts when preview crosses midnight.
    LaunchedEffect(records, query, radioProfilesOnly, selectedRecord, at.atZone(ZoneOffset.UTC).toLocalDate()) {
        engine = null
        scene = null
        candidates = null
        error = null
        if (records.isEmpty()) return@LaunchedEffect
        delay(200L)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                val selection = selectGlobeCandidates(records, query, radioProfilesOnly, selectedRecord, at)
                candidates = selection
                engine = withContext(Dispatchers.Default) { GlobeSceneEngine(selection) }
                if (selectedNoradId == null) {
                    val preferred = selection.records.firstOrNull { it.noradId == "25544" }
                        ?: selection.records.firstOrNull()
                    preferred?.let { onSelectionChanged(it.noradId) }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "Could not prepare globe positions. ${failure.message.orEmpty()}"
            }
        }
    }
    LaunchedEffect(records, query) {
        searchResults = emptyList()
        searchCount = 0
        if (query.isBlank()) return@LaunchedEffect
        delay(250L)
        val result = withContext(Dispatchers.Default) {
            val current = currentCoroutineContext()
            var count = 0
            val first = ArrayList<SatelliteRecord>(8)
            records.forEach { record ->
                current.ensureActive()
                if (matchesGlobeQuery(record, query)) {
                    count++
                    if (first.size < 8) first += record
                }
            }
            count to first
        }
        searchCount = result.first
        searchResults = result.second
    }
    LaunchedEffect(engine, at, observer, selectedNoradId, aboveHorizonOnly) {
        val currentEngine = engine ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                scene = currentEngine.snapshot(at, observer, selectedNoradId,
                    aboveHorizonOnly && observer != null)
                error = null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "Could not update globe positions. ${failure.message.orEmpty()}"
            }
        }
    }

    var nextPass by remember { mutableStateOf<SatellitePass?>(null) }
    var passLoading by remember { mutableStateOf(false) }
    var passError by remember { mutableStateOf<String?>(null) }
    // A single-target pass is refreshed each shown minute, independently of marker animation.
    LaunchedEffect(selectedRecord, observer, at.epochSecond / 60L) {
        nextPass = null
        passError = null
        val record = selectedRecord ?: return@LaunchedEffect
        val site = observer ?: return@LaunchedEffect
        if (record.isKnownDecayedAt(at)) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            passLoading = true
            try {
                nextPass = withContext(Dispatchers.Default) {
                    val end = record.predictionEndBeforeDecay(at, at.plus(Duration.ofHours(24)))
                        ?: return@withContext null
                    SatelliteOrbit(record.orbitElements() ?: error("No valid orbital elements"))
                        .predictPasses(site, at, end, stepSeconds = 120).firstOrNull()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                passError = "Pass prediction unavailable for these elements."
            } finally {
                passLoading = false
            }
        }
    }

    fun previewAt(time: Instant) {
        playing = false
        previewAnchorMillis = liveNow.toEpochMilli()
        previewMillis = time.toEpochMilli().coerceIn(previewAnchorMillis,
            previewAnchorMillis + PREVIEW_WINDOW_MILLIS)
    }

    fun togglePlayback() {
        if (!isPreview) previewAt(liveNow)
        playing = !playing
    }

    fun returnToLive() {
        playing = false
        previewMillis = null
        liveNow = Instant.now()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
    // The square globe follows the current window, including rotation and unfolding. The
    // surrounding controls stay readable without a tablet-wide globe pushing them far away.
    val globeWidth = minOf((maxWidth - 32.dp).coerceAtLeast(0.dp),
        (maxHeight * 0.65f).coerceIn(180.dp, 520.dp))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Orbit explorer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (isPreview) "PREVIEW TIME" else "LIVE PREDICTION",
                color = if (isPreview) OrbitColors.amber else OrbitColors.cyan,
                style = MaterialTheme.typography.labelSmall)
        }
        OutlinedTextField(value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().testTag("globe-search"), singleLine = true,
            label = { Text("Search every orbit by name or NORAD ID") },
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
        if (query.isNotBlank()) {
            Text("$searchCount catalog matches · showing first ${searchResults.size}",
                style = MaterialTheme.typography.labelSmall, color = OrbitColors.muted)
            searchResults.forEach { record ->
                TextButton(onClick = { onSelectionChanged(record.noradId); playing = false },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("${record.name} · ${record.noradId}" +
                        if (record.isKnownDecayedAt(at)) " · historical" else "")
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = radioProfilesOnly, onClick = { radioProfilesOnly = true },
                label = { Text("Radio profiles") })
            FilterChip(selected = !radioProfilesOnly, onClick = { radioProfilesOnly = false },
                label = { Text("All orbits") })
            FilterChip(selected = aboveHorizonOnly,
                onClick = { aboveHorizonOnly = !aboveHorizonOnly }, enabled = observer != null,
                label = { Text("Above horizon") })
        }
        if (engine == null && records.isNotEmpty() && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        val displayed = scene
        Box(Modifier.width(globeWidth).align(Alignment.CenterHorizontally)) {
            GlobeCanvas(
                markers = displayed?.markers.orEmpty(),
                selectedNoradId = selectedNoradId,
                orbitPath = displayed?.orbitPath.orEmpty(),
                groundTrack = displayed?.groundTrack.orEmpty(),
                observer = observer,
                onSatelliteSelected = { onSelectionChanged(it); playing = false },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 4.dp)) {
                OutlinedButton(onClick = ::togglePlayback, modifier = Modifier.testTag("globe-play")) {
                    Text(if (playing) "Pause" else "Play 60×")
                }
                if (isPreview) TextButton(onClick = ::returnToLive) { Text("Back to live") }
            }
        }
        Text("${displayed?.markers?.size ?: 0} predicted positions · ${candidates?.records?.size ?: 0} sampled / " +
            "${candidates?.matchingCount ?: 0} matching orbits · ${records.size} catalog records",
            color = OrbitColors.muted, style = MaterialTheme.typography.labelSmall)
        Text("Up to 128 filter matches plus your selected target. Earth hides satellites on the far side.",
            color = OrbitColors.muted, style = MaterialTheme.typography.labelSmall)
        displayed?.let {
            Text("Positions at ${formatGlobeTime(it.at)}", color = OrbitColors.muted,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("globe-position-time"))
        }
        if (candidates?.historicalCount ?: 0 > 0) Text(
            "${candidates?.historicalCount} source-dated historical matches remain searchable; current positions are withheld.",
            color = OrbitColors.muted, style = MaterialTheme.typography.labelSmall)
        if (aboveHorizonOnly) Text("Above-horizon filter applies to the sampled subset. Selected target stays available.",
            color = OrbitColors.muted, style = MaterialTheme.typography.labelSmall)
        if (displayed?.failedCount ?: 0 > 0) Text("${displayed?.failedCount} sampled orbits have unavailable positions or paths.",
            color = OrbitColors.amber, style = MaterialTheme.typography.labelSmall)
        error?.let { Text(it, color = OrbitColors.amber) }
        if (catalog?.source == CatalogSource.DEMO) Text("Historical demo catalog · prediction accuracy is uncertain.", color = OrbitColors.amber)
        catalog?.manifest?.sourceUpdatedAt?.let { updated ->
            Text("Catalog source: ${formatGlobeTime(updated)}" +
                if (isFreshWithin72Hours(updated, liveNow)) "" else " · older than 72 h or future-dated",
                color = OrbitColors.muted, style = MaterialTheme.typography.labelSmall)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isPreview) "Preview time" else "Live time", fontWeight = FontWeight.Bold)
                Text(formatGlobeTime(at), color = if (isPreview) OrbitColors.amber else OrbitColors.cyan,
                    modifier = Modifier.testTag("globe-time"))
                Text("Explore the next 24 hours", style = MaterialTheme.typography.bodySmall, color = OrbitColors.muted)
                Slider(value = if (isPreview) ((previewMillis!! - previewAnchorMillis) / 60_000f).coerceIn(0f, 1440f) else 0f,
                    onValueChange = { minutes ->
                        playing = false
                        if (!isPreview) previewAnchorMillis = liveNow.toEpochMilli()
                        previewMillis = previewAnchorMillis + (minutes * 60_000L).toLong()
                    }, valueRange = 0f..1440f,
                    modifier = Modifier.semantics { contentDescription = "Preview time over the next 24 hours" })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = ::togglePlayback) { Text(if (playing) "Pause" else "Play 60×") }
                    OutlinedButton(onClick = { previewAt(at.plus(Duration.ofHours(1))) }) { Text("+1 hour") }
                    Button(onClick = ::returnToLive, enabled = isPreview) {
                        Text("Back to live")
                    }
                }
                if (isPreview) Text("Target uses the current time when opened. Preview time stays in this globe.",
                    color = OrbitColors.muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (selectedRecord != null) Card(Modifier.fillMaxWidth().testTag("globe-selected")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SELECTED SATELLITE", color = OrbitColors.cyan, style = MaterialTheme.typography.labelSmall)
                Text(selectedRecord.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("NORAD ${selectedRecord.noradId}")
                if (selectedRecord.isKnownDecayedAt(at)) Text("Source-dated historical record: position withheld at this time.", color = OrbitColors.amber)
                if (!selectedRecord.isKnownDecayedAt(at)) displayed?.selectedError?.let { Text(it, color = OrbitColors.amber) }
                val marker = displayed?.markers?.firstOrNull { it.noradId == selectedNoradId }
                marker?.let {
                    Text("${String.format(Locale.US, "%.0f", it.position.altitudeKm)} km above spherical Earth · predicted position")
                }
                displayed?.selectedLook?.let { look ->
                    Text(String.format(Locale.US, "Azimuth %.1f° · elevation %.1f° · range %.0f km",
                        look.azimuthDegrees, look.elevationDegrees, look.slantRangeKm),
                        color = if (look.elevationDegrees >= 0) OrbitColors.cyan else OrbitColors.muted)
                }
                selectedRecord.orbitElements()?.epoch?.let { epoch ->
                    val age = Duration.between(epoch, liveNow)
                    Text("Elements: ${formatGlobeTime(epoch)} · " +
                        if (age.isNegative) "future-dated" else "${age.toHours()} h old",
                        style = MaterialTheme.typography.bodySmall, color = OrbitColors.muted)
                    if (!selectedRecord.isKnownDecayedAt(at) && !isFreshWithin72Hours(epoch, liveNow))
                        Text("Element age makes this prediction uncertain.", color = OrbitColors.amber)
                }
                if (observer == null) Text("Set your location to calculate pointing and passes.", color = OrbitColors.amber)
                if (passLoading) Text("Calculating selected pass…", color = OrbitColors.muted)
                passError?.let { Text(it, color = OrbitColors.amber) }
                nextPass?.let { pass ->
                    Text(if (!at.isBefore(pass.aos) && at.isBefore(pass.los))
                        "Above horizon until ${formatGlobeTime(pass.los)}" else
                        "Next rise ${formatGlobeTime(pass.aos)}")
                    Text(String.format(Locale.US, "Peak %.1f° at %s", pass.maximumElevationDegrees, formatGlobeTime(pass.tca)),
                        color = OrbitColors.muted)
                    val previewStatus = globePassPreviewStatus(pass.tca, liveNow)
                    OutlinedButton(onClick = {
                        val actionNow = Instant.now()
                        liveNow = actionNow
                        if (globePassPreviewStatus(pass.tca, actionNow) == GlobePassPreviewStatus.AVAILABLE) {
                            previewAt(pass.tca)
                        }
                    }, enabled = previewStatus == GlobePassPreviewStatus.AVAILABLE) { Text("Preview this pass") }
                    when (previewStatus) {
                        GlobePassPreviewStatus.PEAK_PASSED -> Text(
                            "This pass peak has already passed. Preview covers the next 24 h.",
                            color = OrbitColors.muted, style = MaterialTheme.typography.bodySmall)
                        GlobePassPreviewStatus.BEYOND_WINDOW -> Text(
                            "This pass is beyond the current 24 h preview window.",
                            color = OrbitColors.muted, style = MaterialTheme.typography.bodySmall)
                        GlobePassPreviewStatus.AVAILABLE -> Unit
                    }
                }
                if (!passLoading && nextPass == null && passError == null && observer != null && !selectedRecord.isKnownDecayedAt(at))
                    Text("No predicted pass in the next 24 h from the shown time.", color = OrbitColors.muted)
                if (displayed?.orbitPath?.isNotEmpty() == true) Text(
                    "Orbit and ground track: next ${String.format(Locale.US, "%.0f", displayed.pathDurationMinutes)} minutes",
                    style = MaterialTheme.typography.bodySmall, color = OrbitColors.muted)
                Button(onClick = { playing = false; onTrack(selectedRecord) }) { Text("Open Target") }
            }
        }
        Text("Positions and above-horizon geometry are predictions. Signal shows receiver samples, audio and decoded packets separately.",
            modifier = Modifier.padding(bottom = 20.dp), style = MaterialTheme.typography.bodySmall, color = OrbitColors.muted)
    }
    }
}

private fun formatGlobeTime(at: Instant): String =
    DateTimeFormatter.ofPattern("MMM d, HH:mm:ss z", Locale.US).withZone(ZoneId.systemDefault()).format(at)
