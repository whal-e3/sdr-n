# Native source provenance

- `libusb-cmake/`: [libusb-cmake](https://github.com/libusb/libusb-cmake), commit `bea6567b63796ab27c1c33257d230284fb2d8316`, with bundled libusb 1.0.30. LGPL-2.1-or-later; see its `COPYING` and source headers. Build metadata, library sources, and licenses are retained; examples and documentation are omitted.
- `rtl-sdr/`: [RTL-SDR Blog's rtl-sdr fork](https://github.com/rtlsdrblog/rtl-sdr-blog), commit `aed0ea19f3a273370a13c9009b96313c75d54c7b`. GPL-2.0-or-later; see `COPYING` and source headers. The source and headers are retained. This copy adds `rtlsdr_open_fd` so an Android-granted USB descriptor can be wrapped by libusb without root or `/dev/bus/usb` enumeration.
- `libhackrf/`: [Great Scott Gadgets libhackrf](https://github.com/greatscottgadgets/hackrf), commit `7f96cc8e3fa625c4263a71ba8dd44d1f6110e4fa`. Its `hackrf.c` and `hackrf.h` carry a BSD 3-clause license in their source headers. This copy adds `hackrf_open_fd` for Android-granted USB descriptors and disables unprivileged device discovery during initialization.

The native application is receive-only. Upstream RTL-SDR functions that write to the dongle configure its receiver; no radio transmit interface is linked.
