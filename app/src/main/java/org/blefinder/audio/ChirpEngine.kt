package org.blefinder.audio

import android.media.*
import android.os.Process
import org.blefinder.core.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

internal interface PcmOutput : AutoCloseable {
    fun write(buffer: ShortArray, offset: Int, size: Int): Int
}

/** One continuously fed streaming AudioTrack and one reusable PCM buffer per search. */
class ChirpEngine internal constructor(
    private val failure: (String) -> Unit,
    private val outputFactory: () -> PcmOutput,
    private val now: () -> Long,
) : AutoCloseable {
    constructor(failure: (String) -> Unit) : this(failure, ::androidOutput, android.os.SystemClock::elapsedRealtime)
    private data class Tone(val hz: Double, val duration: Int, val gain: Float, val discovery: Boolean, val queuedAt: Long)
    private val queue = ArrayBlockingQueue<Tone>(8)
    private val running = AtomicBoolean(true)
    @Volatile private var silenced = false
    private val worker = Thread({ render() }, "BLE chirps").apply { start() }
    fun silence(value: Boolean) { silenced = value; if (value) queue.clear() }
    fun offer(observation: Observation, discovered: Boolean, settings: Settings) {
        if (silenced || settings.volume <= 0) return
        val special = discovered && settings.discoveries
        if (!special && (!settings.chirps || observation.rssi !in -127..126)) return
        val tone = Tone(PitchMapping.frequency(observation.rssi, settings), settings.chirpMs, settings.volume, special, now())
        // Bound audio backlog only. Every scan result is still persisted independently.
        if (!queue.offer(tone)) {
            if (special) { queue.clear(); queue.offer(tone) }
            else { queue.poll(); queue.offer(tone) }
        }
    }
    private fun render() {
        var output: PcmOutput? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val rate = 48000
            output = outputFactory()
            val buffer = ShortArray(4800)
            while (running.get()) {
                val tone = queue.poll()?.takeIf { !silenced && now() - it.queuedAt <= 200 }
                // Keep MODE_STREAM fed even with sparse target results or all audio muted.
                // A lone chirp may otherwise never refill the startup/underrun threshold.
                val duration = if (tone == null) 10 else if (tone.discovery) 80 else tone.duration
                val size = rate * duration / 1000
                buffer.fill(0, 0, size)
                if (tone != null) for (i in 0 until size) {
                    val t = i.toDouble() / rate
                    val hz = if (tone.discovery) { if (i < size / 2) 1300.0 else 2100.0 } else tone.hz
                    val segment = if (tone.discovery) size / 2 else size
                    val pos = i % segment
                    val envelope = minOf(1.0, pos / 120.0, (segment - 1 - pos) / 120.0).coerceAtLeast(0.0)
                    buffer[i] = (sin(2 * PI * hz * t) * envelope * tone.gain * 16000).toInt().toShort()
                }
                var offset = 0
                while (offset < size && running.get()) {
                    val chunk = minOf(480, size - offset)
                    if (silenced) buffer.fill(0, offset, offset + chunk)
                    val wrote = output.write(buffer, offset, chunk)
                    check(wrote > 0) { "Audio output failed ($wrote)" }
                    offset += wrote
                }
            }
        } catch (_: InterruptedException) { /* Normal shutdown. */ }
          catch (e: Exception) { failure("Audio unavailable; scanning continues: ${e.message}") }
        finally { output?.close() }
    }
    override fun close() { running.set(false); queue.clear(); worker.interrupt() }
}

private fun androidOutput(): PcmOutput {
    val rate = 48000
    val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(min > 0) { "Audio output is unavailable" }
    val track = AudioTrack.Builder()
        .setAudioAttributes(chirpAudioAttributes())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(min, 4800))
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
    try {
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
        track.play()
    } catch (e: Exception) { track.release(); throw e }
    return object : PcmOutput {
        override fun write(buffer: ShortArray, offset: Int, size: Int) =
            track.write(buffer, offset, size, AudioTrack.WRITE_BLOCKING)
        override fun close() { try { runCatching { track.stop() } } finally { track.release() } }
    }
}

internal fun chirpAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    // RSSI audio is the app's output, rather than an optional system/UI click sound.
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()
