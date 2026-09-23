#include "cadu_sync.h"

#ifdef NDEBUG
#undef NDEBUG  // Keep checks active even when this test target uses Release.
#endif
#include <array>
#include <cassert>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace {

void appendBit(std::vector<std::uint8_t>& bytes,
               std::size_t& bits,
               std::uint8_t bit) {
    if (bits % 8 == 0) bytes.push_back(0);
    bytes.back() |= static_cast<std::uint8_t>(bit << (7 - bits % 8));
    ++bits;
}

void appendBytes(std::vector<std::uint8_t>& bytes,
                 std::size_t& bits,
                 const std::uint8_t* input,
                 std::size_t inputBytes) {
    for (std::size_t i = 0; i < inputBytes; ++i) {
        for (int b = 7; b >= 0; --b) {
            appendBit(bytes, bits,
                      static_cast<std::uint8_t>((input[i] >> b) & 1u));
        }
    }
}

lrpt::Cadu makeSyntheticCadu(std::uint8_t seed) {
    lrpt::Cadu cadu{};
    cadu[0] = 0x1A;
    cadu[1] = 0xCF;
    cadu[2] = 0xFC;
    cadu[3] = 0x1D;
    for (std::size_t i = 4; i < cadu.size(); ++i) {
        cadu[i] = static_cast<std::uint8_t>((i * 37 + seed) & 0xFF);
    }
    // Embedded marker must not start another candidate frame.
    cadu[200] = 0x1A;
    cadu[201] = 0xCF;
    cadu[202] = 0xFC;
    cadu[203] = 0x1D;
    return cadu;
}

void testPnKnownVectorAndRoundTrip() {
    lrpt::Cadu cadu{};
    cadu[0] = 0x1A;
    cadu[1] = 0xCF;
    cadu[2] = 0xFC;
    cadu[3] = 0x1D;
    lrpt::applyLegacyCcsdsPn(cadu);

    // Independent published PN sequence: SatDump randomization.cpp and
    // ktauchathuranga/lrpt-encoder scrambler.rs use this prefix.
    constexpr std::array<std::uint8_t, 32> expected = {
        0xFF, 0x48, 0x0E, 0xC0, 0x9A, 0x0D, 0x70, 0xBC,
        0x8E, 0x2C, 0x93, 0xAD, 0xA7, 0xB7, 0x46, 0xCE,
        0x5A, 0x97, 0x7D, 0xCC, 0x32, 0xA2, 0xBF, 0x3E,
        0x0A, 0x10, 0xF1, 0x88, 0x94, 0xCD, 0xEA, 0xB1,
    };
    for (std::size_t i = 0; i < expected.size(); ++i) {
        assert(cadu[i + lrpt::kAsmBytes] == expected[i]);
    }
    assert(cadu[4 + 255] == cadu[4]);
    assert(cadu[0] == 0x1A && cadu[3] == 0x1D);
    lrpt::applyLegacyCcsdsPn(cadu);
    for (std::size_t i = 4; i < cadu.size(); ++i) assert(cadu[i] == 0);
}

void testStreamingBitSlipAndTwoFrames() {
    const auto first = makeSyntheticCadu(11);
    const auto second = makeSyntheticCadu(73);
    std::vector<std::uint8_t> packed;
    std::size_t bitCount = 0;
    appendBit(packed, bitCount, 0);
    appendBit(packed, bitCount, 1);
    appendBit(packed, bitCount, 1);  // ASM starts at bit offset 3.
    appendBytes(packed, bitCount, first.data(), first.size());
    appendBytes(packed, bitCount, second.data(), second.size());
    while (bitCount % 8 != 0) appendBit(packed, bitCount, 0);

    lrpt::CaduSync sync;
    std::vector<lrpt::Cadu> frames;
    const auto sink = [&](const lrpt::Cadu& cadu) { frames.push_back(cadu); };
    std::size_t total = 0;
    for (std::size_t i = 0; i < packed.size(); ++i) {
        total += sync.consume(&packed[i], 1, sink);
    }
    assert(total == 2);
    assert(frames.size() == 2);
    assert(frames[0] == first && frames[1] == second);
}

void testTruncatedAndCorruptMarker() {
    const auto valid = makeSyntheticCadu(5);
    auto corrupted = valid;
    corrupted[0] ^= 0x01;
    // No Reed-Solomon validation exists in this stage, so a marker embedded
    // in the corrupted payload would correctly be reported as a candidate.
    corrupted[200] = 0;
    lrpt::CaduSync sync;
    std::vector<lrpt::Cadu> frames;
    const auto sink = [&](const lrpt::Cadu& cadu) { frames.push_back(cadu); };
    assert(sync.consume(valid.data(), 300, sink) == 0);
    sync.reset();
    assert(sync.consume(corrupted.data(), corrupted.size(), sink) == 0);
    assert(sync.consume(valid.data(), valid.size(), sink) == 1);
    assert(frames.size() == 1 && frames.front() == valid);
}

void testBadArguments() {
    lrpt::CaduSync sync;
    bool threw = false;
    try {
        sync.consume(nullptr, 1, [](const lrpt::Cadu&) {});
    } catch (const std::invalid_argument&) {
        threw = true;
    }
    assert(threw);
}

}  // namespace

int main() {
    testPnKnownVectorAndRoundTrip();
    testStreamingBitSlipAndTwoFrames();
    testTruncatedAndCorruptMarker();
    testBadArguments();
    std::cout << "LRPT CADU experiment: all tests passed\n";
}
