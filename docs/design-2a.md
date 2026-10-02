# Design 2a

The app uses a dark Inter theme with Phosphor regular icons, top tabs, outlined actions and RSSI graphs. Search shows the strongest current unmuted address first. Devices retains all sort options, mute filters and packet-profile groups. Its list holds its order while a device menu is open, so live sorting cannot move the menu under a finger. Returning from tracking keeps the selected tab.

The readiness sheet contains phone settings shortcuts, debug simulation, session unmute and the audio mute action. Baseline review remains available below the Search tools. Target export and additional mute actions live in the tracking menus. Advertisement details and recent results start collapsed. Settings contains separate views for pitch controls, persistent mutes and privacy.

Graph samples are held in per-address deque buffers in `SearchRepository`. Each sample contains the observation's elapsed timestamp, raw RSSI and the existing smoothed RSSI. State publication removes samples older than 60 seconds, including when scanning stops or a device falls silent. New sessions clear the buffers. Unavailable RSSI values do not enter graphs. Recorded observations, exports and the Room schema are unchanged.

Graphs plot raw RSSI against elapsed time using the configured sensitivity bounds. A gap over five seconds separates line segments. Trend labels use a regression slope of the smoothed values in the last ten seconds. The session timer uses monotonic elapsed time.

## Assets

- Inter comes from [Google Fonts](https://github.com/google/fonts/tree/main/ofl/inter). The bundled static fonts were instantiated from the variable font at optical size 14 and weights 400 and 500. Its SIL Open Font License is in [licenses/Inter-OFL.txt](../app/src/main/assets/licenses/Inter-OFL.txt).
- Icons come from [Phosphor Core regular SVGs](https://github.com/phosphor-icons/core/tree/main/assets/regular). Their paths are bundled as Android vector drawables. The MIT license is in [licenses/Phosphor-MIT.txt](../app/src/main/assets/licenses/Phosphor-MIT.txt).

## Interface verification

Run `./gradlew test assembleDebug lintDebug lintRelease assembleRelease :app:exportDebugRuntimeArtifacts :app:exportReleaseRuntimeArtifacts`, then audit both APKs with `tools/verify-apk-runtime.py` and their matching runtime artifact reports.

For a visual check, open Search readiness, enable Debug simulation, dismiss the sheet and start a search. Check the featured device and nearby grid, select a target, inspect the graph and disclosure rows, then go back. Devices should retain sort and grouping choices, expose mute actions through its overflow menu and long press, and show muted addresses unless filtered. Check session export actions and all Settings disclosures. Stop the search and confirm graph samples age out.

Simulation checks rendering and action routing. BLE discovery, speaker routing and screen-off behavior still need a physical phone.
