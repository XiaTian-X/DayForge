package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Unadmitted new maintenance capacity diagnostic; run as this whole class only AFTER safety
 * and preceding-size cost results. Method capacity is 1800s, not a product latency gate.
 * Runner budget must include build/install/cleanup and be recalculated from preceding evidence.
 */
@RunWith(AndroidJUnit4::class)
class CountNodeGcMediumCostTest : CountNodeGcCostFixture() {
    @Test(timeout = 1_800_000) fun householdHistory80WholeChainWithAndWithoutCountCollection() = runBlocking<Unit> {
        maintenanceJourney(80)
    }
}
