# Export all stored sessions

Open **Sessions** and choose **Export all JSON** or **Export all CSV**, then pick a destination in Android's document picker. The file includes all stored session statuses, including archived, stopped, interrupted, active and simulated searches. Choose local storage to keep the export offline. Exporting leaves the database and saved sessions intact. The default filenames are `lost-phone-finder-all-sessions.json` and `lost-phone-finder-all-sessions.csv`.

Both formats include current settings and persistent address mutes, plus each session's stored metadata, per-address device records, event history, original scan observations and independent GPS fixes. Session settings at creation remain in `settingsJson`; later setting changes remain in the session event history. Raw observation JSON keeps the original names, addresses, payloads, timestamps, attached GPS fixes and accuracy, Android metadata and historical target/mute flags. From 0.1.6 onward, independent GPS fixes are sampled once per minute during each GPS run. Each BLE observation still retains its full attached fix. Older sessions retain their original independent fixes; see [GPS retention](LABELS_AND_GPS_0.1.6.md). Historical identity decisions remain in events and can be reconstructed from observations; the exporter does not turn inferred relationships into address merges.

## JSON

The all-session file has `schemaVersion: 2` and `exportType: "all_sessions"`. Its top-level fields are `selectedAt` (Unix milliseconds), `settings`, `persistentMutes` and `sessions`. Each entry in `sessions` is the same schema-version-1 object produced by an individual complete-session JSON export, with `session`, `devices`, `events`, `observations`, `gpsObservations` and the existing target/inference fields. Empty sessions remain present. Individual session and target formats are unchanged.

## CSV

There is one header for the whole UTF-8 file. Its first six columns are `record_type`, `session_id`, `session_started_at_utc`, `session_ended_at_utc`, `session_status` and `session_simulated`. The existing individual-export observation columns follow, and a final `record_json` column preserves the complete record.

| Record type | Contents |
| --- | --- |
| `export` | Schema version, export type and selection time |
| `settings` | Current application settings |
| `persistent_mutes` | Current persistent address mutes |
| `session` | Session metadata, including creation settings |
| `device` | Stored per-address metadata and statistics |
| `event` | Session event and evidence history |
| `observation` | Original scan result, with the usual RSSI/address/time columns and full JSON |
| `gps` | Independent GPS fix, with latitude, longitude, accuracy and fix timestamp columns |

Metadata rows leave inapplicable observation fields blank. Filter `record_type` to `observation` for a combined spreadsheet of scan results. Use `session_id` to distinguish repeated addresses in different sessions. The original names stay in `record_json`, while the spreadsheet name column retains the existing formula protection. Quoting handles commas, quotes and embedded newlines, so use a CSV parser rather than splitting lines or commas manually.

## Large exports and live searches

The session ID list and current settings/mutes are selected once after the document picker returns. New sessions created afterward are excluded. On the IO worker, the exporter captures each selected session immediately before writing it, using the existing transactionally consistent device/event snapshot and result/GPS cutoff IDs. It streams observations and fixes in pages of 500 and retains only one session's device/event snapshot at a time. File writes do not run on the UI thread or repository scan consumer. Each session's short metadata transaction ends before its file write begins.

This is a sequence of consistent session snapshots, not one database-wide instant. During a live search, results stored before that session's snapshot are included; results stored afterward remain on the phone for a later export. Stop the search first when you want all sessions frozen before export. The app continues broad scanning and does not pause the radio for export.

The picker retains the requested scope and format through activity recreation. Existing export status and failure messages cover these files too. A failed write may leave a partial destination file; retry with a writable destination and sufficient space. No data is deleted and no permissions or Room schema changes are required. This is a portable data export; the app does not provide an import/restore feature.

## Automated coverage

`AllSessionsExportTest` covers multiple sessions across page boundaries, all record types, current settings/mutes, empty sessions and an empty database, repeated addresses, original JSON preservation, CSV escaping/formula protection, live snapshot cutoffs, new-session exclusion and output failures without data loss. `AllSessionsExportActivityTest` checks both picker formats and successful all-session output after activity recreation. Existing single-session, target and identity export tests remain in place. Real document-provider behavior and large radio-active exports still need a phone check.

## Verification, 2026-10-03

Both variants passed 147 tests, including eight new all-session export and picker-recreation tests. APK builds, lint and runtime dependency audits passed. Each lint report has zero errors and seven existing warnings. Both APKs include all 12,137 runtime dependency classes from 67 Gradle-resolved artifacts. Signatures verify with the unchanged existing certificate SHA-256 `29235354f935b7dbd62093be213712dec8b426e914fa4ed1134ee3bfa6d4d886`. The Room schema remains version 1 with no schema file changes.

Release APK SHA-256: `f13ea85ae061bd0c40d66cc8953c481e403157e65f0554bbcd8846c7e9d7444f`.
Debug APK SHA-256: `5f82d2cbb42f687b776f5051083bc924e381f0302bec3fce8cda28b3b1f32789`.

For a phone check, save several real sessions, including an empty session, and export both formats from Sessions to local storage. Confirm JSON has one session entry for each saved session and CSV has one header with distinct session IDs and all expected record types. Compare per-session observation counts with individual JSON exports after stopping the search. Repeat while searching, cancel the picker once, and retry after an unwritable/full destination. Confirm saved sessions remain available and scanning continues during a live export.
