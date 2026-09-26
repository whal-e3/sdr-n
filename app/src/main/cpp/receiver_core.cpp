#include "receiver_core.hpp"

#include <algorithm>
#include <cmath>
#include <complex>
#include <limits>
#include <stdexcept>
#include <vector>

namespace satellite_rx {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr std::size_t kWorkerBatch = 4096;
constexpr double kDcAlpha = 1.0 / 4096.0;

void fft(std::array<std::complex<double>, kSpectrumBins>& data) {
    // In-place radix-2 FFT. Fixed size keeps capture memory bounded.
    std::size_t reversed = 0;
    for (std::size_t i = 1; i < kSpectrumBins; ++i) {
        std::size_t bit = kSpectrumBins >> 1;
        while (reversed & bit) {
            reversed ^= bit;
            bit >>= 1;
        }
        reversed ^= bit;
        if (i < reversed) std::swap(data[i], data[reversed]);
    }
    for (std::size_t length = 2; length <= kSpectrumBins; length <<= 1) {
        const double angle = -2.0 * kPi / static_cast<double>(length);
        const std::complex<double> increment(std::cos(angle), std::sin(angle));
        for (std::size_t start = 0; start < kSpectrumBins; start += length) {
            std::complex<double> twiddle(1.0, 0.0);
            for (std::size_t j = 0; j < length / 2; ++j) {
                const auto even = data[start + j];
                const auto odd = twiddle * data[start + j + length / 2];
                data[start + j] = even + odd;
                data[start + j + length / 2] = even - odd;
                twiddle *= increment;
            }
        }
    }
}

}  // namespace

ReceiverCore::ReceiverCore(std::uint32_t sample_rate)
    : sample_rate_(sample_rate),
      spectrum_interval_(std::max<std::size_t>(sample_rate / 100,
                                               kSpectrumBins)),
      queue_(kIqQueueCapacity * 2, 0),
      nfm_(sample_rate),
      audio_queue_(kAudioQueueCapacity, 0) {
    if (sample_rate < kAudioSampleRate)
        throw std::invalid_argument("sample rate is below 48 kS/s");
    spectrum_.fill(-120.0f);
    worker_ = std::thread(&ReceiverCore::workerLoop, this);
}

ReceiverCore::~ReceiverCore() {
    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        running_ = false;
    }
    queue_ready_.notify_one();
    if (worker_.joinable()) worker_.join();
}

int ReceiverCore::pushIq(const std::uint8_t* iq, std::size_t byte_length) {
    if (iq == nullptr || byte_length == 0 || (byte_length & 1) != 0 ||
        byte_length / 2 > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
        return -1;
    }

    const std::size_t requested = byte_length / 2;
    std::size_t accepted = 0;
    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        if (!running_) return -1;
        accepted = std::min(requested, kIqQueueCapacity - queue_size_);
        for (std::size_t i = 0; i < accepted; ++i) {
            const std::size_t dest = 2 * ((queue_tail_ + i) % kIqQueueCapacity);
            queue_[dest] = iq[2 * i];
            queue_[dest + 1] = iq[2 * i + 1];
        }
        queue_tail_ = (queue_tail_ + accepted) % kIqQueueCapacity;
        queue_size_ += accepted;
        accepted_.fetch_add(accepted, std::memory_order_relaxed);
        dropped_.fetch_add(requested - accepted, std::memory_order_relaxed);
    }
    if (accepted != 0) queue_ready_.notify_one();
    return static_cast<int>(accepted);
}

int ReceiverCore::generateTestTone(double tone_hz, std::size_t samples) {
    if (!std::isfinite(tone_hz) || samples == 0 || samples > kIqQueueCapacity ||
        std::abs(tone_hz) >= static_cast<double>(sample_rate_) / 2.0) {
        return -1;
    }
    std::vector<std::uint8_t> iq(samples * 2);
    {
        std::lock_guard<std::mutex> lock(test_tone_mutex_);
        const double increment = 2.0 * kPi * tone_hz / sample_rate_;
        for (std::size_t i = 0; i < samples; ++i) {
            iq[2 * i] = static_cast<std::uint8_t>(std::lround(127.5 + 60.0 *
                                                              std::cos(test_tone_phase_)));
            iq[2 * i + 1] = static_cast<std::uint8_t>(std::lround(127.5 + 60.0 *
                                                                  std::sin(test_tone_phase_)));
            test_tone_phase_ += increment;
            if (test_tone_phase_ > kPi || test_tone_phase_ < -kPi) {
                test_tone_phase_ = std::remainder(test_tone_phase_, 2.0 * kPi);
            }
        }
    }
    return pushIq(iq.data(), iq.size());
}

