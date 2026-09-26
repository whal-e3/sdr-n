#include "nfm_audio.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace satellite_rx {
namespace {

constexpr double kPi = 3.14159265358979323846;

double sinc(double value) {
    if (std::abs(value) < 1e-12) return 1.0;
    return std::sin(kPi * value) / (kPi * value);
}

double hamming(std::size_t index, std::size_t length) {
    return 0.54 - 0.46 * std::cos(2.0 * kPi * index / (length - 1));
}

}  // namespace

NfmAudioDemodulator::NfmAudioDemodulator(std::uint32_t iq_sample_rate)
    : iq_sample_rate_(iq_sample_rate),
      // A longer filter at higher RF sample rates keeps the transition band
      // near 5 kHz without spending CPU on every input sample: filtering is
      // evaluated only at the 48 kHz output instants.
      iq_taps_(std::max<std::size_t>(129,
          std::min<std::size_t>(513,
              2 * ((iq_sample_rate + 9999) / 10000) + 1))),
      iq_half_taps_(iq_taps_ / 2),
      iq_ring_capacity_(iq_taps_ + 2),
      iq_coefficients_((kPhases + 1) * iq_taps_),
      iq_history_(2 * iq_ring_capacity_) {
    if (iq_sample_rate < kAudioSampleRate)
        throw std::invalid_argument("IQ sample rate is below audio output rate");

    const double channel_cutoff = 12000.0 / iq_sample_rate_;
    for (std::size_t phase = 0; phase <= kPhases; ++phase) {
        const double fraction = static_cast<double>(phase) / kPhases;
        double sum = 0.0;
        for (std::size_t tap = 0; tap < iq_taps_; ++tap) {
            const double offset = static_cast<double>(tap) - iq_half_taps_ -
                                  fraction;
            const double coefficient = 2.0 * channel_cutoff *
                sinc(2.0 * channel_cutoff * offset) * hamming(tap, iq_taps_);
            iq_coefficients_[phase * iq_taps_ + tap] =
                static_cast<float>(coefficient);
            sum += coefficient;
        }
        for (std::size_t tap = 0; tap < iq_taps_; ++tap)
            iq_coefficients_[phase * iq_taps_ + tap] = static_cast<float>(
                iq_coefficients_[phase * iq_taps_ + tap] / sum);
    }

    const double audio_cutoff = 4500.0 / kAudioSampleRate;
    double audio_sum = 0.0;
    for (std::size_t tap = 0; tap < kAudioTaps; ++tap) {
        const double offset = static_cast<double>(tap) - (kAudioTaps - 1) / 2.0;
        const double coefficient = 2.0 * audio_cutoff *
            sinc(2.0 * audio_cutoff * offset) * hamming(tap, kAudioTaps);
        audio_taps_[tap] = static_cast<float>(coefficient);
        audio_sum += coefficient;
    }
    for (float& tap : audio_taps_) tap = static_cast<float>(tap / audio_sum);
    configure(5000.0, 75.0);
    reset();
}

void NfmAudioDemodulator::configure(double deviation_hz,
                                    double deemphasis_microseconds) {
    deviation_hz_ = deviation_hz;
    deemphasis_alpha_ = deemphasis_microseconds == 0.0 ? 1.0 :
        1.0 - std::exp(-1.0 / (kAudioSampleRate *
                                 deemphasis_microseconds * 1e-6));
}

void NfmAudioDemodulator::reset() {
    std::fill(iq_history_.begin(), iq_history_.end(),
              std::complex<float>{0.0f, 0.0f});
    iq_index_ = 0;
    next_output_position_ = static_cast<double>(iq_half_taps_);
    previous_sample_ = {0.0, 0.0};
    have_previous_sample_ = false;
    deemphasis_state_ = 0.0;
    audio_dc_state_ = 0.0;
    std::fill(std::begin(audio_history_), std::end(audio_history_), 0.0f);
    audio_write_index_ = 0;
    carrier_count_ = 0;
    carrier_cross_real_ = carrier_cross_imag_ = carrier_cross_magnitude_ = 0.0;
    carrier_phase_weighted_ = carrier_phase_squared_weighted_ = 0.0;
    carrier_power_ = carrier_power_squared_ = 0.0;
    carrier_measurement_.reset();
}

std::optional<FmCarrierMeasurement>
NfmAudioDemodulator::takeCarrierMeasurement() {
    auto measurement = carrier_measurement_;
    carrier_measurement_.reset();
    return measurement;
}

