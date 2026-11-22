package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Existing physical database/Keystore, previous authenticated validator, and real travel fallback.
 * A Room reopen is deliberately not labelled a process-death or latency acceptance test. */
@RunWith(AndroidJUnit4::class)
class CountDisplayLifecycleTest : CountTrustFormalProfileFixture() {
    @Test fun retainedDatabaseReopensWithoutRepeatingTheFullHistoricalCalculation() = runBlocking<Unit> {
        compactRetainedDatabaseReopenUsesTheAuthenticatedProjection()
    }
    @Test fun previousV3BytesAndMacMustPassBeforeTheNewProjectionCanBeIssued() = runBlocking<Unit> {
        compactV3UpgradeChecksAllOldBytesAndMacBeforeReissuing()
    }
    @Test fun differentCalendarZoneUsesTheOriginalAlgorithmsAndPublicationFence() = runBlocking<Unit> {
        compactCalendarTravelFallsBackToTheExactOriginalDisplayAlgorithms()
    }
    @Test fun sameHabitAckWithANewerLocalCountKeepsTheExactCommonProjection() = runBlocking<Unit> {
        compactSameHabitAckWithALocalSuccessorKeepsTheCommonCurrentResult()
    }
    @Test fun extraLegalAckMetadataUsesTheOriginalCompleteRebuild() = runBlocking<Unit> {
        compactExtraLegalAckMetadataInvalidatesAndRebuildsThroughTheOriginalProof()
    }
}
