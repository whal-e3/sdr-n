package org.satelliteeavesdropper.app.receiver

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.min

private const val FFT_BINS = 256
private const val WATERFALL_BINS = 128
private const val IQ_POINTS = 256
private val iqColor = Color(0xFF67DDD7)
private val qColor = Color(0xFFFFC979)

private enum class SignalRepresentation(val label: String) {
    SPECTRUM("Spectrum"), WATERFALL("Waterfall"), WAVEFORM("IQ waveform"),
    CONSTELLATION("Constellation"), OVERVIEW("Overview"),
}

/** Actual receiver samples and DSP output. A trace or IQ scatter does not indicate packet lock. */
@Composable
fun SignalVisuals(snapshot: ReceptionSnapshot) {
    var representation by rememberSaveable { mutableStateOf(SignalRepresentation.SPECTRUM) }
    var clockElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = (LocalContext.current as? LifecycleOwner)?.lifecycle
    LaunchedEffect(lifecycle, snapshot.sessionId, snapshot.hasActiveVisualSession()) {
        if (!snapshot.hasActiveVisualSession()) return@LaunchedEffect
        suspend fun tick() {
            while (isActive) {
                clockElapsedMs = SystemClock.elapsedRealtime()
                delay(250)
            }
        }
        if (lifecycle != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { tick() }
        else tick()
    }
    val nowElapsedMs = maxOf(clockElapsedMs, SystemClock.elapsedRealtime())
    val freshness = signalVisualFreshness(snapshot, nowElapsedMs)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScrollableTabRow(selectedTabIndex = representation.ordinal, edgePadding = 0.dp) {
            SignalRepresentation.entries.forEach { tab ->
                Tab(
                    selected = representation == tab,
                    onClick = { representation = tab },
                    text = { Text(tab.label) },
                )
            }
        }
        SignalProgress(snapshot, freshness, nowElapsedMs)
        // Compose only the selected representation; every trace uses new receiver snapshots.
        when (representation) {
            SignalRepresentation.SPECTRUM -> FrequencyTrace(snapshot)
            SignalRepresentation.WATERFALL -> Spectrogram(snapshot)
            SignalRepresentation.WAVEFORM -> TimeDomainTrace(snapshot)
            SignalRepresentation.CONSTELLATION -> IqScatter(snapshot)
            SignalRepresentation.OVERVIEW -> ReceiverChain(snapshot)
        }
    }
}

