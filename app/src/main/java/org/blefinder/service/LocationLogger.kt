package org.blefinder.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.*
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import org.blefinder.core.GeoFix

class LocationLogger(context: Context, private val receive: (GeoFix) -> Unit) : LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var latest: GeoFix? = null
    private var running = false
    fun recent(atElapsedMillis: Long): GeoFix? = latest?.takeIf {
        val age = atElapsedMillis - it.elapsedNanos / 1_000_000
        age in 0..30_000
    }
    @SuppressLint("MissingPermission")
    fun start() {
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        running = true
    }
    fun stop() { running = false; latest = null; manager.removeUpdates(this) }
    override fun onLocationChanged(location: Location) {
        if (!running || !location.hasAccuracy()) return
        val fix = GeoFix(location.latitude, location.longitude, location.accuracy, location.time, location.elapsedRealtimeNanos)
        latest = fix; receive(fix)
    }
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) { latest = null }
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}
