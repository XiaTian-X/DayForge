package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Independent complete class: ~204 s historical setup plus repeated original audits;
 * estimated 12–20 minutes, run separately with a 30-minute case ceiling and 45-minute runner ceiling. The bulk-case
 * ceiling does not relax the original save/result/ACK/resource latency budgets. */
@RunWith(AndroidJUnit4::class)
class CountSummaryProductionAnnualCostTest : CountSummaryProductionCostFixture() {
    @Test(timeout = 1_800_000) fun sevenHundredThirtyHistoricalFactsAndOneProductionCountChain() = runBlocking<Unit> { journey(730) }
}
