package org.satelliteeavesdropper.app.receiver

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.satelliteeavesdropper.app.NativeReceiver
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

const val SDR_TEST_DEFAULT_FREQUENCY_HZ = 100_000_000L
const val SDR_TEST_SAMPLE_RATE_SPS = 1_024_000

/** Input is MHz. The individual tuner can have a narrower supported range. */
fun parseSdrTestFrequencyHz(input: String): Long? {
    val mhz = input.trim().toDoubleOrNull() ?: return null
    if (!mhz.isFinite() || mhz !in 1.0..6_000.0) return null
    return (mhz * 1_000_000.0).roundToLong()
}

data class SdrTestConfig(
    val deviceLabel: String,
    val frequencyHz: Long = SDR_TEST_DEFAULT_FREQUENCY_HZ,
    val durationMs: Long = 10_000,
    val sampleRateSps: Int = SDR_TEST_SAMPLE_RATE_SPS,
)

data class SdrTestRead(
    val acceptedSamples: Long,
    val droppedSamples: Long,
    val processedSamples: Long,
    val spectrum: List<Float> = emptyList(),
    val iqSamples: List<Float> = emptyList(),
)

/** Owns one receive-only stream. close must release its USB connection, including after failed start. */
interface SdrTestBackend {
    fun start(frequencyHz: Long, sampleRateSps: Int)
    fun read(): SdrTestRead
    fun stop()
    fun close()
}

enum class SdrTestOutcome { PASSED, WARNING, FAILED, STOPPED }

data class SdrTestResult(
    val outcome: SdrTestOutcome,
    val summary: String,
    val snapshot: ReceptionSnapshot,
    val measuredSampleRateSps: Long,
)

/**
 * Runs a finite USB/sample-path check without a satellite, location, or catalog.
 * Noise is sufficient: samples advancing through DSP is the evidence being checked.
 * The caller must serialize hardware ownership before opening the USB connection.
 */
