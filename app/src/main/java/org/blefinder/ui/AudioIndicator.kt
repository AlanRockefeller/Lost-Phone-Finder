package org.blefinder.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blefinder.R

@Composable fun AudioIndicator(muted: Boolean, loud: Boolean, toggle: () -> Unit) {
    val label = when { muted -> "Audio muted. Tap to unmute"; loud -> "Loudspeaker mode. Tap to mute"; else -> "Audio on. Tap to mute" }
    IconButton(onClick = toggle, modifier = Modifier.size(48.dp)) {
        FinderIcon(if (muted) R.drawable.ic_speaker_slash else R.drawable.ic_speaker_high, description = label, modifier = Modifier.size(24.dp))
    }
}
