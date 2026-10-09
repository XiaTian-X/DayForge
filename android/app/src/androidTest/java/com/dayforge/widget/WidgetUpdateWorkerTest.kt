package com.dayforge.widget

import androidx.work.ListenableWorker.Result
import androidx.work.Operation
import androidx.work.WorkerParameters
import com.dayforge.data.repository.LegacyActivityRateRefresher
import com.dayforge.data.repository.NextCoreRequestFixture
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.*
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetUpdateWorkerTest : NextCoreRequestFixture() {
    private val future = mockk<ListenableFuture<Operation.State.SUCCESS>>()
    private val operation = mockk<Operation>()

    @Before fun installEnqueueFixture() {
        mockkObject(WidgetRefreshScheduler)
        every { operation.result } returns future
        every { future.isDone } returns true
        every { future.get() } returns Operation.SUCCESS
        every { WidgetRefreshScheduler.request(app) } returns operation
    }

    @After fun teardown() {
        unmockkObject(WidgetRefreshScheduler)
    }

    private fun worker(attempt: Int = 0): WidgetUpdateWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.runAttemptCount } returns attempt
        return WidgetUpdateWorker(app, params) { LegacyActivityRateRefresher(db, tokens, sessions).refresh() }
    }

    @Test fun midnight_commits_activity_rate_refresh_before_enqueue_and_waits_for_enqueue_result() = runTest {
        val id = db.habitDao().insert(HabitEntity(name = "Midnight", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#2196F3", schedule = HabitSchedule.Daily,
            activityRateUpdatedAt = 1L))
        every { WidgetRefreshScheduler.request(app) } answers {
            assertTrue(runBlocking { requireNotNull(db.habitDao().getHabitById(id)).activityRateUpdatedAt } > 1L)
            operation
        }
        assertEquals(Result.success(), worker().doWork())
        verify(exactly = 1) { WidgetRefreshScheduler.request(app) }
        verify(exactly = 1) { future.get() }
    }

    @Test fun synchronous_and_asynchronous_enqueue_failures_retry_but_are_bounded() = runTest {
        every { WidgetRefreshScheduler.request(app) } returns null
        assertEquals(Result.retry(), worker().doWork())
        assertEquals(Result.failure(), worker(2).doWork())
        every { WidgetRefreshScheduler.request(app) } returns operation
        every { future.get() } throws ExecutionException(IllegalStateException("database busy"))
        assertEquals(Result.retry(), worker().doWork())
    }

    @Test fun cancellation_is_not_converted_to_a_midnight_retry() = runTest {
        every { future.get() } throws CancellationException("cancelled")
        try {
            worker().doWork()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun failed_or_cancelled_cache_refresh_never_enqueues_a_presentation_successor() = runTest {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.runAttemptCount } returns 0
        assertEquals(Result.retry(), WidgetUpdateWorker(app, params) { error("cache unavailable") }.doWork())
        every { params.runAttemptCount } returns 2
        assertEquals(Result.failure(), WidgetUpdateWorker(app, params) { error("cache unavailable") }.doWork())
        try {
            WidgetUpdateWorker(app, params) { throw CancellationException("cache cancelled") }.doWork()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        verify(exactly = 0) { WidgetRefreshScheduler.request(app) }
        verify(exactly = 0) { future.get() }
    }
}
