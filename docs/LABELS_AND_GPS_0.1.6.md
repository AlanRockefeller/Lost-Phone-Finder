# Advertisement labels and GPS retention in 0.1.6

An advertisement hint describes a protocol observed in the current packet. It is independent of physical identity inference, address-specific mutes and packet-profile grouping. The app displays the hint as the title when no name is available, in packet-profile headers, and with its supporting evidence in Target details. Original names and packets remain in storage and exports. Displayed names omit trailing NUL padding.

| Observed clue | Label | Interpretation |
| --- | --- | --- |
| Service FEF3 in the UUID list or service data, from the parser or Android metadata | Possible Android | Google Nearby advertising; other device types can also use this service |
| Service FEED | Possible Tile tracker | A service assigned to Tile, not an identifier for a particular tracker |
| Apple company ID 76 with a complete proximity-pairing message of type 07 and enough bytes for its prefix/model fields | Possible Apple accessory | Audio-accessory pairing evidence; the model is not confirmed |
| Apple company ID 76 with a complete Find My message of type 12 and a two-byte or 25-byte body | Apple Find My device | Short and long protocol forms, neither of which establishes an iPhone model or an accessory category |
| Other manufacturer data advertising company ID 76 | Possible Apple device | The device type remains unknown |

The Apple parser checks the entire type/length/value sequence before using a message hint. It handles multiple messages in one manufacturer payload. Malformed advertisements, incomplete controller data, invalid hex and incomplete message bodies cannot qualify for the more specific Apple labels. It does not decode encrypted identifiers or change the identity engine's scoring. An Apple-looking manufacturer payload from another company ID does not receive an Apple label.

The short Find My messages in the October field recording are insufficient to label a device as AirPods. A separate proximity-pairing message can support the possible-accessory label. No automatic Apple Phone label was added because this data does not establish one. Packet-profile groups continue to compare formats and can contain several physical devices.

References:

- [Bluetooth SIG assigned numbers](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html) assigns FEF3 to Google and FEED to Tile.
- [Google's Nearby advertising test](https://chromium.googlesource.com/chromiumos/third_party/labpack/+/0f6fbd5a857b97d154b70a68d23780cfe1aff253/server/cros/bluetooth/advertisements_data.py) uses FEF3 for Nearby Mediums advertising.
- [Find My packet research](https://github.com/furiousMAC/continuity/blob/master/messages/findmy.md) documents Apple type 12 and its long message.
- [Proximity-pairing research](https://github.com/furiousMAC/continuity/blob/master/messages/proximity_pairing.md) documents Apple type 07 for audio accessories.
- [Apple's archived 2020 accessory specification](https://www.frandroid.com/wp-content/uploads/2020/06/Find_My_network_accessory_protocol_specification.pdf), pages 33 and 34, describes the short and long Find My forms. This is historical protocol context, not proof of a present-day transmitter's model or state.

## GPS storage

Location acquisition still requests one update per second. The latest fresh preceding fix remains available to every BLE result, including muted and non-target results. Each BLE observation retains its full latitude, longitude, accuracy, wall-clock fix time and monotonic fix time. Identity inference continues to receive that same observation and location evidence.

Only standalone location-table writes are sampled. The first valid fix in a GPS run is retained, then the next valid fix at least 60 monotonic seconds after the last retained fix. Duplicate and out-of-order callbacks do not create extra rows. Invalid coordinates, negative/nonfinite accuracy and negative monotonic times do not consume a sample slot. Wall-clock changes do not affect the interval.

Start, a new session and a GPS setting transition reset sampling so the first new fix is available. Existing GPS-generation checks still reject callbacks from an old logger after a rapid off/on transition. Stopped and simulated searches do not write GPS rows. A `gps_sampling` event records the 60,000 ms interval when a real GPS-enabled run starts or GPS is enabled during a run.

A local replay of the October 3 export through the new sampler retained 1,732 of 104,723 standalone fixes, a 98.35% reduction. Keeping every other exported field, the equivalent compact JSON would shrink from 35,472,811 bytes to about 22,261,691 bytes, a 37.24% reduction. The town session alone drops from 39,259 standalone fixes to 649. These are estimates from applying the interval to each saved session; Start/GPS restart sampling can add a few first fixes. The source recording and replay artifacts remain private under ignored build output.

A sparse standalone trail remains useful for showing receiver movement or continued GPS delivery through a BLE reception gap. It is not a complete GPS callback history. Saving only sparse standalone fixes reduces database writes and JSON/CSV rows without removing the location attached to a BLE observation.

This changes future recording. Previously saved sessions and exports are retained in full, and the exporter still includes all stored standalone fixes. There is no automatic cleanup, Room migration or export-schema change. Scanning, audio, radio configuration and the existing private signing configuration are unchanged.

## Verification

Regression coverage checks short/long Apple messages, compound proximity-pairing messages, Android metadata fallback, malformed inputs, uncertain labels and raw-name preservation. GPS tests cover minute boundaries, movement, clock changes, duplicate/late callbacks, invalid fixes, stop/resume, new sessions, rapid GPS generation changes and complete attached BLE coordinates in exports.

Executed with JDK 21:

```sh
./gradlew --no-daemon testDebugUnitTest testReleaseUnitTest lintDebug lintRelease \
  assembleDebug assembleRelease :app:exportDebugRuntimeArtifacts :app:exportReleaseRuntimeArtifacts
python3 tools/check-core-offline.py --java /path/to/jdk21/bin/java
python3 tools/verify-apk-runtime.py app/build/outputs/apk/debug/app-debug.apk \
  --runtime-artifacts app/build/reports/debug-runtime-artifacts.txt
python3 tools/verify-apk-runtime.py app/build/outputs/apk/release/app-release.apk \
  --runtime-artifacts app/build/reports/release-runtime-artifacts.txt
```

- Debug and release each passed 159 tests, with zero failures, errors or skips. The standalone core suite passed 88 tests.
- Both APK builds and lint checks passed. Lint has zero errors and the same seven existing warnings in each variant: two SwitchIntDef, one DataExtractionRules and four UseKtx. Gradle retains its existing deprecation/configuration-cache notices.
- Runtime audits verified all 12,137 dependency classes from 67 Gradle-resolved artifacts in both APKs.
- Both APK signatures verify with the existing field-test certificate SHA-256 `29235354f935b7dbd62093be213712dec8b426e914fa4ed1134ee3bfa6d4d886`. Both pass 16 KB zip alignment checks. Version is 0.1.6, code 7.
- Room schema files and export schema versions are unchanged. `git diff --check` passed. No export, APK, private signing file or build output is committed.

APK SHA-256 values:

| Variant | SHA-256 |
| --- | --- |
| Debug | `047c48fc0fb35724a8f77801d21b234e58b9037f59101d0608e1ef95dd106344` |
| Release | `c20ea9a58f7ad4e9cbd692f67e7687544d0776d096f20b98bb996b870ec3e088` |

Field replay found 43 FEF3 address records with the Possible Android hint, 53 with Find My hints and four with proximity-pairing accessory hints in the town session. These are address counts, not physical-device counts. All 14,932 saved advertisements still reparse identically. The local replay did not modify the original export.

No updated APK was installed on the phone during this change. Field replay validates the recorded input; it does not establish radio throughput, rendering or GPS behavior on a running updated phone. A phone check should verify the visible hints, run GPS logging through a quiet BLE interval, then export and confirm full attached BLE fixes alongside the sparse standalone trail.
