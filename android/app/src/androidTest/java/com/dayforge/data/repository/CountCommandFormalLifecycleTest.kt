package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** New safety scenarios, not UI pixels, process-death or a latency certification. Do not run
 * until the complete v20 primary/schema/legacy-view candidate and its admission gates land.
 * Ordinary 150s stays unchanged; preflight the full class against the current real stage
 * timings. If the full-chain estimate cannot fit, split independent complete questions before
 * execution rather than move setup outside timing or drop any original terminal/oracle.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandFormalLifecycleTest : CountTrustFormalProfileFixture() {
    @Test(timeout = 150_000) fun actualCountParentAndDoubleMetricChildRemainCommittedAfterAckAndDiskReopen() = runBlocking<Unit> {
        commandParentAndActualMetricGroupSurviveDiskReopen()
    }
    @Test(timeout = 150_000) fun originalNoTargetDecisionCannotUndoASeparateLaterCount() = runBlocking<Unit> {
        originalNoTargetDecisionNeverDeletesALaterCountAfterReopen()
    }
    @Test(timeout = 150_000) fun acceptedRoundsMetricGroupDeliversHistoricalResultWithoutAnotherSave() = runBlocking<Unit> {
        completedRoundsMetricGroupDeliversAfterActualAcks()
    }
}
