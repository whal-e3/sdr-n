# Meteor M2-x LRPT CADU experiment

This directory is a **standalone, post-FEC-boundary experiment**, not an app feature. `CaduSync` accepts packed, MSB-first hard bits **after** carrier/timing recovery, OQPSK symbol decisions, any 80k deinterleaving, Viterbi decoding, phase resolution, and required NRZ-M/differential decoding. It detects the standard 32-bit attached sync marker (`1ACFFC1D`) at any bit offset and collects candidate 1,024-byte CADUs across input chunks. `applyLegacyCcsdsPn` XORs the legacy CCSDS pseudo-random sequence into bytes 4–1023, restarting for each CADU; applying it twice recovers the original bytes.

The component does **not** demodulate IQ, run Viterbi or Reed–Solomon correction, verify a candidate, decode AOS/CCSDS packets, or produce imagery. An embedded sync marker can cause a false candidate when the true marker is lost. Do not present its output as a valid satellite frame until all four Reed–Solomon codewords pass correction/checking. Nothing here has been tested against a real Meteor capture.

## Why this boundary

The [current SatDump Meteor pipeline](https://github.com/SatDump/SatDump/blob/master/resources/pipelines/Meteor-M.json) has distinct old-M2 QPSK, M2-x 72k OQPSK, and M2-x 80k OQPSK paths. The M2-x 72k path uses a convolutional/concatenated decoder with NRZ-M enabled; the 80k path additionally deinterleaves soft symbols and differential-decodes. In [SatDump's Meteor LRPT decoder](https://github.com/SatDump/SatDump/blob/master/plugins/meteor_support/meteor/module_meteor_lrpt_decoder.cpp), the M2-x chain then deframes 8,192 bits, derandomizes the 1,020-byte field, applies four-way interleaved RS(255,223), and only writes a CADU when all four codewords pass. The subsequent [MSU-MR module](https://github.com/SatDump/SatDump/blob/master/plugins/meteor_support/meteor/instruments/msumr/module_meteor_msumr_lrpt.cpp) demultiplexes AOS virtual channel 5 and source packets before assembling the image. These mode differences must remain explicit; this experiment is **not** a drop-in replacement for the legacy M2 path.

The [CCSDS TM Synchronization and Channel Coding standard, 131.0-B-5](https://ccsds.org/Pubs/131x0b5.pdf) specifies the `1ACFFC1D` marker, the legacy polynomial `x^8+x^7+x^5+x^3+1`, and the order of derandomization before Reed–Solomon decoding for concatenated coding. The 892-byte VCDU + 128-byte parity + 4-byte ASM sizing matches the independent [Meteor M2-4 encoder's constants](https://github.com/ktauchathuranga/lrpt-encoder/blob/main/src/constants.rs). The frequency and currently active mode are **not** hard-coded here; they must come from a current, reviewed catalog and/or signal confirmation.

## Build and verify

```sh
cmake -S experimental/lrpt -B /tmp/lrpt-build -G Ninja
cmake --build /tmp/lrpt-build
ctest --test-dir /tmp/lrpt-build --output-on-failure
```

On Windows, use any temporary build directory and pass an available C++17 compiler. The tests cover the PN sequence's independently published first 32 bytes, its period and reversibility, 3-bit stream misalignment, chunk boundaries, two adjacent frames, an embedded marker, an incomplete frame, and a corrupted first marker. The PN prefix is independently visible in [SatDump's randomizer](https://github.com/SatDump/SatDump/blob/master/src-core/common/codings/randomization.cpp) and [the Meteor encoder's test](https://github.com/ktauchathuranga/lrpt-encoder/blob/main/src/scrambler.rs). These are **synthetic unit tests**, not an end-to-end image test.

## Test vectors and next implementation step

The [MIT-licensed Meteor M2-4 encoder](https://github.com/ktauchathuranga/lrpt-encoder) can emit `--dump-first-cadu` and `--dump-cadu-stream` before NRZ-M/convolutional coding as well as synthetic baseband IQ. Pin a commit, generate a small image, preserve the exact encoder flags and SHA-256 hashes, and compare the bytes coming out of a new demod/Viterbi path to those dumped CADUs. Then add interleaved RS(255,223) verification and a fixture containing a known-good **RS-checked** 892-byte VCDU before wiring this stage into the Android receiver. Finally, compare decoded source packets and the rendered image with SatDump using both a synthetic capture and a permitted real M2-4 recording. The test image should not be accepted solely because a sync marker was found.

For a real-air sample, [SigIDWiki's LRPT entry](https://www.sigidwiki.com/wiki/Low_Rate_Picture_Transmission_%28LRPT%29) links a raw-IQ example, but its exact satellite/mode, sample metadata, reuse permission, and expected CADU/image output need verification before adding it to this repository. A project's own recording with recorded metadata and explicit sharing permission is preferable.

This code was written independently from the public standard and behavior, not copied from a decoder. SatDump and artlav's `meteor_decoder` are GPL-3.0; dbdexter-dev's [`meteor_demod`](https://github.com/dbdexter-dev/meteor_demod) and [`meteor_decode`](https://github.com/dbdexter-dev/meteor_decode) and the encoder above are MIT-licensed. If importing any implementation, preserve its notices and review Android build compatibility; do not silently vendor source from a different project.
