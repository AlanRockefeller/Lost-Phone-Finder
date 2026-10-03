# Design 2a

The app uses a dark Inter theme with Phosphor regular icons, top tabs, outlined actions and RSSI graphs. Search features a stable, recent, unmuted address and sorts the remaining cards by current RSSI. Devices retains all sort options, mute filters and packet-profile groups. Its list holds its order while a device menu is open, so live sorting cannot move the menu under a finger. Returning from tracking keeps the selected tab.

The readiness sheet contains phone settings shortcuts, debug simulation, session unmute and the audio mute action. Baseline review remains available below the Search tools. Target export and additional mute actions live in the tracking menus. Advertisement details and recent results start collapsed. Settings contains separate views for pitch controls, notification recordings, persistent mutes and privacy.

Graph samples are held in per-address deque buffers in `SearchRepository`. Each sample contains the observation's elapsed timestamp, raw RSSI and the existing smoothed RSSI. State publication removes samples older than 60 seconds, including when scanning stops or a device falls silent. New sessions clear the buffers. Unavailable RSSI values do not enter graphs. Recorded observations, exports and the Room schema are unchanged.

Graphs plot raw RSSI against elapsed time using the configured sensitivity bounds. Every live graph uses the same fixed 60-second axis, fixed configured RSSI bounds, accent stroke and 2dp line thickness. Grid mode adds only grid lines. Line segments split at gaps greater than four times the median of the latest twelve positive inter-sample intervals, clamped to 8–20 seconds. Intervals longer than 20 seconds and likely outage outliers do not inflate the cadence estimate. A preliminary lower median identifies intervals beyond the normal gap allowance before the final median is calculated. No line extends into the silent period after the last observation. Trend labels use a regression slope of the smoothed values in the last ten seconds. The session timer uses monotonic elapsed time.

## Assets

