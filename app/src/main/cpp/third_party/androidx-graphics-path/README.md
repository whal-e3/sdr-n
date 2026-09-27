# AndroidX graphics-path JNI rebuild

Upstream graphics-path 1.0.1 release revision:
`8a05a22af450d589ef911d772a001a49dcb05b71`.
Source: https://android.googlesource.com/platform/frameworks/support/+/8a05a22af450d589ef911d772a001a49dcb05b71/graphics/graphics-path/src/main/cpp/

The Java/Kotlin dependency remains graphics-path 1.0.1. These matching native
sources replace its prebuilt library. The only upstream modification is adding
`-z,common-page-size=16384` alongside the existing maximum-page-size linker flag.
`UPSTREAM_SHA256.json` records original file hashes, before that modification.
Copyright and license headers remain intact (Apache 2.0).

Gradle selects the CMake-built library during native packaging. Always run
`python3 tools/check_release.py` on the final APK and AAB: it rejects improperly
aligned replacement libraries. Alignment checks do not replace 16 KB runtime tests.
