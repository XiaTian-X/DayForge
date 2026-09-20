package com.dayforge.widget.checkin

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

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
 * Production disk Room is shared with the callback; assertions inspect committed side effects.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CheckInActionCallbackTest {
    @get:org.junit.Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:org.junit.Rule(order = 1) val storage = com.dayforge.data.local.PhysicalDatabaseRule()

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

        database = storage.database
        hilt.inject()


        habitDao = database.habitDao()
        completionDao = database.completionDao()

        repository = HabitRepository(habitDao, completionDao, database.timeLogDao(), database)
        callback = CheckInActionCallback()
    }

    @After
    fun teardown() {
        unmockkStatic(WorkManager::class)
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

        assertEquals(0, completionDao.countAll())
        assertEquals(0, database.syncOutboxDao().count())
        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
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
    fun real_toggle_commits_data_and_queues_exactly_one_cross_widget_refresh() = runTest {
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
        assertEquals(2, database.syncOutboxDao().count())
        val reopened = storage.reopen()
        assertEquals(1, reopened.completionDao().countAll())
        assertEquals(2, reopened.syncOutboxDao().count())
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(WidgetRefreshScheduler.WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE, any<OneTimeWorkRequest>())
        }
    }
}
