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
}
