package org.blefinder

import org.blefinder.core.*
import org.junit.Assert.*
import org.junit.Test

class AdvertisementHintsTest {
    private fun apple(hex: String, id: Int = 76) = observation().copy(
        advertisement = Advertisement(manufacturers = listOf(ManufacturerData(id, null, hex))))

    @Test fun nearbyHintUsesRawOrAndroidServiceEvidenceWithoutClaimingAPhone() {
        val uuid = "0000FEF3-0000-1000-8000-00805F9B34FB"
        val observations = listOf(
            observation().copy(advertisement = Advertisement(serviceUuids = listOf(uuid))),
            observation().copy(advertisement = Advertisement(services = listOf(ServiceData(uuid, "4A1723")))),
            observation().copy(metadata = ScanMetadata(0, androidServiceData = listOf(ServiceData(uuid, "4A1723")))),
            observation().copy(metadata = ScanMetadata(0, androidServiceUuids = listOf(uuid))))
        observations.forEach {
            assertEquals("Possible Android", advertisementHint(it)?.label)
            assertTrue(advertisementHint(it)!!.evidence.contains("Other device types"))
        }
    }

    @Test fun shortAndLongFindMyFramesDoNotImplyPhoneGenerationOrAccessory() {
        listOf("12022003", "1202E403", "121920" + "00".repeat(24)).forEach { payload ->
            assertEquals("Apple Find My device", advertisementHint(apple(payload))?.label)
        }
    }

    @Test fun proximityPairingSupportsAPossibleAccessoryIncludingCompoundAppleMessages() {
        val pairing = "0719010E2055998F570000" + "AB".repeat(16)
        assertEquals("Possible Apple accessory", advertisementHint(apple(pairing))?.label)
        assertEquals("Possible Apple accessory", advertisementHint(apple("12026E03$pairing"))?.label)
        val android = observation().copy(metadata = ScanMetadata(0,
            androidManufacturers = listOf(ManufacturerData(76, null, pairing))))
        assertEquals("Possible Apple accessory", advertisementHint(android)?.label)
    }

    @Test fun invalidTruncatedOrNonAppleDataCannotProduceAnAccessoryHint() {
        listOf("071100", "0711GG", "07020000", "0719010E2055998F570000" + "AB".repeat(16) + "12", "0").forEach {
            assertEquals("Possible Apple device", advertisementHint(apple(it))?.label)
        }
        val pairing = apple("0719010E2055998F570000" + "AB".repeat(16))
        assertEquals("Possible Apple device", advertisementHint(pairing.copy(
            advertisement = pairing.advertisement.copy(malformed = true)))?.label)
        assertEquals("Possible Apple device", advertisementHint(pairing.copy(metadata = ScanMetadata(0, dataStatus = 2)))?.label)
        assertNull(advertisementHint(apple("0719010E2055998F570000" + "AB".repeat(16), id = 77)))
    }

    @Test fun completeAppleMessagesWithInvalidProtocolFieldsStayUnspecified() {
        val invalidPairing = listOf(
            "0703000000", // Complete TLV without the remaining pairing fields.
            "0711" + "00".repeat(17),
            "0719" + "00".repeat(25),
            "0719020E2055998F570000" + "AB".repeat(16), // Wrong prefix.
            "0719010E2055998F570001" + "AB".repeat(16)) // Wrong suffix.
        val invalidFindMy = listOf("12020003", "12020403", "1219" + "00".repeat(25),
            "120120", "1203200300")
        (invalidPairing + invalidFindMy).forEach {
            assertEquals(it, "Possible Apple device", advertisementHint(apple(it))?.label)
            val android = observation().copy(metadata = ScanMetadata(0,
                androidManufacturers = listOf(ManufacturerData(76, null, it))))
            assertEquals(it, "Possible Apple device", advertisementHint(android)?.label)
        }
        assertEquals("Apple Find My device", advertisementHint(apple(invalidPairing.first() + "12022003"))?.label)
        assertEquals("Possible Apple device", advertisementHint(apple("1202200312"))?.label)
    }

    @Test fun unknownAppleMessagesAndEmptyAdvertisementsStayUnspecified() {
        assertEquals("Possible Apple device", advertisementHint(apple("FF020102"))?.label)
        assertNull(advertisementHint(observation()))
    }

    @Test fun tileHintDoesNotCombineIdentitiesAndDisplayNamesKeepTheirRawPadding() {
        val o = observation().copy(advertisement = Advertisement(
            services = listOf(ServiceData("0000feed-0000-1000-8000-00805f9b34fb", "02000102030405060708"))))
        assertEquals("Possible Tile tracker", advertisementHint(o)?.label)
        val d = DeviceRecord("A", SignalStats(), o, displayName = "Camera\u0000\u0000")
        assertEquals("Camera", d.displayTitle())
        assertEquals("Camera\u0000\u0000", d.displayName)
        assertEquals("Possible Tile tracker", d.copy(displayName = null).displayTitle())
        assertEquals("Unnamed transmitter", d.copy(displayName = null, latest = observation()).displayTitle())
    }

    @Test fun bucketLabelsDescribeProtocolEvidence() {
        val o = observation().copy(advertisement = Advertisement(serviceUuids = listOf("0000fef3-0000-1000-8000-00805f9b34fb")))
        val d = DeviceRecord("A", SignalStats(), o)
        assertTrue(packetBuckets(listOf(d)).single().label.startsWith("Possible Android"))
        assertTrue(packetBuckets(listOf(d.copy(latest = apple("12022003")))).single().label.startsWith("Apple Find My device"))
    }
}
