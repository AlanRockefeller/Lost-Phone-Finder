package org.blefinder.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.blefinder.R
import org.blefinder.ble.DemoFactory
import org.blefinder.core.*
import org.blefinder.data.*
import org.blefinder.service.Readiness
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("MMM d HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
fun timeText(time: Long): String = if (time == 0L) "N/A" else timeFormat.format(Instant.ofEpochMilli(time))
fun number(value: Double?): String = value?.let { "%.1f".format(it).replace('-', '−') } ?: "N/A"
fun signal(value: Int?): String = value?.toString()?.replace('-', '−') ?: "N/A"
fun ageText(device: DeviceRecord, state: SearchState): String = "${(state.nowElapsed - device.latest.receivedElapsedMillis).coerceAtLeast(0) / 1000}s ago"

@Composable
fun SearchApp(repo: SearchRepository, status: String?, dismissStatus: () -> Unit,
    start: (Boolean) -> Unit, stop: () -> Unit, export: (String, String?, Boolean) -> Unit,
    keepAwake: (Boolean) -> Unit) {
    val state by repo.state.collectAsStateWithLifecycle()
    val sessions by repo.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val tabState = rememberSaveableStateHolder()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var simulation by rememberSaveable { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    var restart by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current
    SideEffect {
        keepAwake(state.active && state.settings.keepAwake)
        (context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    DisposableEffect(Unit) { onDispose { keepAwake(false) } }
    BackHandler(state.target != null) { repo.selectTarget(null) }
    FinderTheme {
        val gradientEnd = with(LocalDensity.current) { 220.dp.toPx() }
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(FinderColors.surface, FinderColors.bg), endY = gradientEnd)).safeDrawingPadding()) {
            val target = state.devices.find { it.address == state.target }
            if (target != null) {
                TargetScreen(state, target, repo, export, stop, status, dismissStatus)
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        val seconds = (state.nowElapsed - state.sessionStartedElapsed).coerceAtLeast(0) / 1000
                        Text(if (state.active) "Searching · %02d:%02d · %d addresses".format(seconds / 60, seconds % 60, state.devices.size) else "Search stopped",
                            fontSize = 12.sp, color = FinderColors.accent300)
                    }
                    AudioIndicator(state.audioMuted, state.settings.loudspeaker, repo::audioMute)
                    ActionButton(if (state.active) "Stop" else "Start", { if (state.active) stop() else explain = true }, enabled = state.ready)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    listOf("Search", "Devices", "Sessions", "Settings").forEachIndexed { i, label ->
                        Column(Modifier.selectableTab(tab == i) { tab = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.height(46.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 13.sp, color = if (tab == i) FinderColors.text else FinderColors.neutral500) }
                            Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == i) FinderColors.accent else androidx.compose.ui.graphics.Color.Transparent))
                        }
                    }
                }
                FadingDivider()
                SearchBanners(state, status, dismissStatus)
                Box(Modifier.weight(1f)) {
                    tabState.SaveableStateProvider(tab) {
                        when (tab) {
                            0, 1 -> DeviceSearchScreen(state, repo, tab == 0, simulation, { simulation = it }, { restart = true })
                            2 -> SessionsScreen(sessions, state, export)
                            3 -> SettingsScreen(state, repo)
                        }
                    }
                }
            }
        }
        if (explain) AlertDialog(onDismissRequest = { explain = false }, shape = RoundedCornerShape(14.dp), containerColor = FinderColors.surface,
            title = { Text("Start ${if (simulation) "simulated " else ""}search") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.nearby_permission_explanation, stringResource(R.string.app_name)))
                Spacer(Modifier.height(12.dp))
                Text("Notifications show the active search and provide a Stop action. Notification denial does not prevent searching. No background location permission is requested.")
                Spacer(Modifier.height(12.dp))
                Text("Search is configured to keep discovering devices and pinging while the screen is off. Active searches keep the CPU awake and use more battery until you press Stop. Phone power settings can still restrict results. All search data stays local.")
            } }, confirmButton = { ActionButton("Continue", { explain = false; start(simulation) }) },
            dismissButton = { ActionButton("Cancel", { explain = false }, secondary = true) })
        if (restart) AlertDialog(onDismissRequest = { restart = false }, shape = RoundedCornerShape(14.dp), containerColor = FinderColors.surface,
            title = { Text("Start a fresh session?") }, text = { Text("The current session is saved in Sessions. The live list, target, baseline and session mutes will reset. Always-muted addresses remain muted.") },
            confirmButton = { ActionButton("New session", { restart = false; repo.restartSession() }) },
            dismissButton = { ActionButton("Cancel", { restart = false }, secondary = true) })
    }
}

