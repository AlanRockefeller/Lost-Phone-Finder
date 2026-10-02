package org.blefinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blefinder.R
import kotlinx.coroutines.*
import org.blefinder.audio.ChirpEngine
import org.blefinder.core.DiscoverySound
import org.blefinder.core.Settings
import org.blefinder.core.PitchMapping
import org.blefinder.data.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SearchState, repo: SearchRepository) {
    val s = state.settings
    val context = LocalContext.current
    val previewScope = rememberCoroutineScope()
    var previewEngine by remember { mutableStateOf<ChirpEngine?>(null) }
    var previewShutdown by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { previewShutdown?.cancel(); previewEngine?.close() } }
    LaunchedEffect(s.loudspeaker, state.audioMuted) {
        previewEngine?.configure(s)
        if (state.audioMuted) previewEngine?.silence(true)
    }
    fun preview(sound: DiscoverySound) {
        if (state.audioMuted) return
        previewShutdown?.cancel()
        val engine = previewEngine ?: ChirpEngine(context, repo::reportError).also { previewEngine = it }
        engine.preview(s.copy(discoverySound = sound))
        previewShutdown = previewScope.launch {
            delay(3000)
            engine.close()
            if (previewEngine === engine) previewEngine = null
        }
    }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var slider by remember { mutableStateOf<String?>(null) }
    BackHandler(page != null || slider != null) { if (slider != null) slider = null else page = null }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        if (page != null) {
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = null }) { FinderIcon(R.drawable.ic_arrow_left, description = "Back to settings") }
                Text(when (page) { "pitch" -> "Chirp pitch"; "sounds" -> "New-device sound"; "mutes" -> "Always-muted addresses"; else -> "Privacy" }, style = MaterialTheme.typography.titleLarge)
            } }
            when (page) {
                "pitch" -> item { PitchControls(s) { repo.updateSettings(it) } }
                "sounds" -> {
                    item { SmallNote("Recordings are bundled with the app and work offline. Preview uses app and phone media volume. ${if (state.audioMuted) "Unmute audio to preview." else ""}") }
                    items(DiscoverySound.entries) { sound ->
                        FinderCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { repo.updateSettings(s.copy(discoverySound = sound)) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = s.discoverySound == sound, onClick = { repo.updateSettings(s.copy(discoverySound = sound)) },
                                    colors = RadioButtonDefaults.colors(selectedColor = FinderColors.accent, unselectedColor = FinderColors.neutral500))
                                Text(sound.label, Modifier.weight(1f))
                            }
                            ActionButton("Preview", { preview(sound) }, enabled = !state.audioMuted && s.volume > 0)
                        } }
                    }
                    item { RecordingCredits() }
                }
                "mutes" -> {
                    item {
                        SmallNote("Matches exact BLE addresses across sessions. A rotating address may reappear under a different address.")
                        if (state.mutes.persistent.isEmpty()) Text("No always-muted addresses.")
                    }
                    items(state.mutes.persistent.sorted(), key = { it }) { address -> FinderCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text(address, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        ActionButton("Remove persistent mute", { repo.setMute(address, muted = false) }, secondary = true)
                    } } }
                }
                else -> item {
                    Text("Everything runs locally. No internet permission, account, analytics, ads, telemetry or uploads. System backup is disabled. Exports contain nearby BLE addresses and advertisements, which may represent other people's devices; treat them as search data.", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    SmallNote("${stringResource(R.string.app_name)} ${org.blefinder.BuildConfig.VERSION_NAME} · RSSI is relative signal strength, not a distance estimate. No device identity or ownership is inferred.")
                }
            }
        } else {
            item { SectionLabel("AUDIO") }
            item {
                Toggle("RSSI chirps", s.chirps) { repo.updateSettings(s.copy(chirps = it)) }
                SmallNote("Play a chirp for each unmuted result. Stronger signals produce higher notes.")
                FadingDivider()
                Toggle("New-device notification", s.discoveries) { repo.updateSettings(s.copy(discoveries = it)) }
                SmallNote("Sound when an address first appears in this session.")
                SettingsRow("Notification sound", s.discoverySound.label) { page = "sounds" }
                FadingDivider()
                Toggle("Loudspeaker mode", s.loudspeaker) { repo.updateSettings(s.copy(loudspeaker = it)) }
                SmallNote("Prefers the phone speaker over connected headphones and boosts sound on the confirmed speaker route. System media volume still limits loudness. The header button cycles normal, loudspeaker and muted audio.")
                SettingSlider("Volume", s.volume, 0f..1f, { "${(it * 100).roundToInt()}%" }) { repo.updateSettings(s.copy(volume = it)) }
                SmallNote("Uses the phone's media/sonification output. Check system volume and audio routing before walking.")
            }
            item {
                FinderCard(Modifier.fillMaxWidth().clickable { page = "pitch" }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Chirp pitch", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            FinderIcon(R.drawable.ic_caret_right, description = "Open chirp pitch", color = FinderColors.neutral600)
                        }
                        PitchPreview(s)
                    }
                }
            }
            item { SectionLabel("SEARCH") }
            item {
                SettingsRow("Smoothing", "%.2f".format(s.smoothing)) { slider = "smoothing" }
                FadingDivider()
                SettingsRow("Baseline duration", "${s.baselineSeconds} sec") { slider = "baseline" }
                FadingDivider()
                Toggle("GPS logging · ${if (s.gps) "On" else "Off"}", s.gps, enabled = !state.active) { repo.updateSettings(s.copy(gps = it)) }
                SmallNote("Optional GPS adds coordinates and accuracy to search observations. It requires location services; BLE can scan with GPS logging disabled. Change it before starting a search.")
                FadingDivider()
                Toggle("Keep display awake · ${if (s.keepAwake) "On" else "Off"}", s.keepAwake) { repo.updateSettings(s.copy(keepAwake = it)) }
                SmallNote("Search is configured to keep discovering devices and pinging while the screen is off. Active searches keep the CPU awake and use more battery; Stop releases it.")
                FadingDivider()
                SettingsRow("Always-muted addresses", state.mutes.persistent.size.toString()) { page = "mutes" }
                FadingDivider()
                SettingsRow("Privacy", "") { page = "privacy" }
            }
            item { ActionButton("Restore default settings", { repo.updateSettings(Settings(gps = if (state.active) s.gps else false)) }, Modifier.fillMaxWidth(), secondary = true) }
        }
    }
    if (slider != null) ModalBottomSheet(onDismissRequest = { slider = null }, containerColor = FinderColors.surface) {
        Column(Modifier.padding(16.dp)) {
            if (slider == "smoothing") {
                SettingSlider("RSSI smoothing coefficient", s.smoothing.toFloat(), .01f..1f, { "%.2f".format(it) }) { repo.updateSettings(s.copy(smoothing = it.toDouble())) }
                SmallNote("Higher smoothing coefficient responds faster. Audio always uses raw per-result RSSI.")
            } else SettingSlider("Baseline duration", s.baselineSeconds.toFloat(), 5f..120f, { "${it.roundToInt()} sec" }) { repo.updateSettings(s.copy(baselineSeconds = it.roundToInt())) }
            ActionButton("Done", { slider = null }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable private fun SettingsRow(label: String, value: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = click).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = FinderColors.neutral400, maxLines = 1)
        FinderIcon(R.drawable.ic_caret_right, color = FinderColors.neutral600)
    }
}

