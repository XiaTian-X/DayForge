package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** One diagnostic method, with all original bootstrap8 F2 business/oracle/terminal assertions.
 * 123 whole JUnit 146.278s * 1.5 + 30s new read-only decomposition + 30s reporting = 279.417s.
 * 360s is predeclared for this NEW diagnostic only; ordinary safety150s and product SLAs stay.
 * Standard targeted runner capacity is2700s; this is neither latency acceptance nor P95.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustFormalOpenFixedCostTest : CountTrustFormalProfileFixture() {
    @Test(timeout = 360_000) fun bootstrapEightCompleteChainThenReadOnlyOpenDecomposition() = runBlocking<Unit> {
        formalMetricEightOpenFixedCostDiagnostic()
    }
}
