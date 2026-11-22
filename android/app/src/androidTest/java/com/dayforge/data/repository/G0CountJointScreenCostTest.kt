package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** One same-image baseline/candidate pair for EACH concurrency condition. Existing
 * six taps/seven syncs and all terminal/pixel/budget assertions remain in the fixture.
 * These bounded windows are not P95, sustained arrivals or multi-habit certification. */
@RunWith(AndroidJUnit4::class)
class G0CountJointScreenCostTest:G0ShortCountBacklogCostFixture() {
    @Test(timeout=900_000) fun eightFactsPairedScreenAndBacklog()=runBlocking<Unit> {
        measure(8,authorityArms=listOf(false,true))
    }
    @Test(timeout=900_000) fun eightyFactsPairedScreenAndBacklog()=runBlocking<Unit> {
        measure(80,authorityArms=listOf(false,true))
    }
    @Test(timeout=900_000) fun eightDatesWithinOneWeekPairedScreenAndBacklog()=runBlocking<Unit> {
        measure(8,listOf(7L,6L,5L,4L,3L,2L,1L,0L),authorityArms=listOf(false,true))
    }
    @Test(timeout=900_000) fun eightDatesAcrossOneYearPairedScreenAndBacklog()=runBlocking<Unit> {
        measure(8,listOf(365L,270L,180L,90L,30L,7L,1L,0L),authorityArms=listOf(false,true))
    }
}

/** Five seconds AFTER a result is visible, not independent five-second arrivals.
 * Background runs throughout; no forced ACK collision is promised by this workload.
 * The zero-think stress class above retains its actual-overlap requirement. */
@RunWith(AndroidJUnit4::class)
class G0CountJointPacedScreenCostTest:G0ShortCountBacklogCostFixture() {
    @Test(timeout=900_000) fun sixInputsWithFiveSecondThinkingAndBackgroundProgress()=runBlocking<Unit> {
        measure(8,authorityArms=listOf(false,true),interResultDelayMs=5_000,concurrentArms=listOf(true))
    }
}

/** Reuse all six original calendar refusals/equivalence checks with the actual candidate. */
@RunWith(AndroidJUnit4::class)
class G0CountJointCalendarSafetyTest:G0ShortCountCalendarSafetyTest() {
    override val jointCountAuthority=true
}