- Inter comes from [Google Fonts](https://github.com/google/fonts/tree/main/ofl/inter). The bundled static fonts were instantiated from the variable font at optical size 14 and weights 400 and 500. Its SIL Open Font License is in [licenses/Inter-OFL.txt](../app/src/main/assets/licenses/Inter-OFL.txt).
- Icons come from [Phosphor Core regular SVGs](https://github.com/phosphor-icons/core/tree/main/assets/regular). Their paths are bundled as Android vector drawables. The MIT license is in [licenses/Phosphor-MIT.txt](../app/src/main/assets/licenses/Phosphor-MIT.txt).

## Interface verification

Run `./gradlew test assembleDebug lintDebug lintRelease assembleRelease :app:exportDebugRuntimeArtifacts :app:exportReleaseRuntimeArtifacts`, then audit both APKs with `tools/verify-apk-runtime.py` and their matching runtime artifact reports.

For a visual check, open Search readiness, enable Debug simulation, dismiss the sheet and start a search. Check the featured device and nearby grid, select a target, inspect the graph and disclosure rows, then go back. Devices should retain sort and grouping choices, expose mute actions through its overflow menu and long press, and show muted addresses unless filtered. Check session export actions and all Settings disclosures. Stop the search and confirm graph samples age out.

Simulation checks rendering and action routing. BLE discovery, speaker routing and screen-off behavior still need a physical phone.

## Sound and Bluetooth field fixes

A fresh process shows “Ready to search” until a search actually starts. After that search stops, the header shows “Search stopped”. Graphs retain and display 60 seconds; trend labels still use the last ten seconds.

The header speaker button cycles normal audio, loudspeaker mode and muted audio. Normal has one speaker wave, loudspeaker has two. Loudspeaker mode requests the built-in speaker and raises PCM gain only after that route is confirmed. At the default app volume, its peak is about three times the normal peak. It does not change system media volume. A rejected or unconfirmed route produces an explanation and keeps ordinary gain on the current output.

RSSI chirps use ten-millisecond raised-cosine ramps. Muting or changing targets cancels the current sound with a short fade, including long notification recordings. The audio stream stays fed during silence.

Settings → Notification sound offers the original two-note chime, a real tugboat steam-whistle recording and a real Montezuma oropendola bloop, with previews. Both recordings are bundled as mono 48 kHz signed 16-bit little-endian PCM. They need no network access or download at runtime. Credits, source URLs, edits and the respective CC BY 4.0 and CC0 links are in the sound chooser and [Discovery-recordings.txt](../app/src/main/assets/licenses/Discovery-recordings.txt). The oropendola source and regeneration recipe are documented in [discovery sounds](DISCOVERY_AUDIO.md).

The activity checks Bluetooth before requesting a search service. The service promotes itself before checking for a radio-state race, then stops promptly if a prerequisite disappeared. Turning Bluetooth off stops the radio and audio with an explanation while retaining saved observations. Android imposes a short foreground-service promotion deadline; see [foreground-service troubleshooting](https://developer.android.com/develop/background-work/services/fgs/troubleshooting).

Regression checks cover 60-second history expiration without deleting logs, initial/stopped status, the three-mode cycle, old settings JSON, full offline assets, chirp ramps, interrupted recordings, confirmed speaker gain, rejected routes and Bluetooth startup/shutdown. Emulator checks cover Bluetooth-off launch and start, active Bluetooth shutdown, mode cycling, sound selection and preview, and debug simulation. Listening for residual clicks and comparing speaker loudness on a car system remain physical listening checks.

## Review fixes

When Android reports a dead audio output, the worker releases and recreates it, reapplies the speaker preference and resumes rendering. Recovery allows three replacements before reporting a persistent failure; one second of successful writes resets the retry allowance. A failed ten-millisecond block is dropped so gain can be recalculated for the replacement's confirmed route. Other write errors keep the existing failure message. See [AudioTrack write errors](https://developer.android.com/reference/android/media/AudioTrack#ERROR_DEAD_OBJECT).

Every foreground Start request is promoted before duplicate-start guards return. A request received during shutdown is promoted, then waits for pending session finalization before stopping. Rejected starts still stop immediately without waiting for storage. Normal Stop retains foreground execution until the stop event, end time and stopped status are saved. The application scope owns finalization so a later promotion failure cannot cancel it. Duplicate starts preserve the existing scan and current notification address count. Stop remains idempotent, and archiving a stopped session preserves its original end timestamp and committed results.

Regression tests cover recreated audio output and later chirps, restored speaker preferences, bounded repeated failures, other write errors, repeated service starts, failed promotion during shutdown, duplicate stop events and session archival timestamps. The offline core check also works with Java on PATH when JAVA_HOME is unset. Historical verification notes explicitly point to the current shared field-test signing certificate.

Header sound-mode changes use validated settings and record settings events, so session JSON exports include loudspeaker changes made after the session started. Regression checks cover the exported mode sequence and a normal Stop blocked by a database transaction, including another Start received during finalization.

## Stable featured signal

The repository retains the featured address while it remains recent and unmuted. A challenger must hold at least a 6 dB current-RSSI advantage for two seconds, with a later observation confirming that advantage. Smaller fluctuations, brief spikes, or a change of challenger do not immediately replace the card. A muted, unavailable or stale selection gives way immediately to the strongest recent eligible address. Staleness uses the same 8–20 second adaptive silence threshold as graph gaps. When no recent signal remains, previously seen addresses stay reachable in compact cards.

Selection and graph history use exact BLE addresses. Shared packet-format buckets remain an optional display grouping; they never infer or combine physical device identities. Regression tests cover intermittent BLE observations, true outages, cadence bounds, fixed axes, clipping and silence, sustained and interrupted challenges, stale or muted selections, session reset and separate addresses with matching profiles.

## GPS logging during a search

GPS logging can be enabled or disabled in Settings without stopping the BLE search or creating a new session. Enabling it registers for GPS fixes; new observations gain the search phone’s coordinates once a recent fix is available. Earlier observations stay unchanged. Disabling it removes the location listener, clears the recent fix and rejects late GPS callbacks and coordinates on new observations. Simulation never starts GPS logging. The helper explains that coordinates require a fix, and the info sheet covers live toggling and indoor delays. Failed GPS startup reports an explanation without stopping BLE scanning.
