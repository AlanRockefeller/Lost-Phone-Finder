package org.blefinder.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blefinder.R

@Composable fun AudioIndicator(muted: Boolean, loud: Boolean, toggle: () -> Unit) {
    val label = when { muted -> "Audio muted. Tap for normal audio"; loud -> "Loudspeaker mode. Tap to mute"; else -> "Normal audio. Tap for loudspeaker mode" }
    IconButton(onClick = toggle, modifier = Modifier.size(48.dp)) {
        FinderIcon(if (muted) R.drawable.ic_speaker_slash else if (loud) R.drawable.ic_speaker_high else R.drawable.ic_speaker_low, description = label, modifier = Modifier.size(24.dp))
    }
}
