package org.blefinder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blefinder.core.Settings
import org.blefinder.data.*
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: SearchState, repo: SearchRepository) {
    val s = state.settings
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Audio and field settings", style = MaterialTheme.typography.headlineSmall)
            Toggle("RSSI chirps", s.chirps) { repo.updateSettings(s.copy(chirps = it)) }
            Toggle("New-device notification", s.discoveries) { repo.updateSettings(s.copy(discoveries = it)) }
            SettingSlider("Audio volume", s.volume, 0f..1f, { "${(it * 100).roundToInt()}%" }) { repo.updateSettings(s.copy(volume = it)) }
            Text("Uses the phone's media/sonification output. Check system volume and audio routing before walking.", style = MaterialTheme.typography.bodySmall)
            SettingSlider("Chirp duration", s.chirpMs.toFloat(), 20f..50f, { "${it.roundToInt()} ms" }) { repo.updateSettings(s.copy(chirpMs = it.roundToInt())) }
            SettingSlider("Minimum RSSI", s.rssiMin.toFloat(), -120f..-60f, { "${it.roundToInt()} dBm" }) { repo.updateSettings(s.copy(rssiMin = it.roundToInt())) }
            SettingSlider("Maximum RSSI", s.rssiMax.toFloat(), -50f..-10f, { "${it.roundToInt()} dBm" }) { repo.updateSettings(s.copy(rssiMax = it.roundToInt())) }
            SettingSlider("Minimum pitch", s.pitchMin.toFloat(), 100f..1000f, { "${it.roundToInt()} Hz" }) { repo.updateSettings(s.copy(pitchMin = it.roundToInt())) }
            SettingSlider("Maximum pitch", s.pitchMax.toFloat(), 2100f..6000f, { "${it.roundToInt()} Hz" }) { repo.updateSettings(s.copy(pitchMax = it.roundToInt())) }
            SettingSlider("RSSI smoothing coefficient", s.smoothing.toFloat(), 0.01f..1f, { "%.2f".format(it) }) { repo.updateSettings(s.copy(smoothing = it.toDouble())) }
            Text("Higher smoothing coefficient responds faster. Audio always uses raw per-result RSSI.", style = MaterialTheme.typography.bodySmall)
            SettingSlider("Baseline duration", s.baselineSeconds.toFloat(), 5f..120f, { "${it.roundToInt()} sec" }) { repo.updateSettings(s.copy(baselineSeconds = it.roundToInt())) }
            Toggle("GPS logging (next search start)", s.gps, enabled = !state.active) { repo.updateSettings(s.copy(gps = it)) }
            Text("Optional GPS adds coordinates and accuracy to search observations. It requires location services; BLE can scan with GPS logging disabled.", style = MaterialTheme.typography.bodySmall)
            Toggle("Keep display awake during search", s.keepAwake) { repo.updateSettings(s.copy(keepAwake = it)) }
            Text("Disabling keep-awake allows screen-off restrictions to interrupt broad BLE scans. The app does not hold a CPU wake lock.")
            OutlinedButton(onClick = { repo.updateSettings(Settings(gps = if (state.active) s.gps else false)) }) { Text("Restore default settings") }
        }
        item {
            HorizontalDivider()
            Text("Persistent mute list", style = MaterialTheme.typography.headlineSmall)
            Text("Matches exact BLE addresses across sessions. A rotating address may reappear under a different address.")
            if (state.mutes.persistent.isEmpty()) Text("No always-muted addresses.")
        }
        items(state.mutes.persistent.sorted(), key = { it }) { address ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(address)
                    OutlinedButton(onClick = { repo.setMute(address, muted = false) }) { Text("Remove persistent mute") }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("Privacy", style = MaterialTheme.typography.headlineSmall)
            Text("Everything runs locally. No internet permission, account, analytics, ads, telemetry or uploads. System backup is disabled. Exports contain nearby BLE addresses and advertisements, which may represent other people's devices; treat them as search data.")
            Text("BLE Search v0.1 • RSSI is relative signal strength, not a distance estimate. No device identity or ownership is inferred.")
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String, changed: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Text("$label: ${format(draft)}")
    Slider(value = draft.coerceIn(range), valueRange = range, onValueChange = { draft = it }, onValueChangeFinished = { changed(draft) })
}