void ReceiverCore::setCorrections(double center_hz, double ppm,
                                  double doppler_hz) {
    if (!std::isfinite(center_hz) || !std::isfinite(ppm) ||
        !std::isfinite(doppler_hz)) return;
    center_frequency_hz_.store(center_hz, std::memory_order_relaxed);
    ppm_.store(ppm, std::memory_order_relaxed);
    doppler_hz_.store(doppler_hz, std::memory_order_relaxed);
}

int ReceiverCore::setMode(int mode) {
    if (mode != static_cast<int>(ReceiverMode::SpectrumOnly) &&
        mode != static_cast<int>(ReceiverMode::NfmAudio) &&
        mode != static_cast<int>(ReceiverMode::Ax25Afsk1200)) return -1;
    mode_.store(mode, std::memory_order_release);
    mode_generation_.fetch_add(1, std::memory_order_acq_rel);
    hdlc_flag_candidates_.store(0, std::memory_order_relaxed);
    failed_frame_crc_.store(0, std::memory_order_relaxed);
    verified_frames_.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(audio_mutex_);
        audio_head_ = audio_tail_ = audio_size_ = 0;
    }
    {
        std::lock_guard<std::mutex> lock(packet_mutex_);
        packet_queue_.clear();
    }
    return 0;
}

int ReceiverCore::configureNfm(double deviation_hz,
                               double deemphasis_microseconds) {
    if (!std::isfinite(deviation_hz) || deviation_hz < 100.0 ||
        deviation_hz > 15000.0 ||
        !std::isfinite(deemphasis_microseconds) ||
        deemphasis_microseconds < 0.0 ||
        deemphasis_microseconds > 1000.0) return -1;
    nfm_deviation_hz_.store(deviation_hz, std::memory_order_release);
    nfm_deemphasis_microseconds_.store(deemphasis_microseconds,
                                       std::memory_order_release);
    mode_generation_.fetch_add(1, std::memory_order_acq_rel);
    hdlc_flag_candidates_.store(0, std::memory_order_relaxed);
    failed_frame_crc_.store(0, std::memory_order_relaxed);
    verified_frames_.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(audio_mutex_);
        audio_head_ = audio_tail_ = audio_size_ = 0;
    }
    {
        std::lock_guard<std::mutex> lock(packet_mutex_);
        packet_queue_.clear();
    }
    return 0;
}

std::size_t ReceiverCore::readAudio(std::int16_t* output,
                                    std::size_t max_frames) {
    if (!output || max_frames == 0) return 0;
    std::lock_guard<std::mutex> lock(audio_mutex_);
    const std::size_t count = std::min(max_frames, audio_size_);
    for (std::size_t i = 0; i < count; ++i)
        output[i] = audio_queue_[(audio_head_ + i) % kAudioQueueCapacity];
    audio_head_ = (audio_head_ + count) % kAudioQueueCapacity;
    audio_size_ -= count;
    return count;
}

bool ReceiverCore::readPacket(std::vector<std::uint8_t>& output) {
    std::lock_guard<std::mutex> lock(packet_mutex_);
    if (packet_queue_.empty()) return false;
    output = std::move(packet_queue_.front());
    packet_queue_.pop_front();
    return true;
}

std::array<float, kSpectrumBins> ReceiverCore::spectrum() const {
    std::lock_guard<std::mutex> lock(spectrum_mutex_);
    return spectrum_;
}

std::array<float, kIqDisplaySamples * 2> ReceiverCore::iqSnapshot() const {
    std::lock_guard<std::mutex> lock(iq_display_mutex_);
    return iq_display_;
}

