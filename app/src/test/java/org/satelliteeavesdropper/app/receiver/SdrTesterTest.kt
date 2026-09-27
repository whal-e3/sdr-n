package org.satelliteeavesdropper.app.receiver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SdrTesterTest {
    private val config = SdrTestConfig("Fake RTL-SDR", durationMs = 5_000)
    private val variedIq = listOf(0.1f, -0.2f, 0.3f, 0.05f, -0.4f, 0.12f)

    @Test fun validatesFiniteMHzAndPreservesFractionalTuning() {
        assertEquals(100_000_000L, parseSdrTestFrequencyHz("100"))
        assertEquals(145_825_000L, parseSdrTestFrequencyHz(" 145.825 "))
        assertEquals(1_000_000L, parseSdrTestFrequencyHz("1"))
        assertEquals(6_000_000_000L, parseSdrTestFrequencyHz("6000"))
        listOf("", "oops", "NaN", "Infinity", "-Infinity", "0", "0.999", "6000.01").forEach {
            assertNull("Unexpected valid frequency: $it", parseSdrTestFrequencyHz(it))
        }
    }

    @Test fun liveSamplesProduceFiniteBoundedCheckWithoutTargetOrDecoder() = runBlocking {
        val frames = mutableListOf<ReceptionSnapshot>()
        val backend = FakeBackend { advancing(it) }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, frames::add, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.PASSED, result.outcome)
        assertTrue(result.measuredSampleRateSps >= 1_024_000)
        assertEquals("Test stopped", result.snapshot.state)
        assertEquals(48, result.snapshot.spectrogram.size)
        assertTrue(result.snapshot.spectrogram.all { it.size == 128 })
        assertTrue(frames.all { it.targetName.isEmpty() && it.targetNoradId.isEmpty() && it.decoderId.isEmpty() })
        assertTrue(frames.any { it.state == "SDR test" && it.processedSamples > 0 })
        assertTrue(frames.all { it.spectrogram.size <= 48 })
        assertNull(frames.first().samplesUpdatedAtElapsedMs)
        assertNotNull(result.snapshot.samplesUpdatedAtElapsedMs)
        assertEquals(listOf("start", "stop", "close"), backend.events)
        assertTrue(result.summary.contains("RF sensitivity, antenna performance, and decoding were not verified"))
        assertTrue(result.snapshot.error == null)
    }

    @Test fun sessionsHaveDistinctIdsAndTerminalEvidenceDoesNotDisappear() = runBlocking {
        val clock = FakeClock()
        val first = runSdrTest(FakeBackend { advancing(it) }, config, {}, clock::now, clock::wait)
        val second = runSdrTest(FakeBackend { advancing(it) }, config, {}, clock::now, clock::wait)
        assertNotEquals(first.snapshot.sessionId, second.snapshot.sessionId)
        assertTrue(first.snapshot.processedSamples > 0)
        assertTrue(first.snapshot.iqSamples.isNotEmpty())
        assertTrue(first.snapshot.spectrum.isNotEmpty())
    }

    @Test fun noUsbSamplesFailsAtWatchdogRatherThanPassingAnEmptyTrace() = runBlocking {
        val backend = FakeBackend { SdrTestRead(0, 0, 0, List(256) { -120f }) }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.FAILED, result.outcome)
        assertTrue(result.snapshot.error!!.contains("no USB IQ samples"))
        assertEquals(3_500L, clock.value - 1_000)
        assertTrue(result.snapshot.spectrum.isEmpty())
        assertNull(result.snapshot.samplesUpdatedAtElapsedMs)
        assertEquals(listOf("start", "stop", "close"), backend.events)
    }

    @Test fun acceptedSamplesWithoutDspProgressFail() = runBlocking {
        val backend = FakeBackend { SdrTestRead(it * 100_000L, 0, 0) }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.FAILED, result.outcome)
        assertTrue(result.snapshot.error!!.contains("DSP did not process"))
        assertTrue(result.snapshot.acceptedSamples > 0)
        assertEquals(0L, result.snapshot.processedSamples)
    }

    @Test fun stalledCounterRetainsLastDataTimeAndDoesNotAppendDuplicateWaterfallRows() = runBlocking {
        val frames = mutableListOf<ReceptionSnapshot>()
        val backend = FakeBackend { advancing(minOf(it, 3)) }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, frames::add, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.FAILED, result.outcome)
        assertTrue(result.snapshot.error!!.contains("stalled"))
        assertEquals(3, result.snapshot.spectrogram.size)
        assertEquals(1_200L, result.snapshot.samplesUpdatedAtElapsedMs)
        assertTrue(frames.filter { it.processedSamples == 3 * 102_400L }
            .all { it.samplesUpdatedAtElapsedMs == 1_200L && it.spectrogram.size == 3 })
    }

    @Test fun constantComplexIqWarnsEvenWhenItsIAndQComponentsDiffer() = runBlocking {
        val backend = FakeBackend { advancing(it).copy(iqSamples = List(512) { index ->
            if (index % 2 == 0) 0.2f else -0.3f
        }) }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.WARNING, result.outcome)
        assertTrue(result.summary.contains("IQ appears constant"))
        assertNull(result.snapshot.error)
    }

    @Test fun sampleDropsAreWarningEvidenceInsteadOfACleanPass() = runBlocking {
        val backend = FakeBackend { advancing(it).copy(droppedSamples = it * 10L) }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.WARNING, result.outcome)
        assertTrue(result.summary.contains("samples were dropped"))
        assertTrue(result.snapshot.droppedSamples > 0)
    }

    @Test fun missingIqAndVeryLowThroughputAreReported() = runBlocking {
        val backend = FakeBackend { SdrTestRead(it * 100L, 0, it * 100L, List(256) { -60f }) }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.WARNING, result.outcome)
        assertTrue(result.summary.contains("no IQ waveform"))
        assertTrue(result.summary.contains("much slower"))
    }

    @Test fun clippingWarnsWithoutClaimingReceiverSensitivity() = runBlocking {
        val backend = FakeBackend { advancing(it).copy(iqSamples = listOf(1f, 0.99f, -1f, -0.99f)) }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.WARNING, result.outcome)
        assertTrue(result.summary.contains("heavily clipped"))
    }

    @Test fun failedStartStillStopsAndClosesItsOwnedConnection() = runBlocking {
        val backend = FakeBackend { advancing(it) }.apply { startError = IllegalStateException("Tuner rejected frequency") }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.FAILED, result.outcome)
        assertEquals("Tuner rejected frequency", result.snapshot.error)
        assertEquals(listOf("start", "stop", "close"), backend.events)
    }

    @Test fun failedStopStillClosesBeforePublishingTerminalState() = runBlocking {
        val backend = FakeBackend { advancing(it) }.apply { stopError = IllegalStateException("stop error") }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, { snapshot ->
            if (snapshot.state == "Test stopped") assertTrue(backend.events.contains("close"))
        }, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.FAILED, result.outcome)
        assertTrue(result.snapshot.error!!.contains("Could not stop"))
        assertEquals(listOf("start", "stop", "close"), backend.events)
    }

    @Test fun failedConnectionCloseCannotProduceAPassedResult() = runBlocking {
        val backend = FakeBackend { advancing(it) }.apply { closeError = IllegalStateException("close error") }
        val clock = FakeClock()
        val result = runSdrTest(backend, config, {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.FAILED, result.outcome)
        assertTrue(result.snapshot.error!!.contains("Could not close"))
        assertEquals(listOf("start", "stop", "close"), backend.events)
    }

    @Test fun cancellationRetainsSampleEvidenceAndClosesBeforeFinishing() = runBlocking {
        val backend = FakeBackend { advancing(it) }
        val frames = Collections.synchronizedList(mutableListOf<ReceptionSnapshot>())
        val waiting = CompletableDeferred<Unit>()
        val job = launch {
            runSdrTest(backend, config, frames::add, { 1_000L }) {
                waiting.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(2_000) { waiting.await() }
        assertFalse(backend.events.contains("close"))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        val terminal = frames.last()
        assertEquals("Test stopped", terminal.state)
        assertTrue(terminal.processedSamples > 0)
        assertNotNull(terminal.samplesUpdatedAtElapsedMs)
        assertNull(terminal.error)
        assertEquals(listOf("start", "stop", "close"), backend.events)
    }

    @Test fun invalidConfigReleasesConnectionWithoutStartingHardware() = runBlocking {
        val backend = FakeBackend { advancing(it) }
        val clock = FakeClock()
        val result = runSdrTest(backend, config.copy(frequencyHz = 0), {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.FAILED, result.outcome)
        assertEquals(listOf("stop", "close"), backend.events)
    }

    @Test fun invalidOrResettingDspDataFailsAndCloses() = runBlocking {
        val clock = FakeClock()
        val invalid = FakeBackend { advancing(it).copy(spectrum = List(256) { Float.NaN }) }
        val invalidResult = runSdrTest(invalid, config, {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.FAILED, invalidResult.outcome)
        assertTrue(invalidResult.summary.contains("spectrum output is invalid"))
        val reset = FakeBackend { advancing(if (it < 3) it else 0) }
        val resetResult = runSdrTest(reset, config, {}, clock::now, clock::wait)
        assertEquals(SdrTestOutcome.FAILED, resetResult.outcome)
        assertTrue(resetResult.summary.contains("counters reset"))
    }

    private fun advancing(read: Int) = SdrTestRead(
        acceptedSamples = read * 102_400L,
        droppedSamples = 0,
        processedSamples = read * 102_400L,
        spectrum = List(256) { -80f + (it % 20) },
        iqSamples = variedIq,
    )

    private class FakeClock {
        var value = 1_000L
        fun now() = value
        suspend fun wait(ms: Long) { value += ms }
    }

    private class FakeBackend(private val frame: (Int) -> SdrTestRead) : SdrTestBackend {
        val events = Collections.synchronizedList(mutableListOf<String>())
        var startError: Exception? = null
        var stopError: Exception? = null
        var closeError: Exception? = null
        private var reads = 0
        override fun start(frequencyHz: Long, sampleRateSps: Int) {
            events.add("start")
            assertEquals(SDR_TEST_SAMPLE_RATE_SPS, sampleRateSps)
            startError?.let { throw it }
        }
        override fun read() = frame(++reads)
        override fun stop() { events.add("stop"); stopError?.let { throw it } }
        override fun close() { events.add("close"); closeError?.let { throw it } }
    }
}
