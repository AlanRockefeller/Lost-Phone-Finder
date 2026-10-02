package org.blefinder.service

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager

object Readiness {
    fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()
    fun scanPermissions(context: Context) = requiredPermissions().all { granted(context, it) }
    @android.annotation.SuppressLint("MissingPermission") // Explicit check plus revocation-safe read.
    fun bluetooth(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && !granted(context, Manifest.permission.BLUETOOTH_CONNECT)) return false
        return runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
    }
    fun location(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java)
        return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    fun startIssue(context: Context, simulated: Boolean): String? = when {
        !scanPermissions(context) -> "Grant Nearby devices and precise location permissions before starting."
        !simulated && !bluetooth(context) -> "Bluetooth is off or unavailable. Turn on Bluetooth to receive BLE advertisements, then start again."
        !simulated && !location(context) -> "Enable location services for BLE proximity scanning, then start again."
        else -> null
    }
    fun checks(context: Context, active: Boolean, gps: Boolean): List<Pair<String, Boolean>> {
        val power = context.getSystemService(PowerManager::class.java)
        return listOf("Bluetooth enabled" to bluetooth(context),
            "Nearby devices permissions" to (Build.VERSION.SDK_INT < 31 ||
                (granted(context, Manifest.permission.BLUETOOTH_SCAN) && granted(context, Manifest.permission.BLUETOOTH_CONNECT))),
            "Precise location permission (BLE proximity)" to granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
            "Location services enabled" to location(context),
            "Battery optimization disabled for this app" to power.isIgnoringBatteryOptimizations(context.packageName),
            "Battery Saver off" to !power.isPowerSaveMode,
            "Search service running" to active,
            "GPS logging enabled" to gps,
            "Notifications permitted" to (Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)))
    }
}
