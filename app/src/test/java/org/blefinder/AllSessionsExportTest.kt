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
import java.io.IOException
import java.io.StringWriter
import java.io.Writer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class AllSessionsExportTest {
    private lateinit var db: SearchDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SearchDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }
    private val fix = GeoFix(1.0, 2.0, 3f, 1000, 1_000_000_000)
    private fun session(id: String, simulated: Boolean = false, status: String = "stopped") {
        db.dao().putSession(SessionEntity(id, 1000, endedAt = if (status == "active") null else 2000,
            status = status, simulated = simulated, settingsJson = SearchJson.encodeToString(Settings(gps = true))))
    }
    private fun store(id: String, index: Int = 0, name: String = "Bird, \"bloop\"\nnearby", simulated: Boolean = false) {
        val o = observation(at = index.toLong(), address = "A").copy(deviceName = name, location = fix, simulated = simulated)
        db.dao().insertObservation(ObservationEntity(sessionId = id, address = o.address, timestamp = o.timestamp, rssi = o.rssi, json = SearchJson.encodeToString(o)))
        db.dao().putDevice(DeviceEntity(id, o.address, SearchJson.encodeToString(DeviceRecord(o.address, SignalStats().add(o, .25), o))))
    }
    private fun selection() = AllSessionsExporter.Selection(db.dao().storedSessions().map { it.id }, Settings(volume = .4f), setOf("A"), 3000)
    private fun write(json: Boolean, selection: AllSessionsExporter.Selection = selection()): String =
        StringWriter().also { AllSessionsExporter(db).write(selection, json, it) }.toString()

    @Test fun jsonIncludesAllSessionsFullRecordsGpsEventsAndPreferencesAcrossPages() {
        session("real", status = "active"); session("demo", simulated = true, status = "archived"); session("empty", status = "interrupted")
        repeat(1101) { store("real", it) }; repeat(501) { store("demo", it, simulated = true) }
        db.dao().insertLocation(LocationEntity(sessionId = "real", json = SearchJson.encodeToString(fix)))
        val event = EventEntity(sessionId = "real", timestamp = 1000, type = "settings", detail = "line 1\n\"line 2\"")
        db.dao().event(event)
        val root = Json.parseToJsonElement(write(true)).jsonObject
        assertEquals(2, root["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals(.4f, root["settings"]!!.jsonObject["volume"]!!.jsonPrimitive.float)
        assertEquals("A", root["persistentMutes"]!!.jsonArray.single().jsonPrimitive.content)
        val sessions = root["sessions"]!!.jsonArray.associateBy { it.jsonObject["session"]!!.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(setOf("real", "demo", "empty"), sessions.keys)
        val real = sessions.getValue("real").jsonObject
        assertEquals(1101, real["observations"]!!.jsonArray.size)
        assertEquals(501, sessions.getValue("demo").jsonObject["observations"]!!.jsonArray.size)
        assertTrue(sessions.getValue("empty").jsonObject["observations"]!!.jsonArray.isEmpty())
        assertEquals(fix, SearchJson.decodeFromString<GeoFix>(real["gpsObservations"]!!.jsonArray.single().toString()))
        assertEquals(event.detail, real["events"]!!.jsonArray.single().jsonObject["detail"]!!.jsonPrimitive.content)
        assertEquals("Bird, \"bloop\"\nnearby", real["devices"]!!.jsonArray.single().jsonObject["latest"]!!.jsonObject["deviceName"]!!.jsonPrimitive.content)
        assertEquals(1101L, db.dao().count("real")); assertEquals(501L, db.dao().count("demo"))
    }

    @Test fun csvHasOneHeaderSessionIdsEveryRecordTypeFullJsonAndCorrectEscaping() {
        session("real,\"id\""); session("demo", simulated = true); session("empty")
        repeat(501) { store("real,\"id\"", it, name = "=SUM(1,2)\n\"quoted\"") }
        store("demo", simulated = true)
        db.dao().insertLocation(LocationEntity(sessionId = "real,\"id\"", json = SearchJson.encodeToString(fix)))
        db.dao().event(EventEntity(sessionId = "real,\"id\"", timestamp = 1000, type = "mute", detail = "quoted, \"value\"\nnext line"))
        val rows = csv(write(false))
        val header = rows.first()
        val records = rows.drop(1).map { assertEquals(header.size, it.size); header.zip(it).toMap() }
        assertEquals(1, rows.count { it == header })
        assertEquals(setOf("export", "settings", "persistent_mutes", "session", "device", "event", "observation", "gps"), records.map { it.getValue("record_type") }.toSet())
        assertEquals(3, records.count { it["record_type"] == "session" })
        val observations = records.filter { it["record_type"] == "observation" }
        assertEquals(502, observations.size)
        val real = observations.first { it["session_id"] == "real,\"id\"" }
        assertEquals("'=SUM(1,2)\n\"quoted\"", real["name"])
        assertEquals("=SUM(1,2)\n\"quoted\"", SearchJson.decodeFromString<Observation>(real.getValue("record_json")).deviceName)
        assertEquals("true", observations.single { it["session_id"] == "demo" }["session_simulated"])
        val gps = records.single { it["record_type"] == "gps" }
        assertEquals("1.0", gps["latitude"]); assertEquals("3.0", gps["accuracy_m"])
        assertEquals(fix, SearchJson.decodeFromString<GeoFix>(gps.getValue("record_json")))
    }

    @Test fun emptyDatabaseStillExportsValidJsonAndCsvSettings() {
        val root = Json.parseToJsonElement(write(true)).jsonObject
        assertTrue(root["sessions"]!!.jsonArray.isEmpty())
        assertEquals(4, csv(write(false)).size) // Header, export metadata, settings, persistent mutes.
    }

    @Test fun liveWriteUsesPerSessionCutoffAndDoesNotAddNewSessionsToSelection() {
        session("original", status = "active"); store("original")
        val selected = selection()
        var appended = false
        val out = object : StringWriter() {
            override fun write(str: String) {
                super.write(str)
                if (!appended && str.contains("\"observations\":[")) {
                    appended = true
                    store("original", 99, "late")
                    db.dao().event(EventEntity(sessionId = "original", timestamp = 9999, type = "late", detail = "late"))
                    db.dao().insertLocation(LocationEntity(sessionId = "original", json = SearchJson.encodeToString(fix)))
                    session("new"); store("new")
                }
            }
        }
        AllSessionsExporter(db).write(selected, true, out)
        assertTrue(appended)
        val snapshot = Json.parseToJsonElement(out.toString()).jsonObject["sessions"]!!.jsonArray.single().jsonObject
        assertEquals(1, snapshot["observations"]!!.jsonArray.size)
        assertTrue(snapshot["events"]!!.jsonArray.isEmpty()); assertTrue(snapshot["gpsObservations"]!!.jsonArray.isEmpty())
        assertNotEquals("late", snapshot["devices"]!!.jsonArray.single().jsonObject["latest"]!!.jsonObject["deviceName"]!!.jsonPrimitive.content)
        assertEquals(2L, db.dao().count("original")); assertEquals(1L, db.dao().count("new"))
    }

    @Test fun outputFailurePropagatesAndLeavesStoredDataIntact() {
        session("real"); store("real")
        val broken = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, count: Int) { throw IOException("No space") }
            override fun flush() = Unit
            override fun close() = Unit
        }
        assertThrows(IOException::class.java) { AllSessionsExporter(db).write(selection(), true, broken) }
        assertEquals(1L, db.dao().count("real")); assertEquals(1, db.dao().storedSessions().size)
    }

    @Test fun repositorySelectionIncludesCurrentSettingsMutesAndArchivedSessions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Preferences(context).settings(Settings()); Preferences(context).mutes(setOf("muted"))
        session("archived", status = "archived")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = SearchRepository(db, Preferences(context), scope)
        try {
            repo.start(false)
            repo.updateSettings(Settings(volume = .3f))
            val selected = repo.allExportSelection()
            assertEquals(setOf("archived", repo.state.value.sessionId), selected.sessionIds.toSet())
            assertEquals(.3f, selected.settings.volume)
            assertEquals(setOf("muted"), selected.persistentMutes)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    // Parse quoted fields, including embedded commas, quotes and newlines as a spreadsheet would.
    private fun csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var fields = mutableListOf<String>(); val field = StringBuilder()
        var quoted = false; var index = 0
        while (index < text.length) {
            val char = text[index++]
            when {
                char == '"' && quoted && index < text.length && text[index] == '"' -> { field.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { fields += field.toString(); field.clear() }
                char == '\r' && !quoted -> Unit
                char == '\n' && !quoted -> { fields += field.toString(); field.clear(); rows += fields; fields = mutableListOf() }
                else -> field.append(char)
            }
        }
        assertFalse(quoted); assertTrue(fields.isEmpty()); assertEquals(0, field.length)
        return rows
    }
}