@Composable private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String, changed: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Text(format(draft), fontSize = 12.sp, color = FinderColors.accent300, maxLines = 1)
    }
    Slider(value = draft.coerceIn(range), valueRange = range, onValueChange = { draft = it }, onValueChangeFinished = { changed(draft) },
        modifier = Modifier.heightIn(min = 48.dp), colors = SliderDefaults.colors(thumbColor = FinderColors.accent200, activeTrackColor = FinderColors.accent, inactiveTrackColor = FinderColors.neutral800))
}

@Composable private fun PitchPreview(s: Settings) {
    Canvas(Modifier.fillMaxWidth().height(64.dp).semantics {
        contentDescription = "Pitch rises from ${s.pitchMin} Hz at ${s.rssiMin} dBm to ${s.pitchMax} Hz at ${s.rssiMax} dBm"
    }) {
        drawLine(FinderColors.neutral800, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        val curve = Path()
        for (i in 0..70) {
            val rssi = s.rssiMin + (s.rssiMax - s.rssiMin) * i / 70
            val fraction = (PitchMapping.frequency(rssi, s) - s.pitchMin) / (s.pitchMax - s.pitchMin)
            val x = size.width * i / 70f
            val y = size.height * (0.9f - 0.8f * fraction.toFloat())
            if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
        }
        drawPath(curve, FinderColors.accent, style = Stroke(2.dp.toPx()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${s.pitchMin} Hz at ${signal(s.rssiMin)} dBm", fontSize = 10.sp, color = FinderColors.neutral500, maxLines = 1)
        Text("${s.pitchMax} Hz at ${signal(s.rssiMax)} dBm", fontSize = 10.sp, color = FinderColors.neutral500, maxLines = 1)
    }
}

@Composable private fun PitchControls(s: Settings, changed: (Settings) -> Unit) {
    FinderCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tune your chirps", style = MaterialTheme.typography.titleLarge)
            SmallNote("Weak signal → low note. Strong signal → high note.")
            PitchPreview(s)
            SettingSlider("Low alert frequency", s.pitchMin.toFloat(), 100f..2000f, { "${it.roundToInt()} Hz" }) { changed(s.copy(pitchMin = it.roundToInt())) }
            SettingSlider("High alert frequency", s.pitchMax.toFloat(), 2100f..8000f, { "${it.roundToInt()} Hz" }) { changed(s.copy(pitchMax = it.roundToInt())) }
            Text("Signal sensitivity", style = MaterialTheme.typography.titleMedium)
            SmallNote("These levels set where the pitch reaches its low and high notes. Weaker / stronger results stay at those notes. All results are still displayed and logged.")
            SettingSlider("Weak signal → low note", s.rssiMin.toFloat(), -120f..-60f, { "${it.roundToInt()} dBm" }) { changed(s.copy(rssiMin = it.roundToInt())) }
            SettingSlider("Strong signal → high note", s.rssiMax.toFloat(), -50f..-10f, { "${it.roundToInt()} dBm" }) { changed(s.copy(rssiMax = it.roundToInt())) }
            SettingSlider("Chirp duration", s.chirpMs.toFloat(), 20f..50f, { "${it.roundToInt()} ms" }) { changed(s.copy(chirpMs = it.roundToInt())) }
        }
    }
}

@Composable private fun RecordingCredits() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallNote("Tugboat horn: a steam whistle recorded on Nixe by Work With Sounds / Konrad Gutkowski with Jonathan Nicolai. Oropendola calls: Richard Ranft, copyright The British Library Board. Both are shortened, filtered and normalized excerpts.")
        CreditLink("Tugboat recording", "https://commons.wikimedia.org/wiki/File:WWS_Steamwhistle.ogg")
        CreditLink("Oropendola recording", "https://commons.wikimedia.org/wiki/File:Montezuma_Oropendola_(Psarocolius_montezuma)_(W_PSAROCOLIUS_MONTEZUMA_R1_C4).ogg")
        CreditLink("Recording license: CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
    }
}

@Composable private fun CreditLink(label: String, url: String) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Text(label, fontSize = 11.sp, color = FinderColors.accent300,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { uriHandler.openUri(url) }.padding(vertical = 14.dp))
}
