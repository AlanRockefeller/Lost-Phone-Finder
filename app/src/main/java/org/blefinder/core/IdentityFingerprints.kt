package org.blefinder.core

/** Once a byte varies it never regains weight within this address and payload family. */
class StableBytes(hex: String, at: Long) {
    private val values = hex.hexBytes()
    private val stable = BooleanArray(values.size) { true }
    private var observations = 0
    private val first = at
    private var last = at
    fun observe(hex: String, at: Long) {
        val bytes = hex.hexBytes()
        if (bytes.size != values.size) return
        bytes.indices.forEach { if (bytes[it] != values[it]) stable[it] = false }
        observations++; last = maxOf(last, at)
    }
    fun snapshot() = ByteFingerprint(values.map { it.toInt() and 255 }, stable.toList(), observations, last - first)
}

data class ByteFingerprint(val values: List<Int>, val stable: List<Boolean>, val samples: Int, val spanMs: Long) {
    val mask: String get() = stable.joinToString(" ") { if (it) "FF" else "00" }
    val stableValues: String get() = values.indices.joinToString(" ") { if (stable[it]) "%02X".format(values[it]) else "--" }
    fun learned(p: IdentityParameters) = samples >= p.learningSamples && spanMs >= p.learningSpanMs
}

internal fun Observation.payloads(maxBytes: Int = 512): Map<String, String> {
    if (advertisement.malformed || metadata.dataStatus == 2) return emptyMap()
    val manufacturers = (advertisement.manufacturers + metadata.androidManufacturers).distinct()
    val services = (advertisement.services + metadata.androidServiceData).distinct()
    // Conflicting duplicate AD entries cannot train a single payload family.
    return (manufacturers.map { "m:${it.id}:${it.hex.length / 2}" to it.hex } +
        services.map { "s:${it.uuid.lowercase()}:${it.hex.length / 2}" to it.hex })
        .filter { (_, hex) -> hex.length in 2..(maxBytes * 2) && hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null } }
        .groupBy({ it.first }, { it.second.uppercase() }).filterValues { it.distinct().size == 1 }
        .mapValues { it.value.first() }
}
internal fun Observation.identityServices() = (advertisement.serviceUuids + metadata.androidServiceUuids).map { it.lowercase() }.toSet()
internal fun Observation.identityCompanies() = (advertisement.manufacturers + metadata.androidManufacturers).map { it.id }.toSet()
internal fun Observation.characteristics(): List<Any?> = listOf(
    advertisement.txPower ?: metadata.txPower?.takeIf { it in -127..126 }, metadata.connectable,
    metadata.legacy, metadata.primaryPhy, metadata.secondaryPhy, metadata.advertisingSid?.takeUnless { it == 255 },
    metadata.periodicInterval, advertisement.flags ?: metadata.androidFlags,
    (advertisement.solicitationUuids + metadata.androidSolicitationUuids).sorted(),
    advertisement.structures.filterNot { it.truncated }.map { "${it.type}:${it.hex.length / 2}" }.sorted())

internal fun Observation.identityIndexKeys(maxBytes: Int = 512, maxServices: Int = 16): Set<String> = payloads(maxBytes).keys +
    identityCompanies().map { "company:$it" } + identityServices().sorted().take(maxServices).map { "uuid:$it" }
