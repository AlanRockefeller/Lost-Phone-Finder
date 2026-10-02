package org.blefinder

import org.blefinder.core.*
import org.blefinder.data.*
import org.blefinder.ui.signalGraphPosition
import org.junit.Assert.*
import org.junit.Test

class LiveSignalTest {
    private fun samples(vararg times: Long) = times.map { RssiSample(it, -65, -65.0) }
    private fun device(address: String, rssi: Int, at: Long): DeviceRecord {
        val result = Observation(address, at, at, rssi, at, metadata = ScanMetadata(at * 1_000_000),
            advertisement = Advertisement(manufacturers = listOf(ManufacturerData(76, "Apple", "0102"))))
        return DeviceRecord(address, SignalStats().add(result, .25), result)
    }
    private fun choose(selector: StrongestDeviceSelector, now: Long, vararg devices: DeviceRecord,
        mutes: MuteRules = MuteRules()): String? = selector.update(devices.toList(), mutes, emptyMap(), now)

    @Test fun intermittentBleResultsStayConnectedAndMedianIgnoresDuplicateTimes() {
        val intermittent = samples(0, 3_000, 7_000, 14_000, 17_000, 21_000)
        assertEquals(16_000L, signalGapMillis(intermittent, 21_000))
        assertEquals(listOf(intermittent), signalSegments(intermittent, 21_000))
        val fast = samples(0, 500, 500, 1_000, 7_000, 7_500, 8_000)
        assertEquals(8_000L, signalGapMillis(fast, 8_000))
        assertEquals(listOf(fast), signalSegments(fast.reversed(), 8_000))
    }

    @Test fun realOutagesBreakLinesWithoutInflatingCadence() {
        val fast = samples(0, 1_000, 2_000, 15_000, 16_000, 17_000)
        assertEquals(8_000L, signalGapMillis(fast, 17_000))
        assertEquals(listOf(fast.take(3), fast.drop(3)), signalSegments(fast, 17_000))
        val slow = samples(0, 6_000, 12_000, 40_000, 46_000, 52_000)
        assertEquals(20_000L, signalGapMillis(slow, 52_000))
        assertEquals(listOf(slow.take(3), slow.drop(3)), signalSegments(slow, 52_000))
        assertEquals(8_000L, signalGapMillis(samples(0, 1_000, 16_000), 16_000))
        assertEquals(2, signalSegments(samples(0, 1_000, 16_000), 16_000).size)
        assertEquals(2, signalSegments(samples(0, 30_000), 30_000).size)
        assertEquals(1, signalSegments(samples(0, 20_000), 20_000).size)
        assertEquals(2, signalSegments(samples(0, 20_001), 20_001).size)
    }

    @Test fun graphUsesFixedSixtySecondAndConfiguredRssiAxesAndLeavesSilenceBlank() {
        assertEquals(60_000L, SIGNAL_HISTORY_MS)
        val positions = samples(10_000, 40_000, 70_000).map { signalGraphPosition(it, 70_000, -100, -30) }
        assertEquals(listOf(0f, .5f, 1f), positions.map { it.x })
        assertTrue(positions.all { it.y == .5f })
        assertEquals(0f, signalGraphPosition(RssiSample(70_000, -10, -10.0), 70_000, -100, -30).y)
        assertEquals(1f, signalGraphPosition(RssiSample(70_000, -120, -120.0), 70_000, -100, -30).y)
        val visible = signalSegments(samples(9_999, 10_000, 40_000, 70_001), 70_000).flatten()
        assertEquals(listOf(10_000L, 40_000L), visible.map { it.elapsedMillis })
        assertEquals(.5f, signalGraphPosition(visible.last(), 70_000, -100, -30).x)
        assertTrue(signalSegments(visible, 100_001).isEmpty())
    }

    @Test fun smallRssiFluctuationsDoNotChangeFeaturedAddress() {
        val selector = StrongestDeviceSelector()
        assertEquals("A", choose(selector, 0, device("A", -50, 0), device("B", -51, 0)))
        assertEquals("A", choose(selector, 1_000, device("A", -50, 1_000), device("B", -49, 1_000)))
        assertEquals("A", choose(selector, 4_000, device("A", -53, 4_000), device("B", -50, 4_000)))
    }

