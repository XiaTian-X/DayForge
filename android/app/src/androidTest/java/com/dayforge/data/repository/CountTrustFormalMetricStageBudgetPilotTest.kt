package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** One preflight pilot, not the annual or mixed-page performance acceptance suite.
 * Same complete bootstrap8 F2 chain measured at 117.511s previously; no additional cold work.
 * This new diagnostic case declares 300s upfront; existing ordinary safety cases stay 150s.
 * 117.511s * 1.5 + 30s instrumentation/report reserve = 206.2665s, below this capacity.
 * Product save/result/cold/resource budgets are unchanged and are not evaluated by this pilot.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustFormalMetricStageBudgetPilotTest : CountTrustFormalProfileFixture() {
    @Test(timeout = 300_000) fun eightHistoricalFactsOriginalCompleteChainStageBudgetPilot() = runBlocking<Unit> {
        formalMetricEightStageBudgetPilot()
    }
}
