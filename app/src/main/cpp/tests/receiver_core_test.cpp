#include "receiver_core.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>

namespace {

#define CHECK(expression) do { if (!(expression)) { \
    std::fprintf(stderr, "Check failed: %s (%s:%d)\n", \
                 #expression, __FILE__, __LINE__); \
    std::abort(); \
} } while (false)

bool waitFor(satellite_rx::ReceiverCore& receiver, std::uint64_t count) {
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::seconds(2);
    while (std::chrono::steady_clock::now() < deadline) {
        if (receiver.stats().processed_complex_samples >= count) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return false;
}

std::size_t peakBin(const satellite_rx::ReceiverCore& receiver) {
    const auto bins = receiver.spectrum();
    return static_cast<std::size_t>(
        std::distance(bins.begin(), std::max_element(bins.begin(), bins.end())));
}

double toneMagnitude(const std::vector<std::int16_t>& audio, double hz,
                     std::size_t start) {
    constexpr double pi = 3.14159265358979323846;
    double real = 0.0;
    double imaginary = 0.0;
    for (std::size_t index = start; index < audio.size(); ++index) {
        const double angle = 2.0 * pi * hz * (index - start) /
                             satellite_rx::kAudioSampleRate;
        real += audio[index] * std::cos(angle);
        imaginary -= audio[index] * std::sin(angle);
    }
    return 2.0 * std::hypot(real, imaginary) / (audio.size() - start);
}

void feedFm(satellite_rx::ReceiverCore& receiver, std::uint32_t rate,
            double raw_carrier_hz, double amplitude,
            std::size_t total_iq) {
    constexpr double pi = 3.14159265358979323846;
    constexpr std::size_t batch_size = 4096;
    double phase = 0.0;
    std::size_t submitted = 0;
    const auto before = receiver.stats().processed_complex_samples;
    while (submitted < total_iq) {
        const auto count = std::min(batch_size, total_iq - submitted);
        std::vector<std::uint8_t> iq(2 * count);
        for (std::size_t i = 0; i < count; ++i) {
            const double time = static_cast<double>(submitted + i) / rate;
            phase += 2.0 * pi * (raw_carrier_hz +
                3000.0 * std::sin(2.0 * pi * 1000.0 * time)) / rate;
            if (phase > pi || phase < -pi)
                phase = std::remainder(phase, 2.0 * pi);
            iq[2 * i] = static_cast<std::uint8_t>(std::lround(
                127.5 + amplitude * std::cos(phase)));
            iq[2 * i + 1] = static_cast<std::uint8_t>(std::lround(
                127.5 + amplitude * std::sin(phase)));
        }
        CHECK(receiver.pushIq(iq.data(), iq.size()) == static_cast<int>(count));
        submitted += count;
        CHECK(waitFor(receiver, before + submitted));
    }
}

void testAfcForOffset(std::uint32_t rate, double raw_carrier_hz,
                      double predicted_hz) {
    satellite_rx::ReceiverCore receiver(rate);
    CHECK(receiver.configureNfm(5000.0, 0.0) == 0);
    CHECK(receiver.setMode(static_cast<int>(
        satellite_rx::ReceiverMode::NfmAudio)) == 0);
    receiver.setCorrections(145800000.0, 0.0, predicted_hz);
    feedFm(receiver, rate, raw_carrier_hz, 80.0, rate * 2);
    const auto afc = receiver.afcStats();
    CHECK(afc.tracking);
    CHECK(std::abs(afc.applied_hz - (raw_carrier_hz - predicted_hz)) < 150.0);
    CHECK(std::abs(afc.residual_hz) < 250.0);

    // A discontinuous new orbital correction must discard old RF evidence.
    receiver.setCorrections(145800000.0, 0.0, predicted_hz + 1000.0);
    const auto before = receiver.stats().processed_complex_samples;
    feedFm(receiver, rate, raw_carrier_hz, 80.0, 4096);
    CHECK(receiver.stats().processed_complex_samples == before + 4096);
    CHECK(!receiver.afcStats().tracking);
    CHECK(receiver.afcStats().applied_hz == 0.0);

    CHECK(receiver.setMode(static_cast<int>(
        satellite_rx::ReceiverMode::SpectrumOnly)) == 0);
    CHECK(receiver.generateTestTone(32000.0, 4096) == 4096);
    CHECK(waitFor(receiver, before + 8192));
    CHECK(!receiver.afcStats().tracking);
    CHECK(receiver.afcStats().applied_hz == 0.0);
}

void testAfcRejectsNoiseAndCenterSpur() {
    constexpr std::uint32_t rate = 256000;
    using satellite_rx::ReceiverCore;
    {
        ReceiverCore receiver(rate);
        CHECK(receiver.setMode(static_cast<int>(
            satellite_rx::ReceiverMode::NfmAudio)) == 0);
        std::uint32_t random = 0x7183ac4d;
        std::size_t submitted = 0;
        while (submitted < rate * 3 / 2) {
            constexpr std::size_t count = 4096;
            std::vector<std::uint8_t> iq(count * 2);
            for (auto& byte : iq) {
                random ^= random << 13;
                random ^= random >> 17;
                random ^= random << 5;
                byte = static_cast<std::uint8_t>(random >> 24);
            }
            CHECK(receiver.pushIq(iq.data(), iq.size()) == count);
            submitted += count;
            CHECK(waitFor(receiver, submitted));
        }
        CHECK(!receiver.afcStats().tracking);
        CHECK(receiver.afcStats().applied_hz == 0.0);
    }
    {
        ReceiverCore receiver(rate);
        CHECK(receiver.setMode(static_cast<int>(
            satellite_rx::ReceiverMode::NfmAudio)) == 0);
        receiver.setCorrections(145800000.0, 0.0, 1500.0);
        // A modulated component at exact tuner center is indistinguishable
        // from a modulated zero-IF artifact, so AFC must abstain.
        feedFm(receiver, rate, 0.0, 80.0, rate * 3 / 2);
        CHECK(!receiver.afcStats().tracking);
        CHECK(receiver.afcStats().applied_hz == 0.0);
    }
    {
        ReceiverCore receiver(rate);
        CHECK(receiver.setMode(static_cast<int>(
            satellite_rx::ReceiverMode::NfmAudio)) == 0);
        feedFm(receiver, rate, 2000.0, 2.0, rate * 3 / 2);
        CHECK(!receiver.afcStats().tracking);
        CHECK(receiver.afcStats().applied_hz == 0.0);
    }
}

void testNfmAudio(std::uint32_t rate) {
    using satellite_rx::ReceiverCore;
    constexpr double pi = 3.14159265358979323846;
    constexpr double audio_hz = 1000.0;
    constexpr double deviation_hz = 3000.0;
    constexpr std::size_t batch_size = 4096;
    const std::size_t total_iq = rate / 4;

    ReceiverCore receiver(rate);
    CHECK(receiver.setMode(17) == -1);
    CHECK(receiver.configureNfm(0.0, 75.0) == -1);
    CHECK(receiver.configureNfm(5000.0, 0.0) == 0);
    CHECK(receiver.setMode(static_cast<int>(satellite_rx::ReceiverMode::NfmAudio))
           == 0);

    std::size_t submitted = 0;
    double phase = 0.0;
    while (submitted < total_iq) {
        const auto count = std::min(batch_size, total_iq - submitted);
        std::vector<std::uint8_t> iq(2 * count);
        for (std::size_t i = 0; i < count; ++i) {
            const double time = static_cast<double>(submitted + i) / rate;
            const double frequency = deviation_hz *
                std::sin(2.0 * pi * audio_hz * time);
            phase += 2.0 * pi * frequency / rate;
            iq[2 * i] = static_cast<std::uint8_t>(std::lround(
                127.5 + 80.0 * std::cos(phase)));
            iq[2 * i + 1] = static_cast<std::uint8_t>(std::lround(
                127.5 + 80.0 * std::sin(phase)));
        }
        CHECK(receiver.pushIq(iq.data(), iq.size()) == static_cast<int>(count));
        submitted += count;
        CHECK(waitFor(receiver, submitted));
    }

    std::vector<std::int16_t> pcm(satellite_rx::kAudioSampleRate / 2);
    const auto count = receiver.readAudio(pcm.data(), pcm.size());
    CHECK(count > satellite_rx::kAudioSampleRate / 5);
    CHECK(count < satellite_rx::kAudioSampleRate / 3);
    pcm.resize(count);
    const auto voice = toneMagnitude(pcm, audio_hz, 1000);
    const auto third_harmonic = toneMagnitude(pcm, 3.0 * audio_hz, 1000);
    CHECK(voice > 5000.0);
    CHECK(voice > 20.0 * third_harmonic);
    CHECK(receiver.readAudio(pcm.data(), pcm.size()) == 0);

    CHECK(receiver.setMode(static_cast<int>(satellite_rx::ReceiverMode::SpectrumOnly))
           == 0);
    CHECK(receiver.generateTestTone(1000.0, batch_size) == batch_size);
    CHECK(waitFor(receiver, submitted + batch_size));
    CHECK(receiver.readAudio(pcm.data(), pcm.size()) == 0);
    CHECK(receiver.readAudio(nullptr, 100) == 0);
}

}  // namespace

