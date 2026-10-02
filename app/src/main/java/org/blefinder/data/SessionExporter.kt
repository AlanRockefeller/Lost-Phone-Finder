package org.blefinder.data

import org.blefinder.core.*
import java.io.Writer

/** Snapshot IDs make live export consistent. Pages bound memory and never hold the radio thread. */
class SessionExporter(private val db: SearchDatabase) {
    data class Snapshot(val session: SessionEntity, val devices: List<DeviceRecord>, val through: Long,
        val locationThrough: Long, val events: List<EventEntity>, val target: String?)
    fun snapshot(id: String, target: String?): Snapshot {
        var result: Snapshot? = null
        db.runInTransaction {
            val dao = db.dao()
            result = Snapshot(requireNotNull(dao.session(id)), dao.devices(id).map { SearchJson.decodeFromString<DeviceRecord>(it.json) }
                .filter { target == null || it.address == target }, dao.maxId(id), dao.maxLocationId(id), dao.events(id, dao.maxEventId(id)), target)
        }
        return requireNotNull(result)
    }
    fun write(snapshot: Snapshot, json: Boolean, out: Writer) {
        if (json) {
            out.write("{\"schemaVersion\":1,\"rssiMeaning\":\"relative signal strength, not distance\",\"session\":")
            out.write(SearchJson.encodeToString(snapshot.session))
            out.write(",\"targetAddress\":${SearchJson.encodeToString(snapshot.target)},\"devices\":")
            out.write(SearchJson.encodeToString(snapshot.devices))
            out.write(",\"events\":${SearchJson.encodeToString(snapshot.events)},\"observations\":[")
        } else out.write(ExportCodec.csvHeader)
        var last = 0L; var first = true
        while (true) {
            val page = db.dao().page(snapshot.session.id, snapshot.target, last, snapshot.through)
            if (page.isEmpty()) break
            page.forEach { row ->
                if (json) { if (!first) out.write(","); out.write(row.json) }
                else out.write(ExportCodec.csvRow(SearchJson.decodeFromString(row.json)))
                first = false; last = row.id
            }
        }
        if (json) {
            out.write("],\"gpsObservations\":[")
            var locationLast = 0L; var locationFirst = true
            while (true) {
                val page = db.dao().locations(snapshot.session.id, locationLast, snapshot.locationThrough)
                if (page.isEmpty()) break
                page.forEach { if (!locationFirst) out.write(","); out.write(it.json); locationFirst = false; locationLast = it.id }
            }
            out.write("]}")
        }
        out.flush()
    }
}
