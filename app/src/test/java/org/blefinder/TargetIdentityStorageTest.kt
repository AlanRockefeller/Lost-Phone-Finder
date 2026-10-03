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
import kotlinx.serialization.json.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class TargetIdentityStorageTest {
    private lateinit var db: SearchDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SearchDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }
    @Test fun targetAudioStatisticsGraphHistoryAndExportContinueAcrossRotation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences(context).settings(Settings()); Preferences(context).mutes(emptySet())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var now = 0L
        val repo = SearchRepository(db, Preferences(context), scope, elapsedNow = { now })
        val audible = mutableListOf<String>()
        repo.sound = { o, _, _ -> audible += o.address }
        try {
            repo.start(false); val id = repo.state.value.sessionId!!
            repeat(4) { i -> now = i * 1000L; repo.receive(identityObservation(ID_A, now)) }
            repo.exportSnapshot(id, null); repo.selectTarget(ID_A); repo.exportSnapshot(id, null)
            audible.clear()
            repeat(4) { i -> now = 6000 + i * 1000L; repo.receive(identityObservation(ID_B, now)) }
            val snapshot = repo.exportSnapshot(id, ID_B)
            repo.tick(now); repo.exportSnapshot(id, null)
            val state = repo.state.value
            assertEquals(ID_A, state.targetSeed); assertEquals(ID_B, state.target)
            assertEquals(5L, state.targetStats!!.count)
            assertEquals(listOf(ID_B), audible)
            assertEquals(setOf(ID_A, ID_B), state.targetCandidate!!.addresses)
            assertEquals(5, state.targetHistory.size)
            assertEquals(ID_A, state.targetAddressChange!!.from)
            val history = repo.recent(ID_B)
            assertEquals(8, history.size); assertEquals(setOf(ID_A, ID_B), history.map { it.address }.toSet())
            val rows = db.dao().page(id, null, 0, Long.MAX_VALUE).map { SearchJson.decodeFromString<Observation>(it.json) }
            assertEquals(8, rows.size); assertEquals(1, rows.count { it.target }); assertEquals(ID_B, rows.last().address)
            assertEquals(2, db.dao().devices(id).size)
            assertTrue(db.dao().events(id, Long.MAX_VALUE).any { it.type == "target_address_changed" && it.detail.contains("algorithmVersion") })
            val json = Json.parseToJsonElement(StringWriter().also { SessionExporter(db).write(snapshot, true, it) }.toString()).jsonObject
            assertEquals(8, json["observations"]!!.jsonArray.size)
            assertEquals(2, json["devices"]!!.jsonArray.size)
            assertNotEquals(JsonNull, json["physicalIdentityInference"])
            val reconstructed = SessionExporter(db).reconstructIdentity(snapshot, ID_A)
            assertEquals(state.targetCandidate.addresses, reconstructed.addresses)
            repo.stop(); repo.start(false); repo.tick(now); repo.exportSnapshot(id, null)
            assertEquals(ID_B, repo.state.value.target); assertEquals(8L, db.dao().count(id))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun insufficientMatchNeverChangesTargetOrSoundsAndContradictionsRevokeAssociation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences(context).settings(Settings()); Preferences(context).mutes(emptySet())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var now = 0L
        val repo = SearchRepository(db, Preferences(context), scope, elapsedNow = { now })
        val audible = mutableListOf<String>(); repo.sound = { o, _, _ -> audible += o.address }
        try {
            repo.start(false); val id = repo.state.value.sessionId!!
            repeat(4) { repo.receive(identityObservation(ID_A, it * 1000L)) }
            repo.selectTarget(ID_A); repo.exportSnapshot(id, null); audible.clear()
            repeat(4) { repo.receive(identityObservation(ID_C, 6000 + it * 1000L, "010203")) }
            now = 9000; repo.tick(now); repo.exportSnapshot(id, null)
            assertEquals(ID_A, repo.state.value.target); assertTrue(audible.isEmpty())
            repeat(4) { repo.receive(identityObservation(ID_B, 6000 + it * 1000L)) }
            now = 9000; repo.tick(now); repo.exportSnapshot(id, null)
            assertEquals(ID_B, repo.state.value.target)
            assertEquals(5L, repo.state.value.targetStats!!.count)
            repeat(4) { i ->
                repo.receive(identityObservation(ID_A, 10000 + i * 1000L))
                repo.receive(identityObservation(ID_B, 10100 + i * 1000L))
            }
            now = 14000; repo.tick(now); repo.exportSnapshot(id, null)
            assertEquals(ID_A, repo.state.value.target)
            assertEquals(setOf(ID_A), repo.state.value.targetCandidate!!.addresses)
            assertTrue(repo.state.value.identitySuggestions.any { it.contradictions.any { reason -> reason.contains("simultaneous") } })
            assertTrue(db.dao().events(id, Long.MAX_VALUE).any { it.type == "target_identity_revoked" })
            val state = repo.state.value
            assertEquals(1L, state.targetStats!!.count)
            assertEquals(listOf(13000L), state.targetHistory.map { it.elapsedMillis })
            assertTrue(state.targetIdentityNotice!!.contains("restarted"))
            assertTrue(db.dao().events(id, Long.MAX_VALUE).any { it.type == "target_statistics_restarted" })
            assertEquals(20L, db.dao().count(id))
            repo.selectTarget(ID_A); repo.exportSnapshot(id, null)
            assertNull(repo.state.value.targetIdentityNotice)
            assertEquals(8L, repo.state.value.targetStats!!.count)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun rejectingPreviousAddressRestartsAggregateEvenWhenAlreadyFollowingSeed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences(context).settings(Settings()); Preferences(context).mutes(emptySet())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var now = 0L
        val repo = SearchRepository(db, Preferences(context), scope, elapsedNow = { now })
        try {
            repo.start(false); val id = repo.state.value.sessionId!!
            repeat(4) { repo.receive(identityObservation(ID_A, it * 1000L)) }
            repo.selectTarget(ID_A); repo.exportSnapshot(id, null)
            repeat(4) { repo.receive(identityObservation(ID_B, 6000 + it * 1000L)) }
            repo.tick(9000); repo.exportSnapshot(id, null)
            assertEquals(ID_B, repo.state.value.target)
            repeat(4) { repo.receive(identityObservation(ID_A, 14000 + it * 1000L)) }
            repo.tick(17000); repo.exportSnapshot(id, null)
            assertEquals(ID_A, repo.state.value.target)
            assertEquals(setOf(ID_A, ID_B), repo.state.value.targetCandidate!!.addresses)
            assertNull(repo.state.value.targetIdentityNotice)
            repeat(4) { i ->
                repo.receive(identityObservation(ID_A, 18000 + i * 1000L))
                repo.receive(identityObservation(ID_B, 18100 + i * 1000L))
            }
            now = 22000; repo.tick(now); repo.exportSnapshot(id, null)
            val state = repo.state.value
            assertEquals(ID_A, state.target)
            assertEquals(setOf(ID_A), state.targetCandidate!!.addresses)
            assertNotNull(state.targetIdentityNotice)
            assertEquals(1L, state.targetStats!!.count)
            assertEquals(listOf(21000L), state.targetHistory.map { it.elapsedMillis })
            assertEquals(20L, db.dao().count(id))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun versionOneRecordsRemainReadableAndPagedReconstructionPreservesRows() {
        val session = SessionEntity("old", 0, settingsJson = "{}")
        db.dao().putSession(session)
        val original = identityObservation(ID_A, 0)
        // Old data has no identity fields added to its JSON, and uses the unchanged Room schema.
        db.dao().putDevice(DeviceEntity("old", ID_A, SearchJson.encodeToString(DeviceRecord(ID_A, SignalStats(), original))))
        val saved = (0..3).map { identityObservation(ID_A, it * 1000L) } + (0..3).map { identityObservation(ID_B, 6000 + it * 1000L) }
        saved.forEach { db.dao().insertObservation(ObservationEntity(sessionId = "old", address = it.address, timestamp = it.timestamp, rssi = it.rssi, json = SearchJson.encodeToString(it))) }
        val exporter = SessionExporter(db); val snapshot = exporter.snapshot("old", null)
        assertEquals(setOf(ID_A, ID_B), exporter.reconstructIdentity(snapshot, ID_A).addresses)
        assertEquals(8L, db.dao().count("old")); assertEquals(1, db.dao().devices("old").size)
        assertNull(snapshot.devices.single().probablePhysicalDeviceId)
    }
}
