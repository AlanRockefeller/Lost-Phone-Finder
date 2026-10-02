package org.blefinder.core

object ExportCodec {
    val csvHeader = "timestamp_utc,received_at_utc,address,rssi,name,manufacturer_ids,manufacturer_names,tx_power,address_type,latitude,longitude,accuracy_m,location_timestamp_utc,raw_advertisement_hex,target,mute,simulated\r\n"
    fun csvRow(o: Observation): String {
        val manufacturers = (o.advertisement.manufacturers + o.metadata.androidManufacturers).distinctBy { it.id }
        val fields = listOf(java.time.Instant.ofEpochMilli(o.timestamp).toString(), java.time.Instant.ofEpochMilli(o.receivedAt).toString(),
            o.address, o.rssi, safeText(o.advertisement.localName ?: o.deviceName ?: ""),
            manufacturers.joinToString(";") { it.id.toString() }, manufacturers.joinToString(";") { it.company ?: "Unknown" },
            o.advertisement.txPower ?: o.metadata.txPower, o.addressType,
            o.location?.latitude, o.location?.longitude, o.location?.accuracy,
            o.location?.let { java.time.Instant.ofEpochMilli(it.timestamp).toString() }, o.advertisement.rawHex,
            o.target, o.mute, o.simulated)
        return fields.joinToString(",") { "\"${(it?.toString() ?: "").replace("\"", "\"\"")}\"" } + "\r\n"
    }
    // Names are untrusted radio input; protect spreadsheet formula interpretation.
    private fun safeText(text: String) = if (text.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r', '\n')) "'$text" else text
}
