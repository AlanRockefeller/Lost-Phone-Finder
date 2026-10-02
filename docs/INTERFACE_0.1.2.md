# Interface update 0.1.2

Search is the default compact recent-results table (address ID, RSSI in dBm, age since receipt). Most recently received observations appear first. Devices retains detailed per-address cards with optional packet-profile buckets. Session tools/readiness are collapsed initially. Global audio mute is the top-right speaker button; muted shows a slash, normal one wave, loudspeaker two waves.

Audio tuning now separates low/high alert frequencies in Hz from weak/strong sensitivity endpoints in dBm, with a curve showing the existing pitch function. Scan collection, chirp mapping, target selection, mute rules and persistence are unchanged.

Packet buckets compare manufacturer IDs/payload lengths, service UUIDs/data lengths, solicitation UUIDs, and advertising structure types/lengths. Changing payload bytes and addresses are ignored. Buckets reflect the latest packet per address, are not persisted, can include multiple physical devices and do not establish continuity across address rotations. Addresses with no manufacturer/service clues remain separate. Tracking and muting still use individual addresses. Sessions > Export JSON supplies the full historical observations/raw packets for further analysis; no overnight export was available during this change.

Changed source: SearchApp.kt, SettingsScreen.kt, AudioIndicator.kt, PacketProfiles.kt, Models.kt (recent receipt sorting), app/build.gradle.kts (version), PacketProfilesTest.kt. README documents the controls and logs. No dependencies were added.

Validation: 64 debug + 64 release tests passed, zero failures/errors/skips. Both full lint checks: zero errors, seven existing warnings. Normal offline Gradle built both signed variants with the existing certificates. DEX audit verified every class from all 67 selected runtime artifacts (12,137 classes), including AbstractResolvableFuture and ListenableFuture. Signature and 16 KB zip alignment verification passed. Outputs and audit logs: build/releases/v0.1.2/. New UI still requires physical-phone validation.

Field checks: compare the Search table with Devices details; tap a row to track; verify global mute icon, normal/loud wave states, and playback; adjust low/high frequencies and sensitivity and compare the curve; enable profile buckets and confirm addresses remain individually trackable/mutable. Export the overnight session as JSON to inspect real packet content before refining identification heuristics.
