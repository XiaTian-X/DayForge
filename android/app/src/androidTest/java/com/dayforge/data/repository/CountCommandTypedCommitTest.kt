package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** New ordinary safety class. No timing/UI/process-death claim; uncompiled and unexecuted.
 * Each case includes setup and its complete current-source/original-domain terminal.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandTypedCommitTest : CountTrustFormalProfileFixture() {
    private val native = SourceWindowNativeSpy()
    override fun sourceWindowNativeSpy() = native

    @Test(timeout = 150_000) fun durableT0DoesNotDirtyCountAndBothDayShapesUseClosedC() = runBlocking<Unit> {
        commandMetadataPreservesCountProofAndUsesActualClosedC(native)
    }
    @Test(timeout = 150_000) fun actualUnknownCEndHasOneDurableSameIdOutcomeAfterReopen() = runBlocking<Unit> {
        actualCommandCountEndLossResolvesSameIdWithoutReplay(native)
    }
    @Test(timeout = 150_000) fun caughtPurePreparationFailureAllowsSameIdRecoveryWhileCallerStaysAlive() = runBlocking<Unit> {
        commandPurePreparationFailureReleasesLiveCallerLease(native)
    }

}