private fun Modifier.selectableTab(selected: Boolean, click: () -> Unit) = this.widthIn(min = 48.dp).width(IntrinsicSize.Max)
    .heightIn(min = 48.dp).selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = click)

@Composable fun Banner(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(FinderColors.accent900, RoundedCornerShape(6.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FinderIcon(R.drawable.ic_info)
        Text(text, fontSize = 12.sp, color = FinderColors.accent300)
    }
}

@Composable fun SearchBanners(state: SearchState, status: String?, dismissStatus: () -> Unit) {
    val context = LocalContext.current
    if (state.simulated && state.sessionId != null) Banner("Simulation · Synthetic observations")
    if (context.getSystemService(PowerManager::class.java).isPowerSaveMode) Banner("Battery saver on · Detection may be reduced")
    state.error?.let { Banner(it) }
    if (status != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Banner(status) }
        ActionButton("Dismiss", dismissStatus, secondary = true)
    }
}

@Composable private fun SessionsScreen(sessions: List<SessionEntity>, state: SearchState, export: (String, String?, Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FinderIcon(R.drawable.ic_lock)
            Text("Observations stay on this phone. JSON has full scan metadata; CSV is one row per result.", fontSize = 12.sp, color = FinderColors.neutral400)
        } }
        if (sessions.isEmpty()) item { Text("Start a search to create a session.") }
        items(sessions, key = { it.id }) { session ->
            FinderCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(session.startedAt)), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    StatusTag(if (session.simulated) "Simulated" else if (session.status == "active") "Active" else "Stopped", session.status == "active" && !session.simulated)
                }
                val minutes = ((session.endedAt ?: state.nowWall) - session.startedAt).coerceAtLeast(0) / 60_000
                val duration = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
                val summary = when {
                    session.status == "active" -> "Running $duration"
                    session.endedAt != null -> "Ended ${DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(session.endedAt))} · $duration"
                    else -> "Interrupted · end time unavailable"
                }
                Text(summary, fontSize = 12.sp, color = FinderColors.neutral400)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.id.take(8), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FinderColors.neutral600)
                    ActionButton("JSON", { export(session.id, null, true) }, icon = R.drawable.ic_export)
                    ActionButton("CSV", { export(session.id, null, false) }, secondary = true)
                }
            } }
        }
    }
}

