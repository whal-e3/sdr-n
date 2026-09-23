#include "hackrf_driver.hpp"

#include <algorithm>
#include <cmath>
#include <utility>
#include <unistd.h>

namespace satellite_rx {
namespace {
constexpr double kPi = 3.14159265358979323846;
}

HackRfDriver::~HackRfDriver() {
    stop();
    if (device_) hackrf_close(device_);
    if (initialized_) hackrf_exit();
    if (owned_fd_ >= 0) close(owned_fd_);
}

ReceiverCapabilities HackRfDriver::capabilities() const {
    return {1000000, 2400000, 1000000, 6000000000ULL};
}

int HackRfDriver::open(int android_usb_fd) {
    if (android_usb_fd < 0 || device_) return -1;
    owned_fd_ = dup(android_usb_fd);
    if (owned_fd_ < 0) return -3;
    if (hackrf_init() != HACKRF_SUCCESS) {
        close(owned_fd_);
        owned_fd_ = -1;
        return -4;
    }
    initialized_ = true;
    if (hackrf_open_fd(&device_, owned_fd_) != HACKRF_SUCCESS || !device_)
        return -4;
    return 0;
}

int HackRfDriver::start(std::uint64_t frequency_hz,
                        std::uint32_t sample_rate, IqCallback on_iq) {
    if (!device_ || !on_iq || streaming_) return -2;
    if (frequency_hz < 1000000 || frequency_hz > 6000000000ULL ||
        sample_rate < 1000000 || sample_rate > 2400000) return -1;

    // 1.024 MS/s -> 8.192 MS/s USB, 2.4 MS/s -> 9.6 MS/s USB.
    // Keep the hardware in its recommended >=8 MS/s range.
    const std::size_t factor = (8000000ULL + sample_rate - 1) / sample_rate;
    if (factor < 4 || factor > 8) return -1;
    const double usb_rate = static_cast<double>(sample_rate) * factor;
    if (hackrf_set_sample_rate(device_, usb_rate) != HACKRF_SUCCESS ||
        hackrf_set_freq(device_, frequency_hz) != HACKRF_SUCCESS ||
        hackrf_set_lna_gain(device_, 16) != HACKRF_SUCCESS ||
        hackrf_set_vga_gain(device_, 16) != HACKRF_SUCCESS) return -3;

    decimation_factor_ = factor;
    phase_count_ = 0;
    write_index_ = 0;
    history_i_.fill(0.0f);
    history_q_.fill(0.0f);
    makeFilter(factor);
    downsampled_.clear();
    downsampled_.reserve(65536);
    on_iq_ = std::move(on_iq);

    const int result = hackrf_start_rx(device_, &HackRfDriver::onSamples, this);
    if (result != HACKRF_SUCCESS) {
        on_iq_ = {};
        return -4;
    }
    streaming_ = true;
    return 0;
}

void HackRfDriver::stop() {
    if (!streaming_) return;
    hackrf_stop_rx(device_);
    streaming_ = false;
    on_iq_ = {};
}

int HackRfDriver::onSamples(hackrf_transfer* transfer) {
    auto* self = static_cast<HackRfDriver*>(transfer ? transfer->rx_ctx : nullptr);
    if (self && transfer->buffer && transfer->valid_length >= 2) {
        self->consume(reinterpret_cast<const std::int8_t*>(transfer->buffer),
                      static_cast<std::size_t>(transfer->valid_length) / 2);
    }
    return 0;
}

void HackRfDriver::makeFilter(std::size_t factor) {
    constexpr int middle = 32;
    const double cutoff = 0.43 / static_cast<double>(factor);
    double total = 0.0;
    for (int i = 0; i < 65; ++i) {
        const int offset = i - middle;
        const double sinc = offset == 0 ? 2.0 * cutoff :
            std::sin(2.0 * kPi * cutoff * offset) / (kPi * offset);
        const double hamming = 0.54 - 0.46 * std::cos(2.0 * kPi * i / 64.0);
        taps_[i] = static_cast<float>(sinc * hamming);
        total += taps_[i];
    }
    for (auto& tap : taps_) tap = static_cast<float>(tap / total);
}

void HackRfDriver::consume(const std::int8_t* iq, std::size_t count) {
    downsampled_.clear();
    for (std::size_t sample = 0; sample < count; ++sample) {
        history_i_[write_index_] = static_cast<float>(iq[2 * sample]) / 128.0f;
        history_q_[write_index_] = static_cast<float>(iq[2 * sample + 1]) / 128.0f;
        write_index_ = (write_index_ + 1) & 127;
        if (++phase_count_ != decimation_factor_) continue;
        phase_count_ = 0;

        float filtered_i = 0.0f;
        float filtered_q = 0.0f;
        for (std::size_t tap = 0; tap < taps_.size(); ++tap) {
            const auto index = (write_index_ - 1 - tap) & 127;
            filtered_i += taps_[tap] * history_i_[index];
            filtered_q += taps_[tap] * history_q_[index];
        }
        downsampled_.push_back(static_cast<std::uint8_t>(std::clamp(
            static_cast<int>(std::lround(127.5 + 127.5 * filtered_i)), 0, 255)));
        downsampled_.push_back(static_cast<std::uint8_t>(std::clamp(
            static_cast<int>(std::lround(127.5 + 127.5 * filtered_q)), 0, 255)));
    }
    if (on_iq_ && !downsampled_.empty())
        on_iq_(downsampled_.data(), downsampled_.size() / 2);
}

}  // namespace satellite_rx
