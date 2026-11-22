package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Complete class; estimate 3–6 min plus build/install, case ceiling 20 min. */
@RunWith(AndroidJUnit4::class)
class CountTrustProductionSmallCostTest : CountTrustProductionCostFixture() {
    @Test(timeout = 1_200_000) fun eightHistoricalFactsAndOneTrustedProductionChain() = runBlocking<Unit> { journey(8) }
}
