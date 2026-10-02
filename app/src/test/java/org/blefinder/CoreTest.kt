package org.blefinder

import org.junit.Assert.*
import org.junit.Test
import org.blefinder.core.*
import kotlinx.serialization.json.*

fun observation(rssi: Int = -70, at: Long = 1000, address: String = "AA:BB:CC:DD:EE:FF") =
    Observation(address, at, at, rssi, at, metadata = ScanMetadata(at * 1_000_000))

class CoreTest {
    @Test fun pitchMatchesEveryTuningPoint() { PitchMapping.points.forEach { (rssi, hz) -> assertEquals(hz, PitchMapping.frequency(rssi), 0.0001) } }
    @Test fun pitchInterpolatesContinuously() { assertEquals(625.0, PitchMapping.frequency(-75), 0.0001); assertTrue(PitchMapping.frequency(-69) > PitchMapping.frequency(-70)) }
    @Test fun pitchClamps() { assertEquals(250.0, PitchMapping.frequency(-127), 0.001); assertEquals(3200.0, PitchMapping.frequency(20), 0.001) }
    @Test fun pitchRespectsCustomEndpoints() { val s = Settings(rssiMin = -110, rssiMax = -20, pitchMin = 400, pitchMax = 5000); assertEquals(400.0, PitchMapping.frequency(-110, s), 0.001); assertEquals(5000.0, PitchMapping.frequency(-20, s), 0.001) }
    @Test fun pitchIsStrictlyIncreasingWithinRange() { (-99..-30).forEach { assertTrue(PitchMapping.frequency(it) > PitchMapping.frequency(it - 1)) } }
    @Test fun settingValidationPreventsZeroRangeAndUnsafeDuration() { val s = Settings(rssiMin = -10, rssiMax = -90, chirpMs = 400, smoothing = -1.0).validated(); assertTrue(s.rssiMax > s.rssiMin); assertEquals(50, s.chirpMs); assertEquals(0.01, s.smoothing, 0.0) }
    @Test fun emaStartsWithFirstRssi() { assertEquals(-80.0, SignalStats().add(observation(-80), .25).smoothed!!, 0.001) }
    @Test fun emaUsesTunableCoefficient() { val s = SignalStats().add(observation(-80), .25).add(observation(-40, 2000), .25); assertEquals(-70.0, s.smoothed!!, 0.001) }
    @Test fun statisticsIncludeExtremaAndOnlineMean() { val s = listOf(-80, -60, -40).mapIndexed { i, r -> observation(r, 1000L + i * 1000) }.fold(SignalStats()) { a, b -> a.add(b, .25) }; assertEquals(3L, s.count); assertEquals(-40, s.current); assertEquals(-40, s.strongest); assertEquals(-80, s.weakest); assertEquals(-60.0, s.mean!!, .001); assertEquals(1000L, s.firstSeen); assertEquals(3000L, s.lastSeen) }
    @Test fun strongValidRssiAbovePitchRangeIsClampedNotDiscarded() {
        val stats = SignalStats().add(observation(50), .25)
        assertEquals(50, stats.current)
        assertEquals(3200.0, PitchMapping.frequency(50), .001)
    }
    @Test fun unknownRssiDoesNotCorruptStatistics() { val s = SignalStats().add(observation(-80), .25).add(observation(127, 2000), .25); assertEquals(2L, s.count); assertEquals(1L, s.validRssiCount); assertNull(s.current); assertEquals(-80.0, s.mean!!, 0.0) }
    @Test fun rateCountsResultsInLastFiveSeconds() { val s = listOf(1000L, 2000, 3000).fold(SignalStats()) { a, t -> a.add(observation(at = t), .25) }; assertEquals(.6, s.rate(3000), .0001); assertEquals(.2, s.rate(7000), .0001); assertEquals(0.0, s.rate(8000), .0001) }
    @Test fun rateHandlesSimultaneousResultsWithoutDivisionByZero() { val s = SignalStats().add(observation(), .25).add(observation(), .25); assertEquals(.4, s.rate(1000), .0001) }
    @Test fun outOfOrderResultsPreserveFirstAndLastTime() { val s = SignalStats().add(observation(at = 3000), .25).add(observation(at = 1000), .25); assertEquals(1000L, s.firstSeen); assertEquals(3000L, s.lastSeen); assertEquals(3000L, s.lastElapsed) }
    @Test fun hexIsLosslessAndUnsigned() { val bytes = byteArrayOf(0, 15, -128, -1); assertEquals("000F80FF", bytes.hex()); assertArrayEquals(bytes, bytes.hex().hexBytes()) }
    @Test fun parsesManufacturerLittleEndian() { val a = AdvertisementParser.parse("07FF4C000215AABB".hexBytes()); assertEquals(76, a.manufacturers.single().id); assertEquals("Apple", a.manufacturers.single().company); assertEquals("0215AABB", a.manufacturers.single().hex) }
    @Test fun unknownManufacturerRemainsRaw() { val a = AdvertisementParser.parse("05FFFEFF0102".hexBytes()); assertNull(a.manufacturers.single().company); assertEquals("0102", a.manufacturers.single().hex) }
    @Test fun repeatedManufacturerStructuresAreRetained() { val a = AdvertisementParser.parse("04FF4C000104FF4C0002".hexBytes()); assertEquals(2, a.manufacturers.size); assertEquals(2, a.structures.size) }
    @Test fun parses16BitServiceData() { val a = AdvertisementParser.parse("04160F1864".hexBytes()); assertEquals("0000180f-0000-1000-8000-00805f9b34fb", a.services.single().uuid); assertEquals("64", a.services.single().hex) }
    @Test fun parses32BitServiceData() { val a = AdvertisementParser.parse("062078563412AA".hexBytes()); assertEquals("12345678-0000-1000-8000-00805f9b34fb", a.services.single().uuid); assertEquals("AA", a.services.single().hex) }
    @Test fun parses128BitUuidLittleEndian() { val a = AdvertisementParser.parse("1107FFEEDDCCBBAA99887766554433221100".hexBytes()); assertEquals("00112233-4455-6677-8899-aabbccddeeff", a.serviceUuids.single()) }
    @Test fun parsesSolicitationAndServiceLists() { val a = AdvertisementParser.parse("05140F180A1803030D18".hexBytes()); assertEquals(2, a.solicitationUuids.size); assertEquals(1, a.serviceUuids.size) }
    @Test fun parsesNamesFlagsAndSignedTxPower() { val a = AdvertisementParser.parse("0201060409414243020AF4".hexBytes()); assertEquals("ABC", a.localName); assertEquals(6, a.flags); assertEquals(-12, a.txPower) }
    @Test fun completeNameWinsOverShortName() { val a = AdvertisementParser.parse("0409414243020841".hexBytes()); assertEquals("ABC", a.localName) }
    @Test fun truncatedDataRetainsAllRawBytes() { val a = AdvertisementParser.parse("08FF4C0001".hexBytes()); assertTrue(a.malformed); assertTrue(a.structures.single().truncated); assertEquals("08FF4C0001", a.rawHex); assertTrue(a.manufacturers.isEmpty()) }
    @Test fun malformedUuidAndEmptyManufacturerAreSafe() { assertTrue(AdvertisementParser.parse("02030F".hexBytes()).malformed); assertTrue(AdvertisementParser.parse("01FF".hexBytes()).malformed) }
    @Test fun zeroTerminatorAndUnknownStructuresRetainRaw() { val a = AdvertisementParser.parse("023FAA0000FF".hexBytes()); assertEquals("023FAA0000FF", a.rawHex); assertEquals("AA", a.structures.single().hex); assertFalse(a.malformed) }
    @Test fun emptyAndLengthOnlyRecordsNeverCrash() { assertEquals("", AdvertisementParser.parse(byteArrayOf()).rawHex); assertTrue(AdvertisementParser.parse(byteArrayOf(10)).malformed) }
    @Test fun parserToleratesArbitraryPayloads() { val random = java.util.Random(123); repeat(1000) { val bytes = ByteArray(random.nextInt(256)); random.nextBytes(bytes); assertEquals(bytes.hex(), AdvertisementParser.parse(bytes).rawHex) } }
    @Test fun baselineIncludesOnlyItsWindow() { val b = Baseline(1000, 31000).observe("A", 999).observe("B", 1000).observe("C", 30999).observe("D", 31000); assertEquals(setOf("B", "C"), b.addresses); assertEquals(30L, b.remainingSeconds(1000)); assertEquals(1L, b.remainingSeconds(30999)); assertEquals(0L, b.remainingSeconds(40000)) }
    @Test fun baselineCreatesOnlySessionMutes() { val result = Baseline(0, 10).observe("A", 1).apply(MuteRules(persistent = setOf("B"))); assertEquals(setOf("A"), result.session); assertEquals(setOf("B"), result.persistent) }
    @Test fun sessionMuteDoesNotSurviveNewSession() { val m = MuteRules().mute("A", false); assertTrue(m.isMuted("A")); assertFalse(m.nextSession().isMuted("A")) }
    @Test fun persistentMuteSurvivesNewSession() { val m = MuteRules().mute("A", true).nextSession(); assertTrue(m.isMuted("A")); assertEquals("persistent", m.kind("A")) }
    @Test fun unmuteRemovesBothTypes() { assertFalse(MuteRules(setOf("A"), setOf("A")).unmute("A").isMuted("A")) }
    @Test fun bulkSessionUnmutePreservesPersistent() { val m = MuteRules(setOf("A"), setOf("B")).copy(session = emptySet()); assertFalse(m.isMuted("A")); assertTrue(m.isMuted("B")) }
    private fun device(address: String, rssi: Int, at: Long, count: Int = 1): DeviceRecord { val o = observation(rssi, at, address); return DeviceRecord(address, (0 until count).fold(SignalStats()) { s, _ -> s.add(o, .25) }, o) }
    @Test fun deviceSortOrders() { val a = device("A", -80, 1000, 3); val b = device("B", -40, 2000); val all = listOf(a, b); val first = { s: SortOrder -> visibleDevices(all, s, false, MuteRules()).first().address }; assertEquals("B", first(SortOrder.RECENT)); assertEquals("B", first(SortOrder.CURRENT)); assertEquals("B", first(SortOrder.BEST)); assertEquals("A", first(SortOrder.FIRST)); assertEquals("A", first(SortOrder.COUNT)) }
    @Test fun filterHidesBothMuteTypesOnlyWhenRequested() { val all = listOf(device("A", -80, 1), device("B", -40, 2), device("C", -50, 3)); val m = MuteRules(setOf("A"), setOf("B")); assertEquals(listOf("C"), visibleDevices(all, SortOrder.CURRENT, true, m).map { it.address }); assertEquals(3, visibleDevices(all, SortOrder.CURRENT, false, m).size) }
    @Test fun jsonRoundTripsRawDecodedMetadataLocationAndMute() { val o = observation().copy(advertisement = AdvertisementParser.parse("07FF4C000215AABB".hexBytes()), location = GeoFix(1.0, 2.0, 3f, 100, 200), target = true, mute = "persistent"); val encoded = SearchJson.encodeToString(o); assertEquals(o, SearchJson.decodeFromString<Observation>(encoded)); assertTrue(Json.parseToJsonElement(encoded).jsonObject.containsKey("metadata")) }
    @Test fun csvEscapesQuotesCommasAndNewlines() { val o = observation().copy(deviceName = "a,\"b\"\nc"); val row = ExportCodec.csvRow(o); assertTrue(row.contains("\"a,\"\"b\"\"\nc\"")); assertTrue(row.endsWith("\r\n")) }
    @Test fun csvKeepsRawDataAndUnknownLocation() { val row = ExportCodec.csvRow(observation().copy(advertisement = Advertisement(rawHex = "00FF"))); assertTrue(row.contains("\"00FF\"")); assertFalse(row.contains("null")); assertEquals(17, ExportCodec.csvHeader.trim().split(',').size) }
    @Test fun csvPreventsAdvertisedNameFormulaExecution() { assertTrue(ExportCodec.csvRow(observation().copy(deviceName = "=1+1")).contains("\"'=1+1\"")) }
}