void NfmAudioDemodulator::push(float i, float q,
                               std::vector<std::int16_t>& output) {
    const std::uint64_t index = iq_index_++;
    const std::size_t slot = index % iq_ring_capacity_;
    iq_history_[slot] = {i, q};
    iq_history_[slot + iq_ring_capacity_] = {i, q};

    // The symmetric FIR needs future input through center + half_taps.
    // Fractional centers need one extra sample, provided by the ring above.
    while (static_cast<double>(index) >=
           std::ceil(next_output_position_ + iq_half_taps_)) {
        const auto center = static_cast<std::uint64_t>(next_output_position_);
        const double fraction = next_output_position_ - center;
        const auto phase = static_cast<std::size_t>(std::lround(
            fraction * static_cast<double>(kPhases)));
        const float* coefficients = iq_coefficients_.data() + phase * iq_taps_;
        const std::uint64_t oldest = center - iq_half_taps_;
        const auto* input = iq_history_.data() + oldest % iq_ring_capacity_;
        double filtered_i = 0.0;
        double filtered_q = 0.0;
        for (std::size_t tap = 0; tap < iq_taps_; ++tap) {
            filtered_i += coefficients[tap] * input[tap].real();
            filtered_q += coefficients[tap] * input[tap].imag();
        }
        demodulate({filtered_i, filtered_q}, output);
        next_output_position_ += static_cast<double>(iq_sample_rate_) /
                                 kAudioSampleRate;
    }
}

void NfmAudioDemodulator::demodulate(
    const std::complex<double>& sample,
    std::vector<std::int16_t>& output) {
    if (!have_previous_sample_) {
        previous_sample_ = sample;
        have_previous_sample_ = true;
        return;
    }
    const auto phase_product = sample * std::conj(previous_sample_);
    previous_sample_ = sample;
    const double phase_step = std::atan2(phase_product.imag(),
                                         phase_product.real());
    const double cross_magnitude = std::abs(phase_product);
    const double power = std::norm(sample);
    carrier_cross_real_ += phase_product.real();
    carrier_cross_imag_ += phase_product.imag();
    carrier_cross_magnitude_ += cross_magnitude;
    carrier_phase_weighted_ += phase_step * cross_magnitude;
    carrier_phase_squared_weighted_ += phase_step * phase_step *
                                       cross_magnitude;
    carrier_power_ += power;
    carrier_power_squared_ += power * power;
    if (++carrier_count_ == kAudioSampleRate / 2) {
        const double mean_power = carrier_power_ / carrier_count_;
        const double mean_phase = carrier_cross_magnitude_ > 0.0
            ? carrier_phase_weighted_ / carrier_cross_magnitude_ : 0.0;
        const double phase_variance = carrier_cross_magnitude_ > 0.0
            ? std::max(0.0, carrier_phase_squared_weighted_ /
                       carrier_cross_magnitude_ - mean_phase * mean_phase)
            : 0.0;
        const double hz_per_radian = kAudioSampleRate / (2.0 * kPi);
        carrier_measurement_ = FmCarrierMeasurement{
            mean_phase * hz_per_radian,
            carrier_cross_magnitude_ > 0.0
                ? std::hypot(carrier_cross_real_, carrier_cross_imag_) /
                  carrier_cross_magnitude_ : 0.0,
            std::sqrt(phase_variance) * hz_per_radian,
            std::sqrt(mean_power),
            mean_power > 0.0
                ? std::max(0.0, carrier_power_squared_ / carrier_count_ -
                                 mean_power * mean_power) /
                  (mean_power * mean_power) : 0.0,
        };
        carrier_count_ = 0;
        carrier_cross_real_ = carrier_cross_imag_ = carrier_cross_magnitude_ = 0.0;
        carrier_phase_weighted_ = carrier_phase_squared_weighted_ = 0.0;
        carrier_power_ = carrier_power_squared_ = 0.0;
    }
    const double frequency_hz = phase_step * kAudioSampleRate / (2.0 * kPi);
    const double normalized = std::clamp(frequency_hz / deviation_hz_, -2.0, 2.0);

    audio_history_[audio_write_index_] = static_cast<float>(normalized);
    audio_write_index_ = (audio_write_index_ + 1) % kAudioTaps;
    double filtered = 0.0;
    for (std::size_t tap = 0; tap < kAudioTaps; ++tap) {
        const auto index = (audio_write_index_ + kAudioTaps - 1 - tap) %
                           kAudioTaps;
        filtered += audio_taps_[tap] * audio_history_[index];
    }

    // Remove residual tuning/DC offset without attenuating speech. The
    // 30 Hz high-pass corner is well below the voice passband.
    const double kAudioDcAlpha = 1.0 -
        std::exp(-2.0 * kPi * 30.0 / kAudioSampleRate);
    audio_dc_state_ += kAudioDcAlpha * (filtered - audio_dc_state_);
    deemphasis_state_ += deemphasis_alpha_ *
        ((filtered - audio_dc_state_) - deemphasis_state_);
    const double pcm = std::clamp(deemphasis_state_ * 28000.0,
                                  -32768.0, 32767.0);
    output.push_back(static_cast<std::int16_t>(std::lround(pcm)));
}

}  // namespace satellite_rx
