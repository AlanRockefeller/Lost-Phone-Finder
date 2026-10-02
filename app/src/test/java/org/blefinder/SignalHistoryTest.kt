package org.blefinder

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.blefinder.core.Settings
import org.blefinder.data.*
import org.blefinder.ui.trend
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class SignalHistoryTest {
    private lateinit var db: SearchDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: SearchRepository
    private val now = AtomicLong(60_000)

    @Before fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SearchDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preferences = Preferences(context)
        preferences.settings(Settings()); preferences.mutes(emptySet())
        repo = SearchRepository(db, preferences, scope, storageUsage = { StorageUsage(0, Long.MAX_VALUE) }, elapsedNow = now::get)
        repo.recent("unused")
        assertFalse(repo.state.value.hasSearched)
        repo.start(true)
    }
    @After fun close() { scope.cancel(); db.close() }
    private suspend fun publish() {
        repo.tick(now.get())
        repo.exportSnapshot(repo.state.value.sessionId!!, null)
    }

    @Test fun historyExpiresDuringSilenceWithoutRemovingRecordedResults() = runBlocking {
        repo.receive(observation(-80, 1_000, "A")); repo.receive(observation(-60, 60_000, "A"))
        publish()
        val oldSnapshot = repo.state.value.signalHistory
        assertEquals(listOf(1_000L, 60_000L), oldSnapshot["A"]!!.map { it.elapsedMillis })
        assertEquals(-75.0, oldSnapshot["A"]!!.last().smoothed, .001)
        now.set(61_001); publish()
        assertEquals(listOf(60_000L), repo.state.value.signalHistory["A"]!!.map { it.elapsedMillis })
        assertEquals(2, oldSnapshot["A"]!!.size)
        now.set(120_001); publish()
        assertTrue(repo.state.value.signalHistory.isEmpty())
        assertEquals(2L, db.dao().count(repo.state.value.sessionId!!))
        assertEquals(2L, repo.state.value.devices.single().stats.count)
    }

    @Test fun lateAndUnavailableSignalsAreStillLoggedAndFreshSessionsClearGraphs() = runBlocking {
        now.set(400_000)
        repo.receive(observation(-70, 1_000, "A"))
        repo.receive(observation(127, 400_000, "B"))
        repo.receive(observation(-50, 400_000, "C"))
        publish()
        val id = repo.state.value.sessionId!!
        assertEquals(setOf("C"), repo.state.value.signalHistory.keys)
        assertEquals(3L, db.dao().count(id))
        repo.restartSession(); publish()
        assertNotEquals(id, repo.state.value.sessionId)
        assertTrue(repo.state.value.signalHistory.isEmpty())
        assertTrue(repo.state.value.devices.isEmpty())
        assertEquals(3L, db.dao().count(id))
    }

    @Test fun trendUsesSmoothedSlopeAndReturnsToSteadyDuringSilence() {
        val rising = listOf(RssiSample(50_000, -80, -75.0), RssiSample(55_000, -50, -70.0), RssiSample(60_000, -90, -65.0))
        assertEquals("Rising", trend(rising, 60_000))
        assertEquals("Falling", trend(rising.map { it.copy(smoothed = -140 - it.smoothed) }, 60_000))
        assertEquals("Steady", trend(rising.map { it.copy(smoothed = -70.0) }, 60_000))
        assertEquals("Steady", trend(rising, 71_000))
    }
    @Test fun stoppedStatusRequiresSearchAndSpeakerCyclesPreserveOtherSettings() = runBlocking {
        assertTrue(repo.state.value.hasSearched)
        repo.stop()
        assertFalse(repo.state.value.active)
        assertTrue(repo.state.value.hasSearched)
        val settings = Settings(volume = .4f, discoverySound = org.blefinder.core.DiscoverySound.TUGBOAT)
        repo.updateSettings(settings); publish()
        repo.cycleSoundMode(); publish()
        assertTrue(repo.state.value.settings.loudspeaker)
        assertFalse(repo.state.value.audioMuted)
        repo.cycleSoundMode(); publish()
        assertTrue(repo.state.value.audioMuted)
        repo.cycleSoundMode(); publish()
        assertFalse(repo.state.value.audioMuted)
        assertEquals(settings, repo.state.value.settings)
    }

    @Test fun publishedFeaturedAddressIsStableDespiteSortChangesAndResetsForMutesAndSessions() = runBlocking {
        repo.receive(observation(-60, 60_000, "A")); repo.receive(observation(-61, 60_000, "B")); publish()
        assertEquals("A", repo.state.value.featuredAddress)
        now.set(61_000)
        repo.receive(observation(-60, 61_000, "A")); repo.receive(observation(-54, 61_000, "B")); publish()
        assertEquals("A", repo.state.value.featuredAddress)
        assertEquals("B", org.blefinder.core.visibleDevices(repo.state.value.devices, org.blefinder.core.SortOrder.CURRENT, true, repo.state.value.mutes).first().address)
        now.set(62_999)
        repo.receive(observation(-60, 62_999, "A")); repo.receive(observation(-54, 62_999, "B")); publish()
        assertEquals("A", repo.state.value.featuredAddress)
        now.set(63_000)
        repo.receive(observation(-54, 63_000, "B")); publish()
        assertEquals("B", repo.state.value.featuredAddress)
        repo.setMute("B"); publish()
        assertEquals("A", repo.state.value.featuredAddress)
        repo.restartSession(); publish()
        assertNull(repo.state.value.featuredAddress)
        assertTrue(repo.state.value.signalHistory.isEmpty())
    }

}
