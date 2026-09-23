#pragma once

#include "receiver_driver.hpp"

#include <atomic>
#include <thread>

struct rtlsdr_dev;
using rtlsdr_dev_t = struct rtlsdr_dev;

namespace satellite_rx {

// Owns a duplicate of Android's USB descriptor. The original descriptor and
// UsbDeviceConnection remain owned by Kotlin until this driver's stop/close.
class RtlSdrDriver final : public ReceiverDriver {
public:
    ~RtlSdrDriver() override;
    ReceiverCapabilities capabilities() const override;
    int open(int android_usb_fd) override;
    int start(std::uint64_t center_frequency_hz,
              std::uint32_t sample_rate,
              IqCallback on_iq) override;
    void stop() override;
    int lastStreamError() const { return last_stream_error_.load(); }

private:
    static void onSamples(unsigned char* iq, std::uint32_t byte_length,
                          void* context);

    rtlsdr_dev_t* device_ = nullptr;
    int owned_fd_ = -1;
    IqCallback on_iq_;
    std::thread stream_thread_;
    std::atomic<bool> stream_active_{false};
    std::atomic<int> last_stream_error_{0};
};

}  // namespace satellite_rx
