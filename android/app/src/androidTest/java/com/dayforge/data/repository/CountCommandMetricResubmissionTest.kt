package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** True T0/private-T1 rollback + real corrected MetricRepository submission. Not host death.
 * Whole v20 package admission and advance budget review are still required before execution.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandMetricResubmissionTest : CountTrustFormalProfileFixture() {
    @Test(timeout = 150_000) fun rolledBackChildCanBeReplacedByCorrectedExplicitInputWithoutLosingAudit() = runBlocking<Unit> {
        actualMetricResubmissionRequiresProvenUncommittedChild("rolled_back")
    }
    @Test(timeout = 150_000) fun liveOriginalChildCannotBeStolenAndLegitimateRetryRemainsPossible() = runBlocking<Unit> {
        actualMetricResubmissionRequiresProvenUncommittedChild("live")
    }
    @Test(timeout = 150_000) fun advancedBusinessGenerationDoesNotTurnMissingOutcomeIntoNoEffects() = runBlocking<Unit> {
        actualMetricResubmissionRequiresProvenUncommittedChild("advanced")
    }
}
