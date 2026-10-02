package org.blefinder

import org.blefinder.audio.ChirpEngine
import org.blefinder.audio.PcmOutput
import org.blefinder.audio.chirpAudioAttributes
import org.blefinder.core.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class ChirpEngineTest {
    @Test fun chirpsUseMediaVolumeRatherThanSystemClickVolume() {
        val attributes = chirpAudioAttributes()
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, attributes.usage)
        assertEquals(android.media.AudioManager.STREAM_MUSIC, attributes.volumeControlStream)
    }
    // Advance one output write at a time, without depending on real audio hardware or sleeps.
    private class Output : PcmOutput {
        val writes = LinkedBlockingQueue<ShortArray>()
        val advance = Semaphore(0)
        val closed = CountDownLatch(1)
        val routes = LinkedBlockingQueue<Boolean>()
        var acceptSpeaker = true
        private var speaker = false
        override fun setSpeaker(enabled: Boolean): Boolean {
            routes.add(enabled)
            speaker = enabled && acceptSpeaker
            return !enabled || acceptSpeaker
        }
        override fun isSpeakerRouted() = speaker
        override fun write(buffer: ShortArray, offset: Int, size: Int): Int {
            advance.acquire()
            writes.put(buffer.copyOfRange(offset, offset + size))
            return size
        }
        fun next(): ShortArray {
            advance.release()
            return checkNotNull(writes.poll(5, TimeUnit.SECONDS)) { "Audio worker stopped feeding output" }
        }
        override fun close() { closed.countDown() }
    }

    @Test fun isolatedChirpsPlayAfterStartupAndLongIdleGaps() {
        val output = Output()
        var clock = 0L
        val failures = LinkedBlockingQueue<String>()
        val engine = ChirpEngine(failures::add, { output }, { clock })
        try {
            repeat(12) { assertTrue(output.next().all { it == 0.toShort() }) }
            engine.offer(observation(), false, Settings())
            // One idle write may already be pending when the observation arrives.
            assertTrue((1..5).map { output.next() }.any { chunk -> chunk.any { it != 0.toShort() } })
            clock = 10_000
            repeat(12) { assertTrue(output.next().all { it == 0.toShort() }) }
            engine.offer(observation(), false, Settings())
            assertTrue((1..5).map { output.next() }.any { chunk -> chunk.any { it != 0.toShort() } })
            assertTrue(failures.isEmpty())
        } finally {
            engine.close()
            assertTrue(output.closed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun muteKeepsStreamAliveAndUnmuteRestoresAnIsolatedChirp() {
        val output = Output()
        val failures = LinkedBlockingQueue<String>()
        val engine = ChirpEngine(failures::add, { output }, { 0L })
        try {
            engine.silence(true)
            engine.offer(observation(), false, Settings())
            repeat(12) { assertTrue(output.next().all { it == 0.toShort() }) }
            engine.silence(false)
            engine.offer(observation(), false, Settings())
            assertTrue((1..5).map { output.next() }.any { chunk -> chunk.any { it != 0.toShort() } })
            assertTrue(failures.isEmpty())
        } finally {
            engine.close()
            assertTrue(output.closed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun speakerModeBoostsOnlyTheSpeakerAndRestoresDefaultRoutingWhenDisabled() {
        val output = Output()
        val failures = LinkedBlockingQueue<String>()
        val engine = ChirpEngine(failures::add, { output }, { 0L })
        try {
            output.next()
            val normal = Settings(volume = 1f)
            engine.offer(observation(), false, normal)
            val ordinary = (1..5).flatMap { output.next().toList() }.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue(ordinary in 15000..16000)
            engine.configure(normal.copy(loudspeaker = true))
            repeat(3) { output.next() }
            engine.offer(observation(), false, normal.copy(loudspeaker = true))
            val boosted = (1..5).flatMap { output.next().toList() }.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue(boosted in 30000..32767)
            engine.configure(normal)
            repeat(3) { output.next() }
            assertEquals(listOf(false, true, false), output.routes.toList())
            assertTrue(failures.isEmpty())
        } finally { engine.close(); assertTrue(output.closed.await(5, TimeUnit.SECONDS)) }
    }

    @Test fun rejectedSpeakerRouteKeepsOrdinaryGainAndReportsFailureWithoutStoppingOutput() {
        val output = Output().apply { acceptSpeaker = false }
        val failures = LinkedBlockingQueue<String>()
        val engine = ChirpEngine(failures::add, { output }, { 0L })
        try {
            val settings = Settings(volume = 1f, loudspeaker = true)
            engine.configure(settings)
            repeat(3) { output.next() }
            engine.offer(observation(), false, settings)
            val peak = (1..5).flatMap { output.next().toList() }.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue(peak in 15000..16000)
            assertEquals(1, failures.size)
        } finally { engine.close(); assertTrue(output.closed.await(5, TimeUnit.SECONDS)) }
    }

    @Test fun speakerBoostStillRespectsAppVolumeAndMute() {
        val output = Output()
        val failures = LinkedBlockingQueue<String>()
        val engine = ChirpEngine(failures::add, { output }, { 0L })
        try {
            val settings = Settings(volume = 0.25f, loudspeaker = true)
            engine.configure(settings)
            repeat(3) { output.next() }
            engine.offer(observation(), false, settings)
            val peak = (1..5).flatMap { output.next().toList() }.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue(peak in 15000..16384)
            engine.offer(observation(), false, settings.copy(volume = 0f))
            repeat(5) { assertTrue(output.next().all { it == 0.toShort() }) }
            engine.silence(true)
            engine.offer(observation(), false, settings)
            repeat(5) { assertTrue(output.next().all { it == 0.toShort() }) }
            assertTrue(failures.isEmpty())
        } finally { engine.close(); assertTrue(output.closed.await(5, TimeUnit.SECONDS)) }
    }
    @Test fun selectedRecordingPlaysBeyondChirpBufferAndMuteCancelsWithFade() {
        val output = Output()
        val failures = LinkedBlockingQueue<String>()
        val recording = ShortArray(48_000) { 20_000 }
        val engine = ChirpEngine(failures::add, { output }, { 0L }, { mapOf(org.blefinder.core.DiscoverySound.OROPENDOLA to recording) })
        try {
            repeat(3) { output.next() }
            engine.offer(observation(), true, Settings(volume = 1f, discoverySound = org.blefinder.core.DiscoverySound.OROPENDOLA))
            val chunks = (1..15).map { output.next() }
            assertTrue(chunks.last().all { it > 9000 })
            // Target changes silence and immediately unmute; the old recording must still stop.
            engine.silence(true); engine.silence(false)
            val cancellation = (1..4).map { output.next() }
            val fade = cancellation.first { it.first() > 0 && it.last() == 0.toShort() }
            assertTrue(fade.toList().zipWithNext().all { (a, b) -> a >= b })
            assertTrue(cancellation.last().all { it == 0.toShort() })
            assertTrue(failures.isEmpty())
        } finally { engine.close(); assertTrue(output.closed.await(5, TimeUnit.SECONDS)) }
    }

}
