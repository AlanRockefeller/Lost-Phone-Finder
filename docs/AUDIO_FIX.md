# Audio follow-up — 2026-10-01

This records the audio-fix debug build tested on the phone. The latest artifacts and completed lint fix are documented in [v0.1.0 release notes](RELEASE_0.1.0.md); the APK path below has since been rebuilt, while an unchanged copy of the tested APK is preserved in the release directory.

The user reports no sound when tracking and clarifies that sound never started. No phone logs or replacement-APK audio test were available, so the specific cause on the user's device remains unconfirmed.

## Defects addressed

The streaming AudioTrack previously received only a chirp's PCM, then the worker blocked waiting for another observation. Android requires enough buffered audio to start/restart playback after an underrun; short, sparse target chirps can remain buffered. The worker now polls for a tone and writes 10 ms silence blocks while idle or muted, with blocking writes pacing the stream. Tone pitch, duration, envelopes, queue bounds and stale-tone limits are preserved. The AudioTrack is still created once per search and released on shutdown.

The output previously used USAGE_ASSISTANCE_SONIFICATION, which maps to system/UI audio and may be muted independently of media volume. It now uses USAGE_MEDIA with CONTENT_TYPE_SONIFICATION. MainActivity directs hardware volume buttons to STREAM_MUSIC. No system volume is changed automatically. Existing application chirp/discovery settings and target/global mutes remain respected. Tracking an existing device still produces regular RSSI chirps only for fresh results from that address.

References: [AudioTrack playback and underruns](https://developer.android.com/reference/android/media/AudioTrack#play()), [Android AudioAttributes volume mapping](https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/AudioAttributes.java).

## Files changed

- `app/src/main/java/org/blefinder/audio/ChirpEngine.kt`: continuous stream feeding, media routing, injectable PCM output for hardware-free regression tests.
- `app/src/main/java/org/blefinder/MainActivity.kt`: media volume button routing.
- `app/src/test/java/org/blefinder/ChirpEngineTest.kt`: three tests for startup/idle isolated chirps, mute/unmute stream continuity and media volume mapping.
- `app/src/test/java/org/blefinder/StorageTest.kt`: tracking an existing transmitter retains its audio callbacks and suppresses others, while retaining all stored observations.
- `README.md`, `docs/VERIFICATION.md`, this record: updated behavior and verification.

## Verification

Normal Gradle execution with the original field-test debug key:

```sh
./gradlew --offline --no-daemon -PfieldTestKeystore=build/offline-apk/debug.keystore \
  testDebugUnitTest testReleaseUnitTest assembleDebug :app:exportDebugRuntimeArtifacts
python3 tools/verify-apk-runtime.py app/build/outputs/apk/debug/app-debug.apk \
  --runtime-artifacts app/build/reports/debug-runtime-artifacts.txt
```

- **104 tests passed**, 52 in each variant: 41 core, 8 storage/repository, 3 audio. Zero errors, failures or skips. Log: `build/gradle-audio-fix.log`; results: `app/build/test-results/`.
- Audio tests use a controlled PCM sink with deterministic write advances; they verify samples and continuous output without relying on physical speakers. They do not prove actual device audibility.
- Final APK runtime audit passed: all 12,137 runtime classes across 67 selected artifacts; both futures classes remain defined. Log: `build/apk-runtime-audit.log`.
- APK signature verification and 16 KB native-library alignment verification passed. Original signing certificate retained. Log: `build/apk-signature-verification.log`.
- Full lint still reports the existing API-27 navigation-bar style attribute in API-26 resources; no audio-source lint errors. Seven existing warnings remain. Log: `build/gradle-audio-lint.log`.

APK: `app/build/outputs/apk/debug/app-debug.apk` (12,332,359 bytes).

SHA-256: `c76b35b5045e1fdb8fd71f2a5bf405a6c166ed7a1e1dcff5eff578a00a6db895`

On the phone, install over the existing debug app, raise **media volume**, enable RSSI chirps in Settings, and ensure the target/global audio are unmuted. Track a transmitter whose received-result count continues to increase. A selected address with no new advertisements produces no chirps. No on-device audio test was performed in this session.
