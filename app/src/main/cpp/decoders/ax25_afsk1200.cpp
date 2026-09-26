#include "ax25_afsk1200.hpp"

#include <algorithm>
#include <cmath>
#include <utility>

namespace satellite_rx {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr double kMarkStep = 2.0 * kPi * 1200.0 / 48000.0;
constexpr double kSpaceStep = 2.0 * kPi * 2200.0 / 48000.0;
constexpr std::uint8_t kHdlcFlag = 0x7e;

// AX.25 has destination and source address subfields, each seven octets;
// optional repeater subfields follow. Call signs are upper-case letters or
// digits, padded with spaces, shifted left one bit. The low bit of the final
// SSID octet terminates the address field. A valid FCS alone proves only that
// HDLC bytes were received, not that they form an AX.25 packet.
bool hasAx25Address(const std::uint8_t* frame, std::size_t body_length) {
    std::size_t offset = 0;
    std::size_t address_count = 0;
    while (offset + 7 < body_length) { // Leave at least one control octet.
        bool has_call_sign = false;
        bool padding_started = false;
        for (std::size_t i = 0; i < 6; ++i) {
            const auto encoded = frame[offset + i];
            if ((encoded & 1u) != 0) return false;
            const auto character = static_cast<char>(encoded >> 1);
            if (character == ' ') {
                padding_started = true;
            } else if (!padding_started &&
                       ((character >= 'A' && character <= 'Z') ||
                        (character >= '0' && character <= '9'))) {
                has_call_sign = true;
            } else {
                return false;
            }
        }
        if (!has_call_sign) return false;
        ++address_count;
        const bool last_address = (frame[offset + 6] & 1u) != 0;
        offset += 7;
        if (last_address) return address_count >= 2;
    }
    return false;
}
}  // namespace

Ax25Afsk1200Decoder::Ax25Afsk1200Decoder() {
    reset();
}

void Ax25Afsk1200Decoder::reset() {
    history_ = {};
    phases_ = {};
    sum_ = {};
    frames_.clear();
    last_frame_.clear();
    last_frame_end_sample_ = 0;
    sample_count_ = 0;
    valid_frame_count_ = 0;
    flag_candidate_count_ = 0;
    bad_fcs_count_ = 0;
    oversized_frame_count_ = 0;
    mark_cos_ = 1.0;
    mark_sin_ = 0.0;
    space_cos_ = 1.0;
    space_sin_ = 0.0;
    audio_dc_ = 0.0;
}

void Ax25Afsk1200Decoder::pushAudio(const std::int16_t* samples,
                                    std::size_t count) {
    if (samples == nullptr) {
        return;
    }
    const double mark_step_cos = std::cos(kMarkStep);
    const double mark_step_sin = std::sin(kMarkStep);
    const double space_step_cos = std::cos(kSpaceStep);
    const double space_step_sin = std::sin(kSpaceStep);

    for (std::size_t n = 0; n < count; ++n) {
        const double input = static_cast<double>(samples[n]) / 32768.0;
        audio_dc_ += 0.0005 * (input - audio_dc_);
        const double centered = input - audio_dc_;
        const std::size_t slot = static_cast<std::size_t>(
            sample_count_ % kSamplesPerBit);
        const Correlation old = history_[slot];
        const Correlation current{
            centered * mark_cos_, centered * mark_sin_,
            centered * space_cos_, centered * space_sin_};
        history_[slot] = current;
        sum_.mark_i += current.mark_i - old.mark_i;
        sum_.mark_q += current.mark_q - old.mark_q;
        sum_.space_i += current.space_i - old.space_i;
        sum_.space_q += current.space_q - old.space_q;

        const double next_mark_cos =
            mark_cos_ * mark_step_cos - mark_sin_ * mark_step_sin;
        mark_sin_ = mark_sin_ * mark_step_cos + mark_cos_ * mark_step_sin;
        mark_cos_ = next_mark_cos;
        const double next_space_cos =
            space_cos_ * space_step_cos - space_sin_ * space_step_sin;
        space_sin_ = space_sin_ * space_step_cos + space_cos_ * space_step_sin;
        space_cos_ = next_space_cos;

        if ((sample_count_ & 4095u) == 4095u) {
            const double mark_norm = std::hypot(mark_cos_, mark_sin_);
            const double space_norm = std::hypot(space_cos_, space_sin_);
            mark_cos_ /= mark_norm;
            mark_sin_ /= mark_norm;
            space_cos_ /= space_norm;
            space_sin_ /= space_norm;
        }

        ++sample_count_;
        if (sample_count_ < kSamplesPerBit) {
            continue;
        }
        const double mark_power = sum_.mark_i * sum_.mark_i +
                                  sum_.mark_q * sum_.mark_q;
        const double space_power = sum_.space_i * sum_.space_i +
                                   sum_.space_q * sum_.space_q;
        // Exactly one of the forty symbol-phase candidates is updated for
        // every 40-sample correlation window.
        receiveTone(phases_[slot], space_power > mark_power);
    }
}

