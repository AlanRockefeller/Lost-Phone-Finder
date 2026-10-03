# Conservative physical identity inference

A BLE address remains an address record. `PhysicalIdentityEngine` learns evidence above those records and produces `PhysicalDeviceCandidate` groups. It never rewrites, combines or deletes raw observations, addresses or per-address statistics. Packet-profile buckets remain format comparisons, independent of inferred identity.

## Address classification

`AddressClassifier` uses Android's raw address-type metadata when available, falling back to the stored reported type on older observations. It does not guess public/random status from a MAC prefix. Raw Android metadata is unchanged. For random addresses, Bluetooth Core Specification [Vol 6, Part B, section 1.3.2](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-61/out/en/low-energy-controller/link-layer-specification.html) defines bits 47:46: `00` NRPA, `01` RPA, `10` reserved, `11` static random. In the normal colon-separated display these are the top two bits of the first octet.

The random part cannot be all zero or all one. RPA validation applies that rule to the 22 random bits of the most significant three octets (prand); it does not apply it to the 24-bit hash. Static random and NRPA validation apply it to the remaining 46 bits. Anonymous, unavailable, malformed, reserved and invalid values remain explicitly classified. Static random may change across power cycles. RPA is expected to rotate. Classification does not resolve an RPA cryptographically; the app does not have the advertiser's Identity Resolving Key.

## Learning and candidate generation

A payload family is manufacturer company ID plus payload length, or service-data UUID plus payload length. Raw-parser and Android copies are deduplicated. Conflicting duplicate entries, malformed advertisements and truncated controller data do not train a family. The first observed bytes initialize a mask. A position that ever changes loses all identity weight for the rest of that address's session; it cannot regain weight by returning to its first value. Different lengths train separate families. Payloads larger than 512 bytes stay in raw storage but are excluded from identity learning. Learning requires at least four observations over two seconds for each family on both addresses.

Detailed matching intersects the learned stable masks. A strong match requires every mutually stable position to agree, with at least eight stable nonzero/non-FF bytes representing at least six different values. Constant zeros, simple repeating patterns, short protocol headers, generic service UUIDs and lengths alone cannot qualify. These entropy checks are safeguards, not proof that bytes uniquely identify a device. Manufacturer-data and service-data matches count as payload evidence. Multiple matched families add at most one supplemental award, because fields within a protocol may be correlated.

Family, company and service UUID indexes generate plausible peers. An address is reevaluated at most once per controller second, against at most 48 peers. Overcrowded indexes receive no new edges; existing edges are still reevaluated. The engine keeps at most 24 payload families (up to 512 bytes each), 48 index keys, eight relationships and eight observed runs per address. Stronger new evidence can replace a weaker, uncontradicted relationship, but never evicts an accepted high-confidence edge or a contradiction to make room. The recent timing window spans 120 seconds. Candidate groups contain at most 16 addresses. Storage's existing address and backlog limits still apply. Learning runs on the repository's serial IO consumer, and registry loading happens there before scanning. The UI receives snapshots at its existing five-Hz cadence. Radio configuration, inclusive screen-off filters, scan rate and wake lock are unchanged.

## Exact scoring and acceptance

All parameters live in `IdentityParameters`. Version 1 uses these heuristic points, never numerical probabilities:

| Evidence | Points |
| --- | ---: |
| First strong stable payload-family match | 50 |
| At least one additional strong family | 20 total supplemental points |
| Shared Bluetooth company ID | 3 |
| Same nonblank advertised local name | 4 |
| Same service UUID set containing a custom UUID | 8 |
| Same advertising characteristics with at least four comparable fields | 5 |
| Same IEEE organization, both addresses public | 2 |
| Observed address handoff within 15 seconds | 20 |
| Robust RSSI continuity at the handoff | 12 |
| Fresh, sufficiently accurate GPS fixes in the same search area | 5 |
| More than one qualifying observed handoff | 5 total supplemental points |
| Each contradictory evidence item | subtract 40, floor total at zero |

Advertising characteristics include AD/scan TX power, connectability, legacy status, primary/secondary PHY, valid advertising SID, periodic interval, flags, solicitation UUIDs and AD type/length structure. Missing values are not matches. AD structures are never used as a unique identifier. Generic SIG service sets are displayed as supporting format context with zero points. IEEE and Bluetooth-company evidence remain independent.

