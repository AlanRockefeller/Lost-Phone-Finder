package org.blefinder.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Scores are conservative heuristic points, not calibrated probabilities. */
@Serializable
data class IdentityParameters(
    val learningSamples: Int = 4, val learningSpanMs: Long = 2000,
    val stableByteMinimum: Int = 8, val distinctByteMinimum: Int = 6,
    val fingerprintPoints: Int = 50, val secondFingerprintPoints: Int = 20,
    val companyPoints: Int = 3, val namePoints: Int = 4, val servicePoints: Int = 8,
    val metadataPoints: Int = 5, val ieeePoints: Int = 2,
    val handoffPoints: Int = 20, val rssiPoints: Int = 12, val gpsPoints: Int = 5,
    val repeatedHandoffPoints: Int = 5, val contradictionPenalty: Int = 40, val highThreshold: Int = 85, val possibleThreshold: Int = 50,
    val handoffMaxMs: Long = 15000, val rssiDifference: Int = 8, val rssiRange: Int = 12,
    val overlapBins: Int = 3, val commonPatternAddresses: Int = 3, val comparableMetadataFields: Int = 4,
    val rssiEndpointSamples: Int = 5, val minRssiSamples: Int = 3, val maxRuns: Int = 8,
    val maxPayloadBytes: Int = 512, val maxServiceIndex: Int = 16, val gpsSlackMeters: Double = 15.0,
    val maxCandidates: Int = 48, val compareIntervalMs: Long = 1000,
    val maxFamilies: Int = 24, val maxLinks: Int = 8, val maxCluster: Int = 16,
    val recentWindowMs: Long = 120000, val runSilenceMs: Long = 5000,
    val maxGpsAccuracy: Float = 50f, val maxFixAgeMs: Long = 15000,
    val incompatibleDistanceM: Double = 150.0, val maxTravelMps: Double = 50.0,
)

@Serializable
enum class IdentityBand(val label: String) { HIGH("High confidence"), POSSIBLE("Possible match"), WEAK("Weak similarity") }
@Serializable
data class IdentityRelationship(val addresses: List<String>, val score: Int, val band: IdentityBand,
    val evidence: List<String>, val contradictions: List<String>, val firstEvaluatedAt: Long,
    val evaluatedAt: Long, val algorithmVersion: Int = 1, val handoffs: Int = 0)
@Serializable
data class PhysicalDeviceCandidate(val id: String, val addresses: Set<String>, val relationships: List<IdentityRelationship>)
@Serializable
data class TargetAddressChange(val from: String, val to: String, val at: Long, val relationship: IdentityRelationship)

private class Run(val first: Observation, private val capacity: Int) {
    var last = first
    val head = ArrayDeque<Int>()
    val tail = ArrayDeque<Int>()
    init { add(first) }
    fun add(o: Observation) {
        last = o
        if (o.rssi in -127..126) {
            if (head.size < capacity) head.addLast(o.rssi)
            tail.addLast(o.rssi); if (tail.size > capacity) tail.removeFirst()
        }
    }
}
private class AddressEvidence(val first: Observation) {
    var latest = first
    val payloads = linkedMapOf<String, StableBytes>()
    val indexKeys = linkedSetOf<String>()
    val bins = linkedSetOf<Long>()
    val runs = ArrayDeque<Run>()
    var nextComparison = Long.MIN_VALUE
    fun observe(o: Observation, p: IdentityParameters) {
        // Out-of-order controller results remain in storage but must not invent handoffs.
        if (o.elapsedMillis < latest.elapsedMillis) return
        latest = o
        o.identityIndexKeys(p.maxPayloadBytes, p.maxServiceIndex).forEach { if (indexKeys.size < p.maxFamilies * 2) indexKeys.add(it) }
        o.payloads(p.maxPayloadBytes).forEach { (key, hex) ->
            if (key in payloads || payloads.size < p.maxFamilies)
                payloads.getOrPut(key) { StableBytes(hex, o.elapsedMillis) }.observe(hex, o.elapsedMillis)
        }
        bins += o.elapsedMillis / 1000
        bins.removeAll { it < (o.elapsedMillis - p.recentWindowMs) / 1000 }
        if (runs.isEmpty() || o.elapsedMillis - runs.last().last.elapsedMillis > p.runSilenceMs) {
            runs.addLast(Run(o, p.rssiEndpointSamples)); if (runs.size > p.maxRuns) runs.removeFirst()
        } else runs.last().add(o)
    }
}

