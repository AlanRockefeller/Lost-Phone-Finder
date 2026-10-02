package org.blefinder.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val SearchJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable
enum class DiscoverySound(val label: String, val asset: String?) {
    TWO_NOTE("Two-note chime", null),
    TUGBOAT("Tugboat horn", "sounds/tugboat.pcm"),
    OROPENDOLA("Oropendola calls", "sounds/oropendola.pcm")
}

@Serializable
data class Settings(
    val chirps: Boolean = true, val discoveries: Boolean = true,
    val volume: Float = 0.65f, val rssiMin: Int = -100, val rssiMax: Int = -30,
    val pitchMin: Int = 250, val pitchMax: Int = 3200, val chirpMs: Int = 30,
    val smoothing: Double = 0.25, val baselineSeconds: Int = 30,
    val gps: Boolean = false, val keepAwake: Boolean = true,
    val loudspeaker: Boolean = false, val discoverySound: DiscoverySound = DiscoverySound.TWO_NOTE,
) {
    fun validated() = copy(volume = volume.coerceIn(0f, 1f), rssiMin = rssiMin.coerceIn(-127, -21),
        rssiMax = rssiMax.coerceIn(rssiMin.coerceIn(-127, -21) + 1, 0),
        pitchMin = pitchMin.coerceIn(100, 2000), pitchMax = pitchMax.coerceIn(2100, 8000),
        chirpMs = chirpMs.coerceIn(20, 50), smoothing = smoothing.coerceIn(0.01, 1.0),
        baselineSeconds = baselineSeconds.coerceIn(5, 300))
}

@Serializable
data class AdStructure(val type: Int, val hex: String, val truncated: Boolean = false)
@Serializable
data class ManufacturerData(val id: Int, val company: String?, val hex: String)
@Serializable
data class ServiceData(val uuid: String, val hex: String)
@Serializable
data class Advertisement(
    val rawHex: String = "", val localName: String? = null, val flags: Int? = null,
    val txPower: Int? = null, val serviceUuids: List<String> = emptyList(),
    val solicitationUuids: List<String> = emptyList(),
    val manufacturers: List<ManufacturerData> = emptyList(), val services: List<ServiceData> = emptyList(),
    val structures: List<AdStructure> = emptyList(), val malformed: Boolean = false,
)
@Serializable
data class ScanMetadata(
    val timestampNanos: Long, val callbackType: Int = 1, val txPower: Int? = null,
    val connectable: Boolean? = null, val legacy: Boolean? = null, val primaryPhy: Int? = null,
    val secondaryPhy: Int? = null, val advertisingSid: Int? = null,
    val periodicInterval: Int? = null, val dataStatus: Int? = null,
    val androidAddressType: Int? = null, val androidFlags: Int? = null,
    val androidServiceUuids: List<String> = emptyList(),
    val androidSolicitationUuids: List<String> = emptyList(),
    val androidManufacturers: List<ManufacturerData> = emptyList(),
    val androidServiceData: List<ServiceData> = emptyList(),
    val androidAdvertisingData: Map<Int, String> = emptyMap(),
)
@Serializable
data class GeoFix(val latitude: Double, val longitude: Double, val accuracy: Float,
    val timestamp: Long, val elapsedNanos: Long)
