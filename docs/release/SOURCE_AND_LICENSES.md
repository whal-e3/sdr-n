# Source and license materials for binary releases

OrbitScope app code is GPL-3.0-only. Original dependency licenses remain in
force; see the root NOTICE.md and **About → Licenses** inside the app.

## Inventory and notices

The resolved release runtime contains 69 artifacts. The inventory, artifact
hashes, POM license declarations, embedded notices and common license texts
are packaged under `app/src/main/assets/legal/`. Native copyright notices,
source revisions and modified build settings remain with the vendored source.

When updating dependencies, run:

```bash
ORBIT_DEPENDENCY_EXPORT=/tmp/orbitscope-dependencies.json ./gradlew -I tools/export_dependencies.gradle :app:exportReleaseDependencies
python3 tools/update_notices.py /tmp/orbitscope-dependencies.json
```

Inspect the result. The generator fails when a license is unknown. Hipparchus
and Guava's tiny listenablefuture artifact inherit Apache 2.0; the remaining
entries use their resolved POM declarations and embedded license/notice files.
The separate Apache 2.0 text and AndroidX native header notice are also bundled.

## Corresponding source and relinking

Ship the matching complete source archive beside any APK/AAB distributed to
users, including Gradle wrapper/build files, all native source, CMake files,
license texts and these instructions. Do not archive private signing material
or an operator's Space-Track download. For a committed release:

```bash
git archive --format=tar.gz --prefix=orbitscope-source/ --output=/path/outside/repository/orbitscope-source.tar.gz HEAD
```

Record the source commit and binary SHA-256 hashes beside the archive. Obtain
the documented JDK 17, SDK 36, Build Tools 36, NDK 27.0.12077973 and CMake
versions from Android's SDK Manager. Gradle downloads the exact Maven versions.
The Gradle wrapper verifies its distribution checksum.

The Git archive covers this repository's app and native source. For a distributed
binary, also supply the matching source archives of its packaged Maven dependencies:

```bash
python3 tools/package_dependency_sources.py /tmp/orbitscope-dependencies.json /path/outside/repository/dependency-sources
python3 tools/package_source_release.py /path/outside/repository/dependency-sources /path/outside/repository/orbitscope-complete-source.tar.gz
```

All 69 source archives were downloaded successfully for this candidate from
Google Maven/Maven Central. Their manifest records official URLs, coordinates
and SHA-256 hashes. The packaging script checks completeness, source hashes
and a clean committed tree before combining app/native/dependency source.

To modify/relink statically linked libusb, edit the vendored libusb source or
replace it with a compatible modified version. Rebuild the complete app with
`./gradlew :app:assembleDebug` (or configure your own release signing key and
use `tools/build_release.py`). This recompiles the library and links the
receiver from its complete source. The app does not check a developer signature
or block modified builds. Android will require uninstalling an incompatible
existing signing identity, or choosing your own application ID, before installing
a self-signed replacement; save any local files/settings first.

For radio catalog updates only the *public* Ed25519 verification key is included
in a release. The upload/app-signing key is independent of that catalog key.
If altering catalog trust in your modified build, use your own provider/public
key through the documented Gradle properties.

Validate packaged alignment after rebuilding:

```bash
python3 tools/check_release.py app/build/outputs/apk/debug/app-debug.apk
```

The check covers all native dependencies; runtime tests are separately required.
Full source allows recipients to rebuild and relink the application rather
than relying on object files from a particular developer machine.
