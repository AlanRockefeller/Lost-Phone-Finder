package org.blefinder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import org.blefinder.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blefinder.core.Settings
import org.blefinder.core.PitchMapping
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
            Toggle("Loudspeaker mode", s.loudspeaker) { repo.updateSettings(s.copy(loudspeaker = it)) }
            Text("Prefers the phone speaker over connected headphones and boosts chirps. Use the audio slider and phone media volume to adjust loudness.", style = MaterialTheme.typography.bodySmall)
            SettingSlider("Audio volume", s.volume, 0f..1f, { "${(it * 100).roundToInt()}%" }) { repo.updateSettings(s.copy(volume = it)) }
            Text("Uses the phone's media/sonification output. Check system volume and audio routing before walking.", style = MaterialTheme.typography.bodySmall)
            SettingSlider("Chirp duration", s.chirpMs.toFloat(), 20f..50f, { "${it.roundToInt()} ms" }) { repo.updateSettings(s.copy(chirpMs = it.roundToInt())) }
            PitchControls(s) { repo.updateSettings(it) }
            SettingSlider("RSSI smoothing coefficient", s.smoothing.toFloat(), 0.01f..1f, { "%.2f".format(it) }) { repo.updateSettings(s.copy(smoothing = it.toDouble())) }
            Text("Higher smoothing coefficient responds faster. Audio always uses raw per-result RSSI.", style = MaterialTheme.typography.bodySmall)
            SettingSlider("Baseline duration", s.baselineSeconds.toFloat(), 5f..120f, { "${it.roundToInt()} sec" }) { repo.updateSettings(s.copy(baselineSeconds = it.roundToInt())) }
            Toggle("GPS logging (next search start)", s.gps, enabled = !state.active) { repo.updateSettings(s.copy(gps = it)) }
            Text("Optional GPS adds coordinates and accuracy to search observations. It requires location services; BLE can scan with GPS logging disabled.", style = MaterialTheme.typography.bodySmall)
            Toggle("Keep display awake during search", s.keepAwake) { repo.updateSettings(s.copy(keepAwake = it)) }
            Text("Search is configured to keep discovering devices and pinging while the screen is off. Active searches keep the CPU awake and use more battery; Stop releases it.")
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
            Text("${stringResource(R.string.app_name)} ${org.blefinder.BuildConfig.VERSION_NAME} • RSSI is relative signal strength, not a distance estimate. No device identity or ownership is inferred.")
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

@Composable private fun PitchControls(s: Settings, changed: (Settings) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tune your chirps", style = MaterialTheme.typography.titleLarge)
            Text("Weak signal → low note. Strong signal → high note.")
            val color = MaterialTheme.colorScheme.primary
            val grid = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.fillMaxWidth().height(100.dp).semantics {
                contentDescription = "Pitch rises from ${s.pitchMin} Hz at ${s.rssiMin} dBm to ${s.pitchMax} Hz at ${s.rssiMax} dBm"
            }) {
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
                val curve = Path()
                for (i in 0..70) {
                    val rssi = s.rssiMin + (s.rssiMax - s.rssiMin) * i / 70
                    val fraction = (PitchMapping.frequency(rssi, s) - s.pitchMin) / (s.pitchMax - s.pitchMin)
                    val x = size.width * i / 70f
                    val y = size.height * (0.9f - 0.8f * fraction.toFloat())
                    if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
                }
                drawPath(curve, color, style = Stroke(3.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Low: ${s.pitchMin} Hz")
                Text("High: ${s.pitchMax} Hz")
            }
            SettingSlider("Low alert frequency", s.pitchMin.toFloat(), 100f..2000f, { "${it.roundToInt()} Hz" }) { changed(s.copy(pitchMin = it.roundToInt())) }
            SettingSlider("High alert frequency", s.pitchMax.toFloat(), 2100f..8000f, { "${it.roundToInt()} Hz" }) { changed(s.copy(pitchMax = it.roundToInt())) }
            Text("Signal sensitivity", style = MaterialTheme.typography.titleMedium)
            Text("These levels set where the pitch reaches its low and high notes. Weaker / stronger results stay at those notes. All results are still displayed and logged.", style = MaterialTheme.typography.bodySmall)
            SettingSlider("Weak signal → low note", s.rssiMin.toFloat(), -120f..-60f, { "${it.roundToInt()} dBm" }) { changed(s.copy(rssiMin = it.roundToInt())) }
            SettingSlider("Strong signal → high note", s.rssiMax.toFloat(), -50f..-10f, { "${it.roundToInt()} dBm" }) { changed(s.copy(rssiMax = it.roundToInt())) }
        }
    }
}
