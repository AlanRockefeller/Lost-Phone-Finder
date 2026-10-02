package org.blefinder

import org.blefinder.core.*
import org.junit.Assert.*
import org.junit.Test

class PacketProfilesTest {
    private fun device(address: String, ad: Advertisement = Advertisement(), metadata: ScanMetadata = ScanMetadata(0), received: Long = 10): DeviceRecord {
        val o = Observation(address, 0, 0, -60, 0, advertisement = ad, metadata = metadata, receivedElapsedMillis = received)
        return DeviceRecord(address, SignalStats().add(o, .25), o)
    }
    private fun manufacturer(id: Int = 76, hex: String = "010203") = Advertisement(manufacturers = listOf(ManufacturerData(id, null, hex)))
    @Test fun rotatingAddressesAndChangingPayloadsCanShareAFormatBucket() {
        val buckets = packetBuckets(listOf(device("A", manufacturer()), device("B", manufacturer(hex = "abcdef"))))
        assertEquals(1, buckets.size)
        assertEquals(2, buckets.single().devices.size)
        assertTrue(buckets.single().devices.all { it.probablePhysicalDeviceId == null })
    }
    @Test fun differentManufacturersOrPayloadLengthsRemainSeparate() {
        assertEquals(3, packetBuckets(listOf(device("A", manufacturer()), device("B", manufacturer(id = 77)), device("C", manufacturer(hex = "00")))).size)
    }
    @Test fun emptyAndNameOnlyAdvertisementsDoNotMerge() {
        assertEquals(3, packetBuckets(listOf(device("A"), device("B"), device("C", Advertisement(localName = "Phone")))).size)
    }
    @Test fun androidFallbackAndParsedMetadataProduceTheSameProfile() {
        val data = ManufacturerData(76, null, "010203")
        val a = device("A", Advertisement(manufacturers = listOf(data)))
        val b = device("B", metadata = ScanMetadata(0, androidManufacturers = listOf(data)))
        val c = device("C", Advertisement(manufacturers = listOf(data)), ScanMetadata(0, androidManufacturers = listOf(data)))
        assertEquals(1, packetBuckets(listOf(a, b, c)).size)
    }
    @Test fun uuidOrderAndCaseDoNotChangeProfile() {
        val a = device("A", Advertisement(serviceUuids = listOf("ABC", "DEF")))
        val b = device("B", Advertisement(serviceUuids = listOf("def", "abc", "abc")))
        assertEquals(1, packetBuckets(listOf(a, b)).size)
    }
    @Test fun recentSortingUsesReceiptTimeEvenWhenControllerTimeIsOld() {
        val a = device("A", received = 100); val b = device("B", received = 200)
        assertEquals(listOf("B", "A"), visibleDevices(listOf(a, b), SortOrder.RECENT, false, MuteRules()).map { it.address })
        assertEquals("address:B", packetBuckets(listOf(a, b)).first().key)
    }
}