High confidence requires at least 85 points, a strong learned payload match, a handoff, and either robust RSSI continuity or multiple strong payload families. It also requires no contradictions, both addresses to have usable classifications, and at least one private address. Two public addresses, two static random addresses or unknown types are never automatically associated by this version. A score of 50 or more without contradictions is a Possible match when the high-confidence gates fail. Everything else is Weak similarity. The UI displays these labels and evidence, not percentages or the internal score.

A run begins after more than five seconds of silence for that address. For a transition, the engine compares an old run's last observation with a new run's first observation. Handoff gaps must be positive and no longer than 15 seconds. It uses the five most recent valid raw RSSIs before the transition and the first five after it, requiring at least three on each side, a range no wider than 12 dBm on either side, and median RSSIs no more than 8 dBm apart. This avoids letting one noisy sample provide continuity. Learned sample counts and time span provide the minimum result-rate evidence; there is no assumption of a fixed over-the-air packet rate. Long gaps provide no handoff points. Repeated alternating runs can reinforce an association, but simultaneous evidence still vetoes it.

GPS describes the receiver's search area, not the transmitter's exact location. Fixes must be finite, in geographic range, no older than 15 controller seconds and have accuracy from 0 through 50 m. A handoff gets GPS support when separation is no more than the sum of both accuracies plus 15 m. Spatial contradiction requires separation beyond both accuracy radii to exceed both 150 m and 50 m/s times the handoff gap. Poor, stale, invalid or absent fixes contribute nothing. Thus fixes 8 m apart with ±20 m accuracy are compatible. GPS never establishes identity by itself.

## False-association safeguards

Every contradiction vetoes automatic association even if the remaining score is high. Contradictions include incompatible learned stable payload bytes (at least four differing mutually stable bytes across at least eight stable positions), differing known public IEEE assignments, differing legacy/extended behavior, clearly incompatible handoff RSSI or GPS, and repeated simultaneous observations.

Three shared one-second observation intervals count as simultaneous evidence. This veto remains for the rest of the session even after recent timing samples age out. There is no protocol-specific exemption in version 1. It may keep a genuine multi-advertiser device separate, which is preferable to following the wrong transmitter. Controller timestamps, rather than callback receipt times, determine overlap. Late controller observations are retained in Room but do not train fingerprints or invent transitions.

If three independent addresses share the same learned stable pattern, it is treated as a common format and vetoed. Previously accepted high-confidence peers are excluded from the independent-peer count so successive rotations can extend a candidate. Every proposed group member must have compatible strong payload evidence and no contradiction with every existing member. A chain of high-confidence edges is insufficient if any members disagree or overlap. Groups and Target eligibility are recomputed as evidence changes; they are not permanent union-find merges. Limits favor abstaining rather than selecting an arbitrary peer.

Identical cloned beacons, intentionally copied payloads, packet loss and two transmitters with coincidentally similar timing can still fool these uncalibrated heuristics. Advertisements are untrusted claims. When the payload is encrypted or all identifying bytes rotate, separate addresses or possible matches are the expected outcome.

## Target mode and persistence

Selecting an address establishes the candidate seed. A strong relationship can add addresses, but Target changes the current followed address only when that incoming address belongs to the candidate, has a high-confidence direct relationship with the current followed address, and the current address has been quiet for at least two seconds. The address-change card says “Likely same physical device”, lists both MACs and shows the evidence. Weak and possible peers appear separately. A contradicted followed association returns tracking to the original seed and logs a revocation.

The tracking statistics retain the seed's existing statistics and then add accepted target observations, including observations after a handoff. The tracking graph retains the existing 60-second window across accepted handoffs. If evidence removes any candidate address, the aggregate Target graph and statistics restart. This also applies when tracking has already returned to the seed. The screen explains the restart and a `target_statistics_restarted` event records the old and new address sets. Existing per-address statistics and raw saved observations are retained. Restarting avoids replaying a potentially large session on the scan consumer or retaining rejected data in cumulative smoothing. New-address results received before sufficient confidence are logged under their original address but do not retrospectively sound or enter target statistics. Recent history combines up to 50 results across current candidate members. Target JSON and CSV export include every current candidate address; JSON also includes the candidate evidence and session events. Audio eligibility uses the current followed address. The service watches the seed when flushing audio, so an inferred handoff does not flush the chirp queue. Mutes remain address-specific and apply before sound.

