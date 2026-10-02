# BLE Search v0.1

An offline Android instrument for finding BLE transmitters outdoors. Kotlin, Jetpack Compose, generated per-result audio, target tracking, baseline muting, local sessions, optional GPS and JSON/CSV export. No account, network permission, telemetry or backend.

A lost phone must be powered and advertising BLE to appear. This is not a Find My / Find Hub client. Scan results received are **not** every packet transmitted over the air. RSSI means relative signal strength; it is never presented as distance or bearing.

## Build and run

- Android Studio supporting AGP 9.4.1; JDK 21 recommended (Java/Kotlin bytecode targets 17).
- Gradle 9.8.0 wrapper, Kotlin/Compose compiler 2.4.20, KSP 2.3.12.
- `minSdk 26` (Android 8), `compileSdk 37`, `targetSdk 37` (Android 17).
- Install Android SDK Platform 37.0 and Build Tools 36.0.0 or the newer version requested by AGP. Set `ANDROID_HOME` or let Android Studio generate `local.properties` with your SDK path. The local file is ignored by version control.

```sh
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease :app:exportDebugRuntimeArtifacts :app:exportReleaseRuntimeArtifacts
python3 tools/verify-apk-runtime.py app/build/outputs/apk/debug/app-debug.apk --runtime-artifacts app/build/reports/debug-runtime-artifacts.txt
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open this directory in Android Studio, sync, select `app`, and run. Release builds use `.release-signing/release.properties` if present, or a properties file supplied with `-PreleaseSigningProperties=/private/path/release.properties`; without one, release APKs are unsigned. CI builds without a private signing key. First-time dependency downloads require internet **on the development machine**; the app itself operates offline. CI runs builds, both variants' JVM tests, runtime APK audits and lint; the workflow has not been dispatched from this workspace.

The local v0.1.0 release has a dedicated permanent signing key. Keep `.release-signing/` private and back up both its keystore and properties file securely; they are excluded from Git and release source archives. See [v0.1.0 release notes](docs/RELEASE_0.1.0.md) for verification, artifacts and installation instructions. Future release builds must reuse this certificate to update existing release installations. A release APK cannot update the debug installation because their certificates differ; export saved sessions before switching installations.

See [verification status](docs/VERIFICATION.md) for exactly what was executed in the development environment, including its Gradle sandbox restriction. Do not equate a compiler check or unit test with physical radio testing.

Build APKs through Gradle so runtime dependencies, Android resources, manifests and Java resources are resolved together. The earlier cache-scanning fallback packager is unsafe: it selected an empty ListenableFuture placeholder without Guava, causing a confirmed ProfileInstaller startup crash. Do not use `build/verification-tools/package-ble.py`. The APK audit above checks class definitions for every resolved runtime JAR and AAR, including embedded AAR JARs; it is intended for non-minified debug APKs. ProfileInstaller remains enabled with its normal transitive dependencies. To update the original field-test installation with the same debug certificate, add `-PfieldTestKeystore=build/offline-apk/debug.keystore` to the Gradle command on the machine holding that key. Otherwise Gradle uses its standard debug key.

## Workflow

1. Start Search, read the permission explanation and grant Nearby devices and precise location. Enable Bluetooth and system location services. GPS recording is optional and defaults off.
2. Keep the screen on. The default dark field interface prevents display sleep during the explicit search. Use readiness checks to inspect battery settings and permissions.
3. Each unmuted received result can sound a 30 ms chirp. Higher RSSI produces higher pitch. A new address gets a distinct two-note notification instead of its first chirp.
4. Tap a transmitter to enter Target mode: only that address sounds. Read current/smoothed RSSI, extrema, average, result count, rate and age. Raw pitch continues to follow individual results. Return to Scan or stop directly from Target.
5. To exclude search-party devices, gather them nearby and run Baseline (30 seconds by default). Addresses detected during the countdown become session-muted. Review each result, unmute false exclusions, or explicitly choose **Always mute**.
6. Stop and resume the same session as needed. **New session** archives the current session and resets the live list/session mutes. Prior observations remain under Sessions. Clearing target statistics resets the tracking view only; it does not erase the session log.
7. Export a session or target using the system document picker. Choose local storage for a fully offline export. Sharing exported data is the user's choice.

Debug builds offer **Debug simulation** while stopped. It emits three synthetic transmitters, then introduces a fourth, with changing RSSI and representative names/manufacturer/service data. Simulated records and sessions are conspicuously labeled and exported as simulated. The real simulator exists **only** in `src/debug`; `src/release` rejects its construction and hides its controls. Simulation uses the same service, repository, sound, baseline, tracking and export paths; the foreground service still requests its normal permissions.

## Project structure

```text
app/src/main/java/org/blefinder/
  core/       Android-independent models, advertisement parser, RSSI math, mute rules, CSV
  ble/        BluetoothLeScanner adapter and scan metadata capture
  audio/      reusable streaming AudioTrack and bounded chirp queue
  data/       Room database, serialized repository, preferences, streaming export
  service/    user-started foreground service, GPS logger, readiness checks
  ui/         Compose search/list, target/details, sessions and settings
  MainActivity.kt / SearchApplication.kt   lifecycle, document picker, manual wiring
