#pragma once

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

#include "nfm_audio.hpp"
#include "decoders/ax25_afsk1200.hpp"

namespace satellite_rx {

constexpr std::size_t kSpectrumBins = 256;
constexpr std::size_t kIqDisplaySamples = 256;
constexpr std::size_t kIqQueueCapacity = 65536;  // Complex samples; 128 KiB.
constexpr std::size_t kAudioQueueCapacity = 48000; // One second at 48 kHz.
constexpr std::size_t kPacketQueueCapacity = 16;

enum class ReceiverMode : int {
    SpectrumOnly = 0,
    NfmAudio = 1,
    Ax25Afsk1200 = 2,
};

struct ReceiverStats {
    std::uint64_t accepted_complex_samples;
    std::uint64_t dropped_complex_samples;
    std::uint64_t processed_complex_samples;
};

struct DecoderStats {
    std::uint64_t hdlc_flag_candidates;
    std::uint64_t failed_frame_crc;
    std::uint64_t verified_frames;
};

struct AfcStats {
    bool tracking;
    // Correction added after predicted Doppler and configured oscillator PPM.
    double applied_hz;
    // Latest measured residual before the most recent AFC update; only
    // meaningful while tracking.
    double residual_hz;
};

// A bounded receive pipeline. The producer never blocks on DSP; if the queue
// fills, the excess samples are counted and discarded.
class ReceiverCore {
public:
    explicit ReceiverCore(std::uint32_t sample_rate);
    ~ReceiverCore();

    ReceiverCore(const ReceiverCore&) = delete;
    ReceiverCore& operator=(const ReceiverCore&) = delete;

    // `byte_length` must be even. Returns accepted complex samples, or -1.
    int pushIq(const std::uint8_t* interleaved_iq, std::size_t byte_length);
    int generateTestTone(double tone_hz, std::size_t complex_samples);
    void setCorrections(double center_frequency_hz, double ppm,
                        double doppler_hz);
    // Modes and FM parameters are validated and applied at the next DSP
    // batch boundary. The returned audio is always 48 kHz mono PCM16.
    int setMode(int mode);
    int configureNfm(double deviation_hz, double deemphasis_microseconds);
    std::size_t readAudio(std::int16_t* output, std::size_t max_frames);
    // Returns one CRC-verified AX.25 body, excluding the transmitted FCS.
    // Empty means no packet is currently available. Bounded to 16 packets.
    bool readPacket(std::vector<std::uint8_t>& output);
    std::uint32_t sampleRate() const { return sample_rate_; }
    std::array<float, kSpectrumBins> spectrum() const;
    // The latest bounded window of complex baseband samples, interleaved I/Q.
    // These are samples entering the demodulator, not decoded symbols.
    std::array<float, kIqDisplaySamples * 2> iqSnapshot() const;
    ReceiverStats stats() const;
    DecoderStats decoderStats() const;
    AfcStats afcStats() const;

private:
    void workerLoop();
    void process(const std::uint8_t* interleaved_iq,
                 std::size_t complex_samples);
    void updateSpectrum();
    void resetAfc(); // DSP worker only, except snapshot publication.
    void updateAfc(const FmCarrierMeasurement& measurement,
                   double nominal_correction_hz);

    const std::uint32_t sample_rate_;
    const std::size_t spectrum_interval_;
    std::vector<std::uint8_t> queue_;
    std::size_t queue_head_ = 0;
    std::size_t queue_tail_ = 0;
    std::size_t queue_size_ = 0;
    bool running_ = true;
    std::mutex queue_mutex_;
    std::condition_variable queue_ready_;
    std::thread worker_;

    std::atomic<std::uint64_t> accepted_{0};
    std::atomic<std::uint64_t> dropped_{0};
    std::atomic<std::uint64_t> processed_{0};
    std::atomic<std::uint64_t> hdlc_flag_candidates_{0};
    std::atomic<std::uint64_t> failed_frame_crc_{0};
    std::atomic<std::uint64_t> verified_frames_{0};
    std::atomic<double> center_frequency_hz_{0.0};
    std::atomic<double> ppm_{0.0};
    std::atomic<double> doppler_hz_{0.0};
    std::atomic<int> mode_{static_cast<int>(ReceiverMode::SpectrumOnly)};
    std::atomic<double> nfm_deviation_hz_{5000.0};
    std::atomic<double> nfm_deemphasis_microseconds_{75.0};
    std::atomic<std::uint64_t> mode_generation_{0};
    std::uint64_t worker_mode_generation_ = 0;
    NfmAudioDemodulator nfm_;
    std::vector<std::int16_t> audio_queue_;
    std::size_t audio_head_ = 0;
    std::size_t audio_tail_ = 0;
    std::size_t audio_size_ = 0;
    std::mutex audio_mutex_;
    Ax25Afsk1200Decoder ax25_; // Worker-thread confined.
    std::deque<std::vector<std::uint8_t>> packet_queue_;
    std::mutex packet_mutex_;

    double afc_correction_hz_ = 0.0; // Worker-thread confined.
    bool afc_tracking_ = false;
    double afc_candidate_hz_ = 0.0;
    int afc_candidate_windows_ = 0;
    int afc_invalid_windows_ = 0;
    double previous_nominal_correction_hz_ = 0.0;
    bool have_previous_nominal_correction_ = false;
    mutable std::mutex afc_mutex_;
    AfcStats afc_stats_{false, 0.0, 0.0};

    double nco_cos_ = 1.0;
    double nco_sin_ = 0.0;
    std::size_t nco_renormalize_count_ = 0;
    double dc_i_ = 0.0;
    double dc_q_ = 0.0;
    std::array<float, kSpectrumBins * 2> fft_input_{};
    std::size_t fft_fill_ = 0;
    std::size_t spectrum_skip_ = 0;
    mutable std::mutex spectrum_mutex_;
    std::array<float, kSpectrumBins> spectrum_{};
    mutable std::mutex iq_display_mutex_;
    std::array<float, kIqDisplaySamples * 2> iq_display_{};

    std::mutex test_tone_mutex_;
    double test_tone_phase_ = 0.0;
};

}  // namespace satellite_rx
