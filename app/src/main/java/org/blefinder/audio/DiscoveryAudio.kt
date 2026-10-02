package org.blefinder.audio

import android.content.Context
import org.blefinder.core.DiscoverySound
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun loadDiscoveryRecordings(context: Context): Map<DiscoverySound, ShortArray> =
    DiscoverySound.entries.filter { it.asset != null }.associateWith { sound ->
        val bytes = context.assets.open(requireNotNull(sound.asset)).use { it.readBytes() }
        require(bytes.size in 2..480_000 && bytes.size % 2 == 0) { "Invalid notification recording" }
        ShortArray(bytes.size / 2) { i -> ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() }
    }

internal fun cosineFade(position: Int, length: Int): Double =
    (1 - cos(PI * position.coerceIn(0, length).toDouble() / length.coerceAtLeast(1))) / 2

/** Zero-ended raised-cosine ramps avoid abrupt amplitude edges at chirp boundaries. */
internal fun chirpPcm(hz: Double, milliseconds: Int, twoNote: Boolean = false): ShortArray {
    val size = 48 * milliseconds
    val segment = if (twoNote) size / 2 else size
    var phase = 0.0
    return ShortArray(size) { i ->
        val frequency = if (twoNote) { if (i < segment) 1300.0 else 2100.0 } else hz
        val position = i % segment
        val ramp = minOf(480, segment / 2)
        val envelope = cosineFade(minOf(position, segment - 1 - position, ramp), ramp)
        val value = (sin(phase) * envelope * 32767).toInt().toShort()
        phase = (phase + 2 * PI * frequency / 48000) % (2 * PI)
        value
    }
}
