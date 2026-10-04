package org.blefinder

import org.blefinder.core.*
import org.junit.Assert.*
import org.junit.Test

class GpsBreadcrumbsTest {
    private fun fix(seconds: Long, wall: Long = 1000) = GeoFix(1.0, 2.0, 3f, wall, seconds * 1_000_000_000)

    @Test fun standaloneFixesAreLimitedToOnePerMinuteEvenDuringMovement() {
        val breadcrumbs = GpsBreadcrumbs()
        val retained = (0L..180).filter { breadcrumbs.retain(fix(it).copy(latitude = 1.0 + it * .001)) }
        assertEquals(listOf(0L, 60L, 120L, 180L), retained)
    }

    @Test fun controllerTimeDefinesCadenceDespiteWallClockChangesAndLateCallbacks() {
        val breadcrumbs = GpsBreadcrumbs()
        assertTrue(breadcrumbs.retain(fix(100)))
        assertFalse(breadcrumbs.retain(fix(100, 999999)))
        assertFalse(breadcrumbs.retain(fix(20, 999999)))
        assertFalse(breadcrumbs.retain(fix(159, 999999)))
        assertTrue(breadcrumbs.retain(fix(160, 1)))
    }

    @Test fun invalidFixesDoNotConsumeTheNextSampleAndLifecycleResetAcceptsTheFirstFix() {
        val breadcrumbs = GpsBreadcrumbs()
        listOf(fix(1).copy(latitude = Double.NaN), fix(1).copy(longitude = 181.0),
            fix(1).copy(accuracy = -1f), fix(1).copy(accuracy = Float.POSITIVE_INFINITY),
            fix(1).copy(elapsedNanos = -1)).forEach { assertFalse(breadcrumbs.retain(it)) }
        assertTrue(breadcrumbs.retain(fix(1)))
        assertFalse(breadcrumbs.retain(fix(2)))
        breadcrumbs.reset()
        assertTrue(breadcrumbs.retain(fix(2)))
    }
}
