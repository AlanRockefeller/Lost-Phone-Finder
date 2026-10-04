package org.blefinder.data

import org.blefinder.core.*
import java.io.Writer
import java.time.Instant

/** One session snapshot at a time bounds memory while scan storage continues between reads. */
class AllSessionsExporter(private val db: SearchDatabase) {
    data class Selection(val sessionIds: List<String>, val settings: Settings,
        val persistentMutes: Set<String>, val selectedAt: Long = System.currentTimeMillis())

    fun write(selection: Selection, json: Boolean, out: Writer) {
        val exporter = SessionExporter(db)
        if (json) {
            out.write("{\"schemaVersion\":2,\"exportType\":\"all_sessions\",\"selectedAt\":${selection.selectedAt}")
            out.write(",\"settings\":${SearchJson.encodeToString(selection.settings)}")
            out.write(",\"persistentMutes\":${SearchJson.encodeToString(selection.persistentMutes)},\"sessions\":[")
        } else {
            out.write(CSV_HEADER)
            csvRecord(out, "export", null, "{\"schemaVersion\":2,\"exportType\":\"all_sessions\",\"selectedAt\":${selection.selectedAt}}")
            csvRecord(out, "settings", null, SearchJson.encodeToString(selection.settings))
            csvRecord(out, "persistent_mutes", null, SearchJson.encodeToString(selection.persistentMutes))
        }
        selection.sessionIds.forEachIndexed { index, id ->
            // Capture each session just before writing it; never retain every session's devices/events.
            val snapshot = exporter.snapshot(id, null)
            if (json) {
                if (index > 0) out.write(",")
                exporter.write(snapshot, true, out)
            } else writeCsv(snapshot, out)
        }
        if (json) out.write("]}")
        out.flush()
    }

    private fun writeCsv(snapshot: SessionExporter.Snapshot, out: Writer) {
        val session = snapshot.session
        csvRecord(out, "session", session, SearchJson.encodeToString(session))
        snapshot.devices.forEach { csvRecord(out, "device", session, SearchJson.encodeToString(it)) }
        snapshot.events.forEach { csvRecord(out, "event", session, SearchJson.encodeToString(it)) }
        var last = 0L
        while (true) {
            val rows = db.dao().page(session.id, null, last, snapshot.through)
            if (rows.isEmpty()) break
            rows.forEach { row ->
                val observation = SearchJson.decodeFromString<Observation>(row.json)
                csvRecord(out, "observation", session, row.json, ExportCodec.csvRow(observation))
                last = row.id
            }
        }
        var locationLast = 0L
        while (true) {
            val rows = db.dao().locations(session.id, locationLast, snapshot.locationThrough)
            if (rows.isEmpty()) break
            rows.forEach { row ->
                val fix = SearchJson.decodeFromString<GeoFix>(row.json)
                val locationFields = mapOf("latitude" to fix.latitude, "longitude" to fix.longitude,
                    "accuracy_m" to fix.accuracy, "location_timestamp_utc" to Instant.ofEpochMilli(fix.timestamp))
                val fields = observationColumnNames.joinToString(",") { quoted(locationFields[it]) }
                csvRecord(out, "gps", session, row.json, "$fields\r\n")
                locationLast = row.id
            }
        }
    }

    private fun csvRecord(out: Writer, type: String, session: SessionEntity?, recordJson: String,
        observationColumns: String? = null) {
        val prefix = listOf(type, session?.id, session?.startedAt?.let(Instant::ofEpochMilli),
            session?.endedAt?.let(Instant::ofEpochMilli), session?.status, session?.simulated)
        out.write(prefix.joinToString(",", transform = ::quoted))
        out.write(",")
        out.write(observationColumns?.removeSuffix("\r\n") ?: observationColumnNames.map { quoted(null) }.joinToString(","))
        out.write(",${quoted(recordJson)}\r\n")
    }

    private fun quoted(value: Any?): String = "\"${(value?.toString() ?: "").replace("\"", "\"\"")}\""

    companion object {
        private val observationColumnNames = ExportCodec.csvHeader.removeSuffix("\r\n").split(",")
        val CSV_HEADER = "record_type,session_id,session_started_at_utc,session_ended_at_utc,session_status,session_simulated," +
            ExportCodec.csvHeader.removeSuffix("\r\n") + ",record_json\r\n"
    }
}
