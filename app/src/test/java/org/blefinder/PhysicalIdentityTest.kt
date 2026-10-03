package org.blefinder

import org.blefinder.core.*
import org.junit.Assert.*
import org.junit.Test

internal const val ID_A = "42:12:34:56:78:90"
internal const val ID_B = "53:23:45:67:89:A1"
internal const val ID_C = "64:34:56:78:9A:B2"
internal const val ID_PAYLOAD = "123456789ABCDEF13579AA55"
internal fun identityObservation(address: String, at: Long, payload: String = ID_PAYLOAD, rssi: Int = -64,
    gps: GeoFix? = null, type: String = "random", service: Boolean = false): Observation = Observation(
    address, at + 1_000_000, at + 1_000_000, rssi, at, type,
    advertisement = Advertisement(localName = "Search phone", manufacturers = if (service) emptyList() else listOf(ManufacturerData(117, null, payload)),
        services = if (service) listOf(ServiceData("12345678-1111-2222-3333-123456789abc", payload)) else emptyList()),
    metadata = ScanMetadata(at * 1_000_000, connectable = false, legacy = true, primaryPhy = 1, secondaryPhy = 0), location = gps)
internal fun trainIdentity(engine: PhysicalIdentityEngine, address: String, start: Long, payload: String = ID_PAYLOAD,
    rssis: List<Int> = listOf(-64, -64, -63, -64), gps: ((Long) -> GeoFix?)? = null, type: String = "random", service: Boolean = false) {
    repeat(4) { i -> engine.observe(identityObservation(address, start + i * 1000, payload, rssis[i], gps?.invoke(start + i * 1000), type, service)) }
}

