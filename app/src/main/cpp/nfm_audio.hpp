#pragma once

#include <complex>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace satellite_rx {

constexpr std::uint32_t kAudioSampleRate = 48000;

// Streaming narrow-FM discriminator. A windowed-sinc fractional resampler
// first limits the complex channel to 12 kHz and converts it to 48 kS/s;
// discriminator audio is then low-passed to 4.5 kHz and de-emphasized.
// All state is owned by the ReceiverCore DSP worker thread.
class NfmAudioDemodulator {
public:
    explicit NfmAudioDemodulator(std::uint32_t iq_sample_rate);

    void configure(double deviation_hz, double deemphasis_microseconds);
    void reset();
    void push(float i, float q, std::vector<std::int16_t>& output);

private:
    void demodulate(const std::complex<double>& sample,
                    std::vector<std::int16_t>& output);

    static constexpr std::size_t kPhases = 64;
    static constexpr std::size_t kAudioTaps = 63;

    const std::uint32_t iq_sample_rate_;
    const std::size_t iq_taps_;
    const std::size_t iq_half_taps_;
    const std::size_t iq_ring_capacity_;
    std::vector<float> iq_coefficients_; // (kPhases + 1) x iq_taps_
    // Mirrored ring makes each FIR read contiguous even across wraparound.
    std::vector<std::complex<float>> iq_history_;
    std::uint64_t iq_index_ = 0;
    double next_output_position_ = 0.0;

    std::complex<double> previous_sample_{0.0, 0.0};
    bool have_previous_sample_ = false;
    double deviation_hz_ = 5000.0;
    double deemphasis_alpha_ = 1.0;
    double deemphasis_state_ = 0.0;
    double audio_dc_state_ = 0.0;
    float audio_taps_[kAudioTaps]{};
    float audio_history_[kAudioTaps]{};
    std::size_t audio_write_index_ = 0;
};

}  // namespace satellite_rx
