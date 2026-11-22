package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Independent complete class: estimated 4–7 minutes plus build/install overhead. */
@RunWith(AndroidJUnit4::class)
class CountSummaryProductionMediumCostTest : CountSummaryProductionCostFixture() {
    @Test(timeout = 1_200_000) fun eightyHistoricalFactsAndOneProductionCountChain() = runBlocking<Unit> { journey(80) }
}
