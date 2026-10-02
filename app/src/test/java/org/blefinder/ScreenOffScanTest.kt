package org.blefinder

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import org.blefinder.ble.screenOffDiscoveryFilters
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class ScreenOffScanTest {
    @Suppress("DEPRECATION")
    @Test fun inclusiveFiltersMatchUnknownAddressesWithoutRequiringNamesUuidsOrManufacturerData() {
        val filters = screenOffDiscoveryFilters()
        assertEquals(2, filters.size)
        assertNotNull(filters[0].serviceUuid)
        assertNotEquals(filters[0], android.bluetooth.le.ScanFilter.Builder().build())
        val adapter = BluetoothAdapter.getDefaultAdapter()
        for (address in listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02")) {
            val newAdvertiser = ScanResult(adapter.getRemoteDevice(address), null, -60, 0L)
            assertTrue(filters.any { it.matches(newAdvertiser) })
        }
    }
}
