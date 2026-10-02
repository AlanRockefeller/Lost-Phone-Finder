package org.blefinder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.blefinder.core.*
import org.blefinder.data.*

@Composable
fun TargetScreen(state: SearchState, device: DeviceRecord, repo: SearchRepository,
    export: (String, String?, Boolean) -> Unit, stop: () -> Unit) {
    val stats = state.targetStats ?: device.stats
    var raw by rememberSaveable(device.address) { mutableStateOf(false) }
    var decoded by rememberSaveable(device.address) { mutableStateOf(true) }
    var history by remember(device.address) { mutableStateOf<List<Observation>>(emptyList()) }
    var selected by remember(device.address) { mutableStateOf<Observation?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val observation = selected ?: device.latest
    val age = if (stats.count > 0) "${(state.nowElapsed - stats.lastElapsed).coerceAtLeast(0) / 1000} s since last result" else "Waiting for a new result"
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            BigButton("← Return to scan mode") { repo.selectTarget(null) }
            if (state.active) BigButton("STOP SEARCH", click = stop)
            Text("TARGET ${if (state.active) "• tracking" else "• search stopped"}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(device.displayName ?: "Unnamed transmitter", style = MaterialTheme.typography.headlineMedium)
            SelectionContainer { Text(device.address, fontFamily = FontFamily.Monospace, fontSize = 20.sp) }
            Text("${signal(stats.current)} dBm", fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Text("Smoothed ${number(stats.smoothed)} dBm", fontSize = 26.sp)
            val value = ((stats.smoothed ?: -100.0) - state.settings.rssiMin) / (state.settings.rssiMax - state.settings.rssiMin)
            LinearProgressIndicator(progress = { value.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(32.dp))
            Text("Weaker   ←   relative signal strength   →   Stronger")
            Text(age, fontSize = 24.sp, color = if (state.nowElapsed - stats.lastElapsed > 5000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Best ${signal(stats.strongest)} • Weakest ${signal(stats.weakest)} dBm", fontSize = 20.sp)
                Text("Average ${number(stats.mean)} dBm", fontSize = 20.sp)
                Text("${stats.count} scan results received", fontSize = 22.sp)
                Text("${number(stats.rate(state.nowElapsed))} results/sec (last 5 sec)", fontSize = 20.sp)
                Text("First: ${timeText(stats.firstSeen)}\nLast: ${timeText(stats.lastSeen)}")
                Text("${device.company ?: "Manufacturer unknown"} (advertised company ID guess)")
                Text("Address type: ${device.latest.addressType}")
                Text(if (device.latest.addressType == "random") "Random address: it may rotate; persistent mutes match this address only." else "Address identity may change. Addresses are never merged automatically.")
            } }
            BigButton(if (state.mutes.isMuted(device.address)) "UNMUTE TARGET" else "MUTE TARGET") {
                repo.setMute(device.address, muted = !state.mutes.isMuted(device.address))
            }
            Text("Mute state: ${state.mutes.kind(device.address)} • All audio: ${if (state.audioMuted) "muted" else "on"}")
            MuteButtons(device.address, state.mutes, repo)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = repo::audioMute, modifier = Modifier.weight(1f)) { Text(if (state.audioMuted) "Enable audio" else "Mute all audio") }
                OutlinedButton(onClick = repo::clearTargetStats, modifier = Modifier.weight(1f)) { Text("Clear target statistics") }
            }
            Text("Clearing resets this tracking view. All recorded observations and session statistics remain exportable.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { state.sessionId?.let { export(it, device.address, true) } }) { Text("Export target JSON") }
                OutlinedButton(onClick = { state.sessionId?.let { export(it, device.address, false) } }) { Text("CSV") }
            }
        }
        item {
            Text("Scan-result details", style = MaterialTheme.typography.headlineSmall)
            Text(if (selected == null) "Live latest result" else "Historical result • ${timeText(observation.timestamp)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { history = repo.recent(device.address); showHistory = !showHistory } }) { Text("Recent 50 results") }
                if (selected != null) TextButton(onClick = { selected = null }) { Text("Back to live") }
            }
            if (showHistory) history.forEach { o ->
                TextButton(onClick = { selected = o; showHistory = false }) { Text("${timeText(o.timestamp)} • ${o.rssi} dBm • ${o.advertisement.rawHex.length / 2} bytes") }
            }
            TextButton(onClick = { decoded = !decoded }) { Text("${if (decoded) "▾" else "▸"} Decoded advertisement / Android metadata") }
            if (decoded) DecodedObservation(observation)
        }
        item {
            TextButton(onClick = { raw = !raw }) { Text("${if (raw) "▾" else "▸"} Raw BLE Advertisement") }
            if (raw) SelectionContainer {
                Column {
                    Text("Complete scan-record bytes (hex):", fontWeight = FontWeight.Bold)
                    Text(observation.advertisement.rawHex.ifEmpty { "No raw scan record supplied by Android" }, fontFamily = FontFamily.Monospace)
                    Text("AD structures, in received order:", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
                    observation.advertisement.structures.forEach { ad ->
                        Text("0x%02X • %s%s".format(ad.type, AdvertisementParser.typeName(ad.type), if (ad.truncated) " • TRUNCATED" else ""))
                        Text(ad.hex.ifEmpty { "(empty payload)" }, fontFamily = FontFamily.Monospace)
                    }
                    Text("Complete stored observation (JSON):", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
                    Text(SearchJson.encodeToString(observation), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
        item { Text("Scan results received are not a complete capture of every radio packet. A lost phone must be powered and advertising BLE to be detected.", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(24.dp)) }
    }
}

@Composable private fun DecodedObservation(o: Observation) {
    val ad = o.advertisement; val m = o.metadata
    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Address: ${o.address} • ${o.addressType} (Android type ${m.androidAddressType ?: "unavailable"})")
        Text("Android name: ${o.deviceName ?: "not supplied"}\nAdvertised local name: ${ad.localName ?: "not supplied"}")
        Text("RSSI: ${o.rssi} dBm\nResult time: ${timeText(o.timestamp)}\nReceived: ${timeText(o.receivedAt)}\nAndroid monotonic timestamp: ${m.timestampNanos} ns")
        Text("TX power: AD ${ad.txPower ?: "unavailable"} / scan ${m.txPower ?: "unavailable"} dBm")
        Text("Advertising flags: ${ad.flags?.let { "0x%02X".format(it) } ?: "unavailable"} • Android flags ${m.androidFlags ?: "unavailable"}")
        Text("Connectable: ${m.connectable ?: "unknown"} • Legacy: ${m.legacy ?: "unknown"}")
        Text("Primary PHY: ${phy(m.primaryPhy)} • Secondary PHY: ${phy(m.secondaryPhy)}")
        Text("Advertising SID: ${m.advertisingSid?.takeUnless { it == 255 } ?: "not present"}")
        Text("Periodic advertising interval: ${m.periodicInterval ?: 0} units (1.25 ms/unit; 0 = absent)")
        Text("Data status: ${when (m.dataStatus) { 0 -> "complete"; 2 -> "truncated"; else -> m.dataStatus?.toString() ?: "unknown" }} • Callback type: ${m.callbackType}")
        Text("Service UUIDs:"); (ad.serviceUuids + m.androidServiceUuids).distinct().ifEmpty { listOf("None supplied") }.forEach { Text(it, fontFamily = FontFamily.Monospace) }
        Text("Solicitation UUIDs:"); (ad.solicitationUuids + m.androidSolicitationUuids).distinct().ifEmpty { listOf("None supplied") }.forEach { Text(it, fontFamily = FontFamily.Monospace) }
        Text("Manufacturer-specific data", fontWeight = FontWeight.Bold)
        val manufacturers = (ad.manufacturers + m.androidManufacturers).distinct()
        if (manufacturers.isEmpty()) Text("None supplied")
        manufacturers.forEach { Text("ID ${it.id} (0x%04X) • ${it.company ?: "Unknown company"}".format(it.id)); Text(it.hex.ifEmpty { "(empty payload)" }, fontFamily = FontFamily.Monospace) }
        Text("Service data", fontWeight = FontWeight.Bold)
        val services = (ad.services + m.androidServiceData).distinct()
        if (services.isEmpty()) Text("None supplied")
        services.forEach { Text(it.uuid); Text(it.hex.ifEmpty { "(empty payload)" }, fontFamily = FontFamily.Monospace) }
        if (ad.malformed) Text("Malformed or truncated AD structure; original bytes retained.", color = MaterialTheme.colorScheme.error)
        if (m.androidAdvertisingData.isNotEmpty()) {
            Text("Android AD map (raw per type):")
            m.androidAdvertisingData.forEach { (type, hex) -> Text("0x%02X: %s".format(type, hex), fontFamily = FontFamily.Monospace) }
        }
        o.location?.let { Text("GPS: ${it.latitude}, ${it.longitude} ±${it.accuracy} m\nFix time: ${timeText(it.timestamp)}") }
        Text("At receipt: target=${o.target}, mute=${o.mute}, simulated=${o.simulated}")
    } }
}
private fun phy(value: Int?) = when (value) { 0 -> "not used"; 1 -> "LE 1M"; 2 -> "LE 2M"; 3 -> "LE Coded"; null -> "unavailable"; else -> value.toString() }
