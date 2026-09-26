package org.satelliteeavesdropper.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Checks the Android/JNI/DSP path with synthetic IQ; it does not test USB or RF hardware. */
@RunWith(AndroidJUnit4::class)
class NativeReceiverInstrumentedTest {
    @Test
    fun syntheticFmProducesAudioAndUpdatesStats() {
        val sampleRate = 256_000
        val handle = NativeReceiver.nativeCreate(sampleRate)
        assertTrue("Native receiver was not created", handle != 0L)
        try {
            assertEquals(-1, NativeReceiver.nativeSetMode(handle, 99))
            assertEquals(0, NativeReceiver.nativeConfigureNfm(handle, 5_000.0, 0.0))
            assertEquals(0, NativeReceiver.nativeSetMode(handle, 1))
            NativeReceiver.nativeSetCorrections(handle, 137_500_000.0, 0.0, 0.0)

            val batchSize = 4_096
            val totalSamples = 32_768
            var submitted = 0
            var phase = 0.0
            while (submitted < totalSamples) {
                val iq = ByteArray(batchSize * 2)
                for (sample in 0 until batchSize) {
                    val timeSeconds = (submitted + sample).toDouble() / sampleRate
                    val instantaneousHz = 3_000.0 * sin(2.0 * PI * 1_000.0 * timeSeconds)
                    phase += 2.0 * PI * instantaneousHz / sampleRate
                    iq[sample * 2] = (127.5 + 80.0 * cos(phase)).roundToInt().toByte()
                    iq[sample * 2 + 1] = (127.5 + 80.0 * sin(phase)).roundToInt().toByte()
                }
                assertEquals(batchSize, NativeReceiver.nativePushIq(handle, iq, iq.size))
                submitted += batchSize
                waitForProcessed(handle, submitted.toLong())
            }

            val pcm = ShortArray(8_192)
            val audioFrames = NativeReceiver.nativeReadAudio(handle, pcm, pcm.size)
            assertTrue("No demodulated PCM was returned: $audioFrames", audioFrames > 3_000)
            assertTrue(
                "Demodulated PCM was silent",
                pcm.take(audioFrames).any { kotlin.math.abs(it.toInt()) > 500 },
            )
            val stats = NativeReceiver.nativeStats(handle)
            assertEquals(3, stats.size)
            assertEquals(totalSamples.toLong(), stats[0])
            assertEquals(0L, stats[1])
            assertEquals(totalSamples.toLong(), stats[2])
        } finally {
            NativeReceiver.nativeDestroy(handle)
        }
    }

    @Test
    fun afcEstimatesModulatedFmOffsetButAbstainsOnNoise() {
        // This exercises the arm64 JNI and DSP path at the catalog's 1.024 MS/s
        // capture rate. The samples are generated locally, not from the SDR.
        val sampleRate = 1_024_000
        val batchSize = 4_096
        val signalSamples = sampleRate * 17 / 10 // 1.7 s, enough for three 0.5 s windows.
        val noiseSamples = sampleRate * 11 / 10
        val handle = NativeReceiver.nativeCreate(sampleRate)
        assertTrue("Native receiver was not created", handle != 0L)
        try {
            assertEquals(0, NativeReceiver.nativeConfigureNfm(handle, 5_000.0, 0.0))
            assertEquals(0, NativeReceiver.nativeSetMode(handle, 1))
            NativeReceiver.nativeSetCorrections(handle, 145_825_000.0, 0.0, 500.0)

            val iq = ByteArray(batchSize * 2)
            var phase = 0.0
            var submitted = 0
            while (submitted < signalSamples) {
                for (sample in 0 until batchSize) {
                    val timeSeconds = (submitted + sample).toDouble() / sampleRate
                    val frequencyHz = 2_500.0 +
                        3_000.0 * sin(2.0 * PI * 1_000.0 * timeSeconds)
                    phase += 2.0 * PI * frequencyHz / sampleRate
                    if (phase > PI || phase < -PI) phase = Math.IEEEremainder(phase, 2.0 * PI)
                    iq[2 * sample] = (127.5 + 80.0 * cos(phase)).roundToInt().toByte()
                    iq[2 * sample + 1] = (127.5 + 80.0 * sin(phase)).roundToInt().toByte()
                }
                assertEquals(batchSize, NativeReceiver.nativePushIq(handle, iq, iq.size))
                submitted += batchSize
                waitForProcessed(handle, submitted.toLong())
            }
            val afc = NativeReceiver.nativeAfcStats(handle)
            assertEquals(3, afc.size)
            assertTrue("AFC did not acquire stable FM IQ: ${afc.toList()}", afc[0] > 0.5)
            assertEquals("Incorrect AFC correction", 2_000.0, afc[1], 250.0)
            assertTrue("Residual remained high: ${afc[2]}", kotlin.math.abs(afc[2]) < 400.0)

            assertEquals(0, NativeReceiver.nativeSetMode(handle, 0))
            assertEquals(0, NativeReceiver.nativeSetMode(handle, 1))
            NativeReceiver.nativeSetCorrections(handle, 145_825_000.0, 0.0, 0.0)
            var random = 0x7183ac4d
            submitted = 0
            while (submitted < noiseSamples) {
                for (index in iq.indices) {
                    random = random xor (random shl 13)
                    random = random xor (random ushr 17)
                    random = random xor (random shl 5)
                    iq[index] = (random ushr 24).toByte()
                }
                assertEquals(batchSize, NativeReceiver.nativePushIq(handle, iq, iq.size))
                submitted += batchSize
                waitForProcessed(handle, (signalSamples + submitted).toLong())
            }
            val noiseAfc = NativeReceiver.nativeAfcStats(handle)
            assertEquals(3, noiseAfc.size)
            assertEquals("Noise must not acquire AFC", 0.0, noiseAfc[0], 0.0)
            assertEquals("Noise must not retain AFC", 0.0, noiseAfc[1], 0.0)
        } finally {
            NativeReceiver.nativeDestroy(handle)
        }
    }

    @Test
    fun syntheticCarrierAppearsInSpectrum() {
        val handle = NativeReceiver.nativeCreate(256_000)
        assertTrue("Native receiver was not created", handle != 0L)
        try {
            assertEquals(4_096, NativeReceiver.nativeGenerateTestTone(handle, 32_000.0, 4_096))
            waitForProcessed(handle, 4_096)
            val spectrum = NativeReceiver.nativeSpectrum(handle)
            assertEquals(256, spectrum.size)
            assertTrue(spectrum.all { it.isFinite() })
            val peakBin = spectrum.indices.maxByOrNull { spectrum[it] }
            assertEquals(160, peakBin)
            assertTrue("Synthetic carrier is missing", spectrum[160] > -30f)
            val iq = NativeReceiver.nativeIqSnapshot(handle)
            assertEquals(512, iq.size)
            assertTrue("No complex samples reached the display path", iq.max() > 0.35f && iq.min() < -0.35f)
            assertTrue(iq.all { it.isFinite() })
            assertEquals(3, NativeReceiver.nativeDecoderStats(handle).size)
            val afc = NativeReceiver.nativeAfcStats(handle)
            assertEquals(3, afc.size)
            assertEquals(0.0, afc[0], 0.0)
            assertEquals(0.0, afc[1], 0.0)
        } finally {
            NativeReceiver.nativeDestroy(handle)
        }
    }

    private fun waitForProcessed(handle: Long, expected: Long) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (NativeReceiver.nativeStats(handle).getOrNull(2) == expected) return
            SystemClock.sleep(5)
        }
        assertEquals("Native DSP worker stalled", expected, NativeReceiver.nativeStats(handle)[2])
    }
}
