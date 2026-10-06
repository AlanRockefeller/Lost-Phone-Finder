# Identity verification: 2026-10-02 Pacific

All checks used the normal Gradle dependency graph and the existing private field-test signing configuration. No new key was created. The implementation and reproducible registry generation are documented in [physical identity](PHYSICAL_IDENTITY.md) and [offline registries](OFFLINE_REGISTRIES.md).

Executed with JDK 21:

```sh
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease \
  assembleDebug assembleRelease \
  :app:exportDebugRuntimeArtifacts :app:exportReleaseRuntimeArtifacts
python3 tools/check-core-offline.py
python3 tools/verify-apk-runtime.py app/build/outputs/apk/debug/app-debug.apk \
  --runtime-artifacts app/build/reports/debug-runtime-artifacts.txt
python3 tools/verify-apk-runtime.py app/build/outputs/apk/release/app-release.apk \
  --runtime-artifacts app/build/reports/release-runtime-artifacts.txt
```

- Debug and release: 147 tests each in the final validation run, zero failures, errors or skips. This includes all 103 existing tests and 44 address/registry, fingerprint/identity, GPS, Target/storage and all-session export regressions.
- Standalone cached-dependency core runner: 78 tests passed. It now includes packet-profile, registry and identity tests, and packages the bundled resources into its test JAR.
- Debug and release compilation, Room KSP, lint and APK assembly passed. Each lint report has zero errors and seven existing warnings: two `SwitchIntDef`, one `DataExtractionRules` and four `UseKtx`. Gradle also reports its existing deprecated-feature/configuration-cache notices.
- Runtime audits: both APKs define all 12,137 runtime dependency classes from 67 resolved artifacts. Debug contains 17,141 class definitions in seven DEX files; release contains 16,365 in two. No cache-scanning APK packager was used.
- Both APK signatures verify with the same existing certificate SHA-256: `29235354f935b7dbd62093be213712dec8b426e914fa4ed1134ee3bfa6d4d886`. Both APKs pass `zipalign -c -P 16 4`.
- Packaged registry resources were checked in both APKs: company TSV 105,159 bytes and IEEE TSV 1,677,016 bytes before ZIP compression. Regeneration from retained authoritative input files produced byte-identical TSV/provenance outputs.
- APK permission inspection found no Internet or background-location permission. BLE source/filter configuration, wake-lock code, storage limits and Room schema files are unchanged.
- `git diff --check` passed. Generated output, logs, downloaded source snapshots, APKs and private signing files are excluded from Git.

APK SHA-256 values for this verification:

| Variant | SHA-256 |
| --- | --- |
| Debug | `28a1c4057ca25749bdc76ff352af76d24d72b2b84e7c579e05927c165d3d1939` |
| Release | `3d26c5c5c6a5a05b427e97afee2af7358f0ff0afe1908435c6a0a6d139f8e745` |

No physical Android device was used. These checks do not establish Samsung radio/address rotation behavior, OEM screen-off delivery, real GPS accuracy, on-device rendering, audio continuity or sustained radio-load performance. Use the S24 procedure in [physical identity](PHYSICAL_IDENTITY.md#samsung-galaxy-s24-field-check), including a second simultaneous advertiser and a no-GPS repeat. The crowd regression checks bounded comparison counts, not Android throughput.

## PR #4 review fixes

The earlier GPS/Target follow-up checks passed with 139 tests in each variant, no failures, errors or skips. Regression coverage now includes rapid GPS off/on without main-loop collection, rejection of old-generation GPS callbacks and observation coordinates, clearing rejected contributions from Target statistics and graphs, and revocation after Target has already returned to its seed. Previously saved observations and per-address statistics remain intact.

Both variants passed lint and APK assembly. Lint still reports only the seven existing warnings listed above. Both APK runtime audits passed with 67 resolved artifacts and all 12,137 dependency classes present. Both APK signatures verify with the same existing certificate SHA-256 `29235354f935b7dbd62093be213712dec8b426e914fa4ed1134ee3bfa6d4d886`. The standalone core suite passed all 78 tests using JDK 21 through the runner's `--java` option. No Room schema or signing configuration changed.

APK SHA-256 after these fixes:

- Debug: `df479c87225f226ef249539c1ac7431a68e8a9b45b6e0d9e43ff6d8b24456ecb`
- Release: `7c208ac4c8c2fb387890b38495b10aa9497e2f412a080f4a882918eb24195801`

Robolectric verified the service lifecycle and GPS request behavior. Rapid toggles, fresh GPS acquisition and identity revocation still need a real-phone field check. To check GPS, obtain a fix during an active real search, toggle GPS off and on quickly, and verify new observations have no coordinates until a new fix arrives. To check revocation, first cause a high-confidence handoff between two test advertisers, then advertise both simultaneously until the association is rejected. Confirm Target returns to its seed, shows the statistics restart notice, and retains both addresses in the raw session export.
