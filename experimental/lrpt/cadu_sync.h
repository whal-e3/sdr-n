#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>

namespace lrpt {

// A post-Viterbi, post-differential hard-bit CADU. This does NOT imply that
// its Reed-Solomon codewords are valid or that it contains an image packet.
constexpr std::size_t kCaduBytes = 1024;
constexpr std::size_t kAsmBytes = 4;
constexpr std::uint32_t kAsm = 0x1ACFFC1Du;
using Cadu = std::array<std::uint8_t, kCaduBytes>;

class CaduSync {
public:
    using FrameSink = std::function<void(const Cadu&)>;

    // Bytes contain MSB-first hard bits. Chunk boundaries may occur anywhere;
    // the marker can begin at any bit offset. Returns candidate frame count.
    std::size_t consume(const std::uint8_t* packedBits,
                        std::size_t byteCount,
                        const FrameSink& onFrame);

    void reset() noexcept;

private:
    void consumeBit(std::uint8_t bit,
                    const FrameSink& onFrame,
                    std::size_t& emitted);

    Cadu frame_{};
    std::uint32_t shift_ = 0;
    std::size_t searchBits_ = 0;
    std::size_t frameBits_ = 0;
};

// XOR the legacy CCSDS 255-bit pseudo-random sequence into the 1020-byte
// RS-coded field, restarting the sequence immediately after the 4-byte ASM.
// XOR is symmetric, so this also randomizes a clear field for tests.
void applyLegacyCcsdsPn(Cadu& cadu) noexcept;

}  // namespace lrpt