suspend fun runSdrTest(
    backend: SdrTestBackend,
    config: SdrTestConfig,
    publish: (ReceptionSnapshot) -> Unit,
    nowElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    wait: suspend (Long) -> Unit = { delay(it) },
): SdrTestResult {
    var snapshot = ReceptionSnapshot(
        state = "SDR test",
        sessionId = UUID.randomUUID().toString(),
        device = config.deviceLabel,
        sampleRateSps = config.sampleRateSps,
        rfCenterHz = config.frequencyHz,
    )
    var failure: String? = null
    var cancellation: CancellationException? = null
    var elapsedMs = 0L
    var varyingFrames = 0
    var iqFrames = 0
    var clippedValues = 0L
    var iqValues = 0L
    val waterfall = ArrayDeque<List<Float>>()
    try {
        require(config.frequencyHz in 1_000_000L..6_000_000_000L) {
            "Enter a frequency from 1 to 6000 MHz. The SDR tuner may support a narrower range."
        }
        require(config.sampleRateSps == SDR_TEST_SAMPLE_RATE_SPS) { "Unsupported SDR test sample rate" }
        require(config.durationMs in 1L..60_000L) { "SDR test duration must be at most 60 seconds" }
        currentCoroutineContext().ensureActive()
        publish(snapshot)
        backend.start(config.frequencyHz, config.sampleRateSps)
        var lastProcessed = 0L
        var lastAccepted = 0L
        var lastDropped = 0L
        var lastProgressAt = nowElapsedMs()
        // Count the measurement interval after opening/tuning the hardware.
        val streamStartedAt = lastProgressAt
        while (true) {
            currentCoroutineContext().ensureActive()
            val frame = backend.read()
            val now = nowElapsedMs()
            elapsedMs = (now - streamStartedAt).coerceAtLeast(0)
            check(frame.acceptedSamples >= lastAccepted && frame.processedSamples >= lastProcessed &&
                frame.droppedSamples >= lastDropped) { "SDR sample counters reset unexpectedly" }
            check(frame.spectrum.isEmpty() ||
                (frame.spectrum.size == 256 && frame.spectrum.all { it.isFinite() })) {
                "SDR spectrum output is invalid"
            }
            check(frame.iqSamples.isEmpty() ||
                (frame.iqSamples.size in 2..512 && frame.iqSamples.size % 2 == 0 &&
                    frame.iqSamples.all { it.isFinite() })) { "SDR IQ output is invalid" }
            val advanced = frame.processedSamples > lastProcessed
            if (advanced) {
                lastProgressAt = now
                if (frame.spectrum.size == 256) {
                    waterfall.addLast(List(128) { bin ->
                        maxOf(frame.spectrum[2 * bin], frame.spectrum[2 * bin + 1])
                    })
                    while (waterfall.size > 48) waterfall.removeFirst()
                }
                if (frame.iqSamples.isNotEmpty()) {
                    iqFrames++
                    val firstI = frame.iqSamples[0]
                    val firstQ = frame.iqSamples[1]
                    if ((2 until frame.iqSamples.size step 2).any {
                            abs(frame.iqSamples[it] - firstI) > 0.00001f ||
                                abs(frame.iqSamples[it + 1] - firstQ) > 0.00001f
                        }) varyingFrames++
                    iqValues += frame.iqSamples.size
                    clippedValues += frame.iqSamples.count { abs(it) >= 0.98f }
                }
            }
            snapshot = snapshot.copy(
                acceptedSamples = frame.acceptedSamples,
                droppedSamples = frame.droppedSamples,
                processedSamples = frame.processedSamples,
                spectrum = if (frame.processedSamples > 0) frame.spectrum else emptyList(),
                iqSamples = if (frame.processedSamples > 0) frame.iqSamples else emptyList(),
                spectrogram = waterfall.toList(),
                samplesUpdatedAtElapsedMs = if (advanced) now else snapshot.samplesUpdatedAtElapsedMs,
            )
            publish(snapshot)
            lastAccepted = frame.acceptedSamples
            lastProcessed = frame.processedSamples
            lastDropped = frame.droppedSamples
            check(now - lastProgressAt < 3_500) {
                when {
                    frame.acceptedSamples == 0L -> "SDR produced no USB IQ samples. Check the connection, power, and USB access."
                    frame.processedSamples == 0L -> "USB IQ samples arrived but DSP did not process them."
                    else -> "SDR sample processing stalled. Check the connection and power."
                }
            }
            if (elapsedMs >= config.durationMs) break
            wait(minOf(100L, config.durationMs - elapsedMs))
        }
        check(snapshot.acceptedSamples > 0 && snapshot.processedSamples > 0) {
            "No processed IQ samples were captured during the SDR test."
        }
    } catch (error: CancellationException) {
        cancellation = error
    } catch (error: Exception) {
        failure = error.message ?: "SDR test failed"
    } finally {
        withContext(NonCancellable) {
            try {
                backend.stop()
            } catch (error: Exception) {
                failure = failure ?: "Could not stop the SDR stream: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                try {
                    backend.close()
                } catch (error: Exception) {
                    failure = failure ?: "Could not close the SDR connection: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }
    // Terminal state is published only after native and Android USB cleanup has completed.
    snapshot = snapshot.copy(state = "Test stopped", error = failure)
    publish(snapshot)
    cancellation?.let { throw it }
    val rate = if (elapsedMs > 0) (snapshot.processedSamples * 1_000.0 / elapsedMs).roundToLong() else 0L
    if (failure != null) return SdrTestResult(SdrTestOutcome.FAILED, "Failed: $failure", snapshot, rate)

    val warnings = buildList {
        if (snapshot.droppedSamples > 0) add("${snapshot.droppedSamples} complex samples were dropped")
        if (iqFrames == 0) add("no IQ waveform was available for inspection")
        else if (varyingFrames.toDouble() / iqFrames < 0.1) add("IQ appears constant; check the SDR and tuner")
        if (iqValues > 0 && clippedValues.toDouble() / iqValues > 0.25)
            add("IQ is heavily clipped; reduce a strong input signal or check the tuner")
        if (elapsedMs >= 1_000 && rate < config.sampleRateSps / 2)
            add("sample processing was much slower than the configured rate")
    }
    val evidence = "${snapshot.acceptedSamples} accepted, ${snapshot.processedSamples} processed, " +
        "${snapshot.droppedSamples} dropped complex samples; ${String.format(Locale.US, "%,d", rate)} samples/s measured."
    val conclusion = if (warnings.isEmpty()) "Passed: USB IQ samples arrived and DSP advanced."
        else "Warning: USB IQ samples arrived and DSP advanced. ${warnings.joinToString("; ")}."
    return SdrTestResult(
        if (warnings.isEmpty()) SdrTestOutcome.PASSED else SdrTestOutcome.WARNING,
        "$conclusion $evidence RF sensitivity, antenna performance, and decoding were not verified.",
        snapshot,
        rate,
    )
}

/** Android grants are opened by the caller, then always closed after the native driver. */
class NativeSdrTestBackend(
    private val fileDescriptor: Int,
    private val deviceType: Int,
    private val closeConnection: () -> Unit,
) : SdrTestBackend {
    private var handle = 0L
    private var closed = false

    override fun start(frequencyHz: Long, sampleRateSps: Int) {
        check(!closed && handle == 0L) { "SDR test backend was already used" }
        handle = NativeReceiver.nativeCreate(sampleRateSps)
        check(handle != 0L) { "Could not create the native receiver" }
        check(NativeReceiver.nativeSetMode(handle, 0) == 0) { "Could not select spectrum-only mode" }
        val open = NativeReceiver.nativeOpenUsb(handle, fileDescriptor, deviceType)
        check(open == 0) { "Could not open the SDR (code $open). Check USB access and reconnect it." }
        val start = NativeReceiver.nativeStartRx(handle, frequencyHz)
        check(start == 0) {
            if (start == -1 && deviceType == 1)
                "This RTL-SDR driver supports 24–1766 MHz. Choose a frequency in that range."
            else "Could not start the SDR at this frequency (code $start). Check its tuner range and connection."
        }
    }

    override fun read(): SdrTestRead {
        check(handle != 0L && !closed) { "SDR stream is not open" }
        val bins = NativeReceiver.nativeSpectrum(handle).toList()
        val iq = NativeReceiver.nativeIqSnapshot(handle).toList()
        val stats = NativeReceiver.nativeStats(handle)
        check(stats.size == 3) { "Could not read SDR sample counters" }
        return SdrTestRead(stats[0], stats[1], stats[2], bins, iq)
    }

    override fun stop() {
        if (handle != 0L) NativeReceiver.nativeStopRx(handle)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (handle != 0L) {
                NativeReceiver.nativeDestroy(handle)
                handle = 0L
            }
        } finally {
            closeConnection()
        }
    }
}
