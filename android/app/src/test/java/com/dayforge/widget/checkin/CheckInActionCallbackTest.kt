package com.dayforge.widget.checkin

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.CheckInService
import com.dayforge.widget.WidgetRefreshScheduler
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for CheckInActionCallback.
 * Tests verify the callback correctly routes actions through CheckInService.
 *
 * Test coverage:
 * - CHECK-04: Widget can handle increment action for counting habits
 * - CHECK-05: Widget can handle decrement action for counting habits
 * - Missing habitId returns early without error
 * - Missing action defaults to "toggle"
 *
 * Note: Tests use in-memory database. The callback creates its own database/service
 * instance internally, so we verify the side effects through the shared database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CheckInActionCallbackTest {

    private lateinit var database: HabitDatabase
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: CompletionDao
    private lateinit var repository: HabitRepository
    private lateinit var callback: CheckInActionCallback
    private lateinit var context: Context
    private val workManager = mockk<WorkManager>(relaxed = true)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager

        // Use in-memory database for test isolation
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()

        // Inject the in-memory database so the callback uses it
        HabitDatabaseProvider.setInstanceForTesting(database)

        habitDao = database.habitDao()
        completionDao = database.completionDao()

        repository = HabitRepository(habitDao, completionDao, database.timeLogDao(), database)
        callback = CheckInActionCallback()
    }

    @After
    fun teardown() {
        unmockkStatic(WorkManager::class)
        HabitDatabaseProvider.clearInstanceForTesting()
        database.close()
    }

    // ==================== CHECK-04: Toggle action ====================

    @Test
    fun testOnAction_toggleAction_togglesCheckInAndRefreshes() = runTest {
        // Create a test CHECK_IN habit
        val habitId = habitDao.insert(HabitEntity(
            name = "Test Habit",
            description = "Test",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#4CAF50",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        ))

        // Create mock GlanceId and ActionParameters
        val glanceId = mockk<GlanceId>(relaxed = true)
        val parameters = actionParametersOf(
            ActionParameters.Key<Long>("habitId") to habitId,
            ActionParameters.Key<String>("action") to "toggle"
        )

        // Execute callback
        callback.onAction(context, glanceId, parameters)

        // Verify completion was created (toggle on empty = check in)
        // We check through the same database to verify the side effect
        val count = repository.getTodayCompletionCount(habitId)
        assertEquals("Should have 1 completion after toggle", 1, count)
    }

    // ==================== CHECK-04: Increment action ====================

    @Test
    fun testOnAction_incrementAction_incrementsCountAndRefreshes() = runTest {
        // Create a test COUNTING habit
        val habitId = habitDao.insert(HabitEntity(
            name = "Counting Habit",
            description = "Test",
            habitType = HabitType.COUNTING,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 5
        ))

        // Create mock GlanceId and ActionParameters
        val glanceId = mockk<GlanceId>(relaxed = true)
        val parameters = actionParametersOf(
            ActionParameters.Key<Long>("habitId") to habitId,
            ActionParameters.Key<String>("action") to "increment"
        )

        // Execute callback
        callback.onAction(context, glanceId, parameters)

        // Verify count was incremented
        val count = repository.getTodayCompletionCount(habitId)
        assertEquals("Should have count of 1 after increment", 1, count)
    }

    // ==================== CHECK-05: Decrement action ====================

    @Test
    fun testOnAction_decrementAction_decrementsCountAndRefreshes() = runTest {
        // Create a test COUNTING habit
        val habitId = habitDao.insert(HabitEntity(
            name = "Counting Habit",
            description = "Test",
            habitType = HabitType.COUNTING,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 5
        ))

        // First increment to have something to decrement
        repository.logCompletion(context, habitId, 1)

        // Create mock GlanceId and ActionParameters
        val glanceId = mockk<GlanceId>(relaxed = true)
        val parameters = actionParametersOf(
            ActionParameters.Key<Long>("habitId") to habitId,
            ActionParameters.Key<String>("action") to "decrement"
        )

        // Execute callback
        callback.onAction(context, glanceId, parameters)

        // Verify count was decremented (1 -> 0)
        val count = repository.getTodayCompletionCount(habitId)
        assertEquals("Should have count of 0 after decrement", 0, count)
    }

    // ==================== Edge case: Missing habitId ====================

    @Test
    fun testOnAction_missingHabitId_returnsEarlyWithoutError() = runTest {
        // Create mock GlanceId and ActionParameters WITHOUT habitId
        val glanceId = mockk<GlanceId>(relaxed = true)
        val parameters = actionParametersOf(
            ActionParameters.Key<String>("action") to "toggle"
        )

        // Execute callback - should not throw
        callback.onAction(context, glanceId, parameters)

        // This test passes if no exception is thrown
        assertTrue("Should complete without error", true)
    }

    // ==================== Edge case: Missing action defaults to toggle ====================

    @Test
    fun testOnAction_missingAction_defaultsToToggle() = runTest {
        // Create a test CHECK_IN habit
        val habitId = habitDao.insert(HabitEntity(
            name = "Test Habit",
            description = "Test",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#4CAF50",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        ))

        // Create mock GlanceId and ActionParameters WITHOUT action (should default to toggle)
        val glanceId = mockk<GlanceId>(relaxed = true)
        val parameters = actionParametersOf(
            ActionParameters.Key<Long>("habitId") to habitId
            // No action parameter - should default to "toggle"
        )

        // Execute callback
        callback.onAction(context, glanceId, parameters)

        // Verify completion was created (default toggle = check in)
        val count = repository.getTodayCompletionCount(habitId)
        assertEquals("Should have 1 completion after default toggle", 1, count)
    }

    @Test
    fun `real toggle commits data and queues exactly one cross-widget refresh`() = runTest {
        val habitId = habitDao.insert(HabitEntity(
            name = "Test Habit",
            description = "Test",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#4CAF50",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        ))

        callback.onAction(context, mockk<GlanceId>(relaxed = true), actionParametersOf(
            ActionParameters.Key<Long>("habitId") to habitId,
            ActionParameters.Key<String>("action") to "toggle"
        ))
        assertEquals(1, repository.getTodayCompletionCount(habitId))
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(WidgetRefreshScheduler.WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE, any<OneTimeWorkRequest>())
        }
    }
}
