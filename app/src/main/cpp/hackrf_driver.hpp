#pragma once

#include "receiver_driver.hpp"

#include <hackrf.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace satellite_rx {

class HackRfDriver final : public ReceiverDriver {
public:
    ~HackRfDriver() override;
    ReceiverCapabilities capabilities() const override;
    int open(int android_usb_fd) override;
    int start(std::uint64_t center_frequency_hz,
              std::uint32_t sample_rate,
              IqCallback on_iq) override;
    void stop() override;

private:
    static int onSamples(hackrf_transfer* transfer);
    void makeFilter(std::size_t factor);
    void consume(const std::int8_t* signed_iq, std::size_t complex_samples);

    hackrf_device* device_ = nullptr;
    int owned_fd_ = -1;
    bool initialized_ = false;
    bool streaming_ = false;
    IqCallback on_iq_;
    std::size_t decimation_factor_ = 8;
    std::size_t phase_count_ = 0;
    std::size_t write_index_ = 0;
    std::array<float, 65> taps_{};
    std::array<float, 128> history_i_{};
    std::array<float, 128> history_q_{};
    std::vector<std::uint8_t> downsampled_;
};

}  // namespace satellite_rx
