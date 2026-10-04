package org.blefinder

import org.blefinder.core.*
import org.junit.Assert.*
import org.junit.Test

class AddressAssignmentsTest {
    @Test fun randomTopBitsUseDisplayMostSignificantOctet() {
        assertEquals(AddressClass.NRPA, AddressClassifier.classify("12:34:56:78:9A:BC", "random"))
        assertEquals(AddressClass.RPA, AddressClassifier.classify("52:34:56:78:9A:BC", "random"))
        assertEquals(AddressClass.STATIC_RANDOM, AddressClassifier.classify("D2:34:56:78:9A:BC", "random"))
        assertEquals(AddressClass.UNKNOWN, AddressClassifier.classify("92:34:56:78:9A:BC", "random"))
        assertEquals(AddressClass.PUBLIC, AddressClassifier.classify("52:34:56:78:9A:BC", "public"))
    }
    @Test fun malformedReservedAndForbiddenRandomPatternsStayUnknown() {
        for (address in listOf("", "12:34:56", "GG:12:34:56:78:90", "123456789ABC", " 12:34:56:78:9A:BC", "00:00:00:00:00:00",
            "3F:FF:FF:FF:FF:FF", "C0:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "40:00:00:12:34:56", "7F:FF:FF:12:34:56")) {
            assertEquals(address, AddressClass.UNKNOWN, AddressClassifier.classify(address, "random"))
        }
        assertEquals(AddressClass.UNKNOWN, AddressClassifier.classify(ID_A, "unknown"))
        assertEquals(AddressClass.ANONYMOUS, AddressClassifier.classify("", "anonymous"))
        assertEquals(AddressClass.RPA, AddressClassifier.classify("5a:bc:de:12:34:56", "random"))
    }
    @Test fun androidRawTypeTakesPrecedenceAndRemainsUnmodified() {
        val o = identityObservation(ID_A, 0).copy(addressType = "public", metadata = ScanMetadata(0, androidAddressType = 1))
        assertEquals(AddressClass.RPA, AddressClassifier.classify(o))
        assertEquals(1, o.metadata.androidAddressType)
        assertEquals("public", o.addressType)
        assertNull(IeeeAssignments.lookup(o))
        assertEquals(AddressClass.UNKNOWN, AddressClassifier.classify(o.copy(metadata = ScanMetadata(0, androidAddressType = 99))))
    }
    @Test fun bluetoothCompanyDatabaseContainsWellKnownAndObscureAssignments() {
        assertTrue(Companies.size() > 4000)
        assertEquals("Apple, Inc.", Companies.name(76))
        assertEquals("Microsoft", Companies.name(6))
        assertEquals("Samsung Electronics Co. Ltd.", Companies.name(117))
        assertEquals("The Linux Foundation", Companies.name(0x05F1))
        assertEquals("Oticon Medical AB", Companies.name(0x112E))
        assertNull(Companies.name(0xFFFE)); assertNull(Companies.name(-1)); assertNull(Companies.name(65536))
    }
    @Test fun ieeeLongestPrefixSelectsSmallAndMediumAssignmentsOverParentOui() {
        val small = IeeeAssignments.lookup("8C:1F:64:AF:A0:01", AddressClass.PUBLIC)!!
        assertEquals("8C1F64AFA", small.prefix); assertEquals("MA-S / OUI-36", small.registry)
        assertEquals("DATA ELECTRONIC DEVICES, INC", small.organization)
        val medium = IeeeAssignments.lookup("C8:5C:E2:70:00:01", AddressClass.PUBLIC)!!
        assertEquals("C85CE27", medium.prefix); assertEquals("MA-M", medium.registry)
        assertEquals("SYNERGY SYSTEMS AND SOLUTIONS", medium.organization)
        val large = IeeeAssignments.lookup("00:00:0C:12:34:56", AddressClass.PUBLIC)!!
        assertEquals("00000C", large.prefix); assertTrue(large.organization.contains("Cisco", ignoreCase = true))
        assertEquals("MA-L / OUI", large.registry)
    }
    @Test fun ieeeUnknownMalformedAndAllNonpublicAddressesHaveNoLookup() {
        assertNull(IeeeAssignments.lookup("FE:FF:FF:12:34:56", AddressClass.PUBLIC))
        assertNull(IeeeAssignments.lookup("broken", AddressClass.PUBLIC))
        for (classification in AddressClass.entries.filter { it != AddressClass.PUBLIC }) {
            assertNull(IeeeAssignments.lookup("00:00:0C:12:34:56", classification))
        }
    }
    @Test fun ieeeHistoricalMultipleAssigneesAreRetained() {
        val assignment = IeeeAssignments.lookup("08:00:30:12:34:56", AddressClass.PUBLIC)!!
        assertTrue(assignment.organization.contains("CERN")); assertTrue(assignment.organization.contains("NETWORK RESEARCH CORPORATION"))
    }
}