    @Test fun challengerNeedsSixDbAndTwoSecondsOfConfirmedStrength() {
        val selector = StrongestDeviceSelector()
        assertEquals("A", choose(selector, 0, device("A", -50, 0), device("B", -51, 0)))
        assertEquals("A", choose(selector, 1_000, device("A", -52, 1_000), device("B", -46, 1_000)))
        assertEquals("A", choose(selector, 2_999, device("A", -52, 2_999), device("B", -46, 2_999)))
        assertEquals("B", choose(selector, 3_000, device("A", -52, 3_000), device("B", -46, 3_000)))
    }

    @Test fun briefSpikeAndInterruptedChallengeResetTheHold() {
        val selector = StrongestDeviceSelector()
        choose(selector, 0, device("A", -60, 0))
        assertEquals("A", choose(selector, 1_000, device("A", -60, 1_000), device("B", -54, 1_000)))
        assertEquals("A", choose(selector, 2_000, device("A", -60, 2_000), device("B", -59, 2_000)))
        assertEquals("A", choose(selector, 2_500, device("A", -60, 2_500), device("B", -54, 2_500)))
        assertEquals("A", choose(selector, 4_499, device("A", -60, 4_499), device("B", -54, 4_499)))
        assertEquals("B", choose(selector, 4_500, device("A", -60, 4_500), device("B", -54, 4_500)))
        selector.reset()
        choose(selector, 0, device("A", -50, 0))
        val spike = device("B", -43, 1_000)
        choose(selector, 1_000, device("A", -50, 0), spike)
        assertEquals("A", choose(selector, 3_000, device("A", -50, 0), spike))
        assertEquals("B", choose(selector, 3_100, device("A", -50, 0), device("B", -43, 3_100)))
    }

    @Test fun staleMutedAndUnavailableIncumbentsGiveWayImmediately() {
        val selector = StrongestDeviceSelector()
        choose(selector, 0, device("A", -50, 0))
        assertEquals("B", choose(selector, 8_001, device("A", -50, 0), device("B", -54, 8_001)))
        assertNull(choose(selector, 16_002, device("B", -54, 8_001)))
        selector.reset()
        choose(selector, 0, device("A", -50, 0))
        assertEquals("B", choose(selector, 1_000, device("A", -50, 1_000), device("B", -54, 1_000), mutes = MuteRules(session = setOf("A"))))
        assertEquals("A", choose(selector, 2_000, device("A", -50, 2_000), device("B", 127, 2_000)))
    }

    @Test fun sparseCadenceKeepsIncumbentFreshLongerAndMatchingProfilesRemainSeparate() {
        val selector = StrongestDeviceSelector()
        val a = device("A", -50, 12_000)
        val b = device("B", -54, 21_000)
        assertEquals(1, packetBuckets(listOf(a, b)).size)
        val history = mapOf("A" to samples(0, 6_000, 12_000))
        assertEquals("A", selector.update(listOf(a), MuteRules(), history, 12_000))
        assertEquals("A", selector.update(listOf(a, b), MuteRules(), history, 21_000))
        assertEquals("B", selector.update(listOf(a, device("B", -54, 33_001)), MuteRules(), history, 33_001))
        assertEquals(2, packetBuckets(listOf(a, b)).single().devices.size)
        assertNull(a.probablePhysicalDeviceId)
        assertNull(b.probablePhysicalDeviceId)
    }

    @Test fun newChallengerAndNewSessionStartNewSelectionPeriods() {
        val selector = StrongestDeviceSelector()
        choose(selector, 0, device("A", -60, 0))
        choose(selector, 1_000, device("A", -60, 1_000), device("B", -54, 1_000))
        assertEquals("A", choose(selector, 2_000, device("A", -60, 2_000), device("B", -54, 2_000), device("C", -52, 2_000)))
        assertEquals("A", choose(selector, 3_000, device("A", -60, 3_000), device("C", -52, 3_000)))
        assertEquals("C", choose(selector, 4_000, device("A", -60, 4_000), device("C", -52, 4_000)))
        selector.reset()
        assertEquals("B", choose(selector, 5_000, device("A", -60, 5_000), device("B", -59, 5_000)))
    }
}
