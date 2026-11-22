package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Actual current prompt close/skip, durable T2 and disk reopen; not process-death evidence.
 * Do not run until the complete v20 package is admitted. Ordinary150s is unchanged.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandFollowUpCloseTest : CountTrustFormalProfileFixture() {
    @Test(timeout = 150_000) fun noRequiredPromptClosesOnlyAfterCurrentOriginalPolicyDecision() = runBlocking<Unit> {
        commandNeverAskDecisionNeedsCurrentProducerAndSurvivesColdReopen()
    }

    @Test(timeout = 150_000) fun closingTheWindowKeepsPendingButExplicitSkipRemovesItAtomically() = runBlocking<Unit> {
        originalWindowCloseRetainsCommandButExplicitSkipPersistsT2()
    }
}
