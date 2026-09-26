#include "../receiver_core.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
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

constexpr double kPi = 3.14159265358979323846;
constexpr std::uint32_t kIqRate = 256000;

std::uint16_t fcs(const std::vector<std::uint8_t>& bytes) {
    std::uint16_t crc = 0xffff;
    for (std::uint8_t byte : bytes) {
        crc ^= byte;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 1) ? static_cast<std::uint16_t>((crc >> 1) ^ 0x8408)
                            : static_cast<std::uint16_t>(crc >> 1);
        }
    }
    return static_cast<std::uint16_t>(crc ^ 0xffff);
}

void flag(std::vector<std::uint8_t>& bits) {
    for (int bit = 0; bit < 8; ++bit) bits.push_back((0x7e >> bit) & 1);
}

std::vector<std::uint8_t> framedBits(const std::vector<std::uint8_t>& body) {
    std::vector<std::uint8_t> bits;
    for (int i = 0; i < 24; ++i) flag(bits);
    auto bytes = body;
    const auto check = fcs(body);
    bytes.push_back(static_cast<std::uint8_t>(check));
    bytes.push_back(static_cast<std::uint8_t>(check >> 8));
    int ones = 0;
    for (std::uint8_t byte : bytes) {
        for (int bit = 0; bit < 8; ++bit) {
            const auto value = static_cast<std::uint8_t>((byte >> bit) & 1);
            bits.push_back(value);
            if (value == 0) {
                ones = 0;
            } else if (++ones == 5) {
                bits.push_back(0);
                ones = 0;
            }
        }
    }
    for (int i = 0; i < 4; ++i) flag(bits);
    return bits;
}

std::vector<std::uint8_t> fmIq(const std::vector<std::uint8_t>& bits,
                               double raw_carrier_hz = 0.0) {
    std::vector<std::uint8_t> tones;
    tones.reserve(bits.size());
    std::uint8_t tone = 0;
    for (const std::uint8_t bit : bits) {
        if (bit == 0) tone ^= 1;
        tones.push_back(tone);
    }
    const std::size_t iq_count = bits.size() * kIqRate / 1200;
    std::vector<std::uint8_t> iq(iq_count * 2);
    double audio_phase = 0.0;
    double fm_phase = 0.0;
    for (std::size_t i = 0; i < iq_count; ++i) {
        const std::size_t bit_index = std::min<std::size_t>(
            i * 1200 / kIqRate, tones.size() - 1);
        const double audio_hz = tones[bit_index] ? 2200.0 : 1200.0;
        audio_phase += 2.0 * kPi * audio_hz / kIqRate;
        if (audio_phase > 2.0 * kPi) audio_phase -= 2.0 * kPi;
        fm_phase += 2.0 * kPi * (raw_carrier_hz +
            3000.0 * std::sin(audio_phase)) / kIqRate;
        if (fm_phase > 2.0 * kPi) fm_phase -= 2.0 * kPi;
        iq[2 * i] = static_cast<std::uint8_t>(std::lround(
            127.5 + 80.0 * std::cos(fm_phase)));
        iq[2 * i + 1] = static_cast<std::uint8_t>(std::lround(
            127.5 + 80.0 * std::sin(fm_phase)));
    }
    return iq;
}

void feed(satellite_rx::ReceiverCore& receiver,
          const std::vector<std::uint8_t>& iq) {
    const std::uint64_t before = receiver.stats().processed_complex_samples;
    constexpr std::size_t kBatch = 4096;
    std::size_t submitted = 0;
    while (submitted < iq.size() / 2) {
        const std::size_t count = std::min(kBatch, iq.size() / 2 - submitted);
        CHECK(receiver.pushIq(iq.data() + submitted * 2, count * 2) ==
              static_cast<int>(count));
        submitted += count;
        const auto deadline = std::chrono::steady_clock::now() +
                              std::chrono::seconds(3);
        while (receiver.stats().processed_complex_samples < before + submitted &&
               std::chrono::steady_clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        CHECK(receiver.stats().processed_complex_samples >= before + submitted);
    }
}

}  // namespace

int main() {
    const std::vector<std::uint8_t> body = {
        'A' << 1, 'P' << 1, 'R' << 1, 'S' << 1, ' ' << 1, ' ' << 1,
        0x60,
        'T' << 1, 'E' << 1, 'S' << 1, 'T' << 1, ' ' << 1, ' ' << 1,
        0x61, 0x03, 0xf0,
        'H', 'e', 'l', 'l', 'o', ' ', 's', 'a', 't', 'e', 'l', 'l', 'i', 't', 'e',
        0xff, 0xff, 0x7e
    };
    const auto iq = fmIq(framedBits(body));
    satellite_rx::ReceiverCore receiver(kIqRate);
    CHECK(receiver.configureNfm(5000.0, 0.0) == 0);
    CHECK(receiver.setMode(static_cast<int>(
        satellite_rx::ReceiverMode::Ax25Afsk1200)) == 0);
    feed(receiver, iq);
    std::vector<std::uint8_t> packet;
    CHECK(receiver.readPacket(packet));
    CHECK(packet == body);
    CHECK(!receiver.readPacket(packet));
    const auto decoded = receiver.decoderStats();
    CHECK(decoded.hdlc_flag_candidates > 0);
    CHECK(decoded.verified_frames == 1);

    feed(receiver, iq);
    CHECK(receiver.setMode(static_cast<int>(
        satellite_rx::ReceiverMode::SpectrumOnly)) == 0);
    CHECK(!receiver.readPacket(packet));
    CHECK(receiver.decoderStats().verified_frames == 0);

    // A real offset FM packet follows a long modulated preamble. The AFC
    // must settle using IQ evidence without corrupting CRC-verified decode.
    std::vector<std::uint8_t> offset_bits;
    for (int i = 0; i < 180; ++i) flag(offset_bits);
    const auto framed = framedBits(body);
    offset_bits.insert(offset_bits.end(), framed.begin(), framed.end());
    satellite_rx::ReceiverCore offset_receiver(kIqRate);
    CHECK(offset_receiver.configureNfm(5000.0, 0.0) == 0);
    CHECK(offset_receiver.setMode(static_cast<int>(
        satellite_rx::ReceiverMode::Ax25Afsk1200)) == 0);
    offset_receiver.setCorrections(145825000.0, 0.0, 500.0);
    feed(offset_receiver, fmIq(offset_bits, 2500.0));
    CHECK(offset_receiver.afcStats().tracking);
    CHECK(std::abs(offset_receiver.afcStats().applied_hz - 2000.0) < 200.0);
    CHECK(offset_receiver.readPacket(packet));
    CHECK(packet == body);
    CHECK(offset_receiver.decoderStats().verified_frames >= 1);
    return 0;
}