bool Ax25Afsk1200Decoder::popFrame(Ax25Frame& output) {
    if (frames_.empty()) {
        return false;
    }
    output = std::move(frames_.front());
    frames_.pop_front();
    return true;
}

void Ax25Afsk1200Decoder::receiveTone(PhaseState& phase, bool tone) {
    if (phase.previous_tone_valid) {
        // AX.25 NRZI: transition=0, no transition=1.
        receiveRawBit(phase, tone == phase.previous_tone ? 1 : 0);
    }
    phase.previous_tone = tone;
    phase.previous_tone_valid = true;
}

void Ax25Afsk1200Decoder::receiveRawBit(PhaseState& phase,
                                        std::uint8_t bit) {
    phase.pending[phase.pending_count++] = bit;
    if (phase.pending_count < phase.pending.size()) {
        return;
    }
    std::uint8_t window = 0;
    for (std::size_t i = 0; i < phase.pending.size(); ++i) {
        window |= static_cast<std::uint8_t>(phase.pending[i] << i);
    }
    if (window == kHdlcFlag) {
        onFlag(phase);
        phase.pending_count = 0;
        return;
    }
    receiveDataBit(phase, phase.pending[0]);
    std::move(phase.pending.begin() + 1, phase.pending.end(),
              phase.pending.begin());
    phase.pending_count = phase.pending.size() - 1;
}

void Ax25Afsk1200Decoder::receiveDataBit(PhaseState& phase,
                                         std::uint8_t bit) {
    if (!phase.in_frame) {
        return;
    }
    if (bit != 0) {
        ++phase.consecutive_ones;
        if (phase.consecutive_ones >= 6) {
            abandon(phase); // HDLC abort or loss of framing.
            return;
        }
    } else {
        if (phase.consecutive_ones == 5) {
            phase.consecutive_ones = 0; // Stuffed zero.
            return;
        }
        phase.consecutive_ones = 0;
    }

    phase.partial_byte |= static_cast<std::uint8_t>(bit << phase.partial_bits);
    if (++phase.partial_bits == 8) {
        if (phase.bytes.size() >= kMaxFrameBytes) {
            ++oversized_frame_count_;
            abandon(phase);
            return;
        }
        phase.bytes.push_back(phase.partial_byte);
        phase.partial_byte = 0;
        phase.partial_bits = 0;
    }
}

void Ax25Afsk1200Decoder::onFlag(PhaseState& phase) {
    ++flag_candidate_count_;
    // AX.25 needs at least destination and source addresses (7 bytes each),
    // one control byte, and two FCS bytes. This also limits random-noise
    // false positives when many symbol phases are searched in parallel.
    if (phase.in_frame && phase.partial_bits == 0 &&
        phase.bytes.size() >= 17) {
        const std::size_t data_length = phase.bytes.size() - 2;
        const std::uint16_t received_fcs =
            static_cast<std::uint16_t>(phase.bytes[data_length]) |
            static_cast<std::uint16_t>(phase.bytes[data_length + 1] << 8);
        if (crc16X25(phase.bytes.data(), data_length) == received_fcs) {
            phase.bytes.resize(data_length);
            if (hasAx25Address(phase.bytes.data(), data_length)) {
                // Parallel symbol phases can decode the same frame. Their
                // flag endpoints differ by at most one symbol, so suppress
                // only that duplicate, not later legitimate repeats.
                const bool duplicate =
                    sample_count_ >= last_frame_end_sample_ &&
                    sample_count_ - last_frame_end_sample_ <= 80 &&
                    phase.bytes == last_frame_;
                if (!duplicate) {
                    last_frame_ = phase.bytes;
                    last_frame_end_sample_ = sample_count_;
                    ++valid_frame_count_;
                    if (frames_.size() == kMaxQueuedFrames) {
                        frames_.pop_front();
                    }
                    frames_.push_back({std::move(phase.bytes), sample_count_});
                }
            }
        } else {
            ++bad_fcs_count_;
        }
    }
    phase.in_frame = true;
    phase.bytes.clear();
    phase.consecutive_ones = 0;
    phase.partial_byte = 0;
    phase.partial_bits = 0;
}

void Ax25Afsk1200Decoder::abandon(PhaseState& phase) {
    phase.in_frame = false;
    phase.bytes.clear();
    phase.consecutive_ones = 0;
    phase.partial_byte = 0;
    phase.partial_bits = 0;
}

std::uint16_t Ax25Afsk1200Decoder::crc16X25(const std::uint8_t* bytes,
                                            std::size_t count) {
    std::uint16_t crc = 0xffff;
    for (std::size_t i = 0; i < count; ++i) {
        crc ^= bytes[i];
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 1) ? static_cast<std::uint16_t>((crc >> 1) ^ 0x8408)
                            : static_cast<std::uint16_t>(crc >> 1);
        }
    }
    return static_cast<std::uint16_t>(crc ^ 0xffff);
}

}  // namespace satellite_rx
