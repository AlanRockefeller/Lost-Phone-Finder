# Lost Phone Finder v0.1.5

Lost Phone Finder is a free app that helps you locate a lost device by picking up bluetooth (BLE) signals.  It is designed to be used to find a phone lost in the woods - probably wouldn't be very useful in a city since there will be a lot of bluetooth signals around.

A lost phone must be powered and advertising BLE to appear (iPhone 11+ and Pixel 8+ can transmit for a few hours after the battery runs down). This is not a Find My / Find Hub client. Scan results received are **not** every packet transmitted over the air due to hardware limitations.

Uses Kotlin, Jetpack Compose, generated per-result audio, target tracking, baseline muting, local sessions, optional GPS and JSON/CSV export. No account, network permission, telemetry or backend.


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

Open this directory in Android Studio, sync, select `app`, and run. Both build variants use the same private signing configuration from `.release-signing/release.properties` if present, or a properties file supplied with `-PreleaseSigningProperties=/private/path/release.properties`. Without one, debug uses the standard development key and release is unsigned. CI builds without a private signing key. First-time dependency downloads require internet **on the development machine**; the app itself operates offline. CI runs builds, both variants' JVM tests, runtime APK audits and lint.

The local debug and release APKs both use the original field-test/debug certificate (SHA-256 `29235354f935b7dbd62093be213712dec8b426e914fa4ed1134ee3bfa6d4d886`). You can update between these variants without uninstalling or losing saved sessions. Both variants use the same package ID and increasing version codes. Debug enables debugging and synthetic simulation; release disables both. The v0.1.0 through v0.1.3 release APKs used a different certificate, so an installation of one of those release APKs still requires a one-time reinstall to switch; export sessions first. The old release key is retained privately for compatibility builds. Keep `.release-signing/` and its backups private; never commit or upload any keystore or signing properties.

See [verification status](docs/VERIFICATION.md) for exactly what was executed in the development environment, including its Gradle sandbox restriction. Do not equate a compiler check or unit test with physical radio testing.

Build APKs through Gradle so runtime dependencies, Android resources, manifests and Java resources are resolved together. The earlier cache-scanning fallback packager is unsafe: it selected an empty ListenableFuture placeholder without Guava, causing a confirmed ProfileInstaller startup crash. Do not use `build/verification-tools/package-ble.py`. The APK audit above checks class definitions for every resolved runtime JAR and AAR, including embedded AAR JARs; it is intended for non-minified debug APKs. ProfileInstaller remains enabled with its normal transitive dependencies. To update the original field-test installation with the same debug certificate, add `-PfieldTestKeystore=build/offline-apk/debug.keystore` to the Gradle command on the machine holding that key. This override signs both variants with that certificate. Without any private configuration or override, Gradle uses its standard debug key and leaves release unsigned.

## Workflow

1. Start Search, read the permission explanation and grant Nearby devices and precise location. Enable Bluetooth and system location services. GPS recording is optional and defaults off.
2. Search is configured to continue scanning and pinging with the screen off, including discovery of new addresses. Start while the app is visible, then lock the phone. The keep-display-awake setting remains optional. Check media volume and power settings. See [screen-off implementation and verification](docs/SCREEN_OFF.md); Android/OEM restrictions can still affect results.
3. Each unmuted received result can sound a 30 ms chirp. Higher RSSI produces higher pitch. A new address gets a distinct two-note notification instead of its first chirp.
4. Tap a transmitter to enter Target mode: only that address sounds. Read current/smoothed RSSI, extrema, average, result count, rate and age. Raw pitch continues to follow individual results. Return to Scan or stop directly from Target.
5. To exclude search-party devices, gather them nearby and run Baseline (30 seconds by default). Addresses detected during the countdown become session-muted. Review each result, unmute false exclusions, or explicitly choose **Always mute**.
6. Stop and resume the same session as needed. **New session** archives the current session and resets the live list/session mutes. Prior observations remain under Sessions. Clearing target statistics resets the tracking view only; it does not erase the session log.
7. Export a session or target using the system document picker. Choose local storage for a fully offline export. Sharing exported data is the user's choice.

Debug builds offer **Debug simulation** while stopped. It emits three synthetic transmitters, then introduces a fourth, with changing RSSI and representative names/manufacturer/service data. Simulated records and sessions are labeled and exported as simulated. The real simulator exists **only** in `src/debug`; `src/release` rejects its construction and hides its controls. Simulation uses the same service, repository, sound, baseline, tracking and export paths; the foreground service still requests its normal permissions.

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

