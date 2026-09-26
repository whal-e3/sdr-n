package org.satelliteeavesdropper.app.day

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.satelliteeavesdropper.app.MainActivity
import org.satelliteeavesdropper.app.dayPassStatus
import org.satelliteeavesdropper.app.dayPredictionStopsAtDecay
import org.satelliteeavesdropper.app.orbitHistoryLabel
import org.satelliteeavesdropper.app.data.CatalogLoadResult
import org.satelliteeavesdropper.app.data.CatalogSource
import org.satelliteeavesdropper.app.data.SatelliteRecord
import org.satelliteeavesdropper.app.data.isFreshWithin72Hours
import org.satelliteeavesdropper.orbit.ObserverLocation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class ListMode { UPCOMING, PASSES, NOW, ALL, DOWNLINKS }

/** Browses every orbital record, including those with no downlink, and all passes on a local day. */
@Composable
internal fun DayScheduleScreen(
    catalog: CatalogLoadResult?,
    observer: ObserverLocation?,
    onSatelliteSelected: (SatelliteRecord) -> Unit,
    scheduleCache: DayScheduleCache,
    modifier: Modifier = Modifier,
    onMissingNoradLookup: (String) -> Unit = {},
    lookupRunning: Boolean = false,
    lookupMessage: String? = null,
    supplementalRevision: String = "",
    onImportFile: () -> Unit = {},
    importRunning: Boolean = false,
    importMessage: String? = null,
    importedCount: Int = 0,
) {
    val lifecycle = (LocalContext.current as MainActivity).lifecycle
    var today by remember { mutableStateOf(LocalDate.now()) }
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                zone = ZoneId.systemDefault()
                now = Instant.now()
                today = now.atZone(zone).toLocalDate()
                delay(30_000)
            }
        }
    }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(ListMode.UPCOMING) }
    var radioProfilesOnly by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val day = today.plusDays(dayOffset.toLong())
    val key = catalog?.let {
        ScheduleCacheKey(ScheduleCatalogIdentity(it.manifest), it.manifest.sequence, it.manifest.generatedAt, it.source,
            it.manifest.satellites.size, supplementalRevision, observer, day, zone)
    }
    var visiblePassLimit by remember(key, query, mode, radioProfilesOnly) { mutableIntStateOf(150) }
    val listState = rememberLazyListState()
    LaunchedEffect(key, query, mode, radioProfilesOnly) { listState.scrollToItem(0) }
    var schedule by remember(key) { mutableStateOf(key?.let(scheduleCache::prepare)) }

    LaunchedEffect(key) {
        val loaded = catalog ?: return@LaunchedEffect
        val cacheKey = key ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val previous = scheduleCache.get(cacheKey)
            calculateDaySchedule(loaded.manifest, observer, day, zone, previous, onUpdate = { result ->
                schedule = result
                scheduleCache.put(cacheKey, result)
            })
        }
    }

    val search = query.trim().lowercase(Locale.ROOT)
    val matchingIds = remember(schedule?.satellites, search) {
        if (search.isEmpty()) null else schedule?.satellites?.asSequence()
            ?.filter { satellite ->
                satellite.record.noradId.contains(search) ||
                    satellite.record.name.lowercase(Locale.ROOT).contains(search) ||
                    satellite.record.aliases.any { it.lowercase(Locale.ROOT).contains(search) }
            }?.map { it.record.noradId }?.toSet()
    }
    var priorityPasses by remember(key, search) { mutableStateOf<List<ScheduledPass>>(emptyList()) }
    var priorityCompletedIds by remember(key, search) { mutableStateOf<Set<String>>(emptySet()) }
    var priorityFailedIds by remember(key, search) { mutableStateOf<Set<String>>(emptySet()) }
    var priorityLimited by remember(key, search) { mutableStateOf(false) }
    var priorityRunning by remember(key, search) { mutableStateOf(false) }
    LaunchedEffect(key, search, schedule?.satellites) {
        if (search.isEmpty() || observer == null || schedule == null || matchingIds == null) return@LaunchedEffect
        val requested = schedule!!.satellites.filter { it.record.noradId in matchingIds && it.index >= schedule!!.processedCount }
        priorityLimited = requested.size > 64
        if (requested.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            priorityRunning = true
            try {
                calculatePriorityPasses(requested.take(64), observer, day, zone, onUpdate = { passes, completed, failed ->
                    priorityPasses = passes
                    priorityCompletedIds = completed
                    priorityFailedIds = failed
                })
            } finally {
                priorityRunning = false
            }
        }
    }
    val passClock = if (mode == ListMode.UPCOMING || mode == ListMode.NOW) now else null
    val shownPasses = remember(schedule?.passes, priorityPasses, matchingIds, mode, radioProfilesOnly, day, zone, passClock) {
        val base = schedule?.passes.orEmpty()
        // The day engine already sorts its results on a worker thread. Avoid
        // sorting tens of thousands of passes on every Compose update.
        val all = if (priorityPasses.isEmpty()) base else sortDayPasses(
            (base + priorityPasses).distinctBy { it.satellite.record.noradId to it.pass.aos },
        )
        val matching = if (matchingIds == null) all else all.filter { it.satellite.record.noradId in matchingIds }
        val radioFiltered = passesForRadioView(matching, radioProfilesOnly)
        passesForTimeView(radioFiltered, when (mode) {
            ListMode.UPCOMING -> PassTimeView.UPCOMING
            ListMode.NOW -> PassTimeView.IN_SIGHT
            ListMode.PASSES, ListMode.ALL, ListMode.DOWNLINKS -> PassTimeView.FULL_DAY
        }, day, zone, now)
    }
    val shownSatellites = remember(schedule?.satellites, matchingIds, mode) {
        schedule?.satellites.orEmpty().filter { satellite ->
            (matchingIds == null || satellite.record.noradId in matchingIds) &&
                (mode != ListMode.DOWNLINKS || satellite.hasConfiguredDownlink)
        }
    }
    val searchHasOnlyDateLimitedRecords = remember(schedule?.satellites, matchingIds, day, zone) {
        if (matchingIds.isNullOrEmpty()) false else {
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            val matches = schedule?.satellites.orEmpty().asSequence().filter { it.record.noradId in matchingIds }
            matches.any() && matches.all { it.record.predictionEndBeforeDecay(start, end) == null }
        }
    }
    val passCounts = remember(schedule?.passes, priorityPasses, mode) {
        if (mode != ListMode.ALL && mode != ListMode.DOWNLINKS) emptyMap()
        else {
            val base = schedule?.passes.orEmpty()
            val counted = if (priorityPasses.isEmpty()) base else
                (base + priorityPasses).distinctBy { it.satellite.record.noradId to it.pass.aos }
            counted.groupingBy { it.satellite.record.noradId }.eachCount()
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (dayOffset == 0) "Today" else if (dayOffset == 1) "Tomorrow" else "Day ${dayOffset + 1}",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))} · ${zone.id}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { dayOffset = (dayOffset - 1).coerceAtLeast(0) },
                        enabled = dayOffset > 0,
                        modifier = Modifier.semantics { contentDescription = "Previous day" }) {
                        Text("‹", style = MaterialTheme.typography.headlineSmall)
                    }
                    IconButton(onClick = { dayOffset = (dayOffset + 1).coerceAtMost(6) },
                        enabled = dayOffset < 6,
                        modifier = Modifier.semantics { contentDescription = "Next day" }) {
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImportFile, enabled = !importRunning) {
                        Text(if (importRunning) "Importing…" else "Import orbit file")
                    }
                    if (importedCount > 0) Text("+$importedCount saved", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "Hide info" else "Info")
                    }
                }
                importMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall) }
                if (catalog?.source == CatalogSource.DEMO) {
                    Text("Demo data: illustrative passes; hardware reception disabled.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (showDetails) {
                    Text("Track signed-catalog and imported orbits. Reception needs a supported public downlink, a fresh signed catalog, and a satellite above the horizon.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Import GP CSV, OMM JSON, or TLE text for tracking only. Multiple files accumulate; imports never enable radio reception.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("All includes historical orbital records. Source-reported decay dates use UTC; current predictions and reception stop at the start of that date. The date does not establish an exact event time or cause. Earlier days can retain historical pass predictions.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (schedule == null) Text(if (catalog == null) "Loading catalog…" else "Reading orbital elements…")
                else {
                    val current = schedule!!
                    Text("${current.satellites.size} / ${current.catalogCount} orbits · ${current.passes.size} passes" +
                        if (current.isComplete) "" else " so far",
                        style = MaterialTheme.typography.bodyMedium)
                    if (observer == null) Text("Set your location to calculate this day's passes.")
                    else if (!current.isComplete) {
                        LinearProgressIndicator(
                            progress = { current.processedCount.toFloat() / current.satellites.size.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Calculated ${current.processedCount} / ${current.satellites.size} orbits",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (current.failedNoradIds.isNotEmpty()) {
                        Text("${current.failedNoradIds.size} propagation failures · tap for details",
                            modifier = Modifier.clickable { showDetails = true },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (showDetails) {
                        if (current.orbitUnavailableCount > 0) {
                            Text("${current.orbitUnavailableCount} records have missing or invalid orbital elements.",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (current.failedNoradIds.isNotEmpty()) {
                            val failures = current.failedNoradIds
                            val names = failures.sorted().take(3).joinToString { id ->
                                val name = current.satellites.firstOrNull { it.record.noradId == id }?.record?.name
                                if (name == null) id else "$name ($id)"
                            }
                            Text("${failures.size} ${if (failures.size == 1) "orbit" else "orbits"} could not be propagated: " +
                                names + if (failures.size > 3) " and ${failures.size - 3} more" else "",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search name or NORAD ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val noradQuery = query.trim()
                val existingOrbit = catalog?.manifest?.satellites?.firstOrNull {
                    it.noradId == noradQuery.trimStart('0')
                }
                val needsRefresh = existingOrbit?.orbitElements()?.epoch?.isBefore(now.minusSeconds(72 * 60 * 60)) != false
                if (noradQuery.matches(Regex("[0-9]{1,9}")) && catalog != null && needsRefresh) {
                    OutlinedButton(onClick = { onMissingNoradLookup(noradQuery) }, enabled = !lookupRunning) {
                        Text(if (lookupRunning) "Looking up orbit…" else if (existingOrbit == null)
                            "Look up NORAD $noradQuery" else "Refresh orbit for NORAD $noradQuery")
                    }
                    Text("Direct CelesTrak lookup updates orbit tracking only; it never enables reception.",
                        style = MaterialTheme.typography.bodySmall)
                }
                lookupMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == ListMode.UPCOMING, onClick = { mode = ListMode.UPCOMING }, label = { Text("Upcoming") })
                    FilterChip(selected = mode == ListMode.PASSES, onClick = { mode = ListMode.PASSES }, label = { Text("Day passes") })
                    FilterChip(selected = mode == ListMode.NOW, onClick = { mode = ListMode.NOW }, label = { Text("In sight") })
                    FilterChip(selected = mode == ListMode.ALL, onClick = { mode = ListMode.ALL }, label = { Text("All") })
                    FilterChip(selected = mode == ListMode.DOWNLINKS, onClick = { mode = ListMode.DOWNLINKS }, label = { Text("Downlinks") })
                }
                if (mode == ListMode.UPCOMING || mode == ListMode.PASSES || mode == ListMode.NOW) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = radioProfilesOnly, onClick = { radioProfilesOnly = true },
                            label = { Text("Radio profiles") })
                        FilterChip(selected = !radioProfilesOnly, onClick = { radioProfilesOnly = false },
                            label = { Text("All orbits") })
                    }
                    if (radioProfilesOnly) Text("Configured downlinks; reception still needs current elements and a real signal.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (mode == ListMode.UPCOMING && observer != null) {
                    Text(if (day == now.atZone(zone).toLocalDate())
                        "${shownPasses.size} ${if (radioProfilesOnly) "radio-profile " else ""}passes underway or ahead today" + if (schedule?.isComplete == true) "" else " so far"
                    else "${shownPasses.size} ${if (radioProfilesOnly) "radio-profile " else ""}passes on this future day" + if (schedule?.isComplete == true) "" else " so far",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (priorityRunning) Text("Calculating passes for search matches…")
                if (priorityLimited) Text("Many satellites match. Refine the search to calculate all matching passes first.")
            }
        }
        if (mode == ListMode.UPCOMING || mode == ListMode.PASSES || mode == ListMode.NOW) {
            if (observer == null) item { Text("Choose a location to see the day schedule.") }
            else if (shownPasses.isEmpty()) item {
                Text(when {
                    searchHasOnlyDateLimitedRecords -> "Predictions stop from the source-reported decay date. Choose All to view historical records."
                    schedule?.isComplete != true -> if (radioProfilesOnly) "Calculating radio-profile passes…" else "Calculating passes…"
                    query.isNotBlank() -> "No passes match this search and view."
                    radioProfilesOnly -> "No radio-profile passes in this view. Choose All orbits for other predictions."
                    mode == ListMode.NOW -> "No satellites are in sight now."
                    mode == ListMode.UPCOMING && day == now.atZone(zone).toLocalDate() -> "No upcoming passes remain today."
                    else -> "No passes predicted for this day."
                })
            }
            items(shownPasses.take(visiblePassLimit), key = { "${it.satellite.record.noradId}:${it.pass.aos.toEpochMilli()}" }) { entry ->
                Card(onClick = { onSatelliteSelected(entry.satellite.record) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.satellite.record.name, fontWeight = FontWeight.Bold)
                        entry.satellite.record.orbitHistoryLabel(now)?.let {
                            Text(it, color = MaterialTheme.colorScheme.secondary)
                        }
                        if (!entry.satellite.record.isKnownDecayedAt(now) &&
                            !now.isBefore(entry.pass.aos) && now.isBefore(entry.pass.los)) {
                            Text("In sight now", color = MaterialTheme.colorScheme.primary)
                        }
                        Text(passWindowLabel(entry.pass, zone,
                            entry.satellite.record.dayPredictionStopsAtDecay(day, zone)))
                        Text("Peak ${"%.0f".format(Locale.US, entry.pass.maximumElevationDegrees)}° elevation")
                        Text("NORAD ${entry.satellite.record.noradId} · ${if (entry.satellite.record.isKnownDecayedAt(now)) "Historical record" else if (entry.satellite.hasConfiguredDownlink) "Configured downlink" else "Tracking only"}")
                        if (!isFreshWithin72Hours(entry.satellite.elements.epoch, now)) {
                            Text("Orbital elements older than 72 h · pass time may be inaccurate",
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (shownPasses.size > visiblePassLimit) item {
                OutlinedButton(onClick = { visiblePassLimit += 150 }, modifier = Modifier.fillMaxWidth()) {
                    Text("Load 150 more passes")
                }
            }
        } else {
            if (shownSatellites.isEmpty() && schedule != null) item { Text("No orbital records match this filter.") }
            items(shownSatellites, key = { "all:${it.record.noradId}" }) { satellite ->
                val calculated = satellite.index < (schedule?.processedCount ?: 0) || satellite.record.noradId in priorityCompletedIds
                val failed = satellite.record.noradId in (schedule?.failedNoradIds ?: emptySet()) || satellite.record.noradId in priorityFailedIds
                val count = passCounts[satellite.record.noradId] ?: 0
                Card(onClick = { onSatelliteSelected(satellite.record) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(satellite.record.name, fontWeight = FontWeight.Bold)
                        Text("NORAD ${satellite.record.noradId} · ${if (satellite.record.isKnownDecayedAt(now)) "Historical record" else if (satellite.hasConfiguredDownlink) "Configured downlink" else if (satellite.hasPublicDownlink) "Public downlink, unsupported setup" else "Tracking only"}")
                        satellite.record.orbitHistoryLabel(now)?.let {
                            Text(it, color = MaterialTheme.colorScheme.secondary)
                        }
                        if (!isFreshWithin72Hours(satellite.elements.epoch, now)) {
                            Text("Elements older than 72 h", color = MaterialTheme.colorScheme.error)
                        }
                        if (observer != null) {
                            Text(satellite.record.dayPassStatus(day, zone, calculated, failed, count))
                            if (satellite.record.dayPredictionStopsAtDecay(day, zone)) {
                                Text("Prediction stops at source-reported decay date",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item { Text("Passes are predicted from catalog orbital elements; an overhead pass does not confirm a transmission or decoding.",
            modifier = Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall) }
    }
}
