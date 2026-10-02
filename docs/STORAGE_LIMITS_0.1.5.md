# Storage limits and review fixes in 0.1.5

Codex identified two problems in PR #1, and repeated the shutdown finding in PR #2. Both apply to the app:

- Normal service shutdown called repository Stop twice. Stop now finishes an open session once, in a transaction, and updates the in-memory session status. Repeated Stop calls, a failed subsequent start, and service cleanup cannot rewrite its end time or add another Stop event. Unexpected shutdown still has a fallback.
- A timer tick could wait behind database writes and then apply baseline mutes before an already-queued cancellation. The tick now carries its original firing time. Regression tests cover both Cancel and Stop before the deadline.

The logging limits are 1 GiB of database storage across all sessions, 256 MiB minimum free phone storage, 50,000 addresses in a session, and 8,192 pending scan results. They are fixed safety limits with test overrides. Muted and non-target observations are still logged normally. Database checks run before start, then every second or 128 stored results, whichever comes first; the 200 ms UI timer also performs due checks when no advertisements arrive. Usage accounts for allocated SQLite pages and main/WAL/shared-memory file sizes. The threshold is sampled, so a small overshoot is possible between checks.

A limit stops scanning/audio through the existing service shutdown path, finalizes the session, records a logging_limit event and displays an explanation. Results after the cutoff are not logged. Stored observations are retained and can be exported. No automatic deletion or schema migration was added. Queue overflow stops the radio promptly; control commands can still be queued. These safeguards bound radio backlog and address growth, but are not an audit of Android's Bluetooth stack or a guarantee against every denial-of-service attack.

Creating a new session clears the address list but not accumulated database storage. If the database quota is reached, export the required sessions before clearing app storage in Android settings. This also resets preferences and mutes. Low free-space warnings require freeing phone storage. Export files are outside this database quota.

The README retains Alan's introduction and uses simpler wording. Em/en dashes were removed from README/source text, and missing UI values now read N/A. AGENTS.md records the preference for plain prose without em dashes.

Validation: 72 debug and 72 release tests passed, including eight new regression/storage tests. Both lint checks: zero errors, seven existing warnings per variant. Gradle built signed debug and release APKs using the same original field-test key. Runtime audit verified all 12,137 dependency classes from 67 selected artifacts; signature, version and 16 KB alignment checks passed. APKs and logs are under build/releases/v0.1.5/. Physical-phone validation is still needed.
