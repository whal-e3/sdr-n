#include "cadu_sync.h"

#include <stdexcept>

namespace lrpt {

void CaduSync::reset() noexcept {
    shift_ = 0;
    searchBits_ = 0;
    frameBits_ = 0;
    frame_.fill(0);
}

void CaduSync::consumeBit(std::uint8_t bit,
                          const FrameSink& onFrame,
                          std::size_t& emitted) {
    if (frameBits_ == 0) {
        shift_ = (shift_ << 1) | bit;
        if (searchBits_ < 32) ++searchBits_;
        if (searchBits_ == 32 && shift_ == kAsm) {
            frame_.fill(0);
            frame_[0] = 0x1A;
            frame_[1] = 0xCF;
            frame_[2] = 0xFC;
            frame_[3] = 0x1D;
            frameBits_ = 32;
        }
        return;
    }

    const std::size_t byte = frameBits_ / 8;
    const unsigned position = 7u - static_cast<unsigned>(frameBits_ % 8);
    frame_[byte] |= static_cast<std::uint8_t>(bit << position);
    ++frameBits_;
    if (frameBits_ == kCaduBytes * 8) {
        // Reset before callback so a callback may reuse or reset this instance.
        frameBits_ = 0;
        shift_ = 0;
        searchBits_ = 0;
        ++emitted;
        onFrame(frame_);
    }
}

std::size_t CaduSync::consume(const std::uint8_t* packedBits,
                              std::size_t byteCount,
                              const FrameSink& onFrame) {
    if ((packedBits == nullptr && byteCount != 0) || !onFrame) {
        throw std::invalid_argument("CADU input and frame sink must be valid");
    }

    std::size_t emitted = 0;
    for (std::size_t i = 0; i < byteCount; ++i) {
        for (int bit = 7; bit >= 0; --bit) {
            consumeBit(static_cast<std::uint8_t>((packedBits[i] >> bit) & 1u),
                       onFrame, emitted);
        }
    }
    return emitted;
}

void applyLegacyCcsdsPn(Cadu& cadu) noexcept {
    // The legacy CCSDS recurrence is x^8 + x^7 + x^5 + x^3 + 1.
    // The register initially contains eight 1s; each output byte is the
    // current register state, then the register advances by eight bits.
    std::uint8_t state = 0xFF;
    for (std::size_t i = kAsmBytes; i < cadu.size(); ++i) {
        cadu[i] ^= state;
        for (int b = 0; b < 8; ++b) {
            const std::uint8_t feedback = static_cast<std::uint8_t>(
                (state ^ (state >> 2) ^ (state >> 4) ^ (state >> 7)) & 1u);
            state = static_cast<std::uint8_t>((state << 1) | feedback);
        }
    }
}

}  // namespace lrpt
