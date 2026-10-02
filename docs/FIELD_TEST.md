# Physical-phone acceptance test

No BLE radio behavior is validated by JVM tests. Record phone model, Android version, app version, audio route, battery state and scan settings with field results. Use a known BLE beacon or second phone explicitly running a BLE advertiser; an idle phone is not guaranteed to advertise.

## User-reported acceptance — 2026-10-01

The user confirmed that the audio-fix debug build works well, then reported "Everything works!" after being asked to check tracking while moving, target/global/baseline muting, stop/resume and app-restart persistence, JSON/CSV exports, and a 20–30 minute stability run. This is recorded as user-reported acceptance of that suggested field-test scope. The development agent did not observe the phone or independently execute those tests.

Tested APK SHA-256: `c76b35b5045e1fdb8fd71f2a5bf405a6c166ed7a1e1dcff5eff578a00a6db895`. An unchanged reference copy is retained at `build/releases/v0.1.0/ble-search-0.1.0-field-tested-debug.apk`.

Phone model, Android version, audio route, exact test duration and exported sample files were not supplied. The broader checklist below (including other Android versions, GPS edge cases, permission denial, screen-off behavior and release simulation isolation on a phone) remains guidance; the general confirmation does not establish those individual outcomes. The newly signed release APK still needs an installation/launch check.

## First run and permissions

1. Install the debug APK. Open Settings and leave debug simulation **off**. Turn off mobile data/Wi-Fi if you want to verify the app's offline behavior; keep Bluetooth on.
2. Start Search. Check that the explanation precedes the system permission prompts. Grant Nearby devices and precise location; allow notifications. Confirm no background-location, internet or file-storage permission prompt.
3. On a separate trial, deny Nearby devices, choose approximate location, or disable Bluetooth/location services. Verify a clear failure/readiness message and recovery after correcting settings. Denying notifications should still allow searching; verify Android's active-app UI.
4. Verify the persistent notification displays address count, opens the app, and stops the search. Repeat on Android 12/13 and 14–17 if available; older Android 8–11 needs separate legacy permission/FGS testing.

## Discovery, signal and sound

1. Advertise a known name, manufacturer ID and service payload on a beacon. Confirm exact raw hex and decoded fields. Include non-connectable and, if supported, extended/LE Coded advertisements.
2. Walk toward/away from the transmitter in an open area. Confirm generally higher/lower pitches without assuming a distance calibration. Check body shielding, orientation and obstacles; RSSI can fluctuate substantially.
3. Introduce a second advertiser. Confirm a distinct new-address sound. Check master audio mute, independent sound toggles, volume and per-device mutes. Muted devices should remain listed and continue accumulating results without either sound.
4. Select a target. Only it should sound; other devices must still log. Check large readings, EMA responsiveness, strongest/weakest/mean, result rate, stale age and raw detail. Turn off the advertiser: rate should decay to zero after five seconds.
5. Reset target statistics. Confirm the display waits for new results and target/session export still includes earlier results. Check recent-result selection and returning to live detail.
6. Test the built-in speaker, wired audio if present and Bluetooth headphones. Listen for latency, clipping and queue buildup at high advertisement density. Android audio routing is device-dependent. Verify phone calls/other audio interruptions do not crash the service.

## Baseline and persistence

1. Set baseline duration to 5 seconds for a quick trial, then repeat at the default 30 seconds.
2. Start with party devices nearby. Ensure all addresses detected within the interval appear in baseline review and become session-muted only.
3. Cancel before completion; confirm no new baseline mutes. Stop during baseline and verify the same.
4. Manually unmute one result and mark another Always mute. Stop/resume: session and persistent mutes should remain. Create a New session: only persistent mutes remain.
5. Force-stop/reopen, then start a new search. Persistent mutes should survive. Settings must list them and allow removal. Verify prior committed observations remain available in Sessions.

## Service, screen and battery behavior

1. Walk for at least 20 minutes with the app visible and screen on. Compare counts with the known advertiser cadence while remembering Android does not report every transmitted packet.
2. Check that the screen stays awake only during the active session, and resumes normal sleep behavior after Stop.
3. Turn off keep-awake and lock the phone. Expect broad unfiltered detection to pause or change. Unlock and verify state and the notification; document model-specific behavior. The app does not promise screen-off coverage, including Target mode in v0.1.
4. Switch to another app, rotate, return, and stop from the notification. Ensure there are no duplicate scans, lost live state on rotation or leaked audio/GPS after stopping.
5. Turn Bluetooth off while searching. Verify the service stops with an error and the session remains exportable. Restore Bluetooth and restart manually.
6. Toggle Battery Saver and inspect the prominent warning. Open optimization settings and return. Neither setting should be described as a guarantee of continuous detection.
7. Repeatedly start/stop only as a deliberate throttling test. Verify scan error 6 is explained; wait at least 30 seconds before retrying. No automatic restart loop should occur.

## GPS and export

1. With GPS logging off, verify every observation's location is absent. Enable it while stopped and start outdoors; wait for a GPS fix.
2. Check GPS accuracy and independent fix timestamps in exports. Stop receiving fixes (indoors/disable provider): observations must not attach fixes older than 30 seconds or invent zero coordinates.
3. Export JSON/CSV while scanning, then again after stopping. Verify valid JSON, headers/escaping, counts, names, raw bytes, scan metadata and target/mute states. A live export intentionally ends at its snapshot, excluding later results.
4. Compare session vs target exports: target observations are filtered; session events/GPS context remain. Open a large export to test page boundaries and storage throughput.
5. Cancel the document picker and test a failing/read-only destination. The search must continue, and write errors must be visible. A failed export may leave a partial destination file; retry to a writable location.
6. Confirm all tests above work with no network connection. The Android document provider can offer cloud destinations; select on-device storage for offline export.

## Debug and release isolation

- In debug simulation, verify three devices appear, then a fourth; signals change, baseline/target/audio/logging work, and exports say simulated.
- Install a locally signed release build. The simulation switch must be absent, and the synthetic names/source implementation must be absent from the APK. Passing a `simulate` extra cannot activate simulation.
- Never treat simulator performance as evidence for actual BLE coverage, battery use, audio latency or GPS accuracy.
