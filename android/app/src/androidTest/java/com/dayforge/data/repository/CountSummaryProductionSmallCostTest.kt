package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Independent complete class: estimated 2–4 minutes plus build/install overhead. */
@RunWith(AndroidJUnit4::class)
class CountSummaryProductionSmallCostTest : CountSummaryProductionCostFixture() {
    @Test(timeout = 1_200_000) fun eightHistoricalFactsAndOneProductionCountChain() = runBlocking<Unit> { journey(8) }
}
