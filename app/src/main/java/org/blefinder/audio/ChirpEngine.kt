package org.blefinder.audio

import android.media.*
import android.os.Process
import org.blefinder.core.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal interface PcmOutput : AutoCloseable {
    fun write(buffer: ShortArray, offset: Int, size: Int): Int
    fun setSpeaker(enabled: Boolean): Boolean
    fun isSpeakerRouted(): Boolean
}

/** One continuously fed streaming AudioTrack and one reusable PCM buffer per search. */
class ChirpEngine internal constructor(
    private val failure: (String) -> Unit,
    private val outputFactory: () -> PcmOutput,
    private val now: () -> Long,
    private val recordingLoader: () -> Map<DiscoverySound, ShortArray> = { emptyMap() },
) : AutoCloseable {
    constructor(context: android.content.Context, failure: (String) -> Unit) :
        this(failure, { androidOutput(context) }, android.os.SystemClock::elapsedRealtime, { loadDiscoveryRecordings(context) })
    private data class Tone(val hz: Double, val duration: Int, val gain: Float, val sound: DiscoverySound?, val queuedAt: Long, val generation: Long)
    private val queue = ArrayBlockingQueue<Tone>(8)
    private val running = AtomicBoolean(true)
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var silenced = false
    @Volatile private var loudspeaker = false
    private val worker = Thread({ render() }, "BLE chirps").apply { start() }
    fun silence(value: Boolean) {
        silenced = value
        if (value) { generation.incrementAndGet(); queue.clear() }
    }
    fun configure(settings: Settings) {
        if (loudspeaker != settings.loudspeaker) {
            generation.incrementAndGet(); queue.clear()
            loudspeaker = settings.loudspeaker
        }
    }
    fun preview(settings: Settings) {
        configure(settings)
        silence(true); silence(false)
        if (settings.volume > 0) enqueue(Tone(1300.0, 80, settings.volume, settings.discoverySound, now(), generation.get()))
    }
    fun offer(observation: Observation, discovered: Boolean, settings: Settings) {
        configure(settings)
        if (silenced || settings.volume <= 0) return
        val special = discovered && settings.discoveries
        if (!special && (!settings.chirps || observation.rssi !in -127..126)) return
        enqueue(Tone(PitchMapping.frequency(observation.rssi, settings), settings.chirpMs, settings.volume.coerceIn(0f, 1f),
            settings.discoverySound.takeIf { special }, now(), generation.get()))
    }
    private fun enqueue(tone: Tone) {
        // Bound audio backlog only. Every scan result is persisted independently.
        if (!queue.offer(tone)) {
            if (tone.sound != null) queue.clear() else queue.poll()
            queue.offer(tone)
        }
    }
    private fun render() {
        var output: PcmOutput? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val recordings = recordingLoader()
            output = outputFactory()
            val buffer = ShortArray(480) // Ten milliseconds, including during silence.
            var recoveryAttempts = 0
            var healthySamples = 0
            var appliedSpeaker: Boolean? = null
            var routeFrames = 0
            var routeReported = false
            var tone: Tone? = null
            var pcm = ShortArray(0)
            var position = 0
            while (running.get()) {
                val stream = checkNotNull(output)
                val requestedSpeaker = loudspeaker
                if (appliedSpeaker != requestedSpeaker) {
                    routeReported = !stream.setSpeaker(requestedSpeaker)
                    if (routeReported) failure("Requested audio route unavailable; sound uses the current output at normal gain.")
                    appliedSpeaker = requestedSpeaker; routeFrames = 0
                }
                val speakerConfirmed = requestedSpeaker && stream.isSpeakerRouted()
                if (requestedSpeaker && !speakerConfirmed && !routeReported && ++routeFrames >= 50) {
                    failure("Phone speaker routing was not confirmed. Sound uses the current output at normal gain.")
                    routeReported = true
                }
                if (tone == null) {
                    tone = queue.poll()?.takeIf { !silenced && it.generation == generation.get() && now() - it.queuedAt <= 200 }
                    tone?.let {
                        pcm = it.sound?.let(recordings::get) ?: chirpPcm(it.hz, if (it.sound == null) it.duration else 80, it.sound != null)
                        position = 0
                    }
                }
                buffer.fill(0)
                tone?.let { active ->
                    val cancelled = silenced || active.generation != generation.get()
                    // Recheck the actual route for each block so headphones never receive the speaker boost.
                    val gain = active.gain.coerceIn(0f, 1f).let { if (speakerConfirmed) (it * 2f).coerceAtMost(1f) else it }
                    val scale = gain * (if (speakerConfirmed) 1f else 16000f / 32767f)
                    val count = minOf(buffer.size, pcm.size - position)
                    for (i in 0 until count) {
                        val fade = if (cancelled) cosineFade(buffer.size - 1 - i, buffer.size - 1) else 1.0
                        buffer[i] = (pcm[position + i] * scale * fade).toInt().coerceIn(-32767, 32767).toShort()
                    }
                    position += count
                    if (cancelled || position >= pcm.size) tone = null
                }
                var offset = 0
                while (offset < buffer.size && running.get()) {
                    val wrote = stream.write(buffer, offset, buffer.size - offset)
                    if (!running.get()) break
                    if (wrote == AudioTrack.ERROR_DEAD_OBJECT) {
                        check(recoveryAttempts < 3) { "Audio output repeatedly became unavailable" }
                        recoveryAttempts++; healthySamples = 0
                        runCatching { stream.close() }
                        output = null
                        output = outputFactory()
                        appliedSpeaker = null; routeFrames = 0; routeReported = false
                        // Drop this failed block; recompute gain on the replacement's confirmed route.
                        break
                    }
                    check(wrote > 0) { "Audio output failed ($wrote)" }
                    offset += wrote
                    healthySamples += wrote
                    // A stable second of playback allows recovery from a later, unrelated route loss.
                    if (healthySamples >= 48_000) { recoveryAttempts = 0; healthySamples = 0 }
                }
            }
        } catch (_: InterruptedException) { /* Normal shutdown. */ }
          catch (e: Exception) { failure("Audio unavailable; scanning continues: ${e.message}") }
        finally { runCatching { output?.close() } }
    }
    override fun close() { running.set(false); queue.clear(); worker.interrupt() }
}

private fun androidOutput(context: android.content.Context): PcmOutput {
    val manager = context.getSystemService(AudioManager::class.java)
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
        override fun setSpeaker(enabled: Boolean): Boolean {
            val device = if (enabled) manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return false else null
            return track.setPreferredDevice(device)
        }
        override fun isSpeakerRouted() = track.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        override fun write(buffer: ShortArray, offset: Int, size: Int) =
            track.write(buffer, offset, size, AudioTrack.WRITE_BLOCKING)
        override fun close() {
            try { runCatching { track.setPreferredDevice(null) }; runCatching { track.stop() } }
            finally { track.release() }
        }
    }
}

internal fun chirpAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    // RSSI audio is the app's output, rather than an optional system/UI click sound.
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()
