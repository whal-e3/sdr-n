#include "../decoders/ax25_afsk1200.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {

#define CHECK(expression) do { if (!(expression)) { \
    std::fprintf(stderr, "Check failed: %s (%s:%d)\n", \
                 #expression, __FILE__, __LINE__); \
    std::abort(); \
} } while (false)

constexpr double kPi = 3.14159265358979323846;

std::uint16_t crc16X25(const std::vector<std::uint8_t>& bytes) {
    std::uint16_t crc = 0xffff;
    for (std::uint8_t value : bytes) {
        crc ^= value;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 1) ? static_cast<std::uint16_t>((crc >> 1) ^ 0x8408)
                            : static_cast<std::uint16_t>(crc >> 1);
        }
    }
    return static_cast<std::uint16_t>(crc ^ 0xffff);
}

void appendFlag(std::vector<std::uint8_t>& bits) {
    for (int bit = 0; bit < 8; ++bit) {
        bits.push_back(static_cast<std::uint8_t>((0x7e >> bit) & 1));
    }
}

std::vector<std::uint8_t> makeBits(const std::vector<std::uint8_t>& body,
                                   bool corrupt_fcs = false) {
    std::vector<std::uint8_t> bits;
    for (int i = 0; i < 20; ++i) {
        appendFlag(bits);
    }

    std::vector<std::uint8_t> bytes = body;
    std::uint16_t fcs = crc16X25(body);
    if (corrupt_fcs) {
        fcs ^= 0x0080;
    }
    bytes.push_back(static_cast<std::uint8_t>(fcs & 0xff));
    bytes.push_back(static_cast<std::uint8_t>(fcs >> 8));

    int ones = 0;
    for (std::uint8_t byte : bytes) {
        for (int bit = 0; bit < 8; ++bit) {
            const std::uint8_t value = static_cast<std::uint8_t>(
                (byte >> bit) & 1);
            bits.push_back(value);
            if (value != 0) {
                if (++ones == 5) {
                    bits.push_back(0); // HDLC bit stuffing.
                    ones = 0;
                }
            } else {
                ones = 0;
            }
        }
    }
    for (int i = 0; i < 4; ++i) {
        appendFlag(bits);
    }
    return bits;
}

std::vector<std::int16_t> bell202Audio(
    const std::vector<std::uint8_t>& bits, int noise_amplitude = 0) {
    std::vector<std::int16_t> audio(17, 0); // Arbitrary initial sample offset.
    audio.reserve(audio.size() + bits.size() * 40);
    std::uint32_t random_state = 0x5a17bd3u;
    double phase = 0.0;
    bool space_tone = false;
    for (std::uint8_t bit : bits) {
        if (bit == 0) {
            space_tone = !space_tone;
        }
        const double step = 2.0 * kPi *
            (space_tone ? 2200.0 : 1200.0) / 48000.0;
        for (int s = 0; s < 40; ++s) {
            phase += step;
            if (phase > 2.0 * kPi) {
                phase -= 2.0 * kPi;
            }
            random_state = random_state * 1664525u + 1013904223u;
            const int noise = noise_amplitude == 0 ? 0 :
                static_cast<int>((random_state >> 16) %
                                  static_cast<std::uint32_t>(
                                      noise_amplitude * 2 + 1)) -
                    noise_amplitude;
            const int value = static_cast<int>(std::lround(
                12000.0 * std::sin(phase))) + noise;
            audio.push_back(static_cast<std::int16_t>(
                std::clamp(value, -32768, 32767)));
        }
    }
    return audio;
}

std::vector<std::uint8_t> exampleUiFrame() {
    // Shifted AX.25 addresses, UI control, no layer-3 protocol, and an
    // information field chosen to exercise long runs of one bits.
    return {
        'A' << 1, 'P' << 1, 'R' << 1, 'S' << 1, ' ' << 1, ' ' << 1,
        0x60,
        'T' << 1, 'E' << 1, 'S' << 1, 'T' << 1, ' ' << 1, ' ' << 1,
        0x61, 0x03, 0xf0,
        0xff, 0xff, 0xff, 0x00, 0x7e, 0x7d, 0x01, 0xfe
    };
}

