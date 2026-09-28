package org.satelliteeavesdropper.app.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.satelliteeavesdropper.app.NativeReceiver
import org.satelliteeavesdropper.app.R
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.OmmElements
import org.satelliteeavesdropper.orbit.SatelliteLook
import org.satelliteeavesdropper.orbit.SatelliteOrbit
import java.time.Instant

data class ReceptionSnapshot(
    val state: String = "Idle",
    /** Unique receive session; observer updates must match this and the target NORAD ID. */
    val sessionId: String = "",
    val device: String = "",
    val spectrum: List<Float> = emptyList(),
    val iqSamples: List<Float> = emptyList(),
    /** Oldest row first; each row is 128 FFT-bin maxima in dBFS. */
    val spectrogram: List<List<Float>> = emptyList(),
    val acceptedSamples: Long = 0,
    val droppedSamples: Long = 0,
    val processedSamples: Long = 0,
    /** Monotonic time when the displayed IQ last advanced, not when status was refreshed. */
    val samplesUpdatedAtElapsedMs: Long? = null,
    val sampleRateSps: Int = 0,
    val targetNoradId: String = "",
    val targetName: String = "",
    /** Physical RF tuner center; Doppler correction is applied in baseband. */
    val rfCenterHz: Long = 0,
    val predictedDopplerHz: Double = 0.0,
    /** Conditional carrier-like FM estimate from channel-filtered IQ. */
    val afcTracking: Boolean = false,
    val afcAppliedHz: Double = 0.0,
    val afcLastResidualHz: Double = 0.0,
    val lookAzimuthDegrees: Double? = null,
    val lookElevationDegrees: Double? = null,
    val observerFeedState: ObserverFeedState = ObserverFeedState.FIXED,
    val observerFixAgeSeconds: Long? = null,
    val decoderId: String = "",
    val hdlcFlagCandidates: Long = 0,
    val failedFrameCrc: Long = 0,
    /** Count of CRC-verified AX.25 frames found by the native decoder. */
    val verifiedFrameCount: Long = 0,
    /** Monotonic age of the latest verified frame, updated only when its count advances. */
    val latestVerifiedFrameAgeSeconds: Long? = null,
    val audioFramesPlayed: Long = 0,
    val packets: List<String> = emptyList(),
    val error: String? = null,
    /** Latest verified frames, oldest first; data is transient until explicitly exported. */
    val decodedPackets: List<DecodedPacket> = emptyList(),
    /** Previously dequeued frames evicted from the bounded history, not failed decoder frames. */
    val omittedPacketCount: Long = 0,
) {
    /** Center of the digitally corrected FFT, including predicted Doppler. */
    val displayCenterHz: Double get() = rfCenterHz + predictedDopplerHz

    /** Keep final sample and decoder evidence visible after a receive session ends. */
    fun stopped(): ReceptionSnapshot = when (state) {
        "Starting", "Receiving" -> copy(state = "Stopped")
        else -> this
    }
}

object ReceptionState {
    private val mutable = MutableStateFlow(ReceptionSnapshot())
    val snapshots = mutable.asStateFlow()
    @Volatile private var activeObserverSession: LiveObserverSession? = null
    private var activityVisible = false
    fun publish(snapshot: ReceptionSnapshot) { mutable.value = snapshot }
    @Synchronized
    internal fun beginObserverSession(session: LiveObserverSession) {
        activeObserverSession = session
        if (!activityVisible) session.pause(session.sessionId, session.targetNoradId, unavailable = false)
    }
    internal fun endObserverSession(session: LiveObserverSession) {
        synchronized(this) {
            if (activeObserverSession === session) activeObserverSession = null
        }
    }
    @Synchronized
    fun clearObserverSession() { activeObserverSession = null }
    @Synchronized
    fun updateObserver(
        sessionId: String, targetNoradId: String, observer: ObserverLocation,
        fixElapsedMs: Long, nowElapsedMs: Long,
    ): Boolean = if (activityVisible)
        activeObserverSession?.update(sessionId, targetNoradId, observer, fixElapsedMs, nowElapsedMs) ?: false
    else false
    fun pauseObserver(sessionId: String, targetNoradId: String, unavailable: Boolean): Boolean =
        activeObserverSession?.pause(sessionId, targetNoradId, unavailable) ?: false
    @Synchronized
    fun setActivityVisible(visible: Boolean) {
        activityVisible = visible
        if (!visible) activeObserverSession?.let {
            it.pause(it.sessionId, it.targetNoradId, unavailable = false)
        }
    }
}

