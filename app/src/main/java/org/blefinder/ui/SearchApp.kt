package org.blefinder.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.blefinder.ble.DemoFactory
import org.blefinder.core.*
import org.blefinder.data.*
import org.blefinder.service.Readiness
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("MMM d HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
fun timeText(time: Long): String = if (time == 0L) "—" else timeFormat.format(Instant.ofEpochMilli(time))
fun number(value: Double?): String = value?.let { "%.1f".format(it) } ?: "—"
fun signal(value: Int?): String = value?.toString() ?: "—"

@Composable
fun SearchApp(repo: SearchRepository, status: String?, dismissStatus: () -> Unit,
    start: (Boolean) -> Unit, stop: () -> Unit, export: (String, String?, Boolean) -> Unit,
    keepAwake: (Boolean) -> Unit) {
    val state by repo.state.collectAsStateWithLifecycle()
    val sessions by repo.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var simulation by rememberSaveable { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    var restart by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SideEffect { keepAwake(state.active && state.settings.keepAwake) }
    DisposableEffect(Unit) { onDispose { keepAwake(false) } }
    BackHandler(state.target != null) { repo.selectTarget(null) }
    val colors = darkColorScheme(primary = Color(0xFF8CFFD0), background = Color(0xFF080D12),
        surface = Color(0xFF121D26), onSurface = Color(0xFFF3F8FF), onBackground = Color(0xFFF3F8FF))
    MaterialTheme(colorScheme = colors) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Search", "Devices", "Sessions", "Settings").forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("◉", "≋", "▤", "⚙")[i]) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text("BLE Search", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (state.simulated && state.sessionId != null) Banner("SIMULATION • Synthetic observations", Color(0xFFFFD180))
                if (status != null) { Text(status, Modifier.padding(12.dp)); TextButton(onClick = dismissStatus) { Text("Dismiss") } }
                state.error?.let { Banner(it, MaterialTheme.colorScheme.error) }
                if (context.getSystemService(PowerManager::class.java).isPowerSaveMode) Banner("BATTERY SAVER ON • Detection may be reduced", Color(0xFFFFD180))
                if (state.target != null && tab in 0..1) {
                    val target = state.devices.find { it.address == state.target }
                    if (target != null) TargetScreen(state, target, repo, export, stop)
                } else when (tab) {
                    0, 1 -> DeviceSearchScreen(state, repo, tab == 0, simulation,
                        { simulation = it }, { if (state.active) stop() else explain = true }, { restart = true })
                    2 -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("Saved searches", style = MaterialTheme.typography.headlineSmall); Text("All observations stay on this phone. JSON includes complete scan metadata; CSV is one row per result.") }
                        if (sessions.isEmpty()) item { Text("Start a search to create a session.") }
                        items(sessions, key = { it.id }) { session ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(timeText(session.startedAt), fontWeight = FontWeight.Bold)
                                    Text("${if (session.simulated) "SIMULATED • " else ""}${session.status} • ${session.id.take(8)}")
                                    session.endedAt?.let { Text("Ended ${timeText(it)}") }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { export(session.id, null, true) }) { Text("Export JSON") }
                                        OutlinedButton(onClick = { export(session.id, null, false) }) { Text("CSV") }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                    3 -> SettingsScreen(state, repo)
                }
            }
        }
        if (explain) AlertDialog(onDismissRequest = { explain = false }, title = { Text("Start ${if (simulation) "simulated " else ""}search") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Nearby devices lets BLE Search detect advertisements and read device names. Precise location is needed because BLE signal strength is used to infer proximity. GPS coordinates are recorded only when GPS logging is enabled.")
                Spacer(Modifier.height(12.dp))
                Text("Notifications show the active search and provide a Stop action. Notification denial does not prevent searching. No background location permission is requested.")
                Spacer(Modifier.height(12.dp))
                Text("Keep the screen on: Android can pause broad, unfiltered scans when the screen is off. Battery settings cannot guarantee continuous detection. All search data stays local.")
            } }, confirmButton = { Button(onClick = { explain = false; start(simulation) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Cancel") } })
        if (restart) AlertDialog(onDismissRequest = { restart = false }, title = { Text("Start a fresh session?") },
            text = { Text("The current session is saved in Sessions. The live list, target, baseline and session mutes will reset. Always-muted addresses remain muted.") },
            confirmButton = { Button(onClick = { restart = false; repo.restartSession() }) { Text("New session") } },
            dismissButton = { TextButton(onClick = { restart = false }) { Text("Cancel") } })
    }
}

@Composable fun Banner(text: String, color: Color) { Text(text, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
@Composable fun BigButton(text: String, enabled: Boolean = true, click: () -> Unit) {
    Button(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(text, fontSize = 19.sp) }
}

@Composable
private fun DeviceSearchScreen(state: SearchState, repo: SearchRepository, search: Boolean,
    simulation: Boolean, setSimulation: (Boolean) -> Unit, toggleSearch: () -> Unit, restart: () -> Unit) {
    var sort by rememberSaveable { mutableStateOf(SortOrder.RECENT) }
    var hide by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var readiness by rememberSaveable { mutableStateOf(true) }
    var review by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val devices = visibleDevices(state.devices, sort, hide, state.mutes)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            BigButton(if (state.active) "STOP SEARCH" else "START SEARCH", state.ready, toggleSearch)
            if (DemoFactory.available && !state.active) Toggle("Debug simulation", simulation, onChange = setSimulation)
            Text(if (state.active) "Searching • ${if (state.target == null) "all unmuted addresses audible" else "target audio only"}" else "Search stopped", Modifier.padding(vertical = 8.dp))
            val muted = state.devices.count { state.mutes.isMuted(it.address) }
            Text("Addresses: ${state.devices.size}   •   Unmuted: ${state.devices.size - muted}   •   Muted: $muted", fontWeight = FontWeight.Bold)
        }
        if (search) item {
            TextButton(onClick = { readiness = !readiness }) { Text("${if (readiness) "▾" else "▸"} Search readiness") }
            if (readiness) {
                Readiness.checks(context, state.active, state.settings.gps && !state.simulated).forEach { (label, good) ->
                    Text("${if (good) "✓" else "○"} $label", Modifier.padding(vertical = 3.dp), color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openSettings(context, AndroidSettings.ACTION_BLUETOOTH_SETTINGS) }) { Text("Bluetooth") }
                    TextButton(onClick = { openSettings(context, AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("Location") }
                }
                OutlinedButton(onClick = { openSettings(context, AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("Battery optimization settings") }
                TextButton(onClick = { openSettings(context, AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")) }) { Text("App permissions / notifications") }
                Text("Broad BLE detection is more reliable with the display on. Keep awake is ${if (state.settings.keepAwake) "enabled" else "disabled"}. Android and phone firmware can still limit scan results.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = repo::audioMute, modifier = Modifier.weight(1f)) { Text(if (state.audioMuted) "Unmute audio" else "Mute all audio") }
                OutlinedButton(onClick = restart, modifier = Modifier.weight(1f), enabled = state.ready) { Text("New session") }
            }
            val baseline = state.baseline
            if (baseline == null) {
                BigButton("Baseline ${state.settings.baselineSeconds} sec", state.active, repo::beginBaseline)
                Text("Collect search-party transmitters, then mute them for this session.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("BASELINE  ${baseline.remainingSeconds(state.nowElapsed)} s", fontSize = 32.sp, color = MaterialTheme.colorScheme.primary)
                Text("${baseline.addresses.size} addresses detected during baseline")
                OutlinedButton(onClick = repo::cancelBaseline) { Text("Cancel baseline") }
            }
            TextButton(onClick = repo::unmuteSession) { Text("Unmute all session devices") }
        }
        if (state.baselineReview.isNotEmpty()) {
            item { TextButton(onClick = { review = !review }) { Text("${if (review) "▾" else "▸"} Baseline review • ${state.baselineReview.size} addresses") } }
            if (review) items(state.baselineReview.toList(), key = { "baseline-$it" }) { address ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(address); Text("Mute: ${state.mutes.kind(address)}")
                        MuteButtons(address, state.mutes, repo)
                    }
                }
            }
        }
        item {
            Text("Advertisements detected", style = MaterialTheme.typography.headlineSmall)
            Box {
                OutlinedButton(onClick = { sortMenu = true }) { Text("Sort: ${sort.label}") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; sortMenu = false }) }
                }
            }
            Toggle("Hide muted devices", hide) { hide = it }
        }
        if (devices.isEmpty()) item { Text(if (state.active) "Listening for BLE advertisements… A phone must be advertising to appear." else "No scan results yet. Start a search outdoors or use debug simulation.") }
        items(devices, key = { it.address }) { device -> DeviceCard(device, state, repo) }
        item { Text("Addresses can rotate. One address does not prove one physical device. RSSI indicates relative signal strength, not distance.", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(16.dp)) }
    }
}

@Composable private fun DeviceCard(device: DeviceRecord, state: SearchState, repo: SearchRepository) {
    val stats = device.stats; val fresh = state.nowWall - stats.firstSeen < 6000
    val age = ((state.nowElapsed - stats.lastElapsed).coerceAtLeast(0) / 1000)
    Card(onClick = { repo.selectTarget(device.address) }, modifier = Modifier.fillMaxWidth(),
        border = if (fresh) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.padding(14.dp)) {
            if (fresh) Text("NEW ADDRESS", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(device.displayName ?: "Unnamed transmitter", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(device.address)
            Text("${signal(stats.current)} dBm", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Smoothed ${number(stats.smoothed)} • Best ${signal(stats.strongest)} dBm")
            Text("${stats.count} scan results • ${number(stats.rate(state.nowElapsed))}/s • last $age s ago")
            Text("First ${timeText(stats.firstSeen)}")
            Text("${device.latest.addressType}${if (device.latest.addressType == "random") " (may rotate)" else ""} • ${device.company ?: "Vendor unknown"}")
            Text("Manufacturer data: ${device.latest.advertisement.manufacturers.isNotEmpty() || device.latest.metadata.androidManufacturers.isNotEmpty()} • Service data: ${device.latest.advertisement.services.isNotEmpty() || device.latest.metadata.androidServiceData.isNotEmpty()}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { repo.setMute(device.address, muted = !state.mutes.isMuted(device.address)) }) { Text(if (state.mutes.isMuted(device.address)) "Unmute (${state.mutes.kind(device.address)})" else "Mute for session") }
                TextButton(onClick = { repo.selectTarget(device.address) }) { Text("Track / details") }
            }
        }
    }
}

@Composable fun MuteButtons(address: String, mutes: MuteRules, repo: SearchRepository) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { repo.setMute(address) }) { Text("Mute for this session") }
        TextButton(onClick = { repo.setMute(address, always = true) }) { Text("Always mute") }
    }
    if (mutes.isMuted(address)) OutlinedButton(onClick = { repo.setMute(address, muted = false) }) { Text("Unmute (remove both mute types)") }
}

@Composable fun Toggle(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}
fun openSettings(context: android.content.Context, action: String, data: Uri? = null) {
    runCatching { context.startActivity(Intent(action, data)) }.onFailure {
        android.widget.Toast.makeText(context, "This settings screen is unavailable on this phone.", android.widget.Toast.LENGTH_LONG).show()
    }
}
