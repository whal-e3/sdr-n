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