void pushChunked(satellite_rx::Ax25Afsk1200Decoder& decoder,
                 const std::vector<std::int16_t>& audio) {
    for (std::size_t offset = 0; offset < audio.size();) {
        const std::size_t count = std::min<std::size_t>(
            137, audio.size() - offset);
        decoder.pushAudio(audio.data() + offset, count);
        offset += count;
    }
}

}  // namespace

int main() {
    using satellite_rx::Ax25Afsk1200Decoder;
    using satellite_rx::Ax25Frame;

    const std::vector<std::uint8_t> body = exampleUiFrame();
    Ax25Afsk1200Decoder decoder;
    Ax25Frame frame;

    pushChunked(decoder, bell202Audio(makeBits(body), 600));
    CHECK(decoder.queuedFrames() == 1);
    CHECK(decoder.validFrameCount() == 1);
    CHECK(decoder.popFrame(frame));
    CHECK(frame.bytes == body);
    CHECK(frame.ending_sample > 0);
    CHECK(!decoder.popFrame(frame));

    // A CRC-valid HDLC payload is not necessarily an AX.25 packet. A call
    // sign octet must be shifted left, leaving its extension bit clear.
    decoder.reset();
    auto invalid_address = body;
    invalid_address[0] |= 1;
    pushChunked(decoder, bell202Audio(makeBits(invalid_address)));
    CHECK(decoder.flagCandidateCount() > 0);
    CHECK(decoder.queuedFrames() == 0);
    CHECK(decoder.validFrameCount() == 0);
    pushChunked(decoder, bell202Audio(makeBits(body)));
    CHECK(decoder.validFrameCount() == 1);
    CHECK(decoder.popFrame(frame));
    CHECK(frame.bytes == body);

    decoder.reset();
    invalid_address = body;
    invalid_address[6] |= 1; // Destination cannot end the address field.
    pushChunked(decoder, bell202Audio(makeBits(invalid_address)));
    CHECK(decoder.queuedFrames() == 0);
    CHECK(decoder.validFrameCount() == 0);

    // A real optional repeater address remains valid when the source
    // extension bit is cleared and the repeater terminates the field.
    decoder.reset();
    auto via_repeater = body;
    via_repeater[13] &= static_cast<std::uint8_t>(~1u);
    const std::vector<std::uint8_t> repeater = {
        'W' << 1, 'I' << 1, 'D' << 1, 'E' << 1, '1' << 1, ' ' << 1,
        0x61,
    };
    via_repeater.insert(via_repeater.begin() + 14,
                        repeater.begin(), repeater.end());
    pushChunked(decoder, bell202Audio(makeBits(via_repeater)));
    CHECK(decoder.validFrameCount() == 1);
    CHECK(decoder.popFrame(frame));
    CHECK(frame.bytes == via_repeater);

    decoder.reset();
    pushChunked(decoder, bell202Audio(makeBits(body, true), 300));
    CHECK(decoder.queuedFrames() == 0);
    CHECK(decoder.validFrameCount() == 0);
    CHECK(decoder.badFcsCount() > 0);

    decoder.reset();
    std::vector<std::int16_t> noise(48000);
    std::uint32_t random_state = 0x12345678u;
    for (std::int16_t& sample : noise) {
        random_state = random_state * 1664525u + 1013904223u;
        sample = static_cast<std::int16_t>(random_state >> 16);
    }
    pushChunked(decoder, noise);
    CHECK(decoder.queuedFrames() == 0);

    decoder.reset();
    std::vector<std::uint8_t> repeated_bits;
    for (int i = 0; i < 20; ++i) {
        const auto one = makeBits(body);
        repeated_bits.insert(repeated_bits.end(), one.begin(), one.end());
    }
    pushChunked(decoder, bell202Audio(repeated_bits));
    CHECK(decoder.validFrameCount() == 20);
    CHECK(decoder.queuedFrames() == Ax25Afsk1200Decoder::kMaxQueuedFrames);

    std::cout << "AX.25 Bell 202 synthetic tests passed\n";
    return 0;
}
