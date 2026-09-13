package com.dayforge.widget

import android.content.Context
import androidx.room.Room
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class WidgetUpdateWorkerTest {
    private lateinit var context: Context
    private lateinit var database: HabitDatabase
    private val future = mockk<ListenableFuture<Operation.State.SUCCESS>>()
    private val operation = mockk<Operation>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java).build()
        HabitDatabaseProvider.setInstanceForTesting(database)
        mockkObject(WidgetRefreshScheduler)
        every { operation.result } returns future
        every { future.isDone } returns true
        every { future.get() } returns Operation.SUCCESS
        every { WidgetRefreshScheduler.request(context) } returns operation
    }

    @After fun teardown() {
        unmockkObject(WidgetRefreshScheduler)
        HabitDatabaseProvider.clearInstanceForTesting()
        database.close()
    }

    private fun worker(attempt: Int = 0): WidgetUpdateWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.runAttemptCount } returns attempt
        return WidgetUpdateWorker(context, params)
    }

    @Test fun `midnight commits activity-rate refresh before enqueue and waits for enqueue result`() = runTest {
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

    @Test fun `synchronous and asynchronous enqueue failures retry but are bounded`() = runTest {
        every { WidgetRefreshScheduler.request(context) } returns null
        assertEquals(Result.retry(), worker().doWork())
        assertEquals(Result.failure(), worker(2).doWork())
        every { WidgetRefreshScheduler.request(context) } returns operation
        every { future.get() } throws ExecutionException(IllegalStateException("database busy"))
        assertEquals(Result.retry(), worker().doWork())
    }

    @Test fun `cancellation is not converted to a midnight retry`() = runTest {
        every { future.get() } throws CancellationException("cancelled")
        try {
            worker().doWork()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
