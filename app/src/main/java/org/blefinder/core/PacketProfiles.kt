package org.blefinder.core

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** A format bucket, never an inferred physical identity. Payload bytes may rotate. */
@Serializable
private data class PacketProfile(
    val manufacturers: List<String>, val services: List<String>, val uuids: List<String>,
    val solicitationUuids: List<String>, val structures: List<String>,
)
data class PacketBucket(val key: String, val label: String, val devices: List<DeviceRecord>)

fun packetBuckets(devices: List<DeviceRecord>): List<PacketBucket> = devices
    .groupBy { packetProfile(it.latest) ?: "address:${it.address}" }
    .map { (profile, members) ->
        val first = members.first()
        val key = profile
        val label = if (profile.startsWith("address:")) "No distinguishing packet data" else
            "Packet profile ${profile.take(8)} • ${first.company?.let { "Bluetooth company: $it" } ?: "Bluetooth company unknown"}"
        PacketBucket(key, label, members)
    }
    .sortedByDescending { bucket -> bucket.devices.maxOf { it.latest.receivedElapsedMillis } }

private fun packetProfile(o: Observation): String? {
    val a = o.advertisement; val m = o.metadata
    val manufacturers = (a.manufacturers + m.androidManufacturers)
        .map { "${it.id}:${it.hex.length / 2}" }.distinct().sorted()
    val services = (a.services + m.androidServiceData)
        .map { "${it.uuid.lowercase()}:${it.hex.length / 2}" }.distinct().sorted()
    val uuids = (a.serviceUuids + m.androidServiceUuids).map { it.lowercase() }.distinct().sorted()
    val solicitations = (a.solicitationUuids + m.androidSolicitationUuids).map { it.lowercase() }.distinct().sorted()
    // Flags, names and Tx power alone are too generic to warrant a bucket.
    if (manufacturers.isEmpty() && services.isEmpty() && uuids.isEmpty() && solicitations.isEmpty()) return null
    val structures = (a.structures.filterNot { it.truncated }.map { "${it.type}:${it.hex.length / 2}" } +
        m.androidAdvertisingData.map { "${it.key}:${it.value.length / 2}" }).distinct().sorted()
    val encoded = SearchJson.encodeToString(PacketProfile(manufacturers, services, uuids, solicitations, structures))
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
