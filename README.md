# Satellite Eavesdropper

Experimental, receive-only Android app for finding cataloged public/amateur satellite downlinks above an observer and inspecting their signals with a USB software-defined radio. The current app calculates visibility from orbital elements, offers manual or device location, and displays a live spectrum or synthetic test tone. It includes experimental NFM voice audio and Bell 202 AFSK1200/AX.25 packet decoding, tested with synthetic signals only. **Meteor LRPT imagery decoding is not implemented yet.**

## Build and run

1. Install Android Studio with Android SDK Platform 36, Build Tools 36.x, NDK 27.0.12077973, CMake, and a JDK 17. Use a physical Android 10+ phone with USB host/OTG support for SDR testing; an arm64 device is preferred. The x86_64 build target is for emulator UI/test-tone checks only.
2. Open this repository as a project in Android Studio and let Gradle sync. Select the `app` run configuration and launch it. Alternatively, run `./gradlew.bat :app:assembleDebug` from PowerShell.
3. Grant location permission when asked, or enter latitude and longitude manually. The app shows cataloged satellites above the horizon. Select one to see its downlinks and the next predicted pass.
4. For a no-hardware check, tap **Start test tone**. It generates synthetic I/Q in the native receiver and plots its spectrum; it is not a satellite transmission.

The bundled `app/src/main/assets/sample_catalog.json` is an **unsigned historical demo**. Its orbits age quickly, so its visibility and pass predictions must not be relied on for observing. The app labels this source as demo and disables hardware reception with it. Hardware reception is also disabled when signed catalog data or orbital elements are more than 72 hours old. For a current catalog, deploy the Worker described in [catalog-service/README.md](catalog-service/README.md), then set the Gradle properties `catalogBaseUrl` (Worker origin, without `/v1`) and `catalogPublicKeyBase64` (base64 of the **raw 32-byte Ed25519 public key**, not an X.509/SPKI wrapper). Keep signing private keys outside this repository. The client fetches the gzip release, requests `/v1/catalog.sig?sequence=N` using its `X-Catalog-Sequence` header, checks the signature and digest before parsing, and falls back to its last verified catalog on failure. This deployment path still needs end-to-end validation and a review of data redistribution terms in [NOTICE.md](NOTICE.md).

## USB reception status

- **RTL-SDR:** The Android USB path and native receive-only driver are implemented but not yet verified on physical hardware. Supported dongles are discovered by known USB IDs or product name, then Android requests explicit USB permission. Connect a suitable external antenna through the dongle.
- **HackRF One:** The receive-only USB path and native driver compile for Android arm64, including on-device decimation to the app sample rate. Physical-hardware behavior has not yet been verified. No transmit path is implemented.
- **Phone antenna:** Android phone antennas are not exposed as a general-purpose SDR input. The phone's GNSS/location hardware locates the observer; it does not substitute for an antenna attached to the USB SDR.

Receive sessions are started manually and run in a foreground service with a persistent notification and Stop action. Location permission is optional because coordinates can be entered manually. Internet permission is used only for catalog refresh. USB permission is requested for the selected device. The app is scoped to curated public/amateur downlinks; it does not authorize interception of private, commercial, safety-critical, or command traffic. Check the rules that apply where you use it.

## Development checks

`./gradlew.bat :app:testDebugUnitTest` runs Android/JVM tests, including signed-catalog verification, catalog parsing, AX.25 frame formatting, and orbit calculations (18 tests passed). Two synthetic JNI tests also passed on an API 36 emulator. The native receiver core and NFM/AX.25 decoders can be checked with CMake/CTest on a development machine; see `app/src/main/cpp/`. The catalog service has separate TypeScript checks and tests described in its README.

The receiver tunes the nominal RF frequency and updates a baseband Doppler correction from the selected orbit and observer location; this has not been checked against a real pass. Current gaps include physical-hardware and audio-output validation, real-pass AX.25 reliability checks, Meteor LRPT imagery decoding, and field tests. A spectrum trace is **not** evidence that a signal has been decoded.
