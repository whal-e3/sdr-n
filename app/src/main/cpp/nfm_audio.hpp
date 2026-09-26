#pragma once

#include <complex>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace satellite_rx {

constexpr std::uint32_t kAudioSampleRate = 48000;

// Measurements of the filtered complex FM channel, before audio DC removal.
// They can describe a carrier-like component, never its source or packet sync.
struct FmCarrierMeasurement {
    double residual_hz;
    double phase_coherence;
    double phase_stddev_hz;
    double rms_amplitude;
    double power_variation;
};

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
    std::optional<FmCarrierMeasurement> takeCarrierMeasurement();

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

    // One half-second window, accumulated only after channel filtering.
    std::size_t carrier_count_ = 0;
    double carrier_cross_real_ = 0.0;
    double carrier_cross_imag_ = 0.0;
    double carrier_cross_magnitude_ = 0.0;
    double carrier_phase_weighted_ = 0.0;
    double carrier_phase_squared_weighted_ = 0.0;
    double carrier_power_ = 0.0;
    double carrier_power_squared_ = 0.0;
    std::optional<FmCarrierMeasurement> carrier_measurement_;
};

}  // namespace satellite_rx
