package com.dayforge.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dayforge.domain.service.IconMaterialOutcome
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconWorkerTest {
    private fun worker(run: suspend () -> IconMaterialOutcome) = AccountIconWorker(
        ApplicationProvider.getApplicationContext<Context>(),mockk<WorkerParameters>(relaxed=true),run)

    @Test fun completeInactiveAndUnsupportedAreFiniteNonSecretSuccesses() = runBlocking<Unit> {
        for (value in listOf(IconMaterialOutcome.COMPLETE,IconMaterialOutcome.INACTIVE,IconMaterialOutcome.UNSUPPORTED))
            assertEquals(Result.success(workDataOf(AccountIconWorker.STATUS to value.name)),worker { value }.doWork())
    }

    @Test fun remainingTransientWorkRequestsBackoffAndExplicitAttentionIsNeverSuccessOrRetry() = runBlocking<Unit> {
        assertEquals(Result.retry(),worker { IconMaterialOutcome.RETRY }.doWork())
        assertEquals(Result.failure(workDataOf(AccountIconWorker.STATUS to "ATTENTION")),worker { IconMaterialOutcome.ATTENTION }.doWork())
    }

    @Test fun actualCancellationIsNotConvertedIntoAFiniteOrSuccessfulWorkResult() = runBlocking<Unit> {
        val original=CancellationException("owned stop")
        try { worker { throw original }.doWork(); fail("Must propagate cancellation") }
        catch (error: CancellationException) { assertSame(original,error) }
    }

    @Test fun dependencyInitializationFailureCannotInventSuccessOrUnlimitedRetry() = runBlocking<Unit> {
        val original=IllegalStateException("dependency unavailable")
        try { worker { throw original }.doWork(); fail("Must propagate dependency failure") }
        catch (error: IllegalStateException) { assertSame(original,error) }
    }
}
