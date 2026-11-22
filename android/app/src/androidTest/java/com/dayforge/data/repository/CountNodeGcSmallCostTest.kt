package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Unadmitted new maintenance capacity diagnostic; run as this whole class only AFTER safety
 * and preceding-size cost results. Method capacity is 1200s, not a product latency gate.
 * Runner budget must include build/install/cleanup and be recalculated from preceding evidence.
 */
@RunWith(AndroidJUnit4::class)
class CountNodeGcSmallCostTest : CountNodeGcCostFixture() {
    @Test(timeout = 1_200_000) fun householdHistory8WholeChainWithAndWithoutCountCollection() = runBlocking<Unit> {
        maintenanceJourney(8)
    }
}
