package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Unadmitted new maintenance capacity diagnostic; run as this whole class only AFTER safety
 * and preceding-size cost results. Method capacity is 3600s, not a product latency gate.
 * Runner budget must include build/install/cleanup and be recalculated from preceding evidence.
 */
@RunWith(AndroidJUnit4::class)
class CountNodeGcAnnualCostTest : CountNodeGcCostFixture() {
    @Test(timeout = 3_600_000) fun householdHistory730WholeChainWithAndWithoutCountCollection() = runBlocking<Unit> {
        maintenanceJourney(730)
    }
}
