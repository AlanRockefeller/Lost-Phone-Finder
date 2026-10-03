package org.blefinder.core

import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 255) }
fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

object AdvertisementParser {
    fun parse(bytes: ByteArray): Advertisement {
        val ads = mutableListOf<AdStructure>(); val manufacturers = mutableListOf<ManufacturerData>()
        val services = mutableListOf<ServiceData>(); val uuids = mutableListOf<String>(); val solicitation = mutableListOf<String>()
        var localName: String? = null; var flags: Int? = null; var power: Int? = null; var malformed = false
        var p = 0
        while (p < bytes.size) {
            val length = bytes[p].toInt() and 255
            if (length == 0) break
            if (p + 1 >= bytes.size) { malformed = true; break }
            val type = bytes[p + 1].toInt() and 255
            val end = minOf(bytes.size, p + length + 1)
            val data = bytes.copyOfRange(p + 2, end)
            val truncated = p + length + 1 > bytes.size
            ads += AdStructure(type, data.hex(), truncated)
            if (truncated) { malformed = true; break }
            when (type) {
                0x01 -> if (data.isNotEmpty()) flags = data[0].toInt() and 255
                0x08, 0x09 -> if (type == 0x09 || localName == null) localName = data.toString(Charsets.UTF_8)
                0x0A -> if (data.isNotEmpty()) power = data[0].toInt()
                0xFF -> if (data.size >= 2) {
                    val id = (data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8)
                    manufacturers += ManufacturerData(id, Companies.name(id), data.drop(2).toByteArray().hex())
                } else malformed = true
                0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x14, 0x15, 0x1F -> {
                    val size = when (type) { 2, 3, 0x14 -> 2; 4, 5, 0x1F -> 4; else -> 16 }
                    val target = if (type in listOf(0x14, 0x15, 0x1F)) solicitation else uuids
                    if (data.size % size != 0) malformed = true
                    for (offset in 0 until data.size - size + 1 step size) target += uuid(data.copyOfRange(offset, offset + size))
                }
                0x16, 0x20, 0x21 -> {
                    val size = when (type) { 0x16 -> 2; 0x20 -> 4; else -> 16 }
                    if (data.size >= size) services += ServiceData(uuid(data.copyOfRange(0, size)), data.copyOfRange(size, data.size).hex())
                    else malformed = true
                }
            }
            p += length + 1
        }
        return Advertisement(bytes.hex(), localName, flags, power, uuids.distinct(), solicitation.distinct(), manufacturers, services, ads, malformed)
    }
    private fun uuid(bytes: ByteArray): String {
        if (bytes.size == 16) {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val low = b.long; val high = b.long
            return UUID(high, low).toString()
        }
        var value = 0L
        bytes.forEachIndexed { i, b -> value = value or ((b.toLong() and 255) shl (8 * i)) }
        return "%08x-0000-1000-8000-00805f9b34fb".format(value)
    }
    fun typeName(type: Int): String = when (type) {
        1 -> "Flags"; 8 -> "Short local name"; 9 -> "Complete local name"; 10 -> "TX power"
        2, 3 -> "16-bit service UUIDs"; 4, 5 -> "32-bit service UUIDs"; 6, 7 -> "128-bit service UUIDs"
        0x14, 0x15, 0x1F -> "Solicitation UUIDs"; 0x16, 0x20, 0x21 -> "Service data"; 0xFF -> "Manufacturer data"
        else -> "AD type 0x%02X".format(type)
    }
}
