package org.satelliteeavesdropper.app

/** JNI surface for the receive-only signal engine. Return codes are negative on failure. */
object NativeReceiver {
    init { System.loadLibrary("satellite_rx") }

    external fun nativeCreate(sampleRate: Int): Long
    external fun nativeDestroy(handle: Long)
    external fun nativePushIq(handle: Long, iq: ByteArray, length: Int): Int
    external fun nativeGenerateTestTone(handle: Long, toneHz: Double, sampleCount: Int): Int
    external fun nativeSpectrum(handle: Long): FloatArray
    external fun nativeStats(handle: Long): LongArray
    external fun nativeSetCorrections(handle: Long, centerFrequencyHz: Double, ppm: Double, dopplerHz: Double)
    external fun nativeSetMode(handle: Long, mode: Int): Int
    external fun nativeConfigureNfm(handle: Long, deviationHz: Double, deEmphasisUs: Double): Int
    external fun nativeReadAudio(handle: Long, pcm: ShortArray, maxSamples: Int): Int
    /** Returns a CRC-verified AX.25 body without FCS, or null when no packet is ready. */
    external fun nativeReadPacket(handle: Long): ByteArray?
    external fun nativeOpenUsb(handle: Long, fd: Int, deviceType: Int): Int
    external fun nativeStartRx(handle: Long, centerFrequencyHz: Long): Int
    external fun nativeStopRx(handle: Long)
}
