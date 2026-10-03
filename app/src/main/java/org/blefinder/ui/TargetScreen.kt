package org.blefinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.blefinder.R
import org.blefinder.core.*
import org.blefinder.data.*

@Composable
fun TargetScreen(state: SearchState, device: DeviceRecord, repo: SearchRepository,
    export: (String, String?, Boolean) -> Unit, stop: () -> Unit,
    status: String? = null, dismissStatus: () -> Unit = {}) {
    val stats = state.targetStats ?: device.stats
    var raw by rememberSaveable(state.targetSeed) { mutableStateOf(false) }
    var decoded by rememberSaveable(state.targetSeed) { mutableStateOf(false) }
    var history by remember(state.targetSeed) { mutableStateOf<List<Observation>>(emptyList()) }
    var selected by remember(state.targetSeed) { mutableStateOf<Observation?>(null) }
    var showHistory by rememberSaveable(state.targetSeed) { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val observation = selected ?: device.latest
    val ageMillis = (state.nowElapsed - stats.lastElapsed).coerceAtLeast(0)
    val age = ageMillis / 1000
    val samples = state.targetHistory
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { repo.selectTarget(null) }) { FinderIcon(R.drawable.ic_arrow_left, description = "Back to previous tab") }
            Column(Modifier.weight(1f)) {
                Text(device.displayName ?: "Unnamed transmitter", fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SelectionContainer { Text(device.address, fontFamily = FontFamily.Monospace, letterSpacing = 0.sp, fontSize = 11.sp, color = FinderColors.neutral400) }
            }
            AudioIndicator(state.audioMuted, state.settings.loudspeaker, repo::cycleSoundMode)
        }
        SearchBanners(state, status, dismissStatus)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                state.targetAddressChange?.let { change ->
                    FinderCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Target address changed", fontWeight = FontWeight.Medium)
                            Text("${change.from} → ${change.to}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text("Likely same physical device; ${change.relationship.band.label.lowercase()}")
                            Text("Evidence: ${change.relationship.evidence.joinToString("; ")}", fontSize = 12.sp)
                        }
                    }
                }
                Text("TRACKING · ONLY THIS TARGET SOUNDS", fontSize = 10.sp, color = FinderColors.accent)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(signal(stats.current), fontSize = 88.sp, maxLines = 1)
                    Text("dBm", Modifier.padding(bottom = 18.dp), fontSize = 20.sp, color = FinderColors.neutral400)
                }
                val trendText = when (trend(samples, state.nowElapsed)) { "Rising" -> "Getting stronger"; "Falling" -> "Getting weaker"; else -> "Steady" }
                Text(when { stats.count == 0L -> "Waiting for a new result"; ageMillis > 5000 -> "No result for $age s"; else -> "$trendText · smoothed ${number(stats.smoothed)}" },
                    fontSize = 13.sp, color = if (ageMillis > 5000 || stats.count == 0L) FinderColors.neutral400 else FinderColors.accent300)
            }
            item {
                SignalGraph(samples, state.nowElapsed, state.settings.rssiMin, state.settings.rssiMax, Modifier.fillMaxWidth().height(120.dp), grid = true)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("60 s ago", fontSize = 10.sp, color = FinderColors.neutral500)
                    Text("now", fontSize = 10.sp, color = FinderColors.neutral500)
                }
            }
            item {
                FinderCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp)) {
                        listOf("Best" to signal(stats.strongest), "Average" to number(stats.mean), "Results" to stats.count.toString(), "Rate" to rateText(stats, state.nowElapsed)).forEach { (label, value) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(value, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(label, fontSize = 10.sp, color = FinderColors.neutral500)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(if (state.mutes.isMuted(device.address)) "Unmute target" else "Mute target", { repo.setMute(device.address, muted = !state.mutes.isMuted(device.address)) }, Modifier.weight(1f))
                    Box {
                        OutlinedIconButton(onClick = { exportMenu = true }, enabled = state.sessionId != null, modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small, border = androidx.compose.foundation.BorderStroke(1.dp, FinderColors.divider)) { FinderIcon(R.drawable.ic_export, description = "Export target") }
                        DropdownMenu(exportMenu, { exportMenu = false }) {
                            DropdownMenuItem(text = { Text("Export target JSON") }, onClick = { state.sessionId?.let { export(it, device.address, true) }; exportMenu = false })
                            DropdownMenuItem(text = { Text("Export target CSV") }, onClick = { state.sessionId?.let { export(it, device.address, false) }; exportMenu = false })
                        }
                    }
                    Box {
                        OutlinedIconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small, border = androidx.compose.foundation.BorderStroke(1.dp, FinderColors.divider)) { FinderIcon(R.drawable.ic_dots_three_vertical, description = "Target actions") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Always mute") }, onClick = { repo.setMute(device.address, always = true); menu = false })
                            DropdownMenuItem(text = { Text("Unmute (both types)") }, onClick = { repo.setMute(device.address, muted = false); menu = false })
                            DropdownMenuItem(text = { Text(if (state.audioMuted) "Enable audio" else "Mute all audio") }, onClick = { repo.audioMute(); menu = false })
                            DropdownMenuItem(text = { Text("Clear target statistics") }, onClick = { repo.clearTargetStats(); menu = false })
                            if (state.active) DropdownMenuItem(text = { Text("Stop search") }, onClick = { stop(); menu = false })
                        }
                    }
                }
                Text("Mute: ${state.mutes.kind(device.address)} · Audio: ${if (state.audioMuted) "muted" else "on"}", Modifier.padding(top = 6.dp), fontSize = 11.sp, color = FinderColors.neutral500)
            }
            item {
                FinderCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Address", fontWeight = FontWeight.Medium)
                        Text(device.address, fontFamily = FontFamily.Monospace)
                        Text(AddressClassifier.classify(device.latest).label)
                        Text("Android reported: ${device.latest.addressType}; raw type ${device.latest.metadata.androidAddressType ?: "unavailable"}")
                        Text("Vendor / protocol information", fontWeight = FontWeight.Medium)
                        IeeeAssignments.lookup(device.latest)?.let { assignment ->
                            Text("IEEE address assignment: ${assignment.organization} (${assignment.registry})")
                        }
                        device.latest.identityCompaniesForUi().forEach { id ->
                            Text("Bluetooth company: ${Companies.name(id) ?: "Unknown ID $id"}")
                        }
                        Text("Advertised name: ${device.latest.advertisement.localName ?: "not supplied"}")
                        Text("Services: ${(device.latest.advertisement.serviceUuids + device.latest.metadata.androidServiceUuids).distinct().joinToString().ifEmpty { "none supplied" }}")
                        Text("Physical identity (inference)", fontWeight = FontWeight.Medium)
                        val candidate = state.targetCandidate
                        if (candidate != null && candidate.addresses.size > 1) {
                            Text("Likely same physical device across ${candidate.addresses.size} observed addresses")
                            candidate.addresses.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                            candidate.relationships.forEach { IdentityEvidence(it) }
                        } else Text("No sufficiently strong cross-address association")
                        state.identitySuggestions.forEach { relation ->
                            Text("${relation.band.label}: ${relation.addresses.joinToString(" / ")}", fontSize = 12.sp)
                            IdentityEvidence(relation)
                        }
                        SmallNote("Company IDs and IEEE assignments describe separate advertised or registered facts. They do not prove the physical device manufacturer. Identity bands are heuristic, not probabilities. Mutes remain address-specific.")
                    }
                }
            }
            item {
                FinderCard(Modifier.fillMaxWidth()) {
                    if (selected != null) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Historical · ${timeText(observation.timestamp)}", Modifier.weight(1f).background(FinderColors.accent800, RoundedCornerShape(6.dp)).padding(6.dp), fontSize = 11.sp, color = FinderColors.accent300)
                        ActionButton("Back to live", { selected = null }, secondary = true)
                    }
                    DisclosureRow("Decoded advertisement", decoded) { decoded = !decoded }
                    if (decoded) Column(Modifier.padding(12.dp)) { DecodedObservation(observation) }
                    FadingDivider()
                    DisclosureRow("Raw BLE advertisement · ${observation.advertisement.rawHex.length / 2} bytes", raw) { raw = !raw }
                    if (raw) SelectionContainer {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Complete scan-record bytes (hex):")
                            Text(observation.advertisement.rawHex.ifEmpty { "No raw scan record supplied by Android" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text("AD structures, in received order:", Modifier.padding(top = 12.dp))
                            observation.advertisement.structures.forEach { ad ->
                                Text("0x%02X · %s%s".format(ad.type, AdvertisementParser.typeName(ad.type), if (ad.truncated) " · TRUNCATED" else ""))
                                Text(ad.hex.ifEmpty { "(empty payload)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                            Text("Complete stored observation (JSON):", Modifier.padding(top = 12.dp))
                            Text(SearchJson.encodeToString(observation), fontFamily = FontFamily.Monospace, letterSpacing = 0.sp, fontSize = 12.sp)
                        }
                    }
                    FadingDivider()
                    DisclosureRow("Recent results · ${if (showHistory) history.size else "50"}", showHistory) {
                        if (showHistory) showHistory = false else scope.launch { history = repo.recent(device.address); showHistory = true }
                    }
                    if (showHistory) {
                        if (history.isEmpty()) Text("No recorded results.", Modifier.padding(12.dp), fontSize = 12.sp)
                        history.forEach { o -> TextButton(onClick = { selected = o; showHistory = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("${timeText(o.timestamp)} · ${o.address} · ${o.rssi} dBm · ${o.advertisement.rawHex.length / 2} bytes", fontSize = 12.sp)
                        } }
                    }
                }
            }
            item {
                SmallNote("Weakest ${signal(stats.weakest)} dBm · First ${timeText(stats.firstSeen)} · Last ${timeText(stats.lastSeen)}")
                SmallNote("Original address records and observations are preserved. Target follows only high-confidence inferred handoffs. Export the whole session to retain every candidate address and the switch evidence.")
                SmallNote("Screen-off scanning stays broad; only this target sounds while tracking. Keep media volume up and press Stop when finished. Phone power settings may still limit results.")
                SmallNote("Clearing resets this tracking view. All recorded observations and session statistics remain exportable.")
                SmallNote("Scan results received are not a complete capture of every radio packet. A lost phone must be powered and advertising BLE to be detected. RSSI is relative signal strength, not distance.")
            }
        }
    }
}

@Composable private fun DecodedObservation(o: Observation) {
    val ad = o.advertisement; val m = o.metadata
    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Address: ${o.address} • ${AddressClassifier.classify(o).label} (Android reported ${o.addressType}; raw type ${m.androidAddressType ?: "unavailable"})", fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)
        Text("Android name: ${o.deviceName ?: "not supplied"}\nAdvertised local name: ${ad.localName ?: "not supplied"}")
        Text("RSSI: ${o.rssi} dBm\nResult time: ${timeText(o.timestamp)}\nReceived: ${timeText(o.receivedAt)}\nAndroid monotonic timestamp: ${m.timestampNanos} ns")
        Text("TX power: AD ${ad.txPower ?: "unavailable"} / scan ${m.txPower ?: "unavailable"} dBm")
        Text("Advertising flags: ${ad.flags?.let { "0x%02X".format(it) } ?: "unavailable"} • Android flags ${m.androidFlags ?: "unavailable"}")
        Text("Connectable: ${m.connectable ?: "unknown"} • Legacy: ${m.legacy ?: "unknown"}")
        Text("Primary PHY: ${phy(m.primaryPhy)} • Secondary PHY: ${phy(m.secondaryPhy)}")
        Text("Advertising SID: ${m.advertisingSid?.takeUnless { it == 255 } ?: "not present"}")
        Text("Periodic advertising interval: ${m.periodicInterval ?: 0} units (1.25 ms/unit; 0 = absent)")
        Text("Data status: ${when (m.dataStatus) { 0 -> "complete"; 2 -> "truncated"; else -> m.dataStatus?.toString() ?: "unknown" }} • Callback type: ${m.callbackType}")
        Text("Service UUIDs:"); (ad.serviceUuids + m.androidServiceUuids).distinct().ifEmpty { listOf("None supplied") }.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        Text("Solicitation UUIDs:"); (ad.solicitationUuids + m.androidSolicitationUuids).distinct().ifEmpty { listOf("None supplied") }.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        Text("Manufacturer-specific data", fontWeight = FontWeight.Medium)
        val manufacturers = (ad.manufacturers + m.androidManufacturers).distinct()
        if (manufacturers.isEmpty()) Text("None supplied")
        manufacturers.forEach { Text("Bluetooth company: ${Companies.name(it.id) ?: "Unknown company"} • ID ${it.id} (0x%04X)".format(it.id)); Text(it.hex.ifEmpty { "(empty payload)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        Text("Service data", fontWeight = FontWeight.Medium)
        val services = (ad.services + m.androidServiceData).distinct()
        if (services.isEmpty()) Text("None supplied")
        services.forEach { Text(it.uuid); Text(it.hex.ifEmpty { "(empty payload)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        if (ad.malformed) Text("Malformed or truncated AD structure; original bytes retained.", color = MaterialTheme.colorScheme.error)
        if (m.androidAdvertisingData.isNotEmpty()) {
            Text("Android AD map (raw per type):")
            m.androidAdvertisingData.forEach { (type, hex) -> Text("0x%02X: %s".format(type, hex), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
        o.location?.let { Text("GPS: ${it.latitude}, ${it.longitude} ±${it.accuracy} m\nFix time: ${timeText(it.timestamp)}") }
        Text("At receipt: target=${o.target}, mute=${o.mute}, simulated=${o.simulated}")
    } }
}
private fun phy(value: Int?) = when (value) { 0 -> "not used"; 1 -> "LE 1M"; 2 -> "LE 2M"; 3 -> "LE Coded"; null -> "unavailable"; else -> value.toString() }

private fun Observation.identityCompaniesForUi() = (advertisement.manufacturers + metadata.androidManufacturers).map { it.id }.distinct()

@Composable private fun IdentityEvidence(relation: IdentityRelationship) {
    Text("${relation.band.label}; evaluated ${timeText(relation.evaluatedAt)}", fontSize = 12.sp)
    relation.evidence.forEach { Text("Evidence: $it", fontSize = 12.sp) }
    relation.contradictions.forEach { Text("Contradiction: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
}
