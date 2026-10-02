package org.blefinder.data

import org.blefinder.core.DeviceRecord
import org.blefinder.core.MuteRules

const val SIGNAL_HISTORY_MS = 60_000L
private const val MIN_SIGNAL_GAP_MS = 8_000L
private const val MAX_SIGNAL_GAP_MS = 20_000L

// Graph samples stay in memory and never enter stored observations or the schema.
data class RssiSample(val elapsedMillis: Long, val rssi: Int, val smoothed: Double)

/** Recent positive intervals estimate cadence; long outages never inflate that estimate. */
fun signalGapMillis(samples: List<RssiSample>, now: Long): Long {
    val intervals = samples.filter { it.elapsedMillis in (now - SIGNAL_HISTORY_MS)..now }
        .sortedBy { it.elapsedMillis }.zipWithNext { a, b -> b.elapsedMillis - a.elapsedMillis }
        .filter { it in 1..MAX_SIGNAL_GAP_MS }.takeLast(12).sorted()
    if (intervals.isEmpty()) return MIN_SIGNAL_GAP_MS
    // A single outage must not establish its own permission to bridge the gap.
    val typical = intervals[(intervals.size - 1) / 2]
    val plausibleGap = (typical * 4).coerceIn(MIN_SIGNAL_GAP_MS, MAX_SIGNAL_GAP_MS)
    val cadence = intervals.filter { it <= plausibleGap }
    val middle = cadence.size / 2
    val median = if (cadence.size % 2 == 0) (cadence[middle - 1] + cadence[middle]) / 2 else cadence[middle]
    return (median * 4).coerceIn(MIN_SIGNAL_GAP_MS, MAX_SIGNAL_GAP_MS)
}

fun signalSegments(samples: List<RssiSample>, now: Long): List<List<RssiSample>> {
    val points = samples.filter { it.elapsedMillis in (now - SIGNAL_HISTORY_MS)..now }.sortedBy { it.elapsedMillis }
    val gap = signalGapMillis(points, now)
    val segments = mutableListOf<MutableList<RssiSample>>()
    points.forEach { point ->
        if (segments.isEmpty() || point.elapsedMillis - segments.last().last().elapsedMillis > gap) {
            segments.add(mutableListOf())
        }
        segments.last().add(point)
    }
    return segments
}

/** Feature one exact address. Matching advertisement formats never join device identities. */
internal class StrongestDeviceSelector {
    private var selected: String? = null
    private var challenger: String? = null
    private var challengedAt = 0L

    fun reset() { selected = null; challenger = null; challengedAt = 0 }

    fun update(devices: Collection<DeviceRecord>, mutes: MuteRules, history: Map<String, List<RssiSample>>, now: Long): String? {
        val fresh = devices.filter {
            val age = now - it.stats.lastElapsed
            !mutes.isMuted(it.address) && it.stats.current != null && age >= 0 &&
                (age <= MIN_SIGNAL_GAP_MS || (age <= MAX_SIGNAL_GAP_MS && age <= signalGapMillis(history[it.address].orEmpty(), now)))
        }
        val best = fresh.minWithOrNull(compareByDescending<DeviceRecord> { it.stats.current }.thenBy { it.address })
        val incumbent = fresh.firstOrNull { it.address == selected }
        if (incumbent == null) {
            selected = best?.address; challenger = null
            return selected
        }
        if (best == null || best.address == selected || best.stats.current!! - incumbent.stats.current!! < 6) {
            challenger = null
            return selected
        }
        if (challenger != best.address || now < challengedAt) {
            challenger = best.address; challengedAt = now
        }
        // Require a later observation to confirm sustained strength, rather than extending one spike.
        if (now - challengedAt >= 2_000 && best.stats.lastElapsed >= challengedAt + 2_000) {
            selected = best.address; challenger = null
        }
        return selected
    }
}
