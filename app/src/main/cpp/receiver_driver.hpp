#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>

namespace satellite_rx {

// The USB layer owns its Android-granted file descriptor. A driver may duplicate
// it for libusb, but must never close the descriptor supplied by Android.
enum class DeviceType : int {
    RtlSdr = 1,
    HackRf = 2,
};

struct ReceiverCapabilities {
    std::uint32_t minimum_sample_rate;
    std::uint32_t maximum_sample_rate;
    std::uint64_t minimum_frequency_hz;
    std::uint64_t maximum_frequency_hz;
};

using IqCallback = std::function<void(const std::uint8_t* interleaved_iq,
                                      std::size_t complex_samples)>;

// Each implementation is receive-only. It supplies unsigned interleaved I/Q
// samples and has no transmit or frequency-gain transmission interface.
class ReceiverDriver {
public:
    virtual ~ReceiverDriver() = default;
    virtual ReceiverCapabilities capabilities() const = 0;
    virtual int open(int android_usb_fd) = 0;
    virtual int start(std::uint64_t center_frequency_hz,
                      std::uint32_t sample_rate,
                      IqCallback on_iq) = 0;
    virtual void stop() = 0;
};

}  // namespace satellite_rx
