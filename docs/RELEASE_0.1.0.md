# BLE Search v0.1.0

Prepared 2026-10-01. Application ID `org.blefinder`, version code 1, version 0.1.0. Android 8/API 26 minimum; target API 37. Local source tag: `v0.1.0`. No remote release was published.

## Behavior

Offline BLE discovery, target tracking with RSSI chirps, baseline/session/persistent muting, optional GPS logging, retained local sessions, and JSON/CSV export. No account, network permission or backend. A powered phone must advertise BLE to be detected; RSSI does not establish distance or bearing.

The first physical-phone test exposed the fallback packager's missing ListenableFuture and bundled Emoji2 classes. APKs now use normal Gradle runtime resolution, with audits of the final DEX definitions. Audio now uses media volume and continuously feeds silence between sparse chirps so a lone result can play. The API-27 navigation-bar theme attribute is scoped to API-27 resources; API-26 uses the shared base theme.

## Acceptance and verification

The user reported that tracking/audio, muting, persistence, exports and the suggested long-run checks worked after the audio fix. This is user-reported acceptance of the debug build, not an independently observed or multi-device test. See [field-test record](FIELD_TEST.md). Phone model and OS version were not supplied. The newly signed release APK has not yet been installed on a phone.

Final verification reran all tasks with normal Gradle:

- 104 tests passed: 52 debug and 52 release; zero failures/errors/skips.
- Full debug and release lint passed with zero errors and seven existing warnings each (integer constants, backup configuration and KTX suggestions).
- Signed debug and release builds succeeded.
- Both APKs define all 12,137 runtime classes from 67 resolved artifacts, including concurrent-futures and ListenableFuture.
- Both APK signatures and 16 KB native-library alignment passed.
- Release binary inspection confirms it is not debuggable, has no internet permission, contains the rejecting simulation factory and lacks the debug simulator's coroutine class and synthetic payload markers.

Logs and runtime audits are retained in `build/releases/v0.1.0/`; Gradle test results and lint reports are under `app/build/`.

## Artifacts

- Signed release: `build/releases/v0.1.0/ble-search-0.1.0-release.apk` (8,751,644 bytes). SHA-256: `fb711ea063f0dd521a80d27c705459379447a0a299f7498edb2e2ffd0f5f303d`.
- Fresh signed debug: `build/releases/v0.1.0/ble-search-0.1.0-debug.apk`. SHA-256: `3aaca8b5ca85a2472aebc12110a61fa2052d551ce5136e8c363c3d52718e0389`.
- Unchanged phone-tested reference: `build/releases/v0.1.0/ble-search-0.1.0-field-tested-debug.apk`. SHA-256: `c76b35b5045e1fdb8fd71f2a5bf405a6c166ed7a1e1dcff5eff578a00a6db895`.
- Source archive: `build/releases/v0.1.0/ble-search-0.1.0-source.tar.gz`, created from the local tag. Private signing files, local SDK configuration and build outputs are excluded.
- `SHA256SUMS` and `release-manifest.json` identify the final artifacts and tagged commit.

The workspace initially had no Git repository. Its local repository was initialized for this release, with the completed source recorded in the tagged initial commit. No previous commit history or remote repository was available.

## Signing and installation

A dedicated RSA-3072 release key was created locally with a 10,000-day certificate. Certificate SHA-256: `b04a1bdad2e9476453fed9b14ebeb4c163faf1b8a9ece2de950610e62757e709`. Future release APKs must use this same key and increase `versionCode`.

Private files are `.release-signing/ble-search-release.jks` and `.release-signing/release.properties`; directory permissions are 700 and files are 600. **Back up both securely outside this workspace before distributing the release.** The properties file contains the randomly generated signing passwords. Neither private file is included in Git or source archives.

Gradle loads `.release-signing/release.properties` locally. An external properties file can be selected with `-PreleaseSigningProperties=/private/path/release.properties`; supply `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without a properties file, builds remain unsigned (as in CI). Explicitly providing a missing properties file fails the build.

The permanent release certificate differs from the phone-tested debug certificate. **Export saved sessions before switching:** the release cannot install over the debug app, and uninstalling/clearing the debug app removes local data. After exporting, uninstall the debug app and install the release. Session import is not implemented; the exported files remain an external record. Launch the release and check permissions, a real scan, tracking audio, exports and Stop before distributing it.

## Remaining limits

Seven lint warnings remain. Release installation and physical behavior on other devices/Android versions are unverified. GPS and advanced permission/background/audio-routing edge cases are not individually established by the general field confirmation. Android/OEM scan throttling, screen-off pauses, rotating addresses and radio interference still apply. Neither simulation nor JVM tests prove radio coverage. See the README and field checklist for the full limits.
