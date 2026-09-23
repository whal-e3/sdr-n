#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>

namespace satellite_rx {

// Raw AX.25 frame bytes (address, control, optional PID and information).
// The two FCS bytes are checked and removed before delivery. Address and
// information fields are intentionally left uninterpreted by this layer.
struct Ax25Frame {
    std::vector<std::uint8_t> bytes;
    std::uint64_t ending_sample = 0;
};

// Streaming receive-only 1200-baud Bell 202 AFSK decoder for 48 kHz mono
// PCM16 from the narrow-FM audio demodulator. Not thread-safe: push and pop
// must be serialized by the caller. A 40-phase symbol search tolerates an
// arbitrary initial sample offset; it does not perform clock recovery.
class Ax25Afsk1200Decoder {
public:
    static constexpr std::uint32_t kSampleRate = 48000;
    static constexpr std::size_t kSamplesPerBit = 40;
    static constexpr std::size_t kMaxFrameBytes = 2048; // Including FCS.
    static constexpr std::size_t kMaxQueuedFrames = 16;

    Ax25Afsk1200Decoder();

    void reset();
    void pushAudio(const std::int16_t* samples, std::size_t count);
    bool popFrame(Ax25Frame& output);
    std::size_t queuedFrames() const { return frames_.size(); }
    std::uint64_t validFrameCount() const { return valid_frame_count_; }
    std::uint64_t badFcsCount() const { return bad_fcs_count_; }
    std::uint64_t oversizedFrameCount() const { return oversized_frame_count_; }

private:
    struct PhaseState {
        bool previous_tone_valid = false;
        bool previous_tone = false;
        bool in_frame = false;
        std::array<std::uint8_t, 8> pending{};
        std::size_t pending_count = 0;
        std::uint8_t consecutive_ones = 0;
        std::uint8_t partial_byte = 0;
        std::uint8_t partial_bits = 0;
        std::vector<std::uint8_t> bytes;
    };

    struct Correlation {
        double mark_i = 0.0;
        double mark_q = 0.0;
        double space_i = 0.0;
        double space_q = 0.0;
    };

    void receiveTone(PhaseState& phase, bool tone);
    void receiveRawBit(PhaseState& phase, std::uint8_t bit);
    void receiveDataBit(PhaseState& phase, std::uint8_t bit);
    void onFlag(PhaseState& phase);
    void abandon(PhaseState& phase);
    static std::uint16_t crc16X25(const std::uint8_t* bytes,
                                  std::size_t count);

    std::array<Correlation, kSamplesPerBit> history_{};
    std::array<PhaseState, kSamplesPerBit> phases_{};
    Correlation sum_{};
    std::deque<Ax25Frame> frames_;
    std::vector<std::uint8_t> last_frame_;
    std::uint64_t last_frame_end_sample_ = 0;
    std::uint64_t sample_count_ = 0;
    std::uint64_t valid_frame_count_ = 0;
    std::uint64_t bad_fcs_count_ = 0;
    std::uint64_t oversized_frame_count_ = 0;
    double mark_cos_ = 1.0;
    double mark_sin_ = 0.0;
    double space_cos_ = 1.0;
    double space_sin_ = 0.0;
    double audio_dc_ = 0.0;
};

}  // namespace satellite_rx
