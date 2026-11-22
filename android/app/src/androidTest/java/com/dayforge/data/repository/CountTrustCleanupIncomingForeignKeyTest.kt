package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Full two-case counterexample class against CURRENT production, not a candidate guard. */
@RunWith(AndroidJUnit4::class)
class CountTrustCleanupIncomingForeignKeyTest : CountTrustCleanupIncomingForeignKeyFixture() {
    @Test fun recoveryCacheDiscardRejectsIncomingCascadeBeforeDeletingAnyOwnedRow() = runBlocking<Unit> {
        register(); val expected = access(); val evidence = installAndProveCascade()
        requireEntryRejectsWithoutEffects("discard_cache", evidence) {
            CountTrustRecoveryPreparation(db, tokens, sessions).discardCache(expected)
        }
    }
    @Test fun accountClearRejectsIncomingCascadeBeforeResettingOrDeletingAnyOwnedRow() = runBlocking<Unit> {
        register(); val evidence = installAndProveCascade()
        requireEntryRejectsWithoutEffects("clear_all_data", evidence) { db.clearAllData() }
    }
}
