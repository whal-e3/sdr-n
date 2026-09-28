# Publishing OrbitScope

Publication preparation, checked September 27, 2026. Start with Google Play's internal testing track, then closed testing where required. The app is experimental: physical RTL-SDR sample streaming passed, while real satellite voice/packet decoding and HackRF behavior remain unverified.

## Current release status

| Item | Status |
| --- | --- |
| Android target | API 36; minimum API 29 (Android 10). |
| App identity | OrbitScope; application ID `io.github.whal_e3.orbitscope` (debug suffix `.debug`); version `0.1.0`, code `1`. Confirm the permanent application ID before the first upload. Changing it creates a separate installation and does not preserve the existing phone's private catalog/settings automatically. |
| Catalog for new installations | Blocked for satellite reception: the bundled historical demo disables reception and `catalogBaseUrl` is empty by default. The locally installed signed cache is not part of the APK/AAB and will expire. A reviewed update source and public verification key must be configured and tested before advertising satellite reception. |
| ISS voice repeater | The current curated profiles do not enable the 437.800 MHz FM repeater. A reviewed, signed catalog update is needed for that downlink. |
| Upload signing | Persistent RSA-4096 upload key created privately outside the repository; signed APK/AAB generated and verified. Windows password backup uses user-bound DPAPI. See [signing and backup](SIGNING.md); an off-device encrypted backup still needs the owner. |
| Native compatibility | Resolved: graphics-path 1.0.1 is pinned and its matching upstream JNI source rebuilt with both 16 KB linker flags. All packaged LOAD/RELRO checks pass. JNI loading and curve iteration passed on the Android 16 / 16 KB x86_64 emulator and Android 11 arm64 phone. This does not test USB hardware on a 16 KB device. |
| Privacy / Data safety | Offline policy and notices are accessible through **About**. The [policy draft](privacy-policy-draft.md) still needs the owner's public developer name/contact and final public URL. Listing/contact and Data safety worksheet are prepared; review CelesTrak and any later catalog provider before submission. |
| License materials | Completed inventory for 71 resolved runtime artifacts, bundled license/notice texts, vendored native source, and 71 matching dependency source archives from official Maven repositories. Source packaging/relink instructions: [SOURCE_AND_LICENSES.md](SOURCE_AND_LICENSES.md). |
| Modern Android testing | Android 16 / 16 KB emulator installed and exercised; a reproducible header/status-bar overlap was fixed with safe system insets. Physical USB/foreground-receiver behavior on modern Android still requires a compatible newer device; the emulator has no SDR attached. See the latest test-results section. |

No catalog service has been deployed. Deployment requires the user's authorization and a review of source redistribution terms. A tracking/SDR-tester preview can be described honestly without claiming working satellite decoding.

## Build and signing