ReceiverStats ReceiverCore::stats() const {
    return {accepted_.load(std::memory_order_relaxed),
            dropped_.load(std::memory_order_relaxed),
            processed_.load(std::memory_order_relaxed)};
}

DecoderStats ReceiverCore::decoderStats() const {
    return {hdlc_flag_candidates_.load(std::memory_order_relaxed),
            failed_frame_crc_.load(std::memory_order_relaxed),
            verified_frames_.load(std::memory_order_relaxed)};
}

AfcStats ReceiverCore::afcStats() const {
    std::lock_guard<std::mutex> lock(afc_mutex_);
    return afc_stats_;
}

void ReceiverCore::resetAfc() {
    afc_correction_hz_ = 0.0;
    afc_tracking_ = false;
    afc_candidate_hz_ = 0.0;
    afc_candidate_windows_ = 0;
    afc_invalid_windows_ = 0;
    have_previous_nominal_correction_ = false;
    std::lock_guard<std::mutex> lock(afc_mutex_);
    afc_stats_ = {false, 0.0, 0.0};
}

void ReceiverCore::updateAfc(const FmCarrierMeasurement& measurement,
                             double nominal_correction_hz) {
    constexpr double kMaxAfcHz = 4000.0;
    // A zero-IF dongle can create a strong DC spur. A candidate whose
    // inferred *raw* location is at the RF tuner center cannot distinguish
    // that spur from a real signal, so it never drives AFC.
    const double estimated_error_hz = afc_correction_hz_ +
                                      measurement.residual_hz;
    const double raw_offset_hz = nominal_correction_hz +
                                 estimated_error_hz;
    const bool credible =
        std::isfinite(estimated_error_hz) &&
        std::abs(estimated_error_hz) <= kMaxAfcHz &&
        std::abs(raw_offset_hz) >= 300.0 &&
        measurement.rms_amplitude >= 0.04 &&
        measurement.power_variation < 0.25 &&
        measurement.phase_coherence > 0.82 &&
        measurement.phase_coherence < 0.997 &&
        measurement.phase_stddev_hz > 250.0 &&
        measurement.phase_stddev_hz < 5000.0;
    if (!credible) {
        afc_tracking_ = false;
        afc_candidate_windows_ = 0;
        // Hold through one bad half-second window, then discard the stale
        // correction rather than following noise after a signal fades.
        if (++afc_invalid_windows_ >= 2) afc_correction_hz_ = 0.0;
        std::lock_guard<std::mutex> lock(afc_mutex_);
        afc_stats_ = {false, afc_correction_hz_, 0.0};
        return;
    }

    afc_invalid_windows_ = 0;
    if (afc_candidate_windows_ == 0 ||
        std::abs(estimated_error_hz - afc_candidate_hz_) > 350.0) {
        afc_candidate_hz_ = estimated_error_hz;
        afc_candidate_windows_ = 1;
        afc_tracking_ = false;
    } else {
        afc_candidate_hz_ = 0.5 * (afc_candidate_hz_ + estimated_error_hz);
        afc_candidate_windows_ = std::min(2, afc_candidate_windows_ + 1);
        if (afc_candidate_windows_ >= 2) {
            afc_correction_hz_ = afc_tracking_
                ? 0.75 * afc_correction_hz_ + 0.25 * afc_candidate_hz_
                : afc_candidate_hz_;
            afc_correction_hz_ = std::clamp(afc_correction_hz_,
                                            -kMaxAfcHz, kMaxAfcHz);
            afc_tracking_ = true;
        }
    }
    std::lock_guard<std::mutex> lock(afc_mutex_);
    afc_stats_ = {afc_tracking_, afc_correction_hz_,
                  afc_tracking_ ? measurement.residual_hz : 0.0};
}