One repository survives activity recreation. The service runs the scanner, audio and GPS. A single IO coroutine processes commands, and the UI updates five times per second. Scan results, including muted and non-target results, are stored until a safety limit stops the search. Each observation and its device summary commit in one transaction. Logging limits and database failures stop searching with an explanation. Audio overload only affects chirps.

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
| `WAKE_LOCK` | Keeps the CPU running for scan callbacks, audio and storage during the explicit active search; released on Stop or service teardown. Does not keep the display on. |

No background location, internet, external-storage, microphone or boot receiver permission. The service is non-exported, uses `START_NOT_STICKY`, immediately promotes itself, and does not auto-start after reboot or process death. It stops scanner, GPS and audio and releases its CPU wake lock on shutdown. The lock uses a ten-minute timeout renewed every five minutes only during a user-started search; a cancelled service scope also releases it. Bluetooth disabling, missing permissions, start restrictions and scanner error codes are surfaced. Data committed before unexpected process termination remains exportable, with previously active sessions marked interrupted on next process start.

Readiness shows Bluetooth, Nearby devices/fine location permissions, location services, battery optimization, Battery Saver, notifications, service and GPS configuration. Battery Saver is prominent. A button opens battery optimization settings; the app never claims exemption guarantees continuous scanning.

## BLE configuration and identity

- `SCAN_MODE_LOW_LATENCY`, `CALLBACK_TYPE_ALL_MATCHES`, report delay 0; broad scanning in both Scan and Target mode. Screen-off discovery uses inclusive OR filters: one concrete Battery Service UUID branch and an unconstrained branch. The unconstrained branch preserves discovery of arbitrary new advertisers; the concrete branch satisfies current AOSP's nonempty-filter classification. This is an Android compatibility workaround, not a promise for every OS/OEM implementation.
- Aggressive matching / maximum hardware matches. Extended advertisements and all supported PHYs where the adapter supports them; legacy-only adapters use legacy scan configuration.
- Android's active scan default; explicit `SCAN_TYPE_ACTIVE` on API 37+, guarded because the setter was introduced in 36.1.
- Capture address, Android address type on API 35+, cached device name, local name, raw RSSI, wall-clock event/receipt times and monotonic controller timestamp. Capture TX powers, flags, service and solicitation UUIDs, manufacturer/service data, connectability, legacy flag, PHYs, SID, periodic interval, data status and callback type. Android's AD map is captured on API 33+.
- The parser preserves ordered/repeated AD structures and malformed/truncated raw bytes. Manufacturer identifiers use a small offline company-name table. Unknown companies/data remain visible in hex. No speculative vendor-specific payload decoder is used.
- Addresses are concrete session keys, **not physical identities**. Public/random/anonymous types are shown only when Android exposes them; older Android versions say unknown. No MAC-bit heuristic pretends to identify the address type. Random addresses may rotate. Persistent mutes match exact addresses and may miss a rotated address. An unused nullable `probablePhysicalDeviceId` field leaves a future grouping extension without merging devices today. Anonymous results may share the platform's placeholder address; no individual anonymous-device identity is claimed.

## Audio and statistics

`PitchMapping.points` holds the requested tuning points from -100 dBm/250 Hz through -30 dBm/3200 Hz. Piecewise linear interpolation is continuous; settings remap the endpoints and clamp RSSI. Default chirps are 30 ms, adjustable from 20 to 50 ms. Unknown Android RSSI 127 is excluded from signal aggregates/normal chirps but its observation is logged.

One audio worker holds one mono 48 kHz PCM `AudioTrack` in low-latency streaming mode and reuses a sample buffer. It feeds silence between chirps so a single sparse target result can play without waiting for other results to fill the streaming buffer. Short attack/release ramps soften clicks. New devices sound two ascending notes over 80 ms. There are separate chirp/discovery toggles, a master amplitude setting, a session-wide audio mute, and per-address mutes. Chirps use media audio, and the app's volume buttons control media volume. System audio routing/volume still apply.

Optional **Loudspeaker mode** in Settings prefers the built-in speaker for this app's AudioTrack and enables full-scale PCM peaks on a confirmed speaker route (roughly twice the ordinary digital amplitude). It leaves the phone's media volume unchanged; adjust the app slider and media-volume buttons. Boosting is withheld on headphone/Bluetooth routes or when the speaker request is rejected. Turning the option off clears the track's preferred route. The setting is saved across restarts and can change during a search. It does not put the phone into a call/communication mode. Hardware loudness remains device-dependent.

