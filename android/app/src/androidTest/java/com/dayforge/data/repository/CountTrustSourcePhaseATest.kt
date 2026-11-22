package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Entry-only original source audits, legal fallback and real private COUNT tickets. */
@RunWith(AndroidJUnit4::class)
class CountTrustSourcePhaseATest : CountTrustFormalProfileFixture() {
    @Test fun realActiveCountUsesEntryOnlyWithBacklogAndQuantityTwoKeepsOriginalFallback() = runBlocking<Unit> {
        countSourceEntryOnlyAndOriginalFallback()
    }
    @Test fun countSourceTicketCannotBeDelegatedToChildJob() = runBlocking<Unit> {
        countSourcePrivateScopeRejects("child")
    }
    @Test fun countSourceTicketCannotBindTwice() = runBlocking<Unit> {
        countSourcePrivateScopeRejects("duplicate")
    }
    @Test fun rolledBackCountSourceTicketCannotBindANewTransaction() = runBlocking<Unit> {
        countSourcePrivateScopeRejects("old")
    }
    @Test fun cancellationAfterActualCountSourceBindingRollsBackAndFreshCommandRetries() = runBlocking<Unit> {
        countSourcePrivateScopeRejects("cancel")
    }
}