Repository-owned evidence survives UI recomposition, screen changes and stop/resume within a process. New session clears learning. Normal process restart marks the previous session interrupted, as before; it does not automatically resume radio tracking. `SessionExporter.reconstructIdentity` replays saved observations in paged insertion order with the current engine to reconstruct a candidate for analysis without modifying old data. This API is tested but not exposed as a historical-session identity viewer. Full session JSON retains the raw evidence needed for future replay.

GPS setting transitions increment a repository-owned generation. The service restarts the location logger when that generation changes, even if a rapid off/on transition is conflated into one settings notification. Cached fixes are usable only for the current generation, and queued location callbacks and scan observations carry their originating generation so the repository can reject stale coordinates before storage or identity learning. These generations are process-local lifecycle state; saved GPS observations remain unchanged.

Room remains schema version 1. The only DAO addition is a candidate-address paging query; no table changes or migration are necessary. The legacy `probablePhysicalDeviceId` export field remains null. Associations are not written into address rows. Target-change events include original addresses, timestamps, score, confidence band, positive/negative evidence and algorithm version 1. Future scoring changes must increment the algorithm version. Historical events describe the decision made at the time, while replay uses current evidence and parameters.

## Samsung Galaxy S24 field check

Use the S24 as the advertising phone and a second Android phone running this build as the search phone. If the S24 runs the finder, the target must be a separate advertiser. An app cannot scan its own advertisements reliably as a lost-device test.

1. Install the signed build over the existing finder on the search phone. Enable Bluetooth and the app's existing scan permissions. Start a new real search in an uncrowded area. GPS is optional; repeat once with it disabled.
2. On the S24, enable the BLE advertising feature you want to find. Confirm that packets actually arrive. Identify its address by stopping and restarting that feature while watching results and saved raw packets, rather than trusting the Samsung company ID alone. Keep both phones stationary. Select that address and let at least four results spanning two seconds train its payload families.
3. Leave Target running with broad discovery. Wait for an actual address rotation. Restarting the S24 advertising feature or toggling its Bluetooth may prompt a new address, but does not guarantee rotation. Record the actual old/new MACs and timestamps; Android controls address selection. A rotation test is inconclusive until a new address is observed. Do not restart the search or seed selection during the handoff.
4. For a qualifying transition, verify the “Target address changed” card, both MACs, High confidence evidence, a continued graph/statistics count, and audible/current RSSI from the new address. Export the entire session JSON. Check the `target_address_changed` event, preserved observations under both addresses, gap, stable payload masks reconstructed by tests/analysis, and absence of sustained overlap. Expect the first few replacement packets to remain non-target until learning finishes.
5. If normal S24 advertisements rotate encrypted identifiers or only expose generic data, verify that Target remains on the seed and shows a possible/weak peer instead. That is a conservative pass, not a successful positive-follow demonstration. For a repeatable positive control, use an Android BLE advertiser on the S24 with a custom 128-bit service UUID and a service-data marker containing at least eight distinct stable bytes (for example `12 34 56 78 9A BC DE F1`), optionally followed by changing counter bytes. Keep that payload unchanged across a real Android-chosen address rotation. Do not replace the address manually in the finder.
6. Repeat with the search phone's screen off through the transition. Then run two separate advertisers with the same test payload simultaneously for several seconds. Verify that overlap prevents grouping or revokes a followed association. Repeat after moving the advertiser to change RSSI, and after a long advertising gap, to confirm those weaker transitions do not automatically reassign Target.

No radio, Samsung firmware, physical GPS, screen-off rotation or on-device audio result is claimed by the automated tests. Firmware may resolve addresses before delivering them to apps, stop advertising, or change entire payloads on rotation. This app cannot override that behavior.

Automated build, lint, signature and test results are recorded in [identity verification](IDENTITY_VERIFICATION.md).
