package org.blefinder

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.blefinder.core.*
import org.blefinder.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class StorageTest {
    private lateinit var db: SearchDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SearchDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }
    private fun session() = SessionEntity("test", 1000, settingsJson = SearchJson.encodeToString(Settings())).also { db.dao().putSession(it) }
    private fun store(o: Observation) {
        db.dao().insertObservation(ObservationEntity(sessionId = "test", address = o.address, timestamp = o.timestamp, rssi = o.rssi, json = SearchJson.encodeToString(o)))
        db.dao().putDevice(DeviceEntity("test", o.address, SearchJson.encodeToString(DeviceRecord(o.address, SignalStats().add(o, .25), o))))
    }
    @Test fun jsonExportSnapshotExcludesLaterResultsAndIncludesGpsEvents() {
        session(); val a = observation(address = "A"); store(a)
        db.dao().insertLocation(LocationEntity(sessionId = "test", json = SearchJson.encodeToString(GeoFix(1.0, 2.0, 3f, 1000, 1000000))))
        db.dao().event(EventEntity(sessionId = "test", timestamp = 1000, type = "mute", detail = "A"))
        val exporter = SessionExporter(db); val snapshot = exporter.snapshot("test", null)
        store(observation(address = "B"))
        val result = StringWriter().also { exporter.write(snapshot, true, it) }.toString()
        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals(1, json["observations"]!!.jsonArray.size)
        assertEquals("A", json["observations"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals(1, json["devices"]!!.jsonArray.size); assertEquals(1, json["gpsObservations"]!!.jsonArray.size); assertEquals(1, json["events"]!!.jsonArray.size)
    }
    @Test fun exportStreamsAcrossMultiplePagesAndFiltersTarget() {
        session(); repeat(1101) { store(observation(at = it.toLong(), address = if (it % 2 == 0) "A" else "B")) }
        val exporter = SessionExporter(db)
        val csv = StringWriter().also { exporter.write(exporter.snapshot("test", null), false, it) }.toString()
        assertEquals(1102, csv.lineSequence().filter { it.isNotEmpty() }.count())
        val target = StringWriter().also { exporter.write(exporter.snapshot("test", "A"), true, it) }.toString()
        val json = Json.parseToJsonElement(target).jsonObject
        assertEquals(551, json["observations"]!!.jsonArray.size)
        assertTrue(json["observations"]!!.jsonArray.all { it.jsonObject["address"]!!.jsonPrimitive.content == "A" })
    }
    @Test fun emptySessionProducesValidJsonAndCsvHeader() {
        session(); val exporter = SessionExporter(db); val snapshot = exporter.snapshot("test", null)
        val json = StringWriter().also { exporter.write(snapshot, true, it) }.toString()
        assertEquals(0, Json.parseToJsonElement(json).jsonObject["observations"]!!.jsonArray.size)
        assertEquals(ExportCodec.csvHeader, StringWriter().also { exporter.write(snapshot, false, it) }.toString())
    }
    @Test fun recoveryMarksUnfinishedSessionsInterrupted() { session(); db.dao().recoverInterrupted(); assertEquals("interrupted", db.dao().session("test")!!.status) }
    @Test fun preferenceMutesSurviveRecreationAndCanBeRemoved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences(context).mutes(setOf("A", "B"))
        assertEquals(setOf("A", "B"), Preferences(context).load().second)
        Preferences(context).mutes(setOf("B"))
        assertEquals(setOf("B"), Preferences(context).load().second)
    }
    @Test fun settingsAreSavedAndValidated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences(context).settings(Settings(chirps = false, baselineSeconds = 60, loudspeaker = true))
        val settings = Preferences(context).load().first
        assertFalse(settings.chirps); assertEquals(60, settings.baselineSeconds)
        assertTrue(settings.loudspeaker)
    }
    @Test fun trackingAnExistingDeviceKeepsItsRssiAudioAndFiltersOtherDevices() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences(context).mutes(emptySet())
        Preferences(context).settings(Settings())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = SearchRepository(db, Preferences(context), scope)
        val audible = mutableListOf<Pair<String, Boolean>>()
        repo.sound = { o, discovered, _ -> audible += o.address to discovered }
        try {
            repo.start(false)
            val id = repo.state.value.sessionId!!
            repo.receive(observation(address = "A")); repo.receive(observation(address = "B"))
            repo.exportSnapshot(id, null)
            audible.clear()
            repo.selectTarget("A")
            repo.receive(observation(address = "B")); repo.receive(observation(address = "A"))
            repo.exportSnapshot(id, null)
            assertEquals(listOf("A" to false), audible)
            assertEquals(4L, db.dao().count(id))
            repo.selectTarget(null)
            repo.receive(observation(address = "B"))
            repo.exportSnapshot(id, null)
            assertEquals(listOf("A" to false, "B" to false), audible)
        } finally { scope.cancel() }
    }
    @Test fun repositoryLogsMutedAndNonTargetResultsAndArchivesSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences(context).mutes(emptySet())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = SearchRepository(db, Preferences(context), scope)
        val audible = mutableListOf<String>()
        repo.sound = { o, _, _ -> audible += o.address }
        try {
            repo.start(false)
            val id = repo.state.value.sessionId!!
            repo.setMute("A"); repo.selectTarget("B")
            repo.receive(observation(address = "A")); repo.receive(observation(address = "B"))
            val snapshot = repo.exportSnapshot(id, null) // serial barrier flushes received results
            assertEquals(2, snapshot.devices.size)
            val rows = db.dao().page(id, null, 0, Long.MAX_VALUE).map { SearchJson.decodeFromString<Observation>(it.json) }
            assertEquals("session", rows[0].mute); assertFalse(rows[0].target); assertTrue(rows[1].target)
            assertEquals(listOf("B"), audible)
            repo.stop(); repo.start(false)
            assertEquals(2L, db.dao().count(id)) // resume must never replace/cascade-delete the session
            repo.audioMute(); repo.receive(observation(address = "B"))
            repo.exportSnapshot(id, null)
            assertEquals(listOf("B"), audible)
            repo.restartSession()
            repo.stop() // also waits for restart
            assertEquals("archived", db.dao().session(id)!!.status)
            assertEquals(3L, db.dao().count(id)); assertTrue(repo.state.value.mutes.session.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun stoppingIsIdempotentAndArchivingPreservesTheOriginalEndTime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = SearchRepository(db, Preferences(context), scope)
        try {
            repo.start(false)
            val id = repo.state.value.sessionId!!
            repo.receive(observation(address = "A"))
            repo.stop()
            val stopped = db.dao().session(id)!!
            delay(20) // Make the archive time distinct from the already recorded stop time.
            repo.stop()
            assertEquals(stopped, db.dao().session(id))
            repo.restartSession()
            repo.exportSnapshot(id, null)
            val archived = db.dao().session(id)!!
            assertEquals(stopped.endedAt, archived.endedAt)
            assertEquals("archived", archived.status)
            assertEquals(1, db.dao().events(id, Long.MAX_VALUE).count { it.type == "stop" })
            assertEquals(1L, db.dao().count(id))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

}