/** Owns the Android USB grant and the native receive loop for a manually started session. */
class ReceptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ReceiverSessionCoordinator(scope)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ReceptionState.clearObserverSession()
            sessions.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val deviceType = intent.getIntExtra(EXTRA_DEVICE_TYPE, 0)
        val frequencyHz = intent.getLongExtra(EXTRA_FREQUENCY_HZ, 0)
        val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 0)
        val decoderId = intent.getStringExtra(EXTRA_DECODER_ID).orEmpty()
        val audioEnabled = decoderId == "AUDIO_NFM"
        val packetEnabled = decoderId == "AX25_AFSK1200"
        val targetNoradId = intent.getStringExtra(EXTRA_TARGET_NORAD).orEmpty()
        val targetName = intent.getStringExtra(EXTRA_TARGET_NAME).orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val automaticObserver = intent.getBooleanExtra(EXTRA_AUTOMATIC_OBSERVER, false)
        val initialFixElapsedMs = intent.getLongExtra(EXTRA_INITIAL_FIX_ELAPSED_MS, -1L)
            .takeIf { it >= 0 }
        if (decoderId !in setOf("", "AUDIO_NFM", "AX25_AFSK1200")) {
            ReceptionState.publish(ReceptionSnapshot(error = "Decoder $decoderId is not implemented on Android"))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val ommJson = intent.getStringExtra(EXTRA_OMM_JSON)
        if (deviceType !in 1..2 || frequencyHz <= 0 || sampleRate <= 0 || ommJson == null ||
            sessionId.isBlank() || targetNoradId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val tracking = runCatching {
            val json = JSONObject(ommJson)
            val fields = json.keys().asSequence().associateWith { json.get(it) }
            val orbit = SatelliteOrbit(OmmElements.fromCelestrakFields(fields))
            val observer = ObserverLocation(
                intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN),
                intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN),
                intent.getDoubleExtra(EXTRA_ALTITUDE, 0.0),
            )
            orbit to observer
        }.getOrElse {
            ReceptionState.publish(ReceptionSnapshot(error = "Invalid orbit/location for Doppler tracking"))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (automaticObserver && !isUsableReceiveFix(initialFixElapsedMs, SystemClock.elapsedRealtime())) {
            ReceptionState.publish(ReceptionSnapshot(error = "Automatic GPS fix expired before reception started"))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = manager.deviceList[deviceName]
        if (usbDevice == null || !manager.hasPermission(usbDevice)) {
            ReceptionState.publish(ReceptionSnapshot(error = "USB device is missing or permission was not granted"))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            startForegroundSession(deviceName, audioEnabled)
        } catch (error: Exception) {
            ReceptionState.publish(ReceptionSnapshot(error = error.message ?: "Could not start foreground receiver"))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val observerSession = LiveObserverSession(
            sessionId, targetNoradId, automaticObserver, tracking.second,
            initialFixElapsedMs, SystemClock.elapsedRealtime(),
        )
        ReceptionState.beginObserverSession(observerSession)
        sessions.replace {
            val connection = manager.openDevice(usbDevice)
            if (connection == null) {
                ReceptionState.publish(ReceptionSnapshot(error = "Could not open the USB device"))
                stopSelf(startId)
                return@replace
            }
            var handle = 0L
            var audioTrack: AudioTrack? = null
            val decodedPackets = DecodedPacketBuffer(sessionId)
            fun retainPacketEvidence(snapshot: ReceptionSnapshot): ReceptionSnapshot {
                if (snapshot.sessionId != sessionId) return snapshot
                val packetSnapshot = decodedPackets.snapshot()
                return snapshot.copy(
                    packets = packetSnapshot.map { it.displayText },
                    decodedPackets = packetSnapshot,
                    omittedPacketCount = decodedPackets.omittedPacketCount,
                )
            }
            try {
                handle = NativeReceiver.nativeCreate(sampleRate)
                require(handle != 0L) { "Could not create receiver" }
                val modeCode = NativeReceiver.nativeSetMode(handle, if (audioEnabled) 1 else if (packetEnabled) 2 else 0)
                require(modeCode == 0) { "Could not configure receive mode (code $modeCode)" }
                if (audioEnabled) {
                    require(NativeReceiver.nativeConfigureNfm(handle, 5_000.0, 75.0) == 0) {
                        "Could not configure NFM demodulator"
                    }
                    val minBuffer = AudioTrack.getMinBufferSize(
                        48_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    )
                    require(minBuffer > 0) { "48 kHz audio output is unavailable" }
                    val output = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                        )
                        .setAudioFormat(
                            AudioFormat.Builder().setSampleRate(48_000)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(maxOf(minBuffer, 16_384))
                        .build()
                    require(output.state == AudioTrack.STATE_INITIALIZED) { "Audio output could not initialize" }
                    output.play()
                    audioTrack = output
                }
                if (packetEnabled) {
                    require(NativeReceiver.nativeConfigureNfm(handle, 5_000.0, 0.0) == 0) {
                        "Could not configure AX.25 FM demodulator"
                    }
                }
                val openCode = NativeReceiver.nativeOpenUsb(handle, connection.fileDescriptor, deviceType)
                require(openCode == 0) { "USB receiver unavailable (code $openCode)" }
                var appliedPredictedDopplerHz = 0.0
                fun updateDoppler(): Pair<SatelliteLook, ObserverTracking> {
                    val observerTracking = observerSession.current(SystemClock.elapsedRealtime())
                    val look = tracking.first.lookFrom(observerTracking.location, Instant.now())
                    appliedPredictedDopplerHz = look.dopplerShiftHz(frequencyHz.toDouble())
                    NativeReceiver.nativeSetCorrections(
                        handle,
                        frequencyHz.toDouble(),
                        0.0,
                        appliedPredictedDopplerHz,
                    )
                    return look to observerTracking
                }
                val firstLook = updateDoppler()
                val startCode = NativeReceiver.nativeStartRx(handle, frequencyHz)
                require(startCode == 0) { "Could not start receive stream (code $startCode)" }
                val receiverName = usbDevice.productName ?: deviceName
                val initial = ReceptionSnapshot(
                    state = "Starting", sessionId = sessionId, device = receiverName, sampleRateSps = sampleRate,
                    targetNoradId = targetNoradId, targetName = targetName,
                    rfCenterHz = frequencyHz, decoderId = decoderId,
                    predictedDopplerHz = firstLook.first.dopplerShiftHz(frequencyHz.toDouble()),
                    lookAzimuthDegrees = firstLook.first.azimuthDegrees,
                    lookElevationDegrees = firstLook.first.elevationDegrees,
                    observerFeedState = firstLook.second.feedState,
                    observerFixAgeSeconds = firstLook.second.fixAgeSeconds,
                )
                ReceptionState.publish(initial)
                val pcm = if (audioEnabled) ShortArray(2_048) else null
                val waterfall = ArrayDeque<List<Float>>()
                val frameEvidence = FrameEvidence()
                var nextDisplayAt = 0L
                var lastAccepted = 0L
                var lastProcessed = 0L
                var samplesUpdatedAtElapsedMs: Long? = null
                var lastProgressAt = SystemClock.elapsedRealtime()
                var audioFramesPlayed = 0L
                while (isActive) {
                    if (packetEnabled) {
                        var drained = 0
                        while (drained < 16) {
                            val frame = NativeReceiver.nativeReadPacket(handle) ?: break
                            val dequeuedAtUtc = Instant.now()
                            val observer = observerSession.current(SystemClock.elapsedRealtime())
                            val packetLook = tracking.first.lookFrom(observer.location, dequeuedAtUtc)
                            val packetAfc = NativeReceiver.nativeAfcStats(handle)
                            decodedPackets.add(frame, dequeuedAtUtc, PacketMetadata(
                                sessionId = sessionId,
                                decoderId = decoderId,
                                targetNoradId = targetNoradId,
                                targetName = targetName,
                                device = receiverName,
                                rfCenterHz = frequencyHz,
                                sampleRateSps = sampleRate,
                                predictedDopplerHz = appliedPredictedDopplerHz,
                                afcTracking = packetAfc.getOrElse(0) { 0.0 } > 0.5,
                                afcAppliedHz = packetAfc.getOrElse(1) { 0.0 },
                                afcLastResidualHz = packetAfc.getOrElse(2) { 0.0 },
                                lookAzimuthDegrees = packetLook.azimuthDegrees,
                                lookElevationDegrees = packetLook.elevationDegrees,
                                observerLatitudeDegrees = observer.location.latitudeDegrees,
                                observerLongitudeDegrees = observer.location.longitudeDegrees,
                                observerAltitudeMeters = observer.location.altitudeMeters,
                                observerFeedState = observer.feedState,
                                observerFixAgeSeconds = observer.fixAgeSeconds,
                            ))
                            drained++
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextDisplayAt) {
                        val look = updateDoppler()
                        val bins = NativeReceiver.nativeSpectrum(handle)
                        val iq = NativeReceiver.nativeIqSnapshot(handle)
                        val stats = NativeReceiver.nativeStats(handle)
                        val decoderStats = if (packetEnabled) NativeReceiver.nativeDecoderStats(handle) else longArrayOf()
                        val verifiedFrames = decoderStats.getOrElse(2) { 0 }
                        val afcStats = if (audioEnabled || packetEnabled)
                            NativeReceiver.nativeAfcStats(handle) else doubleArrayOf()
                        val accepted = stats.getOrElse(0) { 0 }
                        val processed = stats.getOrElse(2) { 0 }
                        if (accepted > lastAccepted && processed > lastProcessed) {
                            lastProgressAt = now
                        }
                        if (now - lastProgressAt > 3_500) {
                            error(if (accepted == 0L) "Receive stream produced no IQ samples" else
                                "Receive stream stalled: IQ sample count stopped advancing")
                        }
                        if (processed > lastProcessed && bins.size == 256) {
                            waterfall.addLast(List(128) { bin -> maxOf(bins[2 * bin], bins[2 * bin + 1]) })
                            while (waterfall.size > 48) waterfall.removeFirst()
                        }
                        if (processed > lastProcessed) samplesUpdatedAtElapsedMs = now
                        lastAccepted = accepted
                        lastProcessed = processed
                        val packetSnapshot = decodedPackets.snapshot()
                        ReceptionState.publish(
                            ReceptionSnapshot(
                                state = if (processed > 0) "Receiving" else "Starting",
                                sessionId = sessionId,
                                device = receiverName,
                                spectrum = bins.toList(),
                                iqSamples = if (processed > 0) iq.toList() else emptyList(),
                                spectrogram = waterfall.toList(),
                                acceptedSamples = accepted,
                                droppedSamples = stats.getOrElse(1) { 0 },
                                processedSamples = processed,
                                samplesUpdatedAtElapsedMs = samplesUpdatedAtElapsedMs,
                                sampleRateSps = sampleRate,
                                targetNoradId = targetNoradId,
                                targetName = targetName,
                                rfCenterHz = frequencyHz,
                                predictedDopplerHz = look.first.dopplerShiftHz(frequencyHz.toDouble()),
                                afcTracking = afcStats.getOrElse(0) { 0.0 } > 0.5,
                                afcAppliedHz = afcStats.getOrElse(1) { 0.0 },
                                afcLastResidualHz = afcStats.getOrElse(2) { 0.0 },
                                lookAzimuthDegrees = look.first.azimuthDegrees,
                                lookElevationDegrees = look.first.elevationDegrees,
                                observerFeedState = look.second.feedState,
                                observerFixAgeSeconds = look.second.fixAgeSeconds,
                                decoderId = decoderId,
                                hdlcFlagCandidates = decoderStats.getOrElse(0) { 0 },
                                failedFrameCrc = decoderStats.getOrElse(1) { 0 },
                                verifiedFrameCount = verifiedFrames,
                                latestVerifiedFrameAgeSeconds =
                                    frameEvidence.latestFrameAgeSeconds(verifiedFrames, now),
                                audioFramesPlayed = audioFramesPlayed,
                                packets = packetSnapshot.map { it.displayText },
                                decodedPackets = packetSnapshot,
                                omittedPacketCount = decodedPackets.omittedPacketCount,
                            ),
                        )
                        nextDisplayAt = now + 100
                    }
                    if (pcm != null) {
                        val count = NativeReceiver.nativeReadAudio(handle, pcm, pcm.size)
                        require(count >= 0) { "Audio decoder stopped (code $count)" }
                        if (count > 0) {
                            var written = 0
                            while (written < count && isActive) {
                                val step = audioTrack!!.write(pcm, written, count - written, AudioTrack.WRITE_BLOCKING)
                                require(step > 0) { "Audio output failed (code $step)" }
                                written += step
                                audioFramesPlayed += step
                            }
                        } else delay(10)
                    } else delay(100)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                ReceptionState.publish(retainPacketEvidence(ReceptionState.snapshots.value).stopped())
                throw error
            } catch (error: Exception) {
                ReceptionState.publish(retainPacketEvidence(ReceptionState.snapshots.value).copy(
                    state = "Stopped", error = error.message ?: "Receive session failed",
                ))
            } finally {
                audioTrack?.let {
                    runCatching { it.pause() }
                    runCatching { it.flush() }
                    runCatching { it.release() }
                }
                if (handle != 0L) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        NativeReceiver.nativeStopRx(handle)
                        NativeReceiver.nativeDestroy(handle)
                    }
                }
                connection.close()
                stopSelf(startId)
            }
        }.invokeOnCompletion { ReceptionState.endObserverSession(observerSession) }
        return START_NOT_STICKY
    }

    private fun startForegroundSession(deviceName: String, audioEnabled: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.receiver_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ReceptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.receiver_notification_title))
            .setContentText(getString(R.string.receiver_notification_device, deviceName))
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
        val foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (audioEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        startForeground(NOTIFICATION_ID, notification, foregroundTypes)
    }

    override fun onDestroy() {
        sessions.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.satelliteeavesdropper.app.START_RECEPTION"
        const val ACTION_STOP = "org.satelliteeavesdropper.app.STOP_RECEPTION"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_FREQUENCY_HZ = "frequency_hz"
        const val EXTRA_SAMPLE_RATE = "sample_rate"
        const val EXTRA_OMM_JSON = "omm_json"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ALTITUDE = "altitude"
        const val EXTRA_DECODER_ID = "decoder_id"
        const val EXTRA_TARGET_NORAD = "target_norad"
        const val EXTRA_TARGET_NAME = "target_name"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_AUTOMATIC_OBSERVER = "automatic_observer"
        const val EXTRA_INITIAL_FIX_ELAPSED_MS = "initial_fix_elapsed_ms"
        private const val CHANNEL = "reception"
        private const val NOTIFICATION_ID = 1001
    }
}
