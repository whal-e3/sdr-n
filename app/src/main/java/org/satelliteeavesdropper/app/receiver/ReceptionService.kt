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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.satelliteeavesdropper.app.NativeReceiver
import org.satelliteeavesdropper.orbit.ObserverLocation
import org.satelliteeavesdropper.orbit.OmmElements
import org.satelliteeavesdropper.orbit.SatelliteOrbit
import java.time.Instant

data class ReceptionSnapshot(
    val state: String = "Idle",
    val device: String = "",
    val spectrum: List<Float> = emptyList(),
    val acceptedSamples: Long = 0,
    val droppedSamples: Long = 0,
    val packets: List<String> = emptyList(),
    val error: String? = null,
)

object ReceptionState {
    private val mutable = MutableStateFlow(ReceptionSnapshot())
    val snapshots = mutable.asStateFlow()
    fun publish(snapshot: ReceptionSnapshot) { mutable.value = snapshot }
}

/** Owns the Android USB grant and the native receive loop for a manually started session. */
class ReceptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            worker?.cancel()
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
        val audioEnabled = intent.getStringExtra(EXTRA_DECODER_ID) == "AUDIO_NFM"
        val packetEnabled = intent.getStringExtra(EXTRA_DECODER_ID) == "AX25_AFSK1200"
        val ommJson = intent.getStringExtra(EXTRA_OMM_JSON)
        if (deviceType !in 1..2 || frequencyHz <= 0 || sampleRate <= 0 || ommJson == null) {
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
        worker?.cancel()
        worker = scope.launch {
            val connection = manager.openDevice(usbDevice)
            if (connection == null) {
                ReceptionState.publish(ReceptionSnapshot(error = "Could not open the USB device"))
                stopSelf(startId)
                return@launch
            }
            var handle = 0L
            var audioTrack: AudioTrack? = null
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
                fun updateDoppler() {
                    val look = tracking.first.lookFrom(tracking.second, Instant.now())
                    NativeReceiver.nativeSetCorrections(
                        handle,
                        frequencyHz.toDouble(),
                        0.0,
                        look.dopplerShiftHz(frequencyHz.toDouble()),
                    )
                }
                updateDoppler()
                val startCode = NativeReceiver.nativeStartRx(handle, frequencyHz)
                require(startCode == 0) { "Could not start receive stream (code $startCode)" }
                ReceptionState.publish(ReceptionSnapshot(state = "Receiving", device = usbDevice.productName ?: deviceName))
                val pcm = if (audioEnabled) ShortArray(2_048) else null
                val packets = ArrayDeque<String>()
                var nextDisplayAt = 0L
                while (isActive) {
                    if (packetEnabled) {
                        var drained = 0
                        while (drained < 16) {
                            val frame = NativeReceiver.nativeReadPacket(handle) ?: break
                            packets.addLast(Ax25Formatter.format(frame))
                            while (packets.size > 20) packets.removeFirst()
                            drained++
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextDisplayAt) {
                        updateDoppler()
                        val bins = NativeReceiver.nativeSpectrum(handle)
                        val stats = NativeReceiver.nativeStats(handle)
                        ReceptionState.publish(
                            ReceptionSnapshot(
                                state = "Receiving",
                                device = (usbDevice.productName ?: deviceName) + if (audioEnabled) " · NFM audio" else "",
                                spectrum = bins.toList(),
                                acceptedSamples = stats.getOrElse(0) { 0 },
                                droppedSamples = stats.getOrElse(1) { 0 },
                                packets = packets.toList(),
                            ),
                        )
                        nextDisplayAt = now + 400
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
                            }
                        } else delay(10)
                    } else delay(100)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                ReceptionState.publish(ReceptionSnapshot())
                throw error
            } catch (error: Exception) {
                ReceptionState.publish(ReceptionSnapshot(error = error.message ?: "Receive session failed"))
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
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSession(deviceName: String, audioEnabled: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Satellite reception", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ReceptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Satellite receiver active")
            .setContentText("Receiving from $deviceName")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
        val foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (audioEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        startForeground(NOTIFICATION_ID, notification, foregroundTypes)
    }

    override fun onDestroy() {
        worker?.cancel()
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
        private const val CHANNEL = "reception"
        private const val NOTIFICATION_ID = 1001
    }
}