@Composable
private fun SignalProgress(snapshot: ReceptionSnapshot, freshness: SignalVisualFreshness, nowElapsedMs: Long) {
    val source = when {
        snapshot.isSyntheticVisualSession() -> "Generated test tone"
        snapshot.isSdrTestVisualSession() -> "USB SDR test"
        else -> "Receiver IQ"
    }
    val headline = when (freshness) {
        SignalVisualFreshness.LIVE -> "LIVE · $source"
        SignalVisualFreshness.WAITING -> "WAITING · $source"
        SignalVisualFreshness.STALE -> "STALE · $source"
        SignalVisualFreshness.STOPPED -> "STOPPED · $source"
        SignalVisualFreshness.FAILED -> "FAILED · $source"
    }
    val updatedAt = snapshot.samplesUpdatedAtElapsedMs
    val age = updatedAt?.takeIf { it >= 0L && it <= nowElapsedMs }
        ?.let { "%.1f s".format(Locale.US, (nowElapsedMs - it) / 1_000.0) }
    val detail = when (freshness) {
        SignalVisualFreshness.LIVE ->
            "Samples updated $age ago · ${formatCount(snapshot.processedSamples)} complex samples processed"
        SignalVisualFreshness.WAITING -> "Waiting for IQ samples; a plot appears when samples arrive."
        SignalVisualFreshness.STALE -> if (age != null)
            "Samples have not advanced for $age. Showing the last captured samples." else
            "Current sample delivery is unverified. Showing the last captured samples."
        SignalVisualFreshness.STOPPED ->
            "Session ended. Showing the last captured samples; no current signal or frame sync is implied."
        SignalVisualFreshness.FAILED -> if (snapshot.processedSamples > 0L)
            "Capture failed. Showing the last captured samples; no current signal is implied." else
            "Capture failed before IQ samples arrived."
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(headline, fontWeight = FontWeight.Bold, color = when (freshness) {
            SignalVisualFreshness.LIVE -> MaterialTheme.colorScheme.primary
            SignalVisualFreshness.STALE -> MaterialTheme.colorScheme.secondary
            SignalVisualFreshness.FAILED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }, style = MaterialTheme.typography.labelLarge)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReceiverChain(snapshot: ReceptionSnapshot) {
    SignalCard("RECEIVER CHAIN", "Predictions and measurements are shown separately") {
        Stage(
            "01", if (snapshot.isSdrTestVisualSession() || snapshot.isSyntheticVisualSession()) "Sample source" else "Orbit target",
            if (snapshot.isSyntheticVisualSession()) "Synthetic test tone · no satellite" else
            if (snapshot.isSdrTestVisualSession()) "SDR hardware test · no satellite" else
            if (snapshot.targetName.isBlank()) "No target selected" else
                "${snapshot.targetName} · NORAD ${snapshot.targetNoradId}",
            orbitTargetDetail(snapshot),
        )
        if (!snapshot.isEndedVisualSession() && !snapshot.isSdrTestVisualSession() &&
            snapshot.lookElevationDegrees?.let { it < 0.0 } == true) {
            Text("Target is below the predicted horizon. Stop and select a visible pass to continue.",
                color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        }
        Stage(
            "02", "Tuning",
            tuningHeadline(snapshot), tuningDetail(snapshot),
        )
        Stage(
            "03", "IQ stream",
            when {
                snapshot.processedSamples > 0 -> "${formatCount(snapshot.processedSamples)} complex samples processed"
                snapshot.state == "Starting" -> "Waiting for SDR samples"
                snapshot.isEndedVisualSession() -> "No IQ samples captured"
                else -> "No samples yet"
            },
            "${formatCount(snapshot.acceptedSamples)} accepted · ${formatCount(snapshot.droppedSamples)} dropped",
        )
        Stage("04", "Signal acquisition", acquisitionHeadline(snapshot), acquisitionDetail(snapshot))
        Stage("05", "Demodulation and frame check", decoderHeadline(snapshot), decoderDetail(snapshot))
        snapshot.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun orbitTargetDetail(snapshot: ReceptionSnapshot): String {
    if (snapshot.isSyntheticVisualSession())
        return "Generated locally; no pass or downlink involved"
    if (snapshot.isSdrTestVisualSession())
        return "Receive-only hardware check; no satellite, location or pass prediction required"
    val look = when {
        snapshot.lookElevationDegrees == null && snapshot.state == "Stopped" ->
            "No orbital prediction captured in last session"
        snapshot.lookElevationDegrees == null -> "Waiting for orbital prediction"
        else -> "${if (snapshot.state == "Stopped") "Last predicted" else "Predicted"} " +
            "azimuth ${degrees(snapshot.lookAzimuthDegrees)} · " +
            "elevation ${degrees(snapshot.lookElevationDegrees)}; RF source unverified"
    }
    return "$look · ${observerFeedDescription(snapshot)}"
}

internal fun observerFeedDescription(snapshot: ReceptionSnapshot): String {
    val age = snapshot.observerFixAgeSeconds?.let { " · fix ${frameAgeText(it)} old" }.orEmpty()
    val ended = snapshot.state == "Stopped"
    return when (snapshot.observerFeedState) {
        ObserverFeedState.FIXED -> if (ended) "Observer was fixed at session start" else
            "Observer fixed at session start"
        ObserverFeedState.AUTO_WAITING ->
            "Auto GPS: using start observer while waiting for a new fix$age"
        ObserverFeedState.AUTO_FOLLOWING -> if (ended)
            "Auto GPS followed the latest fix during the session$age" else
            "Auto GPS following latest fix for pointing and Doppler$age"
        ObserverFeedState.AUTO_PAUSED ->
            "Auto GPS updates paused; last observer held$age"
        ObserverFeedState.AUTO_UNAVAILABLE ->
            "Auto GPS unavailable; last observer held$age"
        ObserverFeedState.AUTO_STALE ->
            "Auto GPS fix stale; last observer held$age"
    }
}

internal fun tuningHeadline(snapshot: ReceptionSnapshot): String = when {
    snapshot.isSyntheticVisualSession() -> "No RF tuner · generated baseband IQ"
    snapshot.isSdrTestVisualSession() && snapshot.isEndedVisualSession() && snapshot.rfCenterHz <= 0 ->
        "RF tuning unavailable in last test"
    snapshot.isSdrTestVisualSession() && snapshot.isEndedVisualSession() && snapshot.rfCenterHz > 0 ->
        "Last RF tuning ${mhz(snapshot.rfCenterHz.toDouble())} MHz"
    snapshot.rfCenterHz <= 0 && snapshot.state == "Stopped" -> "RF tuning unavailable in last session"
    snapshot.rfCenterHz <= 0 -> "RF tuner idle"
    snapshot.state == "Stopped" -> "Last RF tuning ${mhz(snapshot.rfCenterHz.toDouble())} MHz"
    else -> "RF tuner ${mhz(snapshot.rfCenterHz.toDouble())} MHz"
}

internal fun tuningDetail(snapshot: ReceptionSnapshot): String = when {
    snapshot.isSyntheticVisualSession() -> "No orbital Doppler correction or radio signal"
    snapshot.isSdrTestVisualSession() -> "Fixed test frequency · no orbital Doppler correction"
    snapshot.rfCenterHz <= 0 && snapshot.state == "Stopped" -> "No Doppler tuning captured"
    snapshot.rfCenterHz <= 0 -> "Doppler correction pending"
    snapshot.state == "Stopped" ->
        "Last predicted signal ${mhz(snapshot.displayCenterHz)} MHz · " +
            "baseband correction ${signedHz(snapshot.predictedDopplerHz)}"
    else ->
        "Predicted signal ${mhz(snapshot.displayCenterHz)} MHz · " +
            "baseband correction ${signedHz(snapshot.predictedDopplerHz)}"
}

internal fun acquisitionHeadline(snapshot: ReceptionSnapshot): String = when {
    snapshot.isSyntheticVisualSession() -> "No RF signal acquisition"
    snapshot.isSdrTestVisualSession() -> "Hardware sample check · no satellite acquisition"
    snapshot.decoderId !in setOf("AUDIO_NFM", "AX25_AFSK1200") -> "Carrier acquisition not configured"
    snapshot.state == "Stopped" && snapshot.afcTracking -> "Last session · FM component estimate"
    snapshot.state == "Stopped" -> "Last session · no FM estimate"
    snapshot.afcTracking -> "Estimated FM component tracked"
    snapshot.processedSamples > 0 -> "Checking for a stable FM component"
    else -> "Waiting for FM IQ"
}

internal fun acquisitionDetail(snapshot: ReceptionSnapshot): String = when {
    snapshot.isSyntheticVisualSession() -> "Generated IQ only; no carrier or satellite signal"
    snapshot.isSdrTestVisualSession() ->
        "Advancing IQ checks USB capture; noise or a spectrum peak does not verify satellite reception"
    snapshot.decoderId !in setOf("AUDIO_NFM", "AX25_AFSK1200") ->
        "Spectrum processing does not check for a satellite signal or packet sync"
    else -> afcDescription(snapshot)
}

internal fun afcDescription(snapshot: ReceptionSnapshot): String = when {
    snapshot.afcTracking && snapshot.state == "Stopped" ->
        "Last session: estimated FM carrier correction ${signedHz(snapshot.afcAppliedHz)}; " +
            "no current signal or source identity is implied."
    snapshot.state == "Stopped" && snapshot.afcAppliedHz != 0.0 ->
        "Last session: AFC correction ${signedHz(snapshot.afcAppliedHz)} was held while the " +
            "carrier estimate was unavailable; no current signal is implied."
    snapshot.state == "Stopped" && snapshot.processedSamples > 0 ->
        "Last session: no stable FM carrier estimate; AFC did not acquire."
    snapshot.state == "Stopped" -> "No FM IQ samples captured; AFC did not start."
    snapshot.afcTracking ->
        "AFC tracking an estimated FM-like component: ${signedHz(snapshot.afcAppliedHz)} applied; " +
            "last measured residual ${signedHz(snapshot.afcLastResidualHz)}. " +
            "Carrier estimate does not verify satellite identity or packet sync."
    snapshot.afcAppliedHz != 0.0 && snapshot.state == "Receiving" ->
        "Carrier estimate unavailable; AFC ${signedHz(snapshot.afcAppliedHz)} is held briefly " +
            "while signal quality is checked."
    snapshot.processedSamples > 0 ->
        "AFC waiting for a stable, modulated FM component; no carrier estimate yet."
    else -> "AFC waiting for FM IQ samples."
}

internal fun decoderHeadline(snapshot: ReceptionSnapshot): String = when {
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.state == "Stopped" &&
        snapshot.verifiedFrameCount > 0L ->
        "Last session · ${snapshot.verifiedFrameCount} CRC-valid AX.25 frame(s)"
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.state == "Stopped" &&
        snapshot.processedSamples > 0L -> "AX.25 search ended · no valid frames"
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.state == "Stopped" ->
        "No AX.25 IQ captured"
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.processedSamples == 0L ->
        "AX.25 decoder waiting for IQ"
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.verifiedFrameCount > 0L &&
        snapshot.latestVerifiedFrameAgeSeconds?.let { it <= 5L } == true ->
        "Frame verified in last 5 s · ${snapshot.verifiedFrameCount} total"
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.verifiedFrameCount > 0L &&
        snapshot.latestVerifiedFrameAgeSeconds != null ->
        "Last AX.25 frame ${frameAgeText(snapshot.latestVerifiedFrameAgeSeconds)} ago · " +
            "${snapshot.verifiedFrameCount} total"
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.verifiedFrameCount > 0L ->
        "${snapshot.verifiedFrameCount} CRC-valid AX.25 frame(s) · time unknown"
    snapshot.decoderId == "AX25_AFSK1200" -> "Searching for AX.25 frames"
    snapshot.decoderId == "AUDIO_NFM" && snapshot.state == "Stopped" &&
        snapshot.audioFramesPlayed > 0L -> "Last session · NFM audio was written"
    snapshot.decoderId == "AUDIO_NFM" && snapshot.state == "Stopped" &&
        snapshot.processedSamples > 0L -> "NFM session ended · no audio written"
    snapshot.decoderId == "AUDIO_NFM" && snapshot.state == "Stopped" -> "No NFM IQ captured"
    snapshot.decoderId == "AUDIO_NFM" && snapshot.processedSamples == 0L ->
        "NFM demodulator waiting for IQ"
    snapshot.decoderId == "AUDIO_NFM" && snapshot.audioFramesPlayed == 0L ->
        "NFM audio pending"
    snapshot.decoderId == "AUDIO_NFM" -> "NFM audio output active"
    else -> "Spectrum only · decoder off"
}

internal fun decoderDetail(snapshot: ReceptionSnapshot): String = when {
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.state == "Stopped" &&
        snapshot.processedSamples == 0L ->
        "No samples processed in last session; frame search never started"
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.processedSamples == 0L ->
        "No samples processed; frame search has not started"
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.state == "Stopped" &&
        snapshot.verifiedFrameCount > 0L ->
        "Frame CRC passed in last session; transmitter identity is unverified"
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.verifiedFrameCount > 0L &&
        snapshot.latestVerifiedFrameAgeSeconds != null ->
        "Latest CRC-valid frame ${frameAgeText(snapshot.latestVerifiedFrameAgeSeconds)} ago; " +
            "transmitter identity and continuous sync are unverified"
    snapshot.decoderId == "AX25_AFSK1200" && snapshot.verifiedFrameCount > 0L ->
        "Frame CRC passed; latest frame time unavailable; transmitter identity is unverified"
    snapshot.decoderId == "AX25_AFSK1200" ->
        "${formatCount(snapshot.hdlcFlagCandidates)} HDLC flag candidates · " +
            "${formatCount(snapshot.failedFrameCrc)} CRC failures; candidates alone are not sync"
    snapshot.decoderId == "AUDIO_NFM" && snapshot.state == "Stopped" &&
        snapshot.processedSamples == 0L ->
        "No samples processed in last session; audio demodulation never started"
    snapshot.decoderId == "AUDIO_NFM" && snapshot.processedSamples == 0L ->
        "No samples processed; audio demodulation has not started"
    snapshot.decoderId == "AUDIO_NFM" && snapshot.state == "Stopped" &&
        snapshot.audioFramesPlayed > 0L ->
        "${formatCount(snapshot.audioFramesPlayed)} PCM samples written in last session; source unverified"
    snapshot.decoderId == "AUDIO_NFM" && snapshot.state == "Stopped" ->
        "No demodulated audio was written in last session"
    snapshot.decoderId == "AUDIO_NFM" && snapshot.audioFramesPlayed == 0L ->
        "Waiting for demodulated audio"
    snapshot.decoderId == "AUDIO_NFM" ->
        "${formatCount(snapshot.audioFramesPlayed)} PCM samples written; source unverified"
    else -> "FFT and IQ samples alone do not confirm decoding"
}

@Composable
private fun Stage(number: String, title: String, headline: String, detail: String) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(number, color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Text(title.uppercase(Locale.US), color = colors.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.2.sp)
            Text(headline, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FrequencyTrace(snapshot: ReceptionSnapshot) {
    val spectrum = snapshot.spectrum
    SignalCard("FREQUENCY", "Power spectrum · 256 FFT bins · dBFS") {
        if (spectrum.size != FFT_BINS || snapshot.processedSamples == 0L) {
            EmptyPlot(snapshot)
            return@SignalCard
        }
        val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        Canvas(Modifier.fillMaxWidth().height(240.dp)) {
            for (step in 0..4) {
                val x = size.width * step / 4f
                val y = size.height * step / 4f
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            val path = Path()
            spectrum.forEachIndexed { index, db ->
                val x = size.width * index / (FFT_BINS - 1).toFloat()
                val y = size.height * (1f - ((db + 110f) / 110f).coerceIn(0f, 1f))
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, iqColor, style = Stroke(width = 2.5f))
        }
        FrequencyAxis(snapshot)
        Text("Vertical scale: 0 dBFS top · −110 dBFS bottom",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Spectrogram(snapshot: ReceptionSnapshot) {
    val rows = snapshot.spectrogram
    SignalCard("WATERFALL", "Rolling FFT history · newest at bottom") {
        if (rows.isEmpty()) {
            EmptyPlot(snapshot)
            return@SignalCard
        }
        val background = MaterialTheme.colorScheme.surfaceVariant
        Canvas(Modifier.fillMaxWidth().height(260.dp)) {
            drawRect(background)
            val rowHeight = size.height / 48f
            val colWidth = size.width / WATERFALL_BINS
            rows.takeLast(48).forEachIndexed { row, values ->
                val y = size.height - (rows.size - row) * rowHeight
                values.take(WATERFALL_BINS).forEachIndexed { bin, db ->
                    drawRect(
                        waterfallColor(db),
                        topLeft = Offset(bin * colWidth, y),
                        size = androidx.compose.ui.geometry.Size(colWidth + 0.5f, rowHeight + 0.5f),
                    )
                }
            }
        }
        FrequencyAxis(snapshot)
        Text("A new row is added only when captured sample counts advance",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text("Dark: low power · amber: high power",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

private fun waterfallColor(db: Float): Color {
    val level = ((db + 105f) / 90f).coerceIn(0f, 1f)
    return if (level < 0.5f) lerp(Color(0xFF102439), Color(0xFF368CA9), level * 2f)
    else lerp(Color(0xFF368CA9), Color(0xFFFFC979), (level - 0.5f) * 2f)
}

@Composable
private fun TimeDomainTrace(snapshot: ReceptionSnapshot) {
    val iq = snapshot.iqSamples
    SignalCard("TIME DOMAIN", "Latest 256 complex baseband samples · normalized amplitude") {
        if (iq.size != IQ_POINTS * 2 || snapshot.processedSamples == 0L) {
            EmptyPlot(snapshot)
            return@SignalCard
        }
        val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        Canvas(Modifier.fillMaxWidth().height(240.dp)) {
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
            for (step in 1..3) {
                val x = size.width * step / 4f
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
            }
            val iPath = Path()
            val qPath = Path()
            for (index in 0 until IQ_POINTS) {
                val x = size.width * index / (IQ_POINTS - 1).toFloat()
                val iY = size.height * (1f - (iq[2 * index].coerceIn(-1f, 1f) + 1f) / 2f)
                val qY = size.height * (1f - (iq[2 * index + 1].coerceIn(-1f, 1f) + 1f) / 2f)
                if (index == 0) { iPath.moveTo(x, iY); qPath.moveTo(x, qY) }
                else { iPath.lineTo(x, iY); qPath.lineTo(x, qY) }
            }
            drawPath(iPath, iqColor, style = Stroke(width = 2f))
            drawPath(qPath, qColor, style = Stroke(width = 2f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("● I", color = iqColor, style = MaterialTheme.typography.labelSmall)
            Text("● Q", color = qColor, style = MaterialTheme.typography.labelSmall)
        }
        val (start, middle, end) = timeDomainAxisLabels(snapshot.sampleRateSps)
        AxisRow(start, middle, end)
        Text(
            if (snapshot.sampleRateSps > 0)
                "Horizontal: time from first sample · vertical: normalized I/Q, −1 to +1"
            else "Horizontal: sample index · vertical: normalized I/Q, −1 to +1",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** The plotted endpoints are samples 0 and 255, not a full 256-sample period. */
internal fun timeDomainAxisLabels(sampleRateSps: Int): Triple<String, String, String> {
    if (sampleRateSps <= 0) return Triple("Sample 0", "Sample 128", "Sample 255")
    val endMs = (IQ_POINTS - 1) * 1000.0 / sampleRateSps
    return Triple(
        "0 ms",
        "%.3f ms".format(Locale.US, endMs / 2.0),
        "%.3f ms".format(Locale.US, endMs),
    )
}

@Composable
private fun IqScatter(snapshot: ReceptionSnapshot) {
    val iq = snapshot.iqSamples
    SignalCard("IQ CONSTELLATION VIEW", "Raw baseband scatter before decoding · no symbol lock implied") {
        if (iq.size != IQ_POINTS * 2 || snapshot.processedSamples == 0L) {
            EmptyPlot(snapshot)
            return@SignalCard
        }
        val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        Canvas(Modifier.fillMaxWidth().height(280.dp)) {
            val radius = min(size.width, size.height) * 0.43f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(grid, radius, center, style = Stroke(width = 1f))
            drawCircle(grid, radius / 2f, center, style = Stroke(width = 1f))
            drawLine(grid, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1f)
            drawLine(grid, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1f)
            for (index in 0 until IQ_POINTS) {
                val i = iq[2 * index].coerceIn(-1f, 1f)
                val q = iq[2 * index + 1].coerceIn(-1f, 1f)
                drawCircle(iqColor.copy(alpha = 0.48f), radius = 2f,
                    center = Offset(center.x + radius * i, center.y - radius * q))
            }
        }
        AxisRow("−I", "0", "+I")
        Text("Vertical axis: Q · a visible shape does not establish a decoded frame",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SignalCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun EmptyPlot(snapshot: ReceptionSnapshot) {
    Text(when {
        !snapshot.error.isNullOrBlank() || snapshot.state == "Failed" -> "No graph data captured before failure"
        snapshot.isEndedVisualSession() -> "No graph data captured in this session"
        else -> "Waiting for live IQ samples"
    },
        modifier = Modifier.fillMaxWidth().height(80.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun AxisRow(left: String, center: String, right: String) {
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth()) {
        Text(left, modifier = Modifier.weight(1f), style = style, color = color)
        Text(center, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = style, color = color)
        Text(right, modifier = Modifier.weight(1f), textAlign = TextAlign.End, style = style, color = color)
    }
}

@Composable
private fun FrequencyAxis(snapshot: ReceptionSnapshot) {
    val halfRate = snapshot.sampleRateSps / 2.0
    if (snapshot.rfCenterHz <= 0) {
        AxisRow("${(-halfRate / 1000).toInt()} kHz", "0", "+${(halfRate / 1000).toInt()} kHz")
        Text(if (snapshot.isSyntheticVisualSession()) "Baseband offset · no RF tuning in generated test tone" else
            "Baseband offset · RF center unavailable",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    } else {
        val correctedCenterHz = if (snapshot.isSdrTestVisualSession()) snapshot.rfCenterHz.toDouble() else
            snapshot.displayCenterHz + snapshot.afcAppliedHz
        AxisRow(
            mhz(correctedCenterHz - halfRate),
            mhz(correctedCenterHz),
            mhz(correctedCenterHz + halfRate),
        )
        Text(when {
            snapshot.isSdrTestVisualSession() && snapshot.isEndedVisualSession() -> "MHz · last RF test center"
            snapshot.isSdrTestVisualSession() -> "MHz · fixed RF test center"
            snapshot.isEndedVisualSession() -> "MHz · last corrected baseband center"
            else -> "MHz · corrected baseband center"
        }, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
    }
}

private fun mhz(hz: Double): String = "%.3f".format(Locale.US, hz / 1_000_000.0)
private fun signedHz(hz: Double): String = "%+.0f Hz".format(Locale.US, hz)
private fun frameAgeText(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3_600 -> "${seconds / 60} min"
    else -> "${seconds / 3_600} h"
}
private fun degrees(value: Double?): String = if (value == null) "—" else "%.1f°".format(Locale.US, value)
private fun formatCount(value: Long): String = "%,d".format(Locale.US, value)
