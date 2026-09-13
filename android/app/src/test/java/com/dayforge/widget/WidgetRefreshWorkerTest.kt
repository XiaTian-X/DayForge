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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class WidgetRefreshWorkerTest {
    private fun worker(attempt: Int = 0, refresh: suspend () -> Int): WidgetRefreshWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.runAttemptCount } returns attempt
        return WidgetRefreshWorker(ApplicationProvider.getApplicationContext<Context>(), params, refresh)
    }

    @Test fun `complete refresh records zero failed steps`() = runTest {
        assertEquals(Result.success(workDataOf(WidgetRefreshWorker.FAILED_STEPS to 0)), worker { 0 }.doWork())
    }

    @Test fun `partial refresh retries twice then reports failures without poisoning successors`() = runTest {
        assertEquals(Result.retry(), worker(0) { 2 }.doWork())
        assertEquals(Result.retry(), worker(1) { 2 }.doWork())
        assertEquals(Result.success(workDataOf(WidgetRefreshWorker.FAILED_STEPS to 2)), worker(2) { 2 }.doWork())
    }

    @Test fun `unexpected refresh failure retries and cancellation propagates`() = runTest {
        assertEquals(Result.retry(), worker { throw IllegalStateException("unavailable") }.doWork())
        try {
            worker { throw CancellationException("stopped") }.doWork()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