app/src/debug/     synthetic BLE source
app/src/release/   simulator rejection implementation
app/src/test/      JVM logic and Robolectric storage integration tests
app/schemas/       checked-in Room schema v1
```

No dependency injection framework or navigation framework. One application repository survives activity recreation; the service owns scanner/audio/GPS lifetimes. Commands are serialized on an IO coroutine; UI snapshots update at 5 Hz. Every callback is queued for storage, including muted and non-target results. UI rendering and audio overload handling never deliberately discard observations. Each observation and its device summary commit in one transaction. A database failure stops searching and exposes an error instead of silently claiming to log.

## Libraries

| Library | Purpose |
| --- | --- |
| Compose BOM 2026.09.00 / Material 3 | Native dark, high-contrast UI |
| Activity Compose 1.13.0 | Activity, permission/document launchers, Compose host |
| Lifecycle runtime Compose 2.11.0 | Lifecycle-aware Flow observation |
| Coroutines Android 1.11.0 | Service jobs, sequential repository commands and state flows |
| Room 2.8.5 + KSP 2.3.12 | SQLite persistence and checked queries/generated DAO |
| kotlinx.serialization JSON 1.11.0 | Typed lossless observation storage and JSON exports |
| JUnit 4.13.2, Robolectric 4.17, AndroidX test core 1.7.0 | Hardware-free logic and real SQLite/Android preference integration tests |

Small settings and persistent address sets use platform **SharedPreferences**, with checked commits on IO. Room holds the structured search data. This avoids an additional preference-storage runtime dependency. GPS uses `LocationManager`, not Play Services. Audio and scanning use platform APIs.

## Permissions and foreground service

| Permission | Reason / request timing |
| --- | --- |
| `BLUETOOTH_SCAN` (12+) | Receive advertisements after the Start explanation. **No `neverForLocation` assertion**, since RSSI is used for proximity. |
| `BLUETOOTH_CONNECT` (12+) | Inspect adapter readiness and Android device names/address metadata. No GATT connection/pairing is attempted. |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (through API 30) | Legacy scanning support; install-time permissions. |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | Requested together for Android's precise-location choice. Fine permission is required for location-related BLE scanning; also enables optional GPS logging. |
| `POST_NOTIFICATIONS` (13+) | Visible search notification and Stop action. Denial does not prohibit the foreground service; Android may show it only in its active-apps UI. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Explicit, user-started proximity search. Location type from API 29; connected-device type additionally activated from API 34. |

No background location, internet, external-storage, microphone, boot receiver or wake-lock permission. The service is non-exported, uses `START_NOT_STICKY`, immediately promotes itself, and does not auto-start after reboot or process death. It stops scanner, GPS and audio on shutdown. Bluetooth disabling, missing permissions, start restrictions and scanner error codes are surfaced. Data committed before unexpected process termination remains exportable, with previously active sessions marked interrupted on next process start.

Readiness shows Bluetooth, Nearby devices/fine location permissions, location services, battery optimization, Battery Saver, notifications, service and GPS configuration. Battery Saver is prominent. A button opens battery optimization settings; the app never claims exemption guarantees continuous scanning.

## BLE configuration and identity

- `SCAN_MODE_LOW_LATENCY`, `CALLBACK_TYPE_ALL_MATCHES`, report delay 0; unfiltered broad scanning in both Scan and Target mode.
- Aggressive matching / maximum hardware matches. Extended advertisements and all supported PHYs where the adapter supports them; legacy-only adapters use legacy scan configuration.
- Android's active scan default; explicit `SCAN_TYPE_ACTIVE` on API 37+, guarded because the setter was introduced in 36.1.
- Capture address, Android address type on API 35+, cached device name, local name, raw RSSI, wall-clock event/receipt times and monotonic controller timestamp. Capture TX powers, flags, service and solicitation UUIDs, manufacturer/service data, connectability, legacy flag, PHYs, SID, periodic interval, data status and callback type. Android's AD map is captured on API 33+.
- The independently testable parser preserves ordered/repeated AD structures and malformed/truncated raw bytes. Manufacturer identifiers use a small offline company-name table. Unknown companies/data remain visible in hex. No speculative vendor-specific payload decoder is used.
- Addresses are concrete session keys, **not physical identities**. Public/random/anonymous types are shown only when Android exposes them; older Android versions say unknown. No MAC-bit heuristic pretends to identify the address type. Random addresses may rotate. Persistent mutes match exact addresses and may miss a rotated address. An unused nullable `probablePhysicalDeviceId` field leaves a future grouping extension without merging devices today. Anonymous results may share the platform's placeholder address; no individual anonymous-device identity is claimed.

## Audio and statistics

`PitchMapping.points` holds the requested tuning points from -100 dBm/250 Hz through -30 dBm/3200 Hz. Piecewise linear interpolation is continuous; settings remap the endpoints and clamp RSSI. Default chirps are 30 ms, adjustable from 20–50 ms. Unknown Android RSSI 127 is excluded from signal aggregates/normal chirps but its observation is logged.

One audio worker holds one mono 48 kHz PCM `AudioTrack` in low-latency streaming mode and reuses a sample buffer. It feeds silence between chirps so a single sparse target result can play without waiting for other results to fill the streaming buffer. Short attack/release ramps soften clicks. New devices sound two ascending notes over 80 ms. There are separate chirp/discovery toggles, a master amplitude setting, a session-wide audio mute, and per-address mutes. Chirps use media audio, and the app's volume buttons control media volume. System audio routing/volume still apply.

The sound queue holds at most 8 events and drops tones older than 200 ms; overload favors recent feedback, and discovery events can displace pending ordinary tones. Observations are logged independently. At ordinary advertisement rates each result can sound; very dense environments are intentionally not an unlimited audio backlog. Audio failure is reported while scanning/logging continue.

Statistics track count, valid-RSSI count, current/min/max/online mean/EMA, first/last time and a sliding five-second result-rate window. Rate is results within `(now - 5 s, now] / 5`, including the initial window, and decays to zero during silence. EMA defaults to 0.25; settings tune it without affecting raw audio. Resetting a target view does not delete recorded data or rewrite session-wide statistics.

## Baseline and mutes

Baseline membership uses callback receipt time within a monotonic start/end window. Completion adds only session mutes, including addresses that disappeared before completion. Cancel or Stop before completion applies no new baseline mutes. Existing mutes survive cancellation. Review provides **Mute for this session**, **Always mute**, and removal of both mute types. Session-wide unmute leaves persistent mutes alone. Persistent mutes survive process recreation; session mutes last through stop/resume and clear with New session. Muted devices remain logged/visible unless filtered, and produce neither ordinary nor discovery sounds.

## Storage schema (Room v1)

| Table | Contents |
| --- | --- |
| `sessions` | UUID, start/end timestamps, active/stopped/archived/interrupted status, simulation marker, initial settings JSON |
| `devices` | `(sessionId, address)` key; latest observation, retained display name/company guess and aggregate statistics JSON |
| `observations` | Autoincrement ID, session/address/time/RSSI query columns; complete typed observation JSON including raw bytes encoded as hex, decoded fields, metadata, target/mute/simulation state and optional GPS fix |
| `locations` | Every accepted GPS fix independently of BLE arrival, with lat/lon/accuracy/wall time/monotonic time |
| `events` | Start/stop, settings, target changes/resets, mute changes and baseline history |

Foreign keys relate data to sessions, with indexes for session/address/result paging. Session updates use upsert (not replacement) so resume cannot cascade-delete old results. Preferences store validated settings and persistent address mutes. No observations are sent anywhere. App backup is disabled.

GPS is optional and must be changed while stopped. GPS-only updates are requested every second with zero minimum displacement; actual cadence depends on Android/hardware. Results attach only a preceding fix no more than 30 seconds old, retaining its own timestamp and accuracy. Missing/stale GPS stays null, not a fabricated location. GPS logging requires system location services and is suppressed for simulation.

## Exports

System `ACTION_CREATE_DOCUMENT`; no broad file-storage permissions. Active-session exports take a transactionally consistent snapshot of device metadata and maximum result/location/event IDs, then stream results in pages of 500. Scanning can continue, and newer results go into later exports.

- **JSON schemaVersion 1**: session, target selection (null for complete sessions), device metadata/statistics, event history, observations and independent GPS fixes. Raw data remains alongside decoded data; unknown Android values retain numeric representations. Times are Unix milliseconds except explicitly named monotonic nanosecond/millisecond fields.
- **CSV UTF-8**: one result per row, CRLF, quoted and escaped fields: UTC event/receipt timestamps, address, RSSI, name, manufacturer IDs/names, TX power, address type, latitude/longitude/accuracy/fix timestamp, raw advertisement hex, target/mute/simulation state. Missing data is empty. Potential spreadsheet formulas in untrusted names receive a leading apostrophe. JSON preserves the original name.
- Target exports filter observations and device metadata to the address. Session event history and independent session GPS fixes remain included for context. Export snapshots use full-session statistics, even if the live target display was reset.

## Tests and field checks

`CoreTest` covers pitch anchors/interpolation/endpoints/clamping/monotonicity, EMA, extrema/mean/unknown RSSI, rate decay and equal timestamps, hex, known/unknown/repeated manufacturers, 16/32-bit service data, 128-bit UUID byte order, solicitation UUIDs, names/flags/TX power, malformed data and deterministic parser fuzz cases, baseline boundaries, session/persistent mutes, sorting/filtering, JSON round trips and CSV escaping/formula defense.

`StorageTest` uses Robolectric API 35 and generated Room implementations to verify persisted preferences, interrupted-session recovery, empty and multi-page exports, snapshot boundaries, GPS/events, target-only export, muted/non-target logging, audio eligibility and stop/resume/archive retention. No test claims radio coverage.

For a quick hardware-free check, use debug simulation and confirm discovery, raw tones, tracking, baseline review, muting, settings, saved sessions and both export formats. See [FIELD_TEST.md](docs/FIELD_TEST.md) for the physical-phone acceptance checklist.

## Known limits

Android can pause unfiltered scans when the display turns off and can throttle starts, results or background activity; OEM firmware adds variation. Keeping awake and disabling battery optimization improve conditions but do not guarantee coverage. The app intentionally does not restart scanning in a rapid loop. After scanner throttling, wait at least 30 seconds and retry manually.

Names and company IDs are advertised claims, not ownership or verified manufacturer identity. Manufacturer names cover a small table. No auto-clustering, encrypted-identifier resolution, AoA/AoD, maps, network lookups or vendor finder-network integration. Only the most recent 50 results per target are selectable in the detail UI; **all committed results** are exportable. Radio interference, body shielding, reflections, antenna orientation and phone model strongly affect RSSI. RSSI may remain stale during silence; age is displayed prominently.

Abrupt process death, power loss or storage exhaustion may prevent queued callbacks from being committed. There is no lossless over-the-air capture guarantee. Sessions currently have no in-app deletion UI; New session archives instead of destroying data. Android's Clear storage/uninstall removes local data. Export first if needed.

Implementation references: [Android BLE scanner and screen-off behavior](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner), [Bluetooth permission requirements](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [active scan setter availability](https://developer.android.com/reference/android/bluetooth/le/ScanSettings.Builder#setScanType(int)), and [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17).
