package org.blefinder

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.blefinder.core.*
import org.blefinder.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class StorageSafetyTest {
    private lateinit var db: SearchDatabase
    private lateinit var scope: CoroutineScope
    private val now = AtomicLong(1000)
    private val usage = AtomicReference(StorageUsage(0, Long.MAX_VALUE))
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SearchDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking {
            val preferences = Preferences(ApplicationProvider.getApplicationContext<Context>())
            preferences.settings(Settings(baselineSeconds = 5)); preferences.mutes(emptySet())
        }
    }
    @After fun close() { scope.cancel(); db.close() }
    private fun repo(limits: StorageLimits = StorageLimits()) = SearchRepository(db,
        Preferences(ApplicationProvider.getApplicationContext<Context>()), scope, limits, { usage.get() }, { now.get() })
    private fun events(id: String) = db.dao().events(id, Long.MAX_VALUE)

    @Test fun repeatedStopAndFailedStartPreserveOriginalEndAndSingleStopEvent() = runBlocking {
        val repo = repo(); repo.start(false)
        val id = repo.state.value.sessionId!!
        repo.stop(); val stopped = db.dao().session(id)
        repo.stop()
        usage.set(StorageUsage(StorageLimits().maxDatabaseBytes, Long.MAX_VALUE))
        repo.start(false); repo.stop()
        assertEquals(stopped, db.dao().session(id))
        assertEquals(1, events(id).count { it.type == "stop" })
        assertFalse(repo.state.value.active)
        assertTrue(repo.state.value.error!!.contains("Database storage limit"))
    }
    @Test fun databaseLimitStopsAndKeepsAnExportableCutoff() = runBlocking {
        val repo = repo(StorageLimits(maxDatabaseBytes = 100))
        val failures = AtomicInteger(); repo.fatalError = { failures.incrementAndGet() }
        repo.start(false); val id = repo.state.value.sessionId!!
        repo.receive(observation(address = "A")); repo.exportSnapshot(id, null)
        usage.set(StorageUsage(100, Long.MAX_VALUE)); now.set(2000); repo.tick(2000)
        val snapshot = repo.exportSnapshot(id, null)
        repo.receive(observation(address = "B")); repo.stop()
        assertFalse(repo.state.value.active)
        assertEquals(1L, db.dao().count(id))
        assertEquals(1, failures.get())
        assertEquals(1, events(id).count { it.type == "logging_limit" })
        assertEquals(1, events(id).count { it.type == "stop" })
        assertEquals("stopped", db.dao().session(id)!!.status)
        val output = StringWriter().also { SessionExporter(db).write(snapshot, true, it) }.toString()
        assertTrue(output.contains("logging_limit")); assertTrue(output.contains("\"address\":\"A\""))
    }
    @Test fun freeSpaceReserveIsCheckedBeforeStartAndWhileSearching() = runBlocking {
        val repo = repo(StorageLimits(minFreeBytes = 100))
        usage.set(StorageUsage(0, 99)); repo.start(false)
        assertFalse(repo.state.value.active); assertNull(repo.state.value.sessionId)
        usage.set(StorageUsage(0, 100)); repo.start(false)
        assertTrue(repo.state.value.active); val id = repo.state.value.sessionId!!
        usage.set(StorageUsage(0, 99)); now.set(2000); repo.tick(2000); repo.exportSnapshot(id, null)
        assertFalse(repo.state.value.active)
        assertTrue(repo.state.value.error!!.contains("nearly full"))
    }
    @Test fun addressLimitAllowsKnownAddressesAndKeepsPreviousSessions() = runBlocking {
        val repo = repo(StorageLimits(maxSessionAddresses = 2)); repo.start(false)
        val id = repo.state.value.sessionId!!
        repo.receive(observation(address = "A")); repo.receive(observation(address = "B")); repo.receive(observation(address = "B"))
        repo.exportSnapshot(id, null)
        assertEquals(3L, db.dao().count(id)); assertTrue(repo.state.value.active)
        repo.receive(observation(address = "C")); repo.exportSnapshot(id, null)
        assertEquals(3L, db.dao().count(id)); assertFalse(repo.state.value.active)
        repo.start(false); assertFalse(repo.state.value.active)
        repo.restartSession(); repo.stop(); repo.start(false)
        val fresh = repo.state.value.sessionId!!
        assertNotEquals(id, fresh)
        repo.receive(observation(address = "C")); repo.exportSnapshot(fresh, null)
        assertEquals(1L, db.dao().count(fresh)); assertEquals(3L, db.dao().count(id))
    }
    @Test fun backlogLimitStopsPromptlyAndDoesNotBlockControlCommands() = runBlocking {
        val repo = repo(StorageLimits(maxPendingResults = 2)); repo.start(false)
        val id = repo.state.value.sessionId!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val failures = AtomicInteger(); repo.fatalError = { failures.incrementAndGet() }
        repo.sound = { _, _, _ -> entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            repo.receive(observation(address = "A")); assertTrue(entered.await(5, TimeUnit.SECONDS))
            repo.receive(observation(address = "B")); repo.receive(observation(address = "C"))
            assertEquals(1, failures.get()) // Callback fires while persistence is still blocked.
            release.countDown(); repo.exportSnapshot(id, null); repo.stop()
            assertFalse(repo.state.value.active)
            assertTrue(repo.state.value.error!!.contains("Pending scan limit"))
            assertEquals(1L, db.dao().count(id))
            assertEquals(1, events(id).count { it.type == "stop" })
            assertEquals(1, events(id).count { it.type == "logging_limit" })
            repo.sound = null; repo.start(false); repo.receive(observation(address = "D")); repo.exportSnapshot(id, null)
            assertEquals(2L, db.dao().count(id)); assertTrue(repo.state.value.active)
        } finally { release.countDown() }
    }
    @Test fun staleBaselineTickCannotBeatCancellationBeforeDeadline() = runBlocking {
        val repo = repo(); repo.start(false); val id = repo.state.value.sessionId!!
        repo.beginBaseline(); repo.exportSnapshot(id, null)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        repo.sound = { _, _, _ -> entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            repo.receive(observation(address = "A")); assertTrue(entered.await(5, TimeUnit.SECONDS))
            repo.tick(2000) // Fired before the 6000 ms deadline, but blocked behind persistence.
            repo.cancelBaseline(); now.set(7000); release.countDown()
            repo.exportSnapshot(id, null)
            assertNull(repo.state.value.baseline)
            assertTrue(repo.state.value.baselineReview.isEmpty())
            assertTrue(repo.state.value.mutes.session.isEmpty())
            assertEquals(0, events(id).count { it.type == "baseline_finished" })
        } finally { release.countDown() }
    }
    @Test fun staleBaselineTickCannotBeatStopBeforeDeadline() = runBlocking {
        val repo = repo(); repo.start(false); val id = repo.state.value.sessionId!!
        repo.beginBaseline(); repo.exportSnapshot(id, null)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        repo.sound = { _, _, _ -> entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            repo.receive(observation(address = "A")); assertTrue(entered.await(5, TimeUnit.SECONDS))
            repo.tick(2000)
            val stop = async { repo.stop() }
            // Start the suspend stop so its command enters the queue before advancing the clock.
            yield(); now.set(7000); release.countDown(); stop.await()
            assertTrue(repo.state.value.mutes.session.isEmpty())
            assertEquals(0, events(id).count { it.type == "baseline_finished" })
            assertEquals(1, events(id).count { it.type == "baseline_cancelled" })
        } finally { release.countDown() }
    }
    @Test fun storageUsageIncludesAllocatedSqlitePages() {
        assertTrue(StorageUsage.read(db).databaseBytes > 0)
        assertEquals(Long.MAX_VALUE, StorageUsage.read(db).freeBytes)
    }
}
