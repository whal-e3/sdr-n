#include "receiver_core.hpp"
#include "receiver_driver.hpp"
#include "rtl_sdr_driver.hpp"
#include "hackrf_driver.hpp"

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {

struct Session {
    explicit Session(std::uint32_t sample_rate)
        : core(std::make_shared<satellite_rx::ReceiverCore>(sample_rate)) {}

    std::shared_ptr<satellite_rx::ReceiverCore> core;
    std::unique_ptr<satellite_rx::ReceiverDriver> driver;
    std::mutex lifecycle_mutex;
};

std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> next_handle{1};

std::shared_ptr<Session> findSession(jlong handle) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto found = sessions.find(handle);
    return found == sessions.end() ? nullptr : found->second;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeCreate(
    JNIEnv*, jobject, jint sample_rate) {
    if (sample_rate <= 0) return 0;
    try {
        auto session = std::make_shared<Session>(
            static_cast<std::uint32_t>(sample_rate));
        const jlong handle = next_handle.fetch_add(1);
        std::lock_guard<std::mutex> lock(registry_mutex);
        sessions.emplace(handle, std::move(session));
        return handle;
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<Session> session;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        const auto found = sessions.find(handle);
        if (found == sessions.end()) return;
        session = found->second;
        sessions.erase(found);
    }
    std::lock_guard<std::mutex> lock(session->lifecycle_mutex);
    session->driver.reset();  // Joins any active RX thread before core dies.
}

extern "C" JNIEXPORT jint JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativePushIq(
    JNIEnv* env, jobject, jlong handle, jbyteArray iq, jint length) {
    const auto session = findSession(handle);
    if (!session || !iq || length <= 0 || length > env->GetArrayLength(iq) ||
        (length & 1) != 0) return -1;
    jbyte* bytes = env->GetByteArrayElements(iq, nullptr);
    if (!bytes) return -1;
    const int result = session->core->pushIq(
        reinterpret_cast<const std::uint8_t*>(bytes),
        static_cast<std::size_t>(length));
    env->ReleaseByteArrayElements(iq, bytes, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeGenerateTestTone(
    JNIEnv*, jobject, jlong handle, jdouble tone_hz, jint sample_count) {
    const auto session = findSession(handle);
    if (!session || sample_count <= 0) return -1;
    return session->core->generateTestTone(
        tone_hz, static_cast<std::size_t>(sample_count));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeSpectrum(
    JNIEnv* env, jobject, jlong handle) {
    const auto session = findSession(handle);
    if (!session) return env->NewFloatArray(0);
    const auto bins = session->core->spectrum();
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(bins.size()));
    if (result) env->SetFloatArrayRegion(result, 0,
                                         static_cast<jsize>(bins.size()),
                                         bins.data());
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeIqSnapshot(
    JNIEnv* env, jobject, jlong handle) {
    const auto session = findSession(handle);
    if (!session) return env->NewFloatArray(0);
    const auto samples = session->core->iqSnapshot();
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(samples.size()));
    if (result) env->SetFloatArrayRegion(result, 0,
                                         static_cast<jsize>(samples.size()),
                                         samples.data());
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeStats(
    JNIEnv* env, jobject, jlong handle) {
    const auto session = findSession(handle);
    if (!session) return env->NewLongArray(0);
    const auto stats = session->core->stats();
    const jlong values[] = {
        static_cast<jlong>(stats.accepted_complex_samples),
        static_cast<jlong>(stats.dropped_complex_samples),
        static_cast<jlong>(stats.processed_complex_samples),
    };
    jlongArray result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeDecoderStats(
    JNIEnv* env, jobject, jlong handle) {
    const auto session = findSession(handle);
    if (!session) return env->NewLongArray(0);
    const auto stats = session->core->decoderStats();
    const jlong values[] = {
        static_cast<jlong>(stats.hdlc_flag_candidates),
        static_cast<jlong>(stats.failed_frame_crc),
        static_cast<jlong>(stats.verified_frames),
    };
    jlongArray result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeAfcStats(
    JNIEnv* env, jobject, jlong handle) {
    const auto session = findSession(handle);
    if (!session) return env->NewDoubleArray(0);
    const auto stats = session->core->afcStats();
    const jdouble values[] = {
        stats.tracking ? 1.0 : 0.0,
        stats.applied_hz,
        stats.residual_hz,
    };
    jdoubleArray result = env->NewDoubleArray(3);
    if (result) env->SetDoubleArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeSetCorrections(
    JNIEnv*, jobject, jlong handle, jdouble center_frequency_hz,
    jdouble ppm, jdouble doppler_hz) {
    const auto session = findSession(handle);
    if (session) session->core->setCorrections(center_frequency_hz, ppm,
                                                doppler_hz);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeSetMode(
    JNIEnv*, jobject, jlong handle, jint mode) {
    const auto session = findSession(handle);
    return session ? session->core->setMode(mode) : -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeConfigureNfm(
    JNIEnv*, jobject, jlong handle, jdouble deviation_hz,
    jdouble deemphasis_microseconds) {
    const auto session = findSession(handle);
    return session ? session->core->configureNfm(
        deviation_hz, deemphasis_microseconds) : -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeReadAudio(
    JNIEnv* env, jobject, jlong handle, jshortArray pcm, jint max_samples) {
    const auto session = findSession(handle);
    if (!session || !pcm || max_samples <= 0 ||
        max_samples > env->GetArrayLength(pcm)) return -1;
    std::vector<std::int16_t> frames(static_cast<std::size_t>(max_samples));
    const auto count = session->core->readAudio(frames.data(), frames.size());
    if (count != 0)
        env->SetShortArrayRegion(pcm, 0, static_cast<jsize>(count),
                                 reinterpret_cast<const jshort*>(frames.data()));
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeReadPacket(
    JNIEnv* env, jobject, jlong handle) {
    const auto session = findSession(handle);
    if (!session) return nullptr;
    std::vector<std::uint8_t> packet;
    if (!session->core->readPacket(packet)) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(packet.size()));
    if (result) env->SetByteArrayRegion(
        result, 0, static_cast<jsize>(packet.size()),
        reinterpret_cast<const jbyte*>(packet.data()));
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeOpenUsb(
    JNIEnv*, jobject, jlong handle, jint fd, jint device_type) {
    const auto session = findSession(handle);
    if (!session || fd < 0) return -1;
    if (device_type != static_cast<int>(satellite_rx::DeviceType::RtlSdr) &&
        device_type != static_cast<int>(satellite_rx::DeviceType::HackRf))
        return -1;
    std::lock_guard<std::mutex> lock(session->lifecycle_mutex);
    if (session->driver) return -5;
    std::unique_ptr<satellite_rx::ReceiverDriver> driver;
    if (device_type == static_cast<int>(satellite_rx::DeviceType::RtlSdr))
        driver = std::make_unique<satellite_rx::RtlSdrDriver>();
    else
        driver = std::make_unique<satellite_rx::HackRfDriver>();
    const int result = driver->open(fd);
    if (result == 0) session->driver = std::move(driver);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeStartRx(
    JNIEnv*, jobject, jlong handle, jlong center_frequency_hz) {
    const auto session = findSession(handle);
    if (!session || center_frequency_hz <= 0) return -1;
    std::lock_guard<std::mutex> lock(session->lifecycle_mutex);
    if (!session->driver) return -2;
    const std::weak_ptr<satellite_rx::ReceiverCore> core = session->core;
    return session->driver->start(
        static_cast<std::uint64_t>(center_frequency_hz),
        session->core->sampleRate(),
        [core](const std::uint8_t* iq, std::size_t complex_samples) {
            if (const auto receiver = core.lock()) {
                receiver->pushIq(iq, complex_samples * 2);
            }
        });
}

extern "C" JNIEXPORT void JNICALL
Java_org_satelliteeavesdropper_app_NativeReceiver_nativeStopRx(
    JNIEnv*, jobject, jlong handle) {
    const auto session = findSession(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lock(session->lifecycle_mutex);
    if (session->driver) session->driver->stop();
}