@Serializable
data class Observation(
    val address: String, val timestamp: Long, val receivedAt: Long, val rssi: Int,
    val elapsedMillis: Long, val addressType: String = "unknown", val deviceName: String? = null,
    val advertisement: Advertisement = Advertisement(), val metadata: ScanMetadata,
    val location: GeoFix? = null, val target: Boolean = false,
    val mute: String = "none", val simulated: Boolean = false,
    val receivedElapsedMillis: Long = elapsedMillis,
)
@Serializable
data class SignalStats(
    val count: Long = 0, val validRssiCount: Long = 0, val current: Int? = null,
    val strongest: Int? = null, val weakest: Int? = null, val mean: Double? = null,
    val smoothed: Double? = null, val firstSeen: Long = 0, val lastSeen: Long = 0,
    val firstElapsed: Long = 0, val lastElapsed: Long = 0,
    val recentTimes: List<Long> = emptyList(),
) {
    fun add(o: Observation, alpha: Double): SignalStats {
        val valid = o.rssi in -127..126 // 127 means unavailable on Android.
        val n = validRssiCount + if (valid) 1 else 0
        val latest = maxOf(lastElapsed, o.elapsedMillis)
        return copy(count = count + 1, validRssiCount = n,
            current = o.rssi.takeIf { valid },
            strongest = if (valid) maxOf(strongest ?: o.rssi, o.rssi) else strongest,
            weakest = if (valid) minOf(weakest ?: o.rssi, o.rssi) else weakest,
            mean = if (valid) (mean ?: 0.0) + (o.rssi - (mean ?: 0.0)) / n else mean,
            smoothed = if (valid) smoothed?.let { it + alpha.coerceIn(0.0, 1.0) * (o.rssi - it) } ?: o.rssi.toDouble() else smoothed,
            firstSeen = if (count == 0L) o.timestamp else minOf(firstSeen, o.timestamp),
            lastSeen = maxOf(lastSeen, o.timestamp),
            firstElapsed = if (count == 0L) o.elapsedMillis else minOf(firstElapsed, o.elapsedMillis),
            lastElapsed = latest,
            recentTimes = (recentTimes + o.elapsedMillis).filter { it > latest - RATE_WINDOW_MS })
    }
    /** Fixed five-second received-result window; decays to zero during silence. */
    fun rate(nowElapsed: Long): Double = recentTimes.count { it > nowElapsed - RATE_WINDOW_MS && it <= nowElapsed } / 5.0
    companion object { const val RATE_WINDOW_MS = 5000L }
}
@Serializable
data class DeviceRecord(val address: String, val stats: SignalStats, val latest: Observation,
    val displayName: String? = null, val company: String? = null,
    // Reserved link for future explicitly reviewed grouping. Never inferred from MAC bits.
    val probablePhysicalDeviceId: String? = null)

enum class SortOrder(val label: String) {
    RECENT("Most recently seen"), CURRENT("Strongest current RSSI"), BEST("Strongest ever RSSI"),
    FIRST("First seen"), COUNT("Scan result count")
}
fun visibleDevices(devices: Collection<DeviceRecord>, sort: SortOrder, hideMuted: Boolean, mutes: MuteRules): List<DeviceRecord> {
    val comparator = when (sort) {
        SortOrder.RECENT -> compareByDescending<DeviceRecord> { it.latest.receivedElapsedMillis }
        SortOrder.CURRENT -> compareByDescending { it.stats.current ?: Int.MIN_VALUE }
        SortOrder.BEST -> compareByDescending { it.stats.strongest ?: Int.MIN_VALUE }
        SortOrder.FIRST -> compareBy { it.stats.firstSeen }
        SortOrder.COUNT -> compareByDescending { it.stats.count }
    }.thenBy { it.address }
    return devices.filter { !hideMuted || !mutes.isMuted(it.address) }.sortedWith(comparator)
}

@Serializable
data class MuteRules(val session: Set<String> = emptySet(), val persistent: Set<String> = emptySet()) {
    fun isMuted(address: String) = address in session || address in persistent
    fun kind(address: String) = when (address) { in persistent -> "persistent"; in session -> "session"; else -> "none" }
    fun mute(address: String, always: Boolean) = if (always) copy(persistent = persistent + address, session = session - address) else copy(session = session + address)
    fun unmute(address: String) = copy(session = session - address, persistent = persistent - address)
    fun nextSession() = copy(session = emptySet())
}

data class Baseline(val startElapsed: Long, val endElapsed: Long, val addresses: Set<String> = emptySet()) {
    fun observe(address: String, at: Long) = if (at in startElapsed until endElapsed) copy(addresses = addresses + address) else this
    fun remainingSeconds(now: Long) = ((endElapsed - now).coerceAtLeast(0) + 999) / 1000
    fun apply(mutes: MuteRules) = mutes.copy(session = mutes.session + addresses)
}

object PitchMapping {
    // Piecewise continuous interpolation through the field-search tuning points.
    val points = listOf(-100 to 250.0, -90 to 350.0, -80 to 500.0, -70 to 750.0,
        -60 to 1100.0, -50 to 1600.0, -40 to 2300.0, -30 to 3200.0)
    fun frequency(rssi: Int, settings: Settings = Settings()): Double {
        val s = settings.validated()
        val normalized = (rssi.coerceIn(s.rssiMin, s.rssiMax) - s.rssiMin).toDouble() / (s.rssiMax - s.rssiMin)
        val x = -100 + normalized * 70
        val i = ((x + 100) / 10).toInt().coerceIn(0, points.size - 2)
        val (lo, hzLo) = points[i]; val (hi, hzHi) = points[i + 1]
        val hz = hzLo + (x - lo) / (hi - lo) * (hzHi - hzLo)
        return s.pitchMin + (hz - 250) / (3200 - 250) * (s.pitchMax - s.pitchMin)
    }
}
