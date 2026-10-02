package org.blefinder.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import org.blefinder.R
import org.blefinder.data.*

object FinderColors {
    val bg = Color(0xFF161826)
    val surface = Color(0xFF232532)
    val text = Color(0xFFE9E9ED)
    val accent = Color(0xFF9184D9)
    val accent300 = Color(0xFFD2CEFD)
    val accent200 = Color(0xFFE7E5FE)
    val accent600 = Color(0xFF796CBF)
    val accent700 = Color(0xFF5D5294)
    val accent800 = Color(0xFF423A6A)
    val accent900 = Color(0xFF2B2741)
    val neutral400 = Color(0xFFB2B6CA)
    val neutral500 = Color(0xFF9397AB)
    val neutral600 = Color(0xFF75798C)
    val neutral700 = Color(0xFF595D6C)
    val neutral800 = Color(0xFF3F424D)
    val divider = text.copy(alpha = .16f)
}
private val inter = FontFamily(Font(R.font.inter_regular, FontWeight.Normal), Font(R.font.inter_medium, FontWeight.Medium))
private fun type(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = inter, fontWeight = weight, fontSize = size.sp, fontFeatureSettings = "tnum")

@Composable fun FinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = FinderColors.accent, onPrimary = FinderColors.text,
        primaryContainer = FinderColors.accent800, onPrimaryContainer = FinderColors.accent300,
        secondary = FinderColors.accent300, onSecondary = FinderColors.bg,
        background = FinderColors.bg, onBackground = FinderColors.text,
        surface = FinderColors.surface, onSurface = FinderColors.text,
        surfaceVariant = FinderColors.surface, onSurfaceVariant = FinderColors.neutral400,
        surfaceContainer = FinderColors.surface, surfaceContainerHigh = FinderColors.surface,
        surfaceContainerHighest = FinderColors.surface, surfaceContainerLow = FinderColors.bg,
        surfaceContainerLowest = FinderColors.bg,
        outline = FinderColors.neutral800, outlineVariant = FinderColors.divider,
        error = FinderColors.accent300, onError = FinderColors.bg,
        scrim = FinderColors.bg),
        typography = Typography(
            displayLarge = type(88), displayMedium = type(44), displaySmall = type(36),
            headlineLarge = type(28, FontWeight.Medium), headlineMedium = type(24, FontWeight.Medium), headlineSmall = type(20, FontWeight.Medium),
            titleLarge = type(20, FontWeight.Medium), titleMedium = type(15, FontWeight.Medium), titleSmall = type(13, FontWeight.Medium),
            bodyLarge = type(15), bodyMedium = type(13), bodySmall = type(11),
            labelLarge = type(13), labelMedium = type(11), labelSmall = type(10)),
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(14.dp), extraLarge = RoundedCornerShape(14.dp)),
        content = { CompositionLocalProvider(LocalContentColor provides FinderColors.text, content = content) })
}

@Composable fun FinderIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, description: String? = null, color: Color = FinderColors.accent) {
    Icon(painterResource(id), description, modifier.size(20.dp), tint = color)
}

@Composable fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, secondary: Boolean = false, icon: Int? = null, iconAfter: Boolean = false) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, if (secondary) FinderColors.divider else FinderColors.accent),
        interactionSource = interactions, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (secondary) FinderColors.text else FinderColors.accent,
            containerColor = if (pressed) FinderColors.accent.copy(alpha = .22f) else Color.Transparent)) {
        if (icon != null && !iconAfter) { FinderIcon(icon, color = if (secondary) FinderColors.text else FinderColors.accent); Spacer(Modifier.width(6.dp)) }
        Text(label, maxLines = 1)
        if (icon != null && iconAfter) { Spacer(Modifier.width(6.dp)); FinderIcon(icon) }
    }
}

@Composable fun FinderCard(modifier: Modifier = Modifier, featured: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, if (featured) FinderColors.accent600 else FinderColors.neutral800),
        colors = CardDefaults.cardColors(containerColor = FinderColors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), content = content)
}

@Composable fun FadingDivider() {
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        val edge = (48.dp.toPx() / size.width).coerceAtMost(.5f)
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, edge to FinderColors.divider,
            1f - edge to FinderColors.divider, 1f to Color.Transparent))
    }
}

@Composable fun SectionLabel(label: String) { Text(label, color = FinderColors.neutral500, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
@Composable fun SmallNote(text: String) { Text(text, color = FinderColors.neutral500, fontSize = 11.sp) }

fun trend(samples: List<RssiSample>, now: Long): String {
    val recent = samples.filter { it.elapsedMillis in (now - 10_000)..now }.sortedBy { it.elapsedMillis }
    if (recent.size < 2) return "Steady"
    val origin = recent.first().elapsedMillis
    val meanX = recent.map { (it.elapsedMillis - origin) / 1000.0 }.average()
    val meanY = recent.map { it.smoothed }.average()
    val variance = recent.sumOf { val x = (it.elapsedMillis - origin) / 1000.0 - meanX; x * x }
    val slope = if (variance == 0.0) 0.0 else recent.sumOf { ((it.elapsedMillis - origin) / 1000.0 - meanX) * (it.smoothed - meanY) } / variance
    return when { slope > .2 -> "Rising"; slope < -.2 -> "Falling"; else -> "Steady" }
}

@Composable fun SignalGraph(samples: List<RssiSample>, now: Long, min: Int, max: Int,
    modifier: Modifier, muted: Boolean = false, grid: Boolean = false) {
    Canvas(modifier.semantics { contentDescription = "RSSI over the last 60 seconds, from $min to $max dBm" }) {
        if (grid) repeat(3) { i ->
            val y = size.height * (i + 1) / 4f
            drawLine(FinderColors.neutral800, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
        val segments = signalSegments(samples, now)
        fun x(p: RssiSample) = size.width * signalGraphPosition(p, now, min, max).x
        fun y(p: RssiSample) = size.height * signalGraphPosition(p, now, min, max).y
        segments.forEach { segment ->
            val line = Path().apply { segment.forEachIndexed { i, p -> if (i == 0) moveTo(x(p), y(p)) else lineTo(x(p), y(p)) } }
            val area = Path().apply { addPath(line); lineTo(x(segment.last()), size.height); lineTo(x(segment.first()), size.height); close() }
            drawPath(area, FinderColors.accent900)
            val color = if (muted) FinderColors.neutral700 else FinderColors.accent
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (segment.size == 1) drawCircle(color, 2.dp.toPx(), Offset(x(segment.first()), y(segment.first())))
        }
    }
}

/** Normalized coordinates use fixed time and configured RSSI bounds, irrespective of the data. */
fun signalGraphPosition(sample: RssiSample, now: Long, min: Int, max: Int): Offset = Offset(
    ((sample.elapsedMillis - (now - SIGNAL_HISTORY_MS)) / SIGNAL_HISTORY_MS.toFloat()).coerceIn(0f, 1f),
    1f - ((sample.rssi - min).toFloat() / (max - min).coerceAtLeast(1)).coerceIn(0f, 1f),
)
