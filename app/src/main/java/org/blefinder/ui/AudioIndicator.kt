package org.blefinder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun AudioIndicator(muted: Boolean, loud: Boolean, toggle: () -> Unit) {
    val label = when { muted -> "Audio muted. Tap to unmute"; loud -> "Loudspeaker mode. Tap to mute"; else -> "Audio on. Tap to mute" }
    val color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    IconButton(onClick = toggle, modifier = Modifier.semantics { contentDescription = label }) {
        Canvas(Modifier.size(30.dp)) {
            val u = size.width / 30f
            val speaker = Path().apply {
                moveTo(3*u, 11*u); lineTo(8*u, 11*u); lineTo(14*u, 6*u)
                lineTo(14*u, 24*u); lineTo(8*u, 19*u); lineTo(3*u, 19*u); close()
            }
            drawPath(speaker, color)
            if (muted) drawLine(color, Offset(3*u, 3*u), Offset(27*u, 27*u), 2*u)
            else {
                drawArc(color, -55f, 110f, false, Offset(10*u, 9*u), Size(12*u, 12*u), style = Stroke(2*u))
                if (loud) drawArc(color, -55f, 110f, false, Offset(5*u, 4*u), Size(22*u, 22*u), style = Stroke(2*u))
            }
        }
    }
}
