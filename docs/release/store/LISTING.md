# OrbitScope store listing

## Title

OrbitScope

## Short description (under 80 characters)

Explore satellite orbits, plan passes, and test your USB SDR.

## Full description

OrbitScope is an experimental satellite tracking and receive-only SDR toolkit.

Explore satellites on an interactive 3D globe, inspect orbital tracks, and plan
passes for your observer location. Enter coordinates, select a point on the
offline world map, or allow Android to supply your location. Import orbital
elements with the system file picker, or look up a satellite by NORAD ID.

Inspect live USB IQ samples with separate spectrum, waterfall, waveform and
constellation tabs. The independent SDR tester checks sample streaming before
a satellite pass, without requiring a satellite signal. A clearly labeled
synthetic test tone lets you explore the display without radio hardware.

USB reception requires a compatible Android USB host/OTG connection and an
external RTL-SDR or HackRF One with a suitable antenna. The app is receive-only.
RTL-SDR sample streaming has been tested on a Samsung Galaxy A30 running
Android 11. HackRF hardware testing is still pending.

Release preview limitations:
• The bundled historical demo is for exploring the interface. It disables
  satellite reception; a current verified signed catalog is required for that.
• This preview has no automatic catalog update service configured. Importing
  or looking up orbital elements adds tracking data, not authorized radio profiles.
• Experimental narrowband FM audio and AX.25 AFSK1200 packet decoding are
  implemented and tested with generated signals. Real satellite voice and
  packet reception have not been verified. Meteor LRPT decoding is unavailable.
• A spectrum trace or moving constellation does not establish successful decoding.
• Pass predictions depend on fresh orbital elements and an accurate location.

Location is optional and processed on your device. The current app has no
advertising, account login or developer analytics. Internet access is used
for explicitly requested orbital lookups and configured catalog downloads.

Android 10 or newer; supported CPU architectures: arm64 and x86_64.

## Listing contact

Support email: owner to supply (required before submission).
Suggested developer display name: whal-e3 (owner to confirm).
Support website: https://github.com/whal-e3/sdr-n
Privacy policy: final public URL must match the policy packaged in the release.

## Artwork

- `play-icon.png`: 512 × 512, 32-bit PNG (opaque artwork).
- `feature-graphic.png`: 1024 × 500, opaque PNG.
- SVG layouts and `tools/render_store_graphics.py` reproduce the branding.
- `screenshots/`: unaltered captures from the signed preview on the physical phone.

Review the limitations again if catalog configuration or decoder verification changes.