int main() {
    using satellite_rx::ReceiverCore;
    constexpr int rate = 256000;
    constexpr int tone = 32000;  // Exactly +32 FFT bins.

    ReceiverCore receiver(rate);
    CHECK(receiver.generateTestTone(tone, 4096) == 4096);
    CHECK(waitFor(receiver, 4096));
    CHECK(peakBin(receiver) == 160);
    const auto iq_snapshot = receiver.iqSnapshot();
    const auto [minimum_i, maximum_i] = std::minmax_element(
        iq_snapshot.begin(), iq_snapshot.end());
    CHECK(*minimum_i < -0.35f);
    CHECK(*maximum_i > 0.35f);
    CHECK(std::all_of(iq_snapshot.begin(), iq_snapshot.end(),
                      [](float sample) { return std::isfinite(sample); }));

    // Mix away the predicted offset without a phase discontinuity.
    receiver.setCorrections(0.0, 0.0, tone);
    CHECK(receiver.generateTestTone(tone, 4096) == 4096);
    CHECK(waitFor(receiver, 8192));
    CHECK(peakBin(receiver) == 128);

    // PPM contributes center_frequency * ppm * 1e-6 Hz.
    receiver.setCorrections(32000000.0, 1000.0, 0.0);
    CHECK(receiver.generateTestTone(tone, 4096) == 4096);
    CHECK(waitFor(receiver, 12288));
    CHECK(peakBin(receiver) == 128);

    CHECK(receiver.pushIq(nullptr, 512) == -1);
    std::vector<std::uint8_t> oversized((satellite_rx::kIqQueueCapacity + 1024) * 2,
                                        127);
    const int accepted = receiver.pushIq(oversized.data(), oversized.size());
    CHECK(accepted >= 0);
    CHECK(receiver.stats().dropped_complex_samples >= 1024);
    CHECK(receiver.stats().accepted_complex_samples >= 12288);
    CHECK(waitFor(receiver, 12288 + static_cast<std::uint64_t>(accepted)));
    testNfmAudio(256000);
    testNfmAudio(2400000);
    testAfcForOffset(1024000, 2500.0, 500.0);
    testAfcForOffset(256000, -2500.0, -500.0);
    testAfcRejectsNoiseAndCenterSpur();
    return 0;
}
