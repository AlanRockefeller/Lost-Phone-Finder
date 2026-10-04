package org.blefinder.data

import org.blefinder.core.*
import java.io.Writer

/** Snapshot IDs make live export consistent. Pages bound memory and never hold the radio thread. */
class SessionExporter(private val db: SearchDatabase) {
    data class Snapshot(val session: SessionEntity, val devices: List<DeviceRecord>, val through: Long,
        val locationThrough: Long, val events: List<EventEntity>, val target: String?,
        val targetAddresses: Set<String> = target?.let { setOf(it) }.orEmpty(),
        val identityCandidate: PhysicalDeviceCandidate? = null)
    fun snapshot(id: String, target: String?, candidate: PhysicalDeviceCandidate? = null): Snapshot {
        val targetAddresses = candidate?.addresses ?: target?.let { setOf(it) }.orEmpty()
        var result: Snapshot? = null
        db.runInTransaction {
            val dao = db.dao()
            result = Snapshot(requireNotNull(dao.session(id)), dao.devices(id).map { SearchJson.decodeFromString<DeviceRecord>(it.json) }
                .filter { target == null || it.address in targetAddresses }, dao.maxId(id), dao.maxLocationId(id), dao.events(id, dao.maxEventId(id)), target, targetAddresses, candidate)
        }
        return requireNotNull(result)
    }
    /** Replay in insertion order, matching the live engine, without writing inferred conclusions to Room. */
    fun reconstructIdentity(snapshot: Snapshot, seed: String): PhysicalDeviceCandidate {
        val engine = PhysicalIdentityEngine()
        var last = 0L
        while (true) {
            val rows = db.dao().page(snapshot.session.id, null, last, snapshot.through)
            if (rows.isEmpty()) break
            rows.forEach { engine.observe(SearchJson.decodeFromString<Observation>(it.json)); last = it.id }
        }
        return engine.candidate(seed)
    }
    fun write(snapshot: Snapshot, json: Boolean, out: Writer) {
        if (json) {
            out.write("{\"schemaVersion\":1,\"rssiMeaning\":\"relative signal strength, not distance\",\"session\":")
            out.write(SearchJson.encodeToString(snapshot.session))
            out.write(",\"targetAddress\":${SearchJson.encodeToString(snapshot.target)},\"devices\":")
            out.write(SearchJson.encodeToString(snapshot.devices))
            out.write(",\"targetAddresses\":${SearchJson.encodeToString(snapshot.targetAddresses)}")
            out.write(",\"physicalIdentityInference\":${SearchJson.encodeToString(snapshot.identityCandidate)}")
            out.write(",\"events\":${SearchJson.encodeToString(snapshot.events)},\"observations\":[")
        } else out.write(ExportCodec.csvHeader)
        var last = 0L; var first = true
        while (true) {
            val page = if (snapshot.target != null) db.dao().addressPage(snapshot.session.id, snapshot.targetAddresses, last, snapshot.through)
                else db.dao().page(snapshot.session.id, null, last, snapshot.through)
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
