# Offline Bluetooth and IEEE registries

The app bundles identifier-to-name facts under `app/src/main/resources/org/blefinder/core/`. It has no network lookup, Internet permission, telemetry or runtime update job. Unknown identifiers remain unknown.

The snapshot fetched on 2026-10-03 UTC contains 4,045 Bluetooth company identifiers and 54,126 distinct IEEE prefixes: 40,296 MA-L, 6,618 MA-M and 7,212 MA-S. `registry-provenance.json` records the authoritative source URLs and SHA-256 digest of each input. Counts refer to public entries, including historical assignments, rather than an assumption that every numeric identifier is allocated.

## Sources and interpretation

Bluetooth SIG's [Assigned Numbers page](https://www.bluetooth.com/specifications/assigned-numbers/) links its public YAML repository. The generator reads [company_identifiers.yaml](https://bitbucket.org/bluetooth-SIG/public/src/main/assigned_numbers/company_identifiers/company_identifiers.yaml). Company IDs in Manufacturer Specific Data are little-endian values, independent of MAC addresses. The UI says “Bluetooth company”. The company that owns an identifier need not manufacture the physical transmitter. Names are preserved from the source, including Samsung's current spelling `Samsung Electronics Co. Ltd.`.

The [IEEE Registration Authority public listing](https://standards.ieee.org/products-programs/regauth/) provides these authoritative inputs:

- [MA-L / OUI CSV](https://standards-oui.ieee.org/oui/oui.csv), 24-bit prefixes.
- [MA-M CSV](https://standards-oui.ieee.org/oui28/mam.csv), 28-bit prefixes.
- [MA-S / OUI-36 CSV](https://standards-oui.ieee.org/oui36/oui36.csv), 36-bit prefixes.

Lookup tries 36, 28, then 24 bits. It runs only when Android reported a public address and the address parses correctly. A random or unknown address does not receive an IEEE lookup even if its prefix coincides with an assignment. A few historical IEEE prefixes have multiple organization names; all are retained, separated by `/`. An IEEE assignment names its registered assignee, not a verified physical-device manufacturer.

These sources publish assigned-number facts. No permissive software license was present in the Bluetooth public repository root when inspected. Bluetooth SIG and IEEE retain their respective rights, notices and trademarks; the app does not claim to relicense their registries under GPL or imply endorsement. The generated tables contain identifiers and organization names, not the specifications, postal addresses or website prose. The GPL-3.0-only license applies to the app and generator code. Consult the source sites' current terms when redistributing or updating their data.

## Regeneration

Install Python 3, PyYAML and curl. Run from the repository root:

```sh
python3 -m pip install PyYAML
python3 tools/update-offline-registries.py --download
```

Inputs are saved in ignored `build/registry-sources/`. To reproduce exactly from those retained files, including their hashes, run:

```sh
python3 tools/update-offline-registries.py --sources build/registry-sources
```

The generator validates minimum source sizes, identifiers and duplicate assignments before replacing any bundled output. It sorts keys and emits UTF-8 TSV files. Duplicate historical IEEE names are combined deterministically; duplicate Bluetooth company IDs fail validation. It strips postal addresses. Regeneration from identical inputs is byte-for-byte deterministic. A new download can reflect newer assignments and renamed organizations; review the generated diff and provenance hashes together, update this snapshot record, and rerun both variants' tests and builds. Keep source downloads out of the review batch unless an explicit source archival policy is adopted.