void ReceiverCore::workerLoop() {
    std::vector<std::uint8_t> batch(kWorkerBatch * 2);
    for (;;) {
        std::size_t count = 0;
        {
            std::unique_lock<std::mutex> lock(queue_mutex_);
            queue_ready_.wait(lock, [this] { return !running_ || queue_size_ != 0; });
            if (!running_ && queue_size_ == 0) return;
            count = std::min(queue_size_, kWorkerBatch);
            for (std::size_t i = 0; i < count; ++i) {
                const std::size_t source = 2 * ((queue_head_ + i) % kIqQueueCapacity);
                batch[2 * i] = queue_[source];
                batch[2 * i + 1] = queue_[source + 1];
            }
            queue_head_ = (queue_head_ + count) % kIqQueueCapacity;
            queue_size_ -= count;
        }
        process(batch.data(), count);
        processed_.fetch_add(count, std::memory_order_relaxed);
    }
}

void ReceiverCore::process(const std::uint8_t* iq, std::size_t count) {
    const auto generation = mode_generation_.load(std::memory_order_acquire);
    if (generation != worker_mode_generation_) {
        nfm_.configure(nfm_deviation_hz_.load(std::memory_order_acquire),
                       nfm_deemphasis_microseconds_.load(std::memory_order_acquire));
        nfm_.reset();
        ax25_.reset();
        resetAfc();
        worker_mode_generation_ = generation;
    }
    const int mode = mode_.load(std::memory_order_acquire);
    const bool audio_enabled = mode == static_cast<int>(ReceiverMode::NfmAudio);
    const bool packet_enabled = mode ==
        static_cast<int>(ReceiverMode::Ax25Afsk1200);
    std::vector<std::int16_t> produced_audio;
    if (audio_enabled || packet_enabled)
        produced_audio.reserve(count * kAudioSampleRate / sample_rate_ + 8);
    const double nominal_correction_hz =
        center_frequency_hz_.load(std::memory_order_relaxed) *
                                 ppm_.load(std::memory_order_relaxed) * 1e-6 +
                             doppler_hz_.load(std::memory_order_relaxed);
    if (audio_enabled || packet_enabled) {
        // A large change to the predicted tuning moves the entire channel.
        // Discard the partial measurement before it can look like RF lock.
        if (have_previous_nominal_correction_ &&
            std::abs(nominal_correction_hz -
                     previous_nominal_correction_hz_) > 200.0) {
            nfm_.reset();
            resetAfc();
        }
        previous_nominal_correction_hz_ = nominal_correction_hz;
        have_previous_nominal_correction_ = true;
    }
    const double offset_hz = nominal_correction_hz + afc_correction_hz_;
    const double phase_increment = -2.0 * kPi * offset_hz / sample_rate_;
    const double rotation_cos = std::cos(phase_increment);
    const double rotation_sin = std::sin(phase_increment);
    std::array<float, kIqDisplaySamples * 2> iq_display{};
    const std::size_t display_start = count > kIqDisplaySamples
        ? count - kIqDisplaySamples : 0;
    for (std::size_t i = 0; i < count; ++i) {
        const double raw_i = (static_cast<double>(iq[2 * i]) - 127.5) / 127.5;
        const double raw_q = (static_cast<double>(iq[2 * i + 1]) - 127.5) / 127.5;
        dc_i_ += kDcAlpha * (raw_i - dc_i_);
        dc_q_ += kDcAlpha * (raw_q - dc_q_);
        const double corrected_i = raw_i - dc_i_;
        const double corrected_q = raw_q - dc_q_;
        // A centered FM carrier has a genuine DC component (J0(beta)).
        // Subtracting the running IQ mean before FM demodulation would erase
        // that carrier and create severe harmonic distortion. The spectrum
        // view alone uses the DC-corrected samples.
        const float mixed_i = static_cast<float>(raw_i * nco_cos_ -
                                                  raw_q * nco_sin_);
        const float mixed_q = static_cast<float>(raw_i * nco_sin_ +
                                                  raw_q * nco_cos_);
        if (i >= display_start) {
            const std::size_t display_index = i - display_start;
            iq_display[2 * display_index] = mixed_i;
            iq_display[2 * display_index + 1] = mixed_q;
        }
        if (audio_enabled || packet_enabled)
            nfm_.push(mixed_i, mixed_q, produced_audio);
        if (spectrum_skip_ != 0) {
            --spectrum_skip_;
        } else {
            fft_input_[2 * fft_fill_] = static_cast<float>(
                corrected_i * nco_cos_ - corrected_q * nco_sin_);
            fft_input_[2 * fft_fill_ + 1] = static_cast<float>(
                corrected_i * nco_sin_ + corrected_q * nco_cos_);
            if (++fft_fill_ == kSpectrumBins) {
                updateSpectrum();
                fft_fill_ = 0;
                spectrum_skip_ = spectrum_interval_ - kSpectrumBins;
            }
        }
        const double next_cos = nco_cos_ * rotation_cos - nco_sin_ * rotation_sin;
        nco_sin_ = nco_sin_ * rotation_cos + nco_cos_ * rotation_sin;
        nco_cos_ = next_cos;
        if (++nco_renormalize_count_ == 4096) {
            const double amplitude = std::hypot(nco_cos_, nco_sin_);
            nco_cos_ /= amplitude;
            nco_sin_ /= amplitude;
            nco_renormalize_count_ = 0;
        }
    }
    {
        std::lock_guard<std::mutex> lock(iq_display_mutex_);
        iq_display_ = iq_display;
    }
    if ((audio_enabled || packet_enabled) &&
        mode_generation_.load(std::memory_order_acquire) == generation) {
        if (const auto measurement = nfm_.takeCarrierMeasurement())
            updateAfc(*measurement, nominal_correction_hz);
    }
    if (packet_enabled && !produced_audio.empty()) {
        ax25_.pushAudio(produced_audio.data(), produced_audio.size());
        if (mode_generation_.load(std::memory_order_acquire) == generation) {
            hdlc_flag_candidates_.store(ax25_.flagCandidateCount(),
                                        std::memory_order_relaxed);
            failed_frame_crc_.store(ax25_.badFcsCount(),
                                    std::memory_order_relaxed);
            verified_frames_.store(ax25_.validFrameCount(),
                                   std::memory_order_relaxed);
        }
        Ax25Frame frame;
        while (ax25_.popFrame(frame)) {
            // A mode change may have occurred while processing this batch.
            // Do not publish stale packets after setMode clears the queue.
            if (mode_generation_.load(std::memory_order_acquire) != generation)
                break;
            std::lock_guard<std::mutex> lock(packet_mutex_);
            if (mode_generation_.load(std::memory_order_acquire) != generation)
                break;
            if (packet_queue_.size() == kPacketQueueCapacity)
                packet_queue_.pop_front();
            packet_queue_.push_back(std::move(frame.bytes));
        }
    }
    if (audio_enabled && !produced_audio.empty() &&
        mode_generation_.load(std::memory_order_acquire) == generation) {
        std::lock_guard<std::mutex> lock(audio_mutex_);
        // Real-time playback prefers the newest data. A stalled UI reader
        // causes old PCM to be overwritten, never unbounded memory growth.
        for (const auto frame : produced_audio) {
            if (audio_size_ == kAudioQueueCapacity) {
                audio_head_ = (audio_head_ + 1) % kAudioQueueCapacity;
                --audio_size_;
            }
            audio_queue_[audio_tail_] = frame;
            audio_tail_ = (audio_tail_ + 1) % kAudioQueueCapacity;
            ++audio_size_;
        }
    }
}

void ReceiverCore::updateSpectrum() {
    std::array<std::complex<double>, kSpectrumBins> data;
    for (std::size_t i = 0; i < kSpectrumBins; ++i) {
        const double window = 0.5 - 0.5 *
            std::cos(2.0 * kPi * i / static_cast<double>(kSpectrumBins - 1));
        data[i] = {fft_input_[2 * i] * window, fft_input_[2 * i + 1] * window};
    }
    fft(data);
    std::array<float, kSpectrumBins> next;
    for (std::size_t i = 0; i < kSpectrumBins; ++i) {
        const auto shifted = data[(i + kSpectrumBins / 2) % kSpectrumBins];
        const double amplitude = std::abs(shifted) * 2.0 / kSpectrumBins;
        next[i] = static_cast<float>(20.0 *
            std::log10(std::max(amplitude, 1e-6)));
    }
    std::lock_guard<std::mutex> lock(spectrum_mutex_);
    spectrum_ = next;
}

}  // namespace satellite_rx
