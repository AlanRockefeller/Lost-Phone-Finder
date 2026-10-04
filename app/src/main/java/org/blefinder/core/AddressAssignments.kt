package org.blefinder.core

/** Bluetooth Core, Vol 6 Part B 1.3.2. Display order starts with address bits 47..40. */
enum class AddressClass(val label: String, val rotates: Boolean = false) {
    PUBLIC("Public device address"),
    RPA("Resolvable private address (RPA); expected to rotate", true),
    NRPA("Non-resolvable private address (NRPA)", true),
    STATIC_RANDOM("Static random address"),
    ANONYMOUS("Anonymous address"), UNKNOWN("Unknown or invalid address")
}

object AddressClassifier {
    private val format = Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")
    fun compact(address: String): String? = address.takeIf { format.matches(it) }?.replace(":", "")?.uppercase()
    fun classify(o: Observation): AddressClass = classify(o.address, when (o.metadata.androidAddressType) {
        0 -> "public"; 1 -> "random"; 255 -> "anonymous"; null -> o.addressType; else -> "unknown"
    })
    fun classify(address: String, reported: String): AddressClass {
        if (reported == "anonymous") return AddressClass.ANONYMOUS
        val value = compact(address)?.toLong(16) ?: return AddressClass.UNKNOWN
        if (reported == "public") return if (value != 0L && value != 0xFFFFFFFFFFFFL) AddressClass.PUBLIC else AddressClass.UNKNOWN
        if (reported != "random") return AddressClass.UNKNOWN
        val type = (value ushr 46).toInt()
        val randomPart = if (type == 1) (value ushr 24) and 0x3FFFFF else value and 0x3FFFFFFFFFFF
        val maximum = if (type == 1) 0x3FFFFFL else 0x3FFFFFFFFFFFL
        if (randomPart == 0L || randomPart == maximum) return AddressClass.UNKNOWN
        return when (type) { 0 -> AddressClass.NRPA; 1 -> AddressClass.RPA; 3 -> AddressClass.STATIC_RANDOM; else -> AddressClass.UNKNOWN }
    }
}

private fun registry(file: String): Map<String, String> = requireNotNull(Companies::class.java.getResourceAsStream(file)) {
    "Missing bundled offline registry $file"
}.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith("#") }
    .associate { val parts = it.split('\t', limit = 2); parts[0] to parts[1] } }

object Companies {
    private val names by lazy { registry("companies.tsv").mapKeys { it.key.toInt(16) } }
    fun name(id: Int): String? = names[id]
    fun size(): Int = names.size
}

data class IeeeAssignment(val prefix: String, val organization: String) {
    val registry: String get() = when (prefix.length) { 9 -> "MA-S / OUI-36"; 7 -> "MA-M"; else -> "MA-L / OUI" }
}

/** No address-bit guesses about public status: Android must have reported public. */
object IeeeAssignments {
    private val names by lazy { registry("ieee.tsv") }
    fun warmUp() { names.size }
    fun lookup(o: Observation): IeeeAssignment? = lookup(o.address, AddressClassifier.classify(o))
    fun lookup(address: String, classification: AddressClass): IeeeAssignment? {
        if (classification != AddressClass.PUBLIC) return null
        val hex = AddressClassifier.compact(address) ?: return null
        return listOf(9, 7, 6).firstNotNullOfOrNull { length ->
            val prefix = hex.take(length); names[prefix]?.let { IeeeAssignment(prefix, it) }
        }
    }
}
