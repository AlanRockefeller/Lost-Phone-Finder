package org.blefinder.core

/** A description of advertised protocol evidence, separate from physical identity. */
data class AdvertisementHint(val label: String, val evidence: String)

fun advertisementHint(o: Observation): AdvertisementHint? {
    val a = o.advertisement
    val m = o.metadata
    val services = (a.serviceUuids + m.androidServiceUuids +
        a.services.map { it.uuid } + m.androidServiceData.map { it.uuid }).map { it.lowercase() }.toSet()
    if ("0000fef3-0000-1000-8000-00805f9b34fb" in services) return AdvertisementHint(
        "Possible Android", "Google Nearby service FEF3. Other device types can also use this service.")
    if ("0000feed-0000-1000-8000-00805f9b34fb" in services) return AdvertisementHint(
        "Possible Tile tracker", "Service FEED is assigned to Tile. This does not identify a specific tracker.")
    val apple = (a.manufacturers + m.androidManufacturers).filter { it.id == 76 }.distinct()
    if (apple.isEmpty()) return null
    val messages = if (a.malformed || (m.dataStatus != null && m.dataStatus != 0)) emptyList() else apple.flatMap { appleMessages(it.hex) }
    if (messages.any { it.first == 0x07 && it.second >= 3 }) return AdvertisementHint(
        "Possible Apple accessory", "Apple proximity-pairing message 07, used by AirPods and other audio accessories. The model is not confirmed.")
    if (messages.any { it.first == 0x12 && it.second in listOf(2, 25) }) return AdvertisementHint(
        "Apple Find My device", "Apple Find My message 12. Short and long formats do not distinguish an iPhone from an accessory.")
    return AdvertisementHint("Possible Apple device", "Manufacturer data advertises Apple's company ID 76. The device type is not confirmed.")
}

private fun appleMessages(hex: String): List<Pair<Int, Int>> {
    if (hex.length !in 4..1024 || hex.length % 2 != 0 || hex.any { it.digitToIntOrNull(16) == null }) return emptyList()
    val bytes = hex.hexBytes()
    val messages = mutableListOf<Pair<Int, Int>>()
    var offset = 0
    while (offset < bytes.size) {
        if (offset + 2 > bytes.size) return emptyList()
        val type = bytes[offset].toInt() and 255
        val length = bytes[offset + 1].toInt() and 255
        if (offset + 2 + length > bytes.size) return emptyList()
        messages += type to length
        offset += 2 + length
    }
    return messages
}

fun DeviceRecord.displayTitle(): String = displayName?.trimEnd('\u0000')?.trim()?.takeIf { it.isNotEmpty() }
    ?: advertisementHint(latest)?.label ?: "Unnamed transmitter"