@Composable fun StatusTag(label: String, active: Boolean = false) {
    Text(label, modifier = Modifier.background(if (active) FinderColors.accent800 else FinderColors.neutral800, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        color = if (active) FinderColors.accent300 else FinderColors.neutral400, fontSize = 10.sp, maxLines = 1)
}

@Composable private fun ToolChip(label: String, icon: Int? = null, active: Boolean = false, enabled: Boolean = true, click: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 48.dp).background(if (active) FinderColors.accent800 else FinderColors.surface, RoundedCornerShape(6.dp))
            .border(1.dp, if (active) FinderColors.accent else FinderColors.neutral800, RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = click)
            .padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (icon != null) FinderIcon(icon, modifier = Modifier.size(16.dp), color = if (active) FinderColors.accent300 else FinderColors.neutral400)
            Text(label, fontSize = 11.sp, maxLines = 1, color = if (!enabled) FinderColors.neutral600 else if (active) FinderColors.accent300 else FinderColors.neutral400)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DeviceSearchScreen(state: SearchState, repo: SearchRepository, search: Boolean,
    simulation: Boolean, setSimulation: (Boolean) -> Unit, restart: () -> Unit) {
    var sort by rememberSaveable { mutableStateOf(SortOrder.CURRENT) }
    var hide by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var readiness by remember { mutableStateOf(false) }
    var grouped by rememberSaveable { mutableStateOf(false) }
    var review by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val checks = Readiness.checks(context, state.active, state.settings.gps && !state.simulated)
    val failed = checks.count { !it.second }
    var menuDevices by remember { mutableStateOf<List<DeviceRecord>?>(null) }
    // Keep the menu anchor still while live signal strengths reorder the list.
    val devices = menuDevices ?: visibleDevices(state.devices, if (search) SortOrder.CURRENT else sort, search || hide, state.mutes)
    val menuChange: (Boolean) -> Unit = { open -> menuDevices = if (open) devices else null }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (search) {
                    val baseline = state.baseline
                    ToolChip(if (baseline == null) "Baseline ${state.settings.baselineSeconds}s" else "Baseline ${baseline.remainingSeconds(state.nowElapsed)} s · ${baseline.addresses.size} found", R.drawable.ic_timer, enabled = state.active) {
                        if (baseline == null) repo.beginBaseline() else repo.cancelBaseline()
                    }
                    if (baseline != null) ToolChip("Cancel", click = repo::cancelBaseline)
                    ToolChip("New session", R.drawable.ic_plus, enabled = state.ready, click = restart)
                    ToolChip(if (failed == 0) "Ready to search" else "$failed checks need attention", if (failed == 0) R.drawable.ic_check else R.drawable.ic_warning_circle, active = failed == 0) { readiness = true }
                } else {
                    Box {
                        ToolChip(when (sort) { SortOrder.CURRENT -> "Strongest"; SortOrder.RECENT -> "Recent"; SortOrder.BEST -> "Best"; SortOrder.FIRST -> "First seen"; SortOrder.COUNT -> "Results" }, R.drawable.ic_sort_descending, active = true) { sortMenu = true }
                        DropdownMenu(sortMenu, { sortMenu = false }) { SortOrder.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; sortMenu = false }) } }
                    }
                    ToolChip("Hide muted", active = hide) { hide = !hide }
                    ToolChip("Group by profile", active = grouped) { grouped = !grouped }
                }
            }
        }
        if (state.baselineReview.isNotEmpty()) {
            item { DisclosureRow("Baseline review · ${state.baselineReview.size} addresses", review) { review = !review } }
            if (review) items(state.baselineReview.toList(), key = { "baseline-$it" }) { address -> FinderCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(address, fontFamily = FontFamily.Monospace, fontSize = 11.sp); Text("Mute: ${state.mutes.kind(address)}"); MuteButtons(address, state.mutes, repo)
            } } }
        }
        if (devices.isEmpty()) item { Text(if (state.active) "Listening for BLE advertisements… A phone must be advertising to appear." else "No scan results yet. Start a search outdoors or use debug simulation.", color = FinderColors.neutral400) }
        if (search && devices.isNotEmpty()) {
            item(key = "featured-${devices.first().address}") { FeaturedDevice(devices.first(), state, repo) }
            if (devices.size > 1) {
                item { SectionLabel("ALSO NEARBY") }
                items(devices.drop(1).chunked(2), key = { it.first().address }) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { device -> Box(Modifier.weight(1f)) { CompactDevice(device, state, repo) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else if (!search && grouped) {
            packetBuckets(devices).forEachIndexed { i, bucket ->
                item(key = "profile-${bucket.key}") {
                    Text("${bucket.label} · ${bucket.devices.size} addresses", fontSize = 12.sp, color = FinderColors.accent)
                    if (i == 0) SmallNote("Buckets share manufacturer / service data format. They may contain multiple physical devices. Addresses remain separate for tracking and muting.")
                }
                items(bucket.devices, key = { it.address }) { DeviceCard(it, state, repo, menuChange) }
            }
        } else if (!search) items(devices, key = { it.address }) { DeviceCard(it, state, repo, menuChange) }
        item { SmallNote("RSSI is relative signal strength, not distance. Addresses can rotate.") }
    }
    if (readiness) ModalBottomSheet(onDismissRequest = { readiness = false }, containerColor = FinderColors.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Search readiness", style = MaterialTheme.typography.titleLarge)
            checks.forEach { (label, good) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FinderIcon(if (good) R.drawable.ic_check else R.drawable.ic_warning_circle)
                Text(label, fontSize = 12.sp, color = if (good) FinderColors.accent300 else FinderColors.neutral400)
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Bluetooth", { openSettings(context, AndroidSettings.ACTION_BLUETOOTH_SETTINGS) }, secondary = true)
                ActionButton("Location", { openSettings(context, AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS) }, secondary = true)
            }
            ActionButton("Battery optimization settings", { openSettings(context, AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }, secondary = true)
            ActionButton("App permissions / notifications", { openSettings(context, AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")) }, secondary = true)
            SmallNote("Screen-off discovery and pings are enabled; keep display awake is ${if (state.settings.keepAwake) "enabled" else "disabled"}. Phone power settings can still limit results.")
            SmallNote("Baseline collects search-party transmitters, then mutes them for this session.")
            ActionButton("Unmute all session devices", repo::unmuteSession, secondary = true)
            ActionButton(if (state.audioMuted) "Unmute audio" else "Mute all audio", repo::audioMute, secondary = true)
            if (DemoFactory.available && !state.active) Toggle("Debug simulation", simulation, onChange = setSimulation)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable private fun FeaturedDevice(device: DeviceRecord, state: SearchState, repo: SearchRepository) {
    val samples = state.signalHistory[device.address].orEmpty()
    FinderCard(Modifier.fillMaxWidth(), featured = true) {
        Column(Modifier.background(Brush.radialGradient(listOf(FinderColors.accent900.copy(alpha = .65f), FinderColors.surface))).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("STRONGEST NOW", fontSize = 10.sp, color = FinderColors.accent)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.displayName ?: "Unnamed transmitter", fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(device.address, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FinderColors.neutral400)
                }
                Text(signal(device.stats.current), fontSize = 44.sp, color = FinderColors.accent300, maxLines = 1)
            }
            SignalGraph(samples, state.nowElapsed, state.settings.rssiMin, state.settings.rssiMax, Modifier.fillMaxWidth().height(56.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${trend(samples, state.nowElapsed)} · smoothed ${number(device.stats.smoothed)} · ${ageText(device, state)}", Modifier.weight(1f), fontSize = 11.sp, color = FinderColors.neutral400)
                ActionButton("Track", { repo.selectTarget(device.address) }, icon = R.drawable.ic_arrow_right, iconAfter = true)
            }
        }
    }
}

@Composable private fun CompactDevice(device: DeviceRecord, state: SearchState, repo: SearchRepository) {
    FinderCard(Modifier.fillMaxWidth().clickable { repo.selectTarget(device.address) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.displayName ?: "Unnamed transmitter", Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.mutes.isMuted(device.address)) FinderIcon(R.drawable.ic_bell_slash, description = "Muted", modifier = Modifier.size(16.dp))
            }
            Text(device.address.takeLast(8), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FinderColors.neutral600)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(signal(device.stats.current), Modifier.weight(1f), fontSize = 24.sp, maxLines = 1)
                Text(ageText(device, state), fontSize = 10.sp, color = FinderColors.neutral500, maxLines = 1)
            }
            SignalGraph(state.signalHistory[device.address].orEmpty(), state.nowElapsed, state.settings.rssiMin, state.settings.rssiMax, Modifier.fillMaxWidth().height(22.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun DeviceCard(device: DeviceRecord, state: SearchState, repo: SearchRepository, menuChange: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    fun setMenu(open: Boolean) { menu = open; menuChange(open) }
    val stats = device.stats
    val muted = state.mutes.isMuted(device.address)
    val strongest = state.devices.maxByOrNull { it.stats.current ?: Int.MIN_VALUE }?.address == device.address
    FinderCard(Modifier.fillMaxWidth().combinedClickable(onClick = { repo.selectTarget(device.address) }, onLongClick = { setMenu(true) })) {
        Column(Modifier.padding(start = 14.dp, top = 8.dp, end = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(device.displayName ?: "Unnamed transmitter", Modifier.weight(1f, fill = false), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (muted) StatusTag("Muted") else if (state.nowWall - stats.firstSeen < 6000) StatusTag("New", true)
                    }
                    Text(device.address, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FinderColors.neutral400, maxLines = 1)
                }
                SignalGraph(state.signalHistory[device.address].orEmpty(), state.nowElapsed, state.settings.rssiMin, state.settings.rssiMax, Modifier.padding(horizontal = 6.dp).size(64.dp, 28.dp), muted)
                Text(signal(stats.current), fontSize = 26.sp, color = if (muted) FinderColors.neutral500 else if (strongest) FinderColors.accent300 else FinderColors.text, maxLines = 1)
                Box {
                    IconButton(onClick = { setMenu(true) }) { FinderIcon(R.drawable.ic_dots_three_vertical, description = "Device actions", color = FinderColors.neutral500) }
                    DropdownMenu(menu, { setMenu(false) }) {
                        DropdownMenuItem(text = { Text("Mute for session") }, onClick = { repo.setMute(device.address); setMenu(false) })
                        DropdownMenuItem(text = { Text("Always mute") }, onClick = { repo.setMute(device.address, always = true); setMenu(false) })
                        DropdownMenuItem(text = { Text("Unmute (both types)") }, onClick = { repo.setMute(device.address, muted = false); setMenu(false) })
                    }
                }
            }
            Text("Best ${signal(stats.strongest)} · ${stats.count} results · ${number(stats.rate(state.nowElapsed))}/s · ${ageText(device, state)}", fontSize = 11.sp, color = FinderColors.neutral500, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable fun MuteButtons(address: String, mutes: MuteRules, repo: SearchRepository) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Mute for session", { repo.setMute(address) }, secondary = true)
        ActionButton("Always mute", { repo.setMute(address, always = true) }, secondary = true)
    }
    if (mutes.isMuted(address)) ActionButton("Unmute (remove both mute types)", { repo.setMute(address, muted = false) }, secondary = true)
}

@Composable fun Toggle(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = value, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = FinderColors.accent200, checkedTrackColor = FinderColors.accent700,
                uncheckedThumbColor = FinderColors.neutral500, uncheckedTrackColor = FinderColors.neutral800,
                checkedBorderColor = FinderColors.accent700, uncheckedBorderColor = FinderColors.neutral800))
    }
}

@Composable fun DisclosureRow(label: String, expanded: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = click).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        FinderIcon(if (expanded) R.drawable.ic_caret_down else R.drawable.ic_caret_right, description = if (expanded) "Collapse" else "Expand", color = FinderColors.neutral600)
    }
}

fun openSettings(context: android.content.Context, action: String, data: Uri? = null) {
    runCatching { context.startActivity(Intent(action, data)) }.onFailure {
        android.widget.Toast.makeText(context, "This settings screen is unavailable on this phone.", android.widget.Toast.LENGTH_LONG).show()
    }
}
