# Screen-off discovery and optional loudspeaker — v0.1.1

The user requested pings with the screen off and specifically chose discovery of brand-new advertisers while locked, rather than limiting scanning to a selected or previously discovered address. This build keeps one broad scan in both Scan and Track modes. Track continues to select which address is audible in the repository; other/new addresses remain eligible for storage.

## Scan configuration

Android suspends unfiltered scans on screen-off. A singleton empty ScanFilter is also classified as unfiltered by current AOSP. The scanner now supplies an inclusive OR list: a concrete Battery Service UUID filter (`0000180f-0000-1000-8000-00805f9b34fb`) plus an unconstrained filter. The latter preserves arbitrary new-device matching, including advertisements with no name, UUID or manufacturer data. There is no whitelist of known addresses. Current AOSP ScanManager classifies a scan as filtered when at least one branch has a nonempty field; the concrete UUID branch provides that field. Filters are ORed, so the UUID does not restrict overall discovery.

This is a compatibility workaround based on the inspected AOSP implementation, rather than a public Android guarantee of screen-off broad discovery. OEMs and Bluetooth module updates can implement stricter classification or power policies. The API-35 regression test verifies public filter matching for unknown addresses without requiring advertisement fields, and checks that the concrete branch differs from an empty filter. It does not execute the phone's Bluetooth service/controller or prove its screen-off behavior. No periodic scanner restarts, screen-on tricks, root access or hidden APIs are used.

Sources: [BluetoothLeScanner screen-off behavior](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner), [AOSP filter classification and scan suspension](https://chromium.googlesource.com/aosp/platform/packages/modules/Bluetooth/+/e145a3ec0711471c0d9242b629697261c9dd7d80/android/app/src/com/android/bluetooth/le_scan/ScanManager.java).

## CPU and service lifetime

A partial CPU wake lock is acquired only after foreground-service promotion for the user-started search. It has a ten-minute timeout, renewed every five minutes while the service scope is active. Stop, Bluetooth/scan/start failures routed through service shutdown, onDestroy, and service-scope cancellation release it. It never holds the display on. The existing keep-display-awake setting is separate. Active searches consume additional battery even with audio muted, because scanning and logging remain active; press Stop when finished.

The foreground service retains its existing location/connected-device types and starts from the visible app. The only new permission is WAKE_LOCK, which has no runtime prompt. No background-location or internet permission was added. Audio retains the media-volume routing and continuous silent PCM feeding added in the previous fix. Partial wake locks cannot override all Doze/OEM restrictions; keep the existing battery-readiness checks visible.

Sources: [Wake-lock acquisition/release](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/set), [Doze restrictions](https://developer.android.com/training/monitoring-device-state/doze-standby).

## Verification and APKs

The user's additional request for optional stronger speaker output is included in this same v0.1.1 build. **Settings → Loudspeaker mode** is off by default and saved with preferences. The renderer requests this AudioTrack's built-in speaker using `setPreferredDevice`, retaining media usage. It does not change system volume or global communication routing. Turning it off clears the preferred device, and shutdown releases the track and its preference.

When the setting is enabled and `getRoutedDevice()` confirms the built-in speaker, PCM peak amplitude rises from 16,000 to 32,767 before multiplying by the app volume and envelope. This is about twice the digital amplitude, with samples bounded to avoid integer overflow/clipping. An unavailable or rejected speaker request reports an error while scanning/audio continue; an unconfirmed headphone/Bluetooth route retains ordinary gain. Android can reject or override a preferred route, and acoustic output depends on the phone's speaker/DSP and media volume.

The audio tests cover live mode changes, stronger PCM, clearing the preference when disabled, rejected-route fallback, and volume/mute behavior. The existing persistence test also verifies that the loudspeaker setting survives preferences reload. Existing settings JSON without the field defaults to normal mode. No on-device speaker-routing/loudness test was performed.

Source: [AudioTrack routing API](https://developer.android.com/reference/android/media/AudioTrack#setPreferredDevice(android.media.AudioDeviceInfo)).

Normal Gradle `testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease` and both runtime-artifact exports succeeded. Version code is 2, version name 0.1.1.

- **116 tests passed**, 58 per variant: 41 core, 8 storage/repository, 6 audio, 1 inclusive scan-filter, 2 CPU-lock lifecycle tests. No failures/errors/skips.
- Full debug and release lint: zero errors, seven existing warnings per variant.
- Both APKs passed the runtime class audit: all 12,137 classes from 67 selected artifacts, including both futures classes.
- Both signatures and 16 KB alignment passed; debug and release certificates match their respective previous builds.
- Manifest inspection confirmed v0.1.1, the new WAKE_LOCK permission, and no internet permission.

Artifacts and logs are in `build/releases/v0.1.1/`:

- `ble-search-0.1.1-debug.apk`: SHA-256 `ca35febb986bb5d9bb62085bca93bad9b4cf21d26dd02535a1bb3447fc1055ac`.
- `ble-search-0.1.1-release.apk`: SHA-256 `d79697d08d3cff9c8bf9afcae9c82b8f023e2aec15b87910f7fc80b8150f0c50`.
- `gradle-verification.log`, runtime audits, signature logs, manifest badging, `SHA256SUMS` and `release-manifest.json`.

Install the variant matching the app already installed to retain local sessions. The v0.1.0 tag and its phone-tested reference APK remain unchanged. This new behavior has not been confirmed on a physical phone; no v0.1.1 field-acceptance tag was created.

## Phone acceptance

Start Search while visible, confirm ordinary pings, then lock the phone in Scan mode. Wait several minutes, introduce a new advertiser, and listen for its discovery ping. Unlock and check that its address/count was saved. Repeat in Track mode: the target keeps sounding while all other/new results still log. Continue beyond ten minutes to exercise wake-lock renewal. Stop from the notification and confirm silence; also test Bluetooth-off and restart cleanup. Use media volume, and record phone/OS, battery mode and audio route. If results pause, check the existing battery settings and document the model-specific limitation rather than claiming universal background coverage.