/** Owned by the repository's serial IO worker. Indexes bound comparisons even in crowded scans. */
class PhysicalIdentityEngine(val parameters: IdentityParameters = IdentityParameters()) {
    private val addresses = linkedMapOf<String, AddressEvidence>()
    private val families = hashMapOf<String, MutableSet<String>>()
    private val links = hashMapOf<String, MutableMap<String, IdentityRelationship>>()
    private var revision = 0L
    private val candidateCache = hashMapOf<String, Pair<Long, PhysicalDeviceCandidate>>()
    var comparisons: Long = 0; private set
    fun observe(o: Observation): Set<String> {
        val p = parameters
        val profile = addresses.getOrPut(o.address) { AddressEvidence(o) }
        val before = profile.indexKeys.toSet()
        profile.observe(o, p)
        (profile.indexKeys - before).forEach { families.getOrPut(it) { linkedSetOf() }.add(o.address) }
        if (o.elapsedMillis < profile.nextComparison) return emptySet()
        profile.nextComparison = o.elapsedMillis + p.compareIntervalMs
        revision++
        val candidates = linkedSetOf<String>()
        // Always reassess accepted/suggested edges, including newly contradictory evidence.
        candidates.addAll(links[o.address].orEmpty().keys)
        for (key in profile.indexKeys) {
            val members = families[key].orEmpty()
            if (members.size <= p.maxCandidates) candidates.addAll(members)
        }
        candidates.remove(o.address)
        if (candidates.size > p.maxCandidates) {
            // Ambiguous populations get no new edges; already known edges still receive vetoes.
            candidates.retainAll(links[o.address].orEmpty().keys)
        }
        candidates.sorted().forEach { other ->
            val result = compare(o.address, other) ?: return@forEach
            remember(o.address, other, result)
        }
        return candidates + o.address
    }
    private fun remember(a: String, b: String, result: IdentityRelationship) {
        val p = parameters
        if (result.score == 0 && result.contradictions.isEmpty() && links[a]?.containsKey(b) != true) return
        if (links[a]?.containsKey(b) != true) {
            fun eviction(address: String): String? = links[address].orEmpty().entries
                .filter { it.value.band != IdentityBand.HIGH && it.value.contradictions.isEmpty() && it.value.score < result.score }
                .minByOrNull { it.value.score }?.key
            val evictA = if (links[a].orEmpty().size >= p.maxLinks) eviction(a) ?: return else null
            val evictB = if (links[b].orEmpty().size >= p.maxLinks) eviction(b) ?: return else null
            evictA?.let { links[a]?.remove(it); links[it]?.remove(a) }
            evictB?.let { links[b]?.remove(it); links[it]?.remove(b) }
        }
        links.getOrPut(a) { linkedMapOf() }[b] = result
        links.getOrPut(b) { linkedMapOf() }[a] = result
    }
    fun relationships(address: String): List<IdentityRelationship> = links[address].orEmpty().values.sortedByDescending { it.score }
    fun fingerprint(address: String, family: String): ByteFingerprint? = addresses[address]?.payloads?.get(family)?.snapshot()
    fun candidate(seed: String): PhysicalDeviceCandidate {
        candidateCache[seed]?.takeIf { it.first == revision }?.let { return it.second }
        val members = linkedSetOf(seed)
        val queue = ArrayDeque<String>(); queue.add(seed)
        while (queue.isNotEmpty() && members.size < parameters.maxCluster) {
            val address = queue.removeFirst()
            links[address].orEmpty().entries.sortedBy { it.key }.forEach { (other, edge) ->
                if (other !in members && edge.band == IdentityBand.HIGH && members.size < parameters.maxCluster &&
                    members.all { member -> compatible(member, other) } && links[address]?.get(other)?.band == IdentityBand.HIGH) {
                    members += other; queue.add(other)
                }
            }
        }
        val result = PhysicalDeviceCandidate("candidate:$seed", members, members.flatMap { relationships(it) }
            .filter { it.addresses.all(members::contains) }.distinctBy { it.addresses })
        candidateCache.clear(); candidateCache[seed] = revision to result
        return result
    }
    private fun compatible(a: String, b: String): Boolean {
        val result = compare(a, b) ?: return false
        if (links[a]?.containsKey(b) == true || result.contradictions.isNotEmpty()) remember(a, b, result)
        return result.contradictions.isEmpty() && result.evidence.any { it.startsWith("Stable ") }
    }
    fun follow(seed: String, active: String, observation: Observation): TargetAddressChange? {
        if (observation.address == active) return null
        val edge = links[active]?.get(observation.address)?.takeIf { it.band == IdentityBand.HIGH } ?: return null
        if (observation.address !in candidate(seed).addresses) return null
        val old = addresses[active]?.latest ?: return null
        if (observation.elapsedMillis - old.elapsedMillis < parameters.learningSpanMs) return null
        return TargetAddressChange(active, observation.address, observation.timestamp, edge)
    }
    private fun compare(a: String, b: String): IdentityRelationship? {
        comparisons++
        val left = addresses[a] ?: return null; val right = addresses[b] ?: return null
        val p = parameters; val good = mutableListOf<String>(); val bad = mutableListOf<String>()
        var score = 0; var strong = 0
        val shared = left.payloads.keys.intersect(right.payloads.keys)
        for (family in shared) {
            val x = left.payloads.getValue(family).snapshot(); val y = right.payloads.getValue(family).snapshot()
            if (!x.learned(p) || !y.learned(p)) continue
            val positions = x.values.indices.filter { x.stable[it] && y.stable[it] }
            val unusual = positions.filter { x.values[it] !in listOf(0, 255) }
            val differences = positions.count { x.values[it] != y.values[it] }
            if (positions.size >= p.stableByteMinimum && differences >= p.stableByteMinimum / 2) {
                bad += "Incompatible stable payload bytes ($family)"; continue
            }
            if (differences == 0 && unusual.size >= p.stableByteMinimum && unusual.map { x.values[it] }.distinct().size >= p.distinctByteMinimum) {
                // Three observed addresses sharing these bytes make them an ambiguous group format.
                val common = families[family].orEmpty().asSequence().take(p.maxCandidates + 1).filter { address ->
                    address == a || address == b || (links[a]?.get(address)?.band != IdentityBand.HIGH && links[b]?.get(address)?.band != IdentityBand.HIGH)
                }.filter { address ->
                    val z = addresses[address]?.payloads?.get(family)?.snapshot()
                    z != null && z.learned(p) && positions.all { z.stable[it] && z.values[it] == x.values[it] }
                }.take(p.commonPatternAddresses).count()
                if (common >= p.commonPatternAddresses || families[family].orEmpty().size > p.maxCandidates) {
                    bad += "Stable payload shared by several addresses ($family)"
                } else {
                    strong++; good += "Stable ${if (family.startsWith("m:")) "manufacturer-data" else "service-data"} fingerprint ($family; ${positions.size} bytes)"
                }
            }
        }
        if (strong > 0) score += p.fingerprintPoints + if (strong > 1) p.secondFingerprintPoints else 0
        val x = left.latest; val y = right.latest
        if (x.identityCompanies().intersect(y.identityCompanies()).isNotEmpty()) { score += p.companyPoints; good += "Same Bluetooth company ID" }
        val name = x.advertisement.localName
        if (!name.isNullOrBlank() && name == y.advertisement.localName) { score += p.namePoints; good += "Same advertised local name" }
        val services = x.identityServices()
        if (services.isNotEmpty() && services == y.identityServices()) {
            // Adopted SIG services are common. Only custom UUID combinations add identity points.
            if (services.any { !it.endsWith("-0000-1000-8000-00805f9b34fb") }) { score += p.servicePoints; good += "Same custom service UUID set" }
            else good += "Same generic service UUID set (no identity points)"
        }
        val characteristics = x.characteristics(); val otherCharacteristics = y.characteristics()
        val comparable = characteristics.indices.filter { characteristics[it] != null && otherCharacteristics[it] != null }
        if (comparable.size >= p.comparableMetadataFields && comparable.all { characteristics[it] == otherCharacteristics[it] }) {
            score += p.metadataPoints; good += "Same advertising characteristics"
        }
        if (x.metadata.legacy != null && y.metadata.legacy != null && x.metadata.legacy != y.metadata.legacy) bad += "Different legacy/extended advertising behavior"
        val ieeeX = IeeeAssignments.lookup(x); val ieeeY = IeeeAssignments.lookup(y)
        if (ieeeX != null && ieeeY != null) {
            if (ieeeX.organization != ieeeY.organization) bad += "Incompatible public IEEE address assignments"
            else { score += p.ieeePoints; good += "Same IEEE address assignment" }
        }
        val overlap = left.bins.intersect(right.bins)
        links[a]?.get(b)?.contradictions?.filter { it.startsWith("Repeated simultaneous") }?.let(bad::addAll)
        if (overlap.size >= p.overlapBins && bad.none { it.startsWith("Repeated simultaneous") }) bad += "Repeated simultaneous observations (${overlap.size} shared one-second intervals)"
        val transitions = (left.runs.flatMap { old -> right.runs.map { new -> old to new } } +
            right.runs.flatMap { old -> left.runs.map { new -> old to new } })
            .filter { (old, new) -> new.first.elapsedMillis - old.last.elapsedMillis in 1..p.handoffMaxMs }
        val transition = transitions.minByOrNull { (old, new) -> new.first.elapsedMillis - old.last.elapsedMillis }
        var rssiContinuous = false
        if (transition != null) {
            val (old, new) = transition
            val gap = new.first.elapsedMillis - old.last.elapsedMillis
            score += p.handoffPoints; good += "${"%.1f".format(java.util.Locale.ROOT, gap / 1000.0)} second address handoff"
            fun median(samples: Collection<Int>): Int = samples.sorted()[samples.size / 2]
            if (old.tail.size >= p.minRssiSamples && new.head.size >= p.minRssiSamples &&
                old.tail.max() - old.tail.min() <= p.rssiRange && new.head.max() - new.head.min() <= p.rssiRange) {
                val from = median(old.tail); val to = median(new.head)
                if (abs(from - to) <= p.rssiDifference) { score += p.rssiPoints; rssiContinuous = true; good += "RSSI continuity $from to $to dBm" }
                else bad += "Incompatible RSSI near handoff ($from to $to dBm)"
            }
            val gps = spatial(old.last, new.first)
            if (gps > 0) { score += p.gpsPoints; good += "Same GPS search area within fix accuracy (receiver location)" }
            if (gps < 0) bad += "Incompatible GPS search areas for this time gap"
            if (transitions.size > 1) { score += p.repeatedHandoffPoints; good += "Repeated address handoffs (${transitions.size})" }
        }
        val classes = listOf(AddressClassifier.classify(x), AddressClassifier.classify(y))
        val eligible = classes.none { it == AddressClass.UNKNOWN || it == AddressClass.ANONYMOUS } && classes.any { it.rotates }
        // Every contradiction vetoes automatic association. Transition and learned bytes are mandatory.
        score = (score - bad.size * p.contradictionPenalty).coerceAtLeast(0)
        val high = score >= p.highThreshold && strong > 0 && transition != null && (rssiContinuous || strong > 1) && bad.isEmpty() && eligible
        val band = if (high) IdentityBand.HIGH else if (score >= p.possibleThreshold && bad.isEmpty()) IdentityBand.POSSIBLE else IdentityBand.WEAK
        val prior = links[a]?.get(b)
        return IdentityRelationship(listOf(a, b).sorted(), score, band, good, bad, prior?.firstEvaluatedAt ?: maxOf(x.timestamp, y.timestamp),
            maxOf(x.timestamp, y.timestamp), handoffs = transitions.size)
    }
    /** GPS fixes locate the scanner, not the transmitter. Only nearby, fresh, accurate fixes support a handoff. */
    private fun spatial(a: Observation, b: Observation): Int {
        val x = a.location ?: return 0; val y = b.location ?: return 0; val p = parameters
        fun usable(f: GeoFix, o: Observation) = f.latitude.isFinite() && f.longitude.isFinite() && f.latitude in -90.0..90.0 &&
            f.longitude in -180.0..180.0 && f.accuracy.isFinite() && f.accuracy in 0f..p.maxGpsAccuracy &&
            o.elapsedMillis - f.elapsedNanos / 1_000_000 in 0..p.maxFixAgeMs
        if (!usable(x, a) || !usable(y, b)) return 0
        val lat = Math.toRadians(y.latitude - x.latitude); val lon = Math.toRadians(y.longitude - x.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(x.latitude)) * cos(Math.toRadians(y.latitude)) * sin(lon / 2).pow(2)
        val distance = 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        val uncertainty = x.accuracy + y.accuracy
        if (distance <= uncertainty + p.gpsSlackMeters) return 1
        val seconds = abs(b.elapsedMillis - a.elapsedMillis) / 1000.0
        return if (distance - uncertainty > maxOf(p.incompatibleDistanceM, seconds * p.maxTravelMps)) -1 else 0
    }
}
