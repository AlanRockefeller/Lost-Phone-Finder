package org.blefinder

import org.blefinder.audio.chirpPcm
import org.blefinder.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DiscoveryAudioTest {
    @Test fun chirpsAndTwoNoteChimeHaveSmoothZeroEndedRamps() {
        for (hz in listOf(250.0, 3200.0, 8000.0)) for (duration in listOf(20, 30, 50)) {
            val pcm = chirpPcm(hz, duration)
            assertEquals(48 * duration, pcm.size)
            assertEquals(0, pcm.first().toInt())
            assertEquals(0, pcm.last().toInt())
            assertTrue(pcm.take(10).all { abs(it.toInt()) < 40 })
            assertTrue(pcm.takeLast(10).all { abs(it.toInt()) < 40 })
            assertTrue(pcm.any { abs(it.toInt()) > 10000 })
        }
        val chime = chirpPcm(1300.0, 80, true)
        assertEquals(0, chime[chime.size / 2 - 1].toInt())
        assertEquals(0, chime[chime.size / 2].toInt())
    }
    @Test fun oldPreferencesKeepDefaultSoundAndAllChoicesRoundTrip() {
        assertEquals(DiscoverySound.TWO_NOTE, SearchJson.decodeFromString<Settings>("{\"volume\":0.4}").discoverySound)
        for (sound in DiscoverySound.entries) {
            val settings = Settings(discoverySound = sound)
            assertEquals(settings, SearchJson.decodeFromString<Settings>(SearchJson.encodeToString(settings)))
        }
    }
}
