package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Complete class; estimate 6–12 min plus build/install, run separately, case ceiling 30 min. */
@RunWith(AndroidJUnit4::class)
class CountTrustProductionMediumCostTest : CountTrustProductionCostFixture() {
    @Test(timeout = 1_800_000) fun eightyHistoricalFactsAndOneTrustedProductionChain() = runBlocking<Unit> { journey(80) }
}