The sound queue holds at most 8 events and drops tones older than 200 ms; overload favors recent feedback, and discovery events can displace pending ordinary tones. Observations are logged independently. At ordinary advertisement rates each result can sound; busy radio environments can produce more results than the speaker can play. Audio failure is reported while scanning/logging continue.

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

Android can pause unfiltered scans when the display turns off and can throttle starts, results or background activity; OEM firmware adds variation. The inclusive filter configuration and CPU wake lock support screen-off discovery and pings but cannot override Doze or all OEM restrictions. Active searches use more battery, even with the display off. Press Stop when finished. The app does not restart scanning when the screen or selected target changes. After scanner throttling, wait at least 30 seconds and retry manually.

Names and company IDs are advertised claims, not ownership or verified manufacturer identity. Manufacturer names cover a small table. No auto-clustering, encrypted-identifier resolution, AoA/AoD, maps, network lookups or vendor finder-network integration. Only the most recent 50 results per target are selectable in the detail UI; **all committed results** are exportable. Radio interference, body shielding, reflections, antenna orientation and phone model strongly affect RSSI. RSSI may remain stale during silence; age is displayed prominently.

Abrupt process death, power loss or storage exhaustion may prevent queued callbacks from being committed. There is no lossless over-the-air capture guarantee. Sessions currently have no in-app deletion UI; New session archives instead of destroying data. Android's Clear storage/uninstall removes local data. Export first if needed.

Implementation references: [Android BLE scanner and screen-off behavior](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner), [Bluetooth permission requirements](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [active scan setter availability](https://developer.android.com/reference/android/bluetooth/le/ScanSettings.Builder#setScanType(int)), and [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17).

## Recent-results interface (v0.1.2)

Search opens with an ID/address, RSSI (dBm), and received-age table, ordered by most recent receipt. Tap a row to track that address. Devices keeps full statistics and offers optional packet-profile buckets. Session tools and readiness are expandable. The top-right speaker button toggles global audio mute; loudspeaker mode adds a second wave.

Settings separates low/high alert frequencies (Hz) from the weak/strong signal levels (dBm) that reach those frequencies. A curve previews the existing pitch mapping. Sensitivity levels do not filter scan results or logs.

Packet profiles compare manufacturer IDs and payload lengths, service UUIDs and service-data lengths, solicitation UUIDs, and advertising structure types/lengths. They ignore the address, RSSI, timestamps and changing payload bytes. These are format buckets, potentially shared by many physical devices, not verified identities. Advertisements without manufacturer/service clues remain separate. Tracking and muting continue to use exact addresses. Buckets reflect each address's latest packet and can change when its advertisement changes; no grouping is persisted or written into exported identity fields.

To analyze rotating addresses, open Sessions and export the overnight session as JSON. It includes every stored observation and raw advertisement, so timing, payload changes and overlap can be compared rather than relying on the latest-packet view.

## Development workflow

All coding happens on `test`. Push changes to `test` and open a pull request into `main` for CodeRabbit review. Alan merges through GitHub; coding agents must not merge or push application changes directly to `main`. Keep review batches at no more than 100 changed files. Source, tests, build configuration, the Gradle wrapper, Room schema, verification scripts and documentation belong in Git. Build output, caches, APKs, local settings, logs and signing keys do not.

## License

Copyright (c) 2026 Alan Rockefeller. This project is licensed under the GNU General Public License version 3 (SPDX: GPL-3.0-only). See [LICENSE](LICENSE). Third-party dependencies retain their respective licenses.

## Logging limits

Search stops with an explanation if the database reaches 1 GiB, free phone storage falls below 256 MiB, a session reaches 50,000 addresses, or more than 8,192 scan results are waiting to be stored. These limits are intended for unusually heavy traffic. Existing results remain available to export; nothing is automatically deleted. Results arriving after the cutoff are not logged, and the session records why logging stopped.

Database usage includes the main SQLite file, pending WAL writes and shared-memory files. It is checked before each start and during a search every second or 128 stored results, whichever comes first. File sizes can briefly exceed the threshold between checks. The database limit applies across all saved sessions. Creating a new session does not reset that limit. If the database limit is reached, export the sessions you need before using Android app settings to clear this app's storage. Clearing storage also resets preferences and mutes. For the address or queue limit, a new session or a restart after the traffic has subsided can be enough.
