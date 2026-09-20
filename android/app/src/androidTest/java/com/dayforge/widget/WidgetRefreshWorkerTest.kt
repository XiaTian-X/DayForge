package com.dayforge.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetRefreshWorkerTest {
    private fun worker(attempt: Int = 0, refresh: suspend () -> Int): WidgetRefreshWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.runAttemptCount } returns attempt
        return WidgetRefreshWorker(ApplicationProvider.getApplicationContext<Context>(), params, refresh)
    }

    @Test fun complete_refresh_records_zero_failed_steps() = runTest {
        assertEquals(Result.success(workDataOf(WidgetRefreshWorker.FAILED_STEPS to 0)), worker { 0 }.doWork())
    }

    @Test fun partial_refresh_retries_twice_then_reports_failures_without_poisoning_successors() = runTest {
        assertEquals(Result.retry(), worker(0) { 2 }.doWork())
        assertEquals(Result.retry(), worker(1) { 2 }.doWork())
        assertEquals(Result.success(workDataOf(WidgetRefreshWorker.FAILED_STEPS to 2)), worker(2) { 2 }.doWork())
    }

    @Test fun unexpected_refresh_failure_retries_and_cancellation_propagates() = runTest {
        assertEquals(Result.retry(), worker { throw IllegalStateException("unavailable") }.doWork())
        try {
            worker { throw CancellationException("stopped") }.doWork()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