class PhysicalIdentityTest {
    @Test fun learnsMaskAndNeverRestoresRotatingBytes() {
        val bytes = StableBytes("123456789ABCDEF100", 0)
        repeat(4) { bytes.observe("123456789ABCDEF1%02X".format(it), it * 1000L) }
        assertEquals("FF FF FF FF FF FF FF FF 00", bytes.snapshot().mask)
        assertEquals("12 34 56 78 9A BC DE F1 --", bytes.snapshot().stableValues)
        bytes.observe("123456789ABCDEF100", 5000)
        assertFalse(bytes.snapshot().stable.last())
    }
    @Test fun distinctStableBytesAndTemporalRssiHandoffProduceHighConfidenceWithoutGps() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0); trainIdentity(engine, ID_B, 6000, rssis = listOf(-63, -63, -64, -63))
        val edge = engine.relationships(ID_A).single()
        assertEquals(IdentityBand.HIGH, edge.band); assertEquals(94, edge.score)
        assertTrue(edge.evidence.any { it.contains("3.0 second") })
        assertTrue(edge.evidence.any { it.contains("RSSI continuity") })
        assertEquals(setOf(ID_A, ID_B), engine.candidate(ID_A).addresses)
    }
    @Test fun rotatingPayloadBytesLoseWeightButStablePatternsStillMatch() {
        val engine = PhysicalIdentityEngine()
        repeat(4) { engine.observe(identityObservation(ID_A, it * 1000L, "123456789ABCDEF1%02X%02X".format(it, it + 1))) }
        repeat(4) { engine.observe(identityObservation(ID_B, 6000 + it * 1000L, "123456789ABCDEF1%02X%02X".format(it + 12, it + 22))) }
        assertEquals(IdentityBand.HIGH, engine.relationships(ID_A).single().band)
        assertEquals("FF FF FF FF FF FF FF FF 00 00", engine.fingerprint(ID_A, "m:117:10")!!.mask)
    }
    @Test fun genericFormatsNamesCompanyAndRssiDoNotAssociate() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0, "010100000000000000000000")
        trainIdentity(engine, ID_B, 6000, "010100000000000000000000")
        assertEquals(setOf(ID_A), engine.candidate(ID_A).addresses)
        assertTrue(engine.relationships(ID_A).all { it.band != IdentityBand.HIGH })
    }
    @Test fun matchingFingerprintWithoutHandoffRemainsPossible() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0); trainIdentity(engine, ID_B, 100_000)
        assertEquals(IdentityBand.POSSIBLE, engine.relationships(ID_A).single().band)
        assertEquals(setOf(ID_A), engine.candidate(ID_A).addresses)
    }
    @Test fun incompatibleStableBytesVetoMatchingPacketFormat() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0); trainIdentity(engine, ID_B, 6000, "21436587A9CBED0F2468BB66")
        val relation = engine.relationships(ID_A).single()
        assertEquals(IdentityBand.WEAK, relation.band)
        assertTrue(relation.contradictions.any { it.contains("Incompatible stable payload") })
    }
    @Test fun repeatedSimultaneousResultsVetoAndRemainVetoedAfterRecentWindow() {
        val engine = PhysicalIdentityEngine()
        repeat(4) { i ->
            engine.observe(identityObservation(ID_A, i * 1000L))
            engine.observe(identityObservation(ID_B, i * 1000L + 100))
        }
        assertEquals(setOf(ID_A), engine.candidate(ID_A).addresses)
        assertTrue(engine.relationships(ID_A).single().contradictions.any { it.contains("simultaneous") })
        trainIdentity(engine, ID_A, 200_000); trainIdentity(engine, ID_B, 206_000)
        assertEquals(IdentityBand.WEAK, engine.relationships(ID_A).single().band)
    }
    @Test fun noisyOrIncompatibleRssiDoesNotAutomaticallyFollow() {
        for (rssis in listOf(listOf(-40, -100, -63, -85), listOf(-100, -100, -99, -100))) {
            val engine = PhysicalIdentityEngine(); trainIdentity(engine, ID_A, 0)
            trainIdentity(engine, ID_B, 6000, rssis = rssis)
            assertNotEquals(IdentityBand.HIGH, engine.relationships(ID_A).single().band)
            assertNull(engine.follow(ID_A, ID_A, identityObservation(ID_B, 10_000)))
        }
    }
    private fun fix(at: Long, latitude: Double = 37.0, longitude: Double = -122.0, accuracy: Float = 20f) =
        GeoFix(latitude, longitude, accuracy, at + 1_000_000, at * 1_000_000)
    @Test fun gpsWithinAccuracyAddsSupportingEvidence() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0, gps = { fix(it) })
        trainIdentity(engine, ID_B, 6000, gps = { fix(it, latitude = 37.000072) })
        assertEquals(99, engine.relationships(ID_A).single().score)
        assertTrue(engine.relationships(ID_A).single().evidence.any { it.contains("Same GPS") })
    }
    @Test fun poorStaleInvalidOrMissingGpsAddsNoWeightAndDoesNotPreventMatch() {
        for (gps in listOf<(Long) -> GeoFix?>({ null }, { fix(it, accuracy = 200f) },
            { fix(it).copy(elapsedNanos = -100_000_000_000) }, { fix(it, latitude = Double.NaN) })) {
            val engine = PhysicalIdentityEngine()
            trainIdentity(engine, ID_A, 0, gps = gps); trainIdentity(engine, ID_B, 6000, gps = gps)
            assertEquals(IdentityBand.HIGH, engine.relationships(ID_A).single().band)
            assertFalse(engine.relationships(ID_A).single().evidence.any { it.contains("GPS") })
        }
    }
    @Test fun incompatibleGpsInShortHandoffVetoes() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0, gps = { fix(it) })
        trainIdentity(engine, ID_B, 6000, gps = { fix(it, latitude = 38.0) })
        assertEquals(IdentityBand.WEAK, engine.relationships(ID_A).single().band)
        assertTrue(engine.relationships(ID_A).single().contradictions.any { it.contains("GPS") })
    }
    @Test fun gpsAndRssiAloneCannotIdentifyADevice() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0, "00", gps = { fix(it) })
        trainIdentity(engine, ID_B, 6000, "00", gps = { fix(it) })
        assertEquals(setOf(ID_A), engine.candidate(ID_A).addresses)
    }
    @Test fun learnedServiceDataWorksWithoutManufacturerData() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0, service = true); trainIdentity(engine, ID_B, 6000, service = true)
        assertEquals(IdentityBand.HIGH, engine.relationships(ID_A).single().band)
        assertTrue(engine.relationships(ID_A).single().evidence.any { it.startsWith("Stable service-data") })
    }
    @Test fun targetFollowsOnlyAnAcceptedHandoff() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0); trainIdentity(engine, ID_B, 6000)
        val change = engine.follow(ID_A, ID_A, identityObservation(ID_B, 10_000))!!
        assertEquals(ID_A, change.from); assertEquals(ID_B, change.to)
        assertNull(engine.follow(ID_A, ID_B, identityObservation(ID_A, 10_000)))
        trainIdentity(engine, ID_C, 100_000, "010203")
        assertNull(engine.follow(ID_A, ID_B, identityObservation(ID_C, 104_000, "010203")))
    }
    @Test fun multipleSequentialRotationsCanExtendCandidateWithoutTransitiveContradictions() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0); trainIdentity(engine, ID_B, 6000); trainIdentity(engine, ID_C, 12000)
        assertEquals(setOf(ID_A, ID_B, ID_C), engine.candidate(ID_A).addresses)
        assertNotNull(engine.follow(ID_A, ID_B, identityObservation(ID_C, 16000)))
    }
    @Test fun thirdIndependentAddressSharingStableFormatRevokesEarlierAssociation() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_C, 0); trainIdentity(engine, ID_A, 30_000); trainIdentity(engine, ID_B, 36_000)
        assertEquals(setOf(ID_A), engine.candidate(ID_A).addresses)
        assertTrue(engine.relationships(ID_A).any { edge -> edge.contradictions.any { it.contains("shared by several") } })
    }
    @Test fun publicAndUnknownAddressesAreNotAutomaticallyReassigned() {
        for (type in listOf("public", "unknown", "anonymous")) {
            val engine = PhysicalIdentityEngine()
            trainIdentity(engine, ID_A, 0, type = type); trainIdentity(engine, ID_B, 6000, type = type)
            assertEquals(setOf(ID_A), engine.candidate(ID_A).addresses)
        }
    }
    @Test fun lateObservationsAndTruncatedPacketsCannotTrainIdentity() {
        val engine = PhysicalIdentityEngine()
        repeat(4) { engine.observe(identityObservation(ID_A, it * 1000L).copy(advertisement = Advertisement(malformed = true))) }
        assertNull(engine.fingerprint(ID_A, "m:117:12"))
        trainIdentity(engine, ID_B, 10000)
        engine.observe(identityObservation(ID_B, 5000, "21436587A9CBED0F2468BB66"))
        assertTrue(engine.fingerprint(ID_B, "m:117:12")!!.stable.all { it })
    }
    @Test fun crowdLimitsAvoidQuadraticComparisonsAndDoNotCreateClusters() {
        val engine = PhysicalIdentityEngine(IdentityParameters(maxCandidates = 8))
        repeat(200) { i -> engine.observe(identityObservation("%02X:12:34:56:%02X:90".format(64 + i % 32, i), i * 1000L, "010203")) }
        assertTrue(engine.comparisons < 400)
        assertEquals(setOf(ID_A), engine.candidate(ID_A).addresses)
    }
    @Test fun replayOfSavedRawObservationsReconstructsEvidence() {
        val saved = (0..3).map { identityObservation(ID_A, it * 1000L) } + (0..3).map { identityObservation(ID_B, 6000 + it * 1000L) }
        fun reconstruct(): PhysicalDeviceCandidate {
            val engine = PhysicalIdentityEngine()
            saved.map { SearchJson.decodeFromString<Observation>(SearchJson.encodeToString(it)) }.forEach(engine::observe)
            return engine.candidate(ID_A)
        }
        assertEquals(reconstruct(), reconstruct())
        assertEquals(setOf(ID_A, ID_B), reconstruct().addresses)
        assertTrue(saved.take(4).all { it.address == ID_A })
    }
    @Test fun incompatiblePublicAssignmentsAndLegacyBehaviorAreExplicitContradictions() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, "00:00:0C:12:34:56", 0, type = "public")
        trainIdentity(engine, "00:00:00:12:34:56", 6000, type = "public")
        assertTrue(engine.relationships("00:00:0C:12:34:56").single().contradictions.any { it.contains("IEEE") })
        val metadataEngine = PhysicalIdentityEngine()
        trainIdentity(metadataEngine, ID_A, 0)
        repeat(4) { i ->
            val o = identityObservation(ID_B, 6000 + i * 1000L)
            metadataEngine.observe(o.copy(metadata = o.metadata.copy(legacy = false)))
        }
        assertTrue(metadataEngine.relationships(ID_A).single().contradictions.any { it.contains("legacy/extended") })
        assertEquals(setOf(ID_A), metadataEngine.candidate(ID_A).addresses)
    }
    @Test fun strongerMatchCanReplaceWeakIndexPeersWithoutMergingThem() {
        val engine = PhysicalIdentityEngine(IdentityParameters(maxLinks = 2))
        trainIdentity(engine, ID_A, 0)
        trainIdentity(engine, "41:22:33:44:55:66", 6000, "00")
        trainIdentity(engine, "42:22:33:44:55:66", 7000, "00")
        trainIdentity(engine, ID_B, 6000)
        assertEquals(setOf(ID_A, ID_B), engine.candidate(ID_A).addresses)
    }
    @Test fun recurringRunsReinforceRatherThanRssiAloneCreatingIdentity() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0); trainIdentity(engine, ID_B, 6000)
        trainIdentity(engine, ID_A, 12000); trainIdentity(engine, ID_B, 18000)
        val relation = engine.relationships(ID_A).single()
        assertEquals(IdentityBand.HIGH, relation.band)
        assertTrue(relation.handoffs >= 3)
        assertTrue(relation.evidence.any { it.contains("Repeated address handoffs") })
    }
    @Test fun aThirdContradictoryMemberCannotJoinThroughAHigherConfidenceChain() {
        val engine = PhysicalIdentityEngine()
        trainIdentity(engine, ID_A, 0); trainIdentity(engine, ID_B, 6000)
        repeat(4) { i ->
            engine.observe(identityObservation(ID_C, 12000 + i * 1000L))
            engine.observe(identityObservation(ID_A, 12100 + i * 1000L))
        }
        assertFalse(ID_C in engine.candidate(ID_A).addresses)
        assertTrue(engine.relationships(ID_C).any { it.contradictions.any { reason -> reason.contains("simultaneous") } })
    }

}