Use JDK 17, Android SDK 36, Build Tools 36, NDK 27.0.12077973 and CMake as in the main README.

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease
```

Windows uses `gradlew.bat`. The bundle is `app/build/outputs/bundle/release/app-release.aab`. Without upload credentials it is unsigned; do not upload it or distribute it as an installable APK.

A persistent upload key now exists on this machine. Use `python3 tools/build_release.py` in WSL, or `tools/build_release.ps1` with the configured Windows toolchain. See [SIGNING.md](SIGNING.md) for private local paths and backup instructions; do not regenerate a replacement key for each build. On a different machine, Android Studio's **Build → Generate Signed Bundle / APK** can create a separate initial key when one does not already exist. Configure Play App Signing in Play Console. The upload key and the catalog's Ed25519 signing key serve different purposes and must remain separate.

Command-line signing uses these environment variables in the build process:

- `ORBIT_UPLOAD_KEYSTORE`: absolute path to the upload keystore.
- `ORBIT_UPLOAD_KEY_ALIAS`: upload key alias.
- `ORBIT_UPLOAD_STORE_PASSWORD`: keystore password.
- `ORBIT_UPLOAD_KEY_PASSWORD`: key password.

Set them through a local secret manager or protected shell session; never commit them or paste passwords into chat. All four are required when any is supplied. Keep keystores and local signing configuration outside version control. Run `:app:bundleRelease` for Google Play or `:app:assembleRelease` for a signed standalone APK. Increment `versionCode` for subsequent Play uploads.

When enabling ongoing catalog updates, also provide `catalogBaseUrl` and `catalogPublicKeyBase64` as documented in [catalog-service/README.md](../../catalog-service/README.md). Only the public verification key belongs in the app. The build's default empty URL cannot provide fresh radio profiles to new users.

Inspect every packaged native library's ELF LOAD and GNU_RELRO alignment and the generated APK's ZIP alignment, including transitive libraries. Test on a real 16 KB environment; alignment inspection alone cannot establish runtime compatibility. Use Android's [16 KB guidance](https://developer.android.com/guide/practices/page-sizes) and bundletool to inspect the bundle's `PAGE_ALIGNMENT_16K` setting. An API 36 x86_64 emulator with 16 KB pages now provides native/UI runtime evidence. A first boot under host memory pressure had system-level failures before app testing; stable checks were repeated after freeing the build daemon. No USB hardware is forwarded into this emulator.

## Store preparation

- [Listing, icon, feature graphic and screenshots](store/LISTING.md).
- [App content / Data safety / foreground service worksheet](PLAY_DECLARATIONS.md).
- [Step-by-step account registration](REGISTER_GOOGLE_PLAY.md).
- [Upload signing and backup](SIGNING.md).
- [Source archive and license materials](SOURCE_AND_LICENSES.md).

Signed local candidates are for testing. Final privacy/contact details and the account gates remain before submission. A fresh installed preview has the historical demo and no update endpoint; its independent SDR tester works without a catalog, while satellite reception remains disabled.

## Google Play steps

1. Create/verify the developer's Play Console account. Google lists a one-time US$25 registration fee; the account owner handles payment and identity/device verification.
2. Create **OrbitScope** as an app and choose its default language, distribution countries, free/paid status and permanent application ID before the first upload.
3. Complete the store listing: accurate description, support contact, privacy-policy URL, 512 × 512 app icon, 1024 × 500 feature graphic and screenshots from the release build. Describe USB OTG/external SDR requirements and the experimental decoder limits.
4. Complete app content declarations: Data safety, ads, content rating, target audience and app access. There is no app login in the current source. Declare foreground-service uses for `connectedDevice` and `mediaPlayback`, providing the requested feature demonstration video. Do not claim microphone recording: the app outputs received audio.
5. Upload the signed AAB to internal testing, configure Play App Signing, and inspect Play's compatibility/pre-launch reports. Verify a fresh installation, rather than relying on this development phone's existing signed cache.
6. If the personal account was created after November 13, 2023, run the required closed test with at least 12 testers continuously opted in for 14 days, then apply for production access. Internal testing does not satisfy that requirement.
7. Publish production after the account gates, release checks and declared functionality are ready. Store approval and public rollout are separate from building an AAB.

The owner must sign in to Play Console and supply the account/listing information. No store upload or rollout has been performed during this preparation.

## Alternative: GitHub Releases

A GitHub release can distribute a signed release APK and the matching source/rebuild materials to early testers. It avoids Play's production-testing gate, but still needs a persistent signing key, truthful feature descriptions, privacy information and license materials. Users install it manually. Publishing a public release is a separate step from committing build preparation.

## Official references

- [Play Console registration](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en)
- [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- [Personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
- [Release tracks and rollout](https://support.google.com/googleplay/android-developer/answer/9859348?hl=en)
- [Privacy-policy requirements](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
- [Foreground-service declarations](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
- [App signing](https://developer.android.com/studio/publish/app-signing)

Requirements can change. Recheck the linked policies and the actual Play Console dashboard before submission.
