package com.dayforge.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.Operation
import androidx.work.WorkerParameters
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
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
class WidgetUpdateWorkerTest {
    @get:org.junit.Rule val storage = com.dayforge.data.local.PhysicalDatabaseRule()
    private lateinit var context: Context
    private lateinit var database: HabitDatabase
    private val future = mockk<ListenableFuture<Operation.State.SUCCESS>>()
    private val operation = mockk<Operation>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = storage.database
        mockkObject(WidgetRefreshScheduler)
        every { operation.result } returns future
        every { future.isDone } returns true
        every { future.get() } returns Operation.SUCCESS
        every { WidgetRefreshScheduler.request(context) } returns operation
    }

    @After fun teardown() {
        unmockkObject(WidgetRefreshScheduler)
        database.close()
    }

    private fun worker(attempt: Int = 0): WidgetUpdateWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.runAttemptCount } returns attempt
        return WidgetUpdateWorker(context, params)
    }

    @Test fun midnight_commits_activity_rate_refresh_before_enqueue_and_waits_for_enqueue_result() = runTest {
        val id = database.habitDao().insert(HabitEntity(name = "Midnight", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#2196F3", schedule = HabitSchedule.Daily,
            activityRateUpdatedAt = 1L))
        every { WidgetRefreshScheduler.request(context) } answers {
            assertTrue(runBlocking { requireNotNull(database.habitDao().getHabitById(id)).activityRateUpdatedAt } > 1L)
            operation
        }
        assertEquals(Result.success(), worker().doWork())
        verify(exactly = 1) { WidgetRefreshScheduler.request(context) }
        verify(exactly = 1) { future.get() }
    }

    @Test fun synchronous_and_asynchronous_enqueue_failures_retry_but_are_bounded() = runTest {
        every { WidgetRefreshScheduler.request(context) } returns null
        assertEquals(Result.retry(), worker().doWork())
        assertEquals(Result.failure(), worker(2).doWork())
        every { WidgetRefreshScheduler.request(context) } returns operation
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
}
