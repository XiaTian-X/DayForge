package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room/Keystore/producer/source guards. Complete original classes are retained. */
@RunWith(AndroidJUnit4::class)
class CountDisplayProductionSafetyTest : CountTrustFormalProfileFixture() {
    @Test fun claimedGlobalLossFailsWithoutMintingAnEmptyProjection() = runBlocking<Unit> {
        compactRelatedBlockRejectsAndRestores(CompactDamage.GLOBAL_MISSING)
    }
    @Test fun changedGlobalBytesFailBeforeAnyCompactPublication() = runBlocking<Unit> {
        compactRelatedBlockRejectsAndRestores(CompactDamage.GLOBAL_BYTES)
    }
    @Test fun changedRequiredYearBytesFailEvenWithAnUnchangedGlobalEntry() = runBlocking<Unit> {
        compactRelatedBlockRejectsAndRestores(CompactDamage.YEAR_BYTES)
    }
    @Test fun invalidClaimedCoverageCannotBecomeAnOriginalFallback() = runBlocking<Unit> {
        compactRelatedBlockRejectsAndRestores(CompactDamage.INVALID_COVERAGE)
    }
    @Test fun absentOptionalCoverageKeepsTheOriginalReaderAndWholeMainUnchanged() = runBlocking<Unit> {
        compactAbsentCoverageKeepsTheOriginalLegalReader()
    }
    @Test fun typedFirstDayGenericQuantityAndUndoMaintainTheOriginalCurrentResult() = runBlocking<Unit> {
        compactGenericUndoAndTypedFirstDayMaintainTheSameResults()
    }
    @Test fun compactResultCannotOutliveTheOriginalAccountPublicationFence() = runBlocking<Unit> {
        compactPublicationStillRequiresFreshOriginalAuthority()
    }
}
