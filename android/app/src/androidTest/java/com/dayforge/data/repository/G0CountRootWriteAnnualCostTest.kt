package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** PROPOSED separate bulk-case allowance, matching the existing annual fixture's 900s.
 * Historical 730 producer+construction alone took133s; do not execute under a150s
 * assumption. This does not change cold2s or save/ACK500/read700/resource150ms goals. */
@RunWith(AndroidJUnit4::class)
class G0CountRootWriteAnnualCostTest:G0CountRootWriteCostFixture() {
    @Test(timeout=900_000) fun annualPastFactsColdFirstWriteAndTwoConcurrentAckWindows()=runBlocking<Unit> {rootWriteJourney(730)}
}
