package org.blefinder.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.blefinder.core.*

interface BleSource { fun start(); fun stop() }

@SuppressLint("MissingPermission") // UI and service independently check runtime prerequisites before starting.
class AndroidBleSource(context: Context, private val receive: (Observation) -> Unit,

    private val failure: (String) -> Unit) : BleSource {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = deliver(callbackType, result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { deliver(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) } }
        override fun onScanFailed(errorCode: Int) {
            if (scanner == null) return
            failure("BLE scan failed ($errorCode): " + when (errorCode) {
            SCAN_FAILED_ALREADY_STARTED -> "already started"
            SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android scanner registration failed; stop and retry"
            SCAN_FAILED_FEATURE_UNSUPPORTED -> "scan configuration unsupported by this phone"
            SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth stack error"
            5 -> "out of hardware resources"
            6 -> "Android scan start limit reached; wait at least 30 seconds before retrying"
            else -> "unknown Android scanner error"
            })
        }
    }
    override fun start() {
        check(adapter?.isEnabled == true) { "Enable Bluetooth before searching" }
        scanner = adapter.bluetoothLeScanner ?: error("BLE scanner is unavailable")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setLegacy(!adapter.isLeExtendedAdvertisingSupported).setPhy(if (adapter.isLeCodedPhySupported) ScanSettings.PHY_LE_ALL_SUPPORTED else 1)
        // Active is the platform default. Explicit selection is available from 36.1;
        // guard at the next major API for compatibility with Android 16.0.
        if (Build.VERSION.SDK_INT >= 37) settings.setScanType(ScanSettings.SCAN_TYPE_ACTIVE)
        scanner!!.startScan(screenOffDiscoveryFilters(), settings.build(), callback)
    }
    override fun stop() {
        val previous = scanner; scanner = null
        runCatching { previous?.stopScan(callback) }
    }
    private fun deliver(type: Int, result: ScanResult) {
        if (scanner == null) return // Ignore callbacks delivered after this source was stopped.
        try {
            val record = result.scanRecord
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtimeNanos()
            val addressType = if (Build.VERSION.SDK_INT >= 35) result.device.addressType else null
            val parsed = AdvertisementParser.parse(record?.bytes ?: byteArrayOf())
            val manufacturers = record?.manufacturerSpecificData?.let { data ->
                (0 until data.size()).map { i -> ManufacturerData(data.keyAt(i), Companies.name(data.keyAt(i)), data.valueAt(i).hex()) }
            } ?: emptyList()
            val o = Observation(address = result.device.address, timestamp = now - ((elapsed - result.timestampNanos).coerceAtLeast(0) / 1_000_000),
                receivedAt = now, rssi = result.rssi, elapsedMillis = result.timestampNanos / 1_000_000,
                receivedElapsedMillis = elapsed / 1_000_000,
                addressType = when (addressType) { 0 -> "public"; 1 -> "random"; 0xFF -> "anonymous"; else -> "unknown" },
                deviceName = result.device.name,
                advertisement = parsed.copy(localName = record?.deviceName ?: parsed.localName,
                    txPower = record?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE } ?: parsed.txPower),
                metadata = ScanMetadata(timestampNanos = result.timestampNanos, callbackType = type,
                    txPower = result.txPower.takeUnless { it == 127 }, connectable = result.isConnectable,
                    legacy = result.isLegacy, primaryPhy = result.primaryPhy, secondaryPhy = result.secondaryPhy,
                    advertisingSid = result.advertisingSid, periodicInterval = result.periodicAdvertisingInterval,
                    dataStatus = result.dataStatus, androidAddressType = addressType,
                    androidFlags = record?.advertiseFlags?.takeUnless { it == -1 },
                    androidServiceUuids = record?.serviceUuids?.map { it.toString() } ?: emptyList(),
                    androidSolicitationUuids = if (Build.VERSION.SDK_INT >= 29) record?.serviceSolicitationUuids?.map { it.toString() } ?: emptyList() else emptyList(),
                    androidManufacturers = manufacturers,
                    androidServiceData = record?.serviceData?.map { ServiceData(it.key.toString(), it.value.hex()) } ?: emptyList(),
                    androidAdvertisingData = if (Build.VERSION.SDK_INT >= 33) record?.advertisingDataMap?.mapValues { it.value.hex() } ?: emptyMap() else emptyMap()))
            receive(o)
        } catch (e: SecurityException) { failure("Bluetooth permission was revoked: ${e.message}") }
          catch (e: Exception) { failure("Could not preserve a scan result: ${e.message}") }
    }
}

/**
 * Preserve discovery of arbitrary NEW advertisers with OR filters. The UUID branch
 * is a concrete filter; the unconstrained branch preserves broad matching.
 * AOSP ScanManager requires at least one nonempty branch to avoid screen-off
 * suspension. This is a compatibility workaround, not an Android/OEM guarantee.
 */
internal fun screenOffDiscoveryFilters(): List<ScanFilter> = listOf(
    ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid.fromString(
        "0000180f-0000-1000-8000-00805f9b34fb")).build(),
    ScanFilter.Builder().build(),
)
