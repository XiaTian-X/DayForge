package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Complete class; previous 730-row construction alone took ~204 s. Allow 12–20 min for
 * fixture + first qualification + original terminal oracle; run alone with a 45-min runner
 * ceiling and this 30-min case ceiling. No individual latency budget is relaxed. */
@RunWith(AndroidJUnit4::class)
class CountTrustProductionAnnualCostTest : CountTrustProductionCostFixture() {
    @Test(timeout = 1_800_000) fun sevenHundredThirtyHistoricalFactsAndOneTrustedProductionChain() = runBlocking<Unit> { journey(730) }
}
