#include "rtl_sdr_driver.hpp"

#include <rtl-sdr.h>

#include <chrono>
#include <limits>
#include <thread>
#include <unistd.h>

namespace satellite_rx {

RtlSdrDriver::~RtlSdrDriver() {
    stop();
    if (device_) rtlsdr_close(device_);
    if (owned_fd_ >= 0) close(owned_fd_);
}

ReceiverCapabilities RtlSdrDriver::capabilities() const {
    return {900001, 2400000, 24000000, 1766000000};
}

int RtlSdrDriver::open(int android_usb_fd) {
    if (android_usb_fd < 0 || device_) return -1;
    owned_fd_ = dup(android_usb_fd);
    if (owned_fd_ < 0) return -3;
    const int result = rtlsdr_open_fd(&device_, owned_fd_);
    if (result < 0 || !device_) {
        close(owned_fd_);
        owned_fd_ = -1;
        return -4;
    }
    return 0;
}

int RtlSdrDriver::start(std::uint64_t frequency_hz,
                        std::uint32_t sample_rate, IqCallback on_iq) {
    if (!device_ || !on_iq || stream_thread_.joinable()) return -2;
    if (frequency_hz < 24000000 || frequency_hz > 1766000000 ||
        ((sample_rate < 900001 || sample_rate > 2400000) &&
         (sample_rate < 225001 || sample_rate > 300000))) return -1;

    if (rtlsdr_set_sample_rate(device_, sample_rate) < 0 ||
        rtlsdr_set_center_freq(device_, static_cast<std::uint32_t>(frequency_hz)) < 0 ||
        rtlsdr_set_tuner_gain_mode(device_, 0) < 0 ||
        rtlsdr_reset_buffer(device_) < 0) return -3;

    on_iq_ = std::move(on_iq);
    last_stream_error_.store(0);
    stream_active_.store(true);
    try {
        stream_thread_ = std::thread([this] {
            // 8 * 64 KiB buffers. Each transfer fits into the 128 KiB IQ
            // queue when empty. The callback only copies into the bounded
            // core queue, leaving demodulation to its own worker.
            const int result = rtlsdr_read_async(device_, &RtlSdrDriver::onSamples,
                                                  this, 8, 64 * 1024);
            last_stream_error_.store(result);
            stream_active_.store(false);
        });
    } catch (...) {
        stream_active_.store(false);
        on_iq_ = {};
        return -4;
    }
    return 0;
}

void RtlSdrDriver::stop() {
    if (!stream_thread_.joinable()) return;
    // read_async enters its running state on the worker thread. Retry until
    // it reaches that state or exits, including an immediate stop after start.
    while (stream_active_.load()) {
        if (rtlsdr_cancel_async(device_) == 0) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    stream_thread_.join();
    on_iq_ = {};
}

void RtlSdrDriver::onSamples(unsigned char* iq, std::uint32_t byte_length,
                             void* context) {
    auto* self = static_cast<RtlSdrDriver*>(context);
    if (self && self->on_iq_ && iq && byte_length >= 2) {
        self->on_iq_(iq, byte_length / 2);
    }
}

}  // namespace satellite_rx
