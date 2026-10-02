# Verification record: 2026-10-01 packaging fix (historical)

This page preserves the earlier packaging investigation and the historical [v0.1.0 verification record](RELEASE_0.1.0.md). Its signing guidance does not apply to current APKs. Current debug and release field-test builds share the existing private certificate described in [v0.1.4 signing notes](SIGNING_0.1.4.md).

At the time of that investigation, the signed debug APK at `app/build/outputs/apk/debug/app-debug.apk` included the later [audio fix](AUDIO_FIX.md). The packaging-only APK described below was 12,224,747 bytes and has been superseded at that path.

SHA-256: `1d7b3cf853862eaa861b898d02b2a841967b64abdf1d3a88d6008f6f5523948f`

## Confirmed runtime crash and root cause

The first physical-phone test crashed in AndroidX ProfileInstaller 1.4.0. The earlier fallback packager under `build/verification-tools/package-ble.py` scanned cached artifacts and selected the numerically highest version rather than resolving the project's dependency graph. It packaged `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava`, an intentionally empty placeholder used when full Guava supplies the interface, without packaging full Guava.

Inspection of the old APK's DEX class-definition tables confirms that `androidx.concurrent.futures.AbstractResolvableFuture` **was present**, but `com.google.common.util.concurrent.ListenableFuture` was absent. AbstractResolvableFuture implements that interface, so Android could not load it and reported the nested class-resolution failures. This was a packaging defect, not a BLE or application exception-handling defect.

The cache scan also ignored AAR `libs/*.jar` entries. The old APK lacked Emoji2's bundled `androidx.emoji2.text.flatbuffer` classes. An audit against the resolved runtime graph rejects the old APK; three additional differences in AndroidX Core are attributable to the fallback selecting a different Core version, rather than proof of another omitted dependency.

## Fix and chosen build path

Gradle now runs with JDK 21 and approved access to its cache/local IPC and missing dependency downloads. The replacement APK was produced by **normal AGP `assembleDebug`**, not by the fallback tool. ProfileInstaller remains enabled. Its published dependency graph resolves concurrent-futures **1.1.0**, ListenableFuture **1.0**, and startup-runtime **1.1.1**, together with the rest of the application's complete runtime graph. No forced dependency versions, full Guava runtime, initializer removal or exception suppression were needed.

`app/build.gradle.kts` enables release host unit tests using AGP's host-test API and exports the actual selected `debugRuntimeClasspath` artifact files. The optional `fieldTestKeystore` property reuses the original fallback debug key; otherwise Gradle retains its standard debug signing configuration. No BLE, service, data or UI source was changed.

`tools/verify-apk-runtime.py` reads DEX class-definition tables and checks all class definitions in every selected runtime JAR and AAR, including embedded AAR JARs. It explicitly requires ProfileInstaller, AbstractResolvableFuture, ResolvableFuture, ListenableFuture and Startup's provider. It is for non-minified debug APKs. CI performs this audit after assembling, before its separate lint step. The unsafe generated fallback packager is retired; do not run it.

## Executed checks

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew --no-daemon \
  -PfieldTestKeystore=build/offline-apk/debug.keystore \
  testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease \
  :app:exportDebugRuntimeArtifacts
python3 tools/verify-apk-runtime.py app/build/outputs/apk/debug/app-debug.apk \
  --runtime-artifacts app/build/reports/debug-runtime-artifacts.txt
```

- **Debug: 48 tests passed** (41 CoreTest, 7 Robolectric StorageTest), zero failures/errors/skips. Debug tests ran through normal Gradle before the final assembly and remained up to date with identical application/test sources in the final build.
- **Release: 48 tests passed** (41 CoreTest, 7 StorageTest), zero failures/errors/skips. Both variants' XML results are under `app/build/test-results/`.
- **Normal Gradle build succeeded**: Room KSP processing, compilation, dependency duplicate-class checks, manifest/resource merging, DEX generation, debug packaging, and unsigned release assembly. Log: `build/gradle-packaging-fix.log`.
- **APK runtime audit passed**: 67 resolved artifacts, 12,137 runtime classes, 7 DEX files, 16,941 total APK class definitions. Every checked runtime class is defined in the final APK. Log: `build/apk-runtime-audit.log`; selected artifacts: `app/build/reports/debug-runtime-artifacts.txt`.
- **Negative check**: the same audit rejects the original fallback `build/offline-apk/aligned.apk`, including missing ListenableFuture and Emoji2 embedded classes. Log: `build/old-apk-runtime-audit.log`.
- **APK signature verified**, v2 scheme, RSA 2048; certificate SHA-256 `29235354f935b7dbd62093be213712dec8b426e914fa4ed1134ee3bfa6d4d886` matches the original field-test keystore. Log: `build/apk-signature-verification.log`. APK alignment also passed `zipalign -c -P 16 4`.
- **Full Gradle lintDebug ran and failed** with one pre-existing error and seven warnings. `app/src/main/res/values/styles.xml` places `android:windowLightNavigationBar` (API 27) in the API-26 base style. Warnings concern integer constants, backup configuration and KTX suggestions. These are left unchanged to preserve this crash fix's scope. Report: `app/build/reports/lint-results-debug.html`; final log: `build/gradle-lint-fix.log`. The earlier direct lint subset did not detect this resource issue.
- Release vital lint and unsigned release assembly succeeded. No release signing key was provided.

## Remaining limits

The replacement APK has no known dependency omissions caused by the custom cache-scanning build, because that build path is no longer used. Restricted environments still need permission for Gradle cache writes, local IPC and initial downloads; without it, stop rather than silently shipping another cache-scanned APK. The original generated scripts remain as local historical evidence, not a supported build interface.

The class audit proves presence of the resolved runtime classes, not every possible reflective/platform linkage or on-device behavior. No replacement APK installation, launch or radio test was performed in this session. The user-provided physical-phone crash establishes the old APK's failure; repeat launch and the [field checklist](FIELD_TEST.md) with the replacement APK. Full lint remains blocked by the existing API-26 style error. No CI run was initiated.
