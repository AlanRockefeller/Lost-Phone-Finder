package org.blefinder.core

/** BLE observations keep their own full fix. Standalone fixes provide a sparse diagnostic trail. */
class GpsBreadcrumbs {
    private var lastElapsedNanos: Long? = null
    fun reset() { lastElapsedNanos = null }
    fun retain(fix: GeoFix): Boolean {
        if (!fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0 ||
            !fix.accuracy.isFinite() || fix.accuracy < 0 || fix.elapsedNanos < 0) return false
        val previous = lastElapsedNanos
        if (previous != null && (fix.elapsedNanos <= previous ||
                fix.elapsedNanos - previous < INTERVAL_MILLIS * 1_000_000)) return false
        lastElapsedNanos = fix.elapsedNanos
        return true
    }
    companion object { const val INTERVAL_MILLIS = 60_000L }
}
