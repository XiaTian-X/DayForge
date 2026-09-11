package com.dayforge.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.StreakCalculator
import com.dayforge.util.DateTimeUtils
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class HabitRepositoryTest {

    private lateinit var repository: HabitRepository
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: CompletionDao
    private lateinit var database: HabitDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Use in-memory database for test isolation
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()
        habitDao = database.habitDao()
        completionDao = database.completionDao()
        repository = HabitRepository(habitDao, completionDao, database.timeLogDao(), database)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun createHabit_insertsViaDaoAndReturnsId() = runTest {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "Test Description",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        assertTrue("Created habit ID should be greater than 0", habitId > 0)

        val retrievedHabit = habitDao.getHabitById(habitId)
        assertNotNull("Habit should be retrievable from DAO", retrievedHabit)
        assertEquals("Habit name should match", "Test Habit", retrievedHabit?.name)
    }

    @Test
    fun allHabitsFlow_emitsEmptyListInitially() = runTest {
        repository.allHabits.test {
            val initialHabits = awaitItem()
            assertTrue("Initial habits list should be empty", initialHabits.isEmpty())
        }
    }

    @Test
    fun allHabitsFlow_emitsUpdatedListAfterInsert() = runTest {
        repository.allHabits.test {
            // Skip initial empty emission
            skipItems(1)

            // Insert a habit
            repository.createHabit(
                name = "Test Habit",
                description = "",
                habitType = HabitType.CHECK_IN,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )

            // Wait for updated emission
            val updatedHabits = awaitItem()

            assertEquals("Should have one habit after insert", 1, updatedHabits.size)
            assertEquals("Habit name should match", "Test Habit", updatedHabits[0].name)
        }
    }

    @Test
    fun getHabit_returnsCorrectHabitById() = runTest {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "Test Description",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        repository.getHabit(habitId).test {
            val retrievedHabit = awaitItem()

            assertNotNull("Retrieved habit should not be null", retrievedHabit)
            assertEquals("Habit ID should match", habitId, retrievedHabit?.id)
            assertEquals("Habit name should match", "Test Habit", retrievedHabit?.name)
            assertEquals("Habit description should match", "Test Description", retrievedHabit?.description)
        }
    }

    @Test
    fun updateHabit_callsDaoUpdate() = runTest {
        val habitId = repository.createHabit(
            name = "Original Name",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        val originalHabit = habitDao.getHabitById(habitId)
        assertNotNull("Original habit should exist", originalHabit)

        repository.updateHabit(originalHabit!!.copy(name = "Updated Name"))

        val updatedHabit = habitDao.getHabitById(habitId)
        assertEquals("Habit name should be updated", "Updated Name", updatedHabit?.name)
        assertTrue("Updated timestamp should be later",
            (updatedHabit?.updatedAt ?: 0) > (originalHabit.updatedAt))
    }

    @Test
    fun deleteHabit_callsDaoDelete() = runTest {
        val habitId = repository.createHabit(
            name = "To Delete",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        val habit = habitDao.getHabitById(habitId)
        assertNotNull("Habit should exist before deletion", habit)

        repository.deleteHabit(habit!!)

        val deletedHabit = habitDao.getHabitById(habitId)
        assertNull("Habit should be null after deletion", deletedHabit)
    }

    @Test
    fun deleteHabit_isOfflineSafeAndDoesNotRequireServerState() = runTest {
        val habitId = repository.createHabit(
            name = "Synced Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        val habit = habitDao.getHabitById(habitId)!!

        repository.deleteHabit(habit)

        assertNull("Habit should be deleted locally", habitDao.getHabitById(habitId))
    }

    @Test
    fun repositoryIsSingleSourceOfTruth_allOperationsGoThroughIt() = runTest {
        // Verify repository provides the only way to access data
        val habitId = repository.createHabit(
            name = "Test",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        // All reads should go through repository
        repository.getHabit(habitId).test {
            val habit = awaitItem()
            assertNotNull("Repository should return habit", habit)
        }

        // Repository wraps DAO operations
        repository.allHabits.test {
            val habits = awaitItem()
            assertEquals("Repository should return all habits", 1, habits.size)
        }
    }

    // ==================== NEW COMPLETION METHODS TESTS ====================

    @Test
    fun logCompletion_insertsCompletionWithCorrectDate() = runTest {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        val completionId = repository.logCompletion(context, habitId, 1)

        assertTrue("Completion ID should be greater than 0", completionId > 0)

        val completion = completionDao.getCompletionById(completionId)
        assertNotNull("Completion should exist", completion)
        assertEquals("Habit ID should match", habitId, completion?.habitId)
        assertEquals("Value should match", 1, completion?.value)

        // Verify date is normalized to start of day (local timezone)
        val calendar = Calendar.getInstance()  // Uses default (local) timezone
        calendar.timeInMillis = completion!!.date
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        assertEquals("Date should be normalized to midnight local timezone", calendar.timeInMillis, completion.date)
    }

    @Test
    fun undoCompletion_deletesSpecificCompletion() = runTest {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        val completionId = repository.logCompletion(context, habitId, 1)

        // Verify completion exists
        val completionBefore = completionDao.getCompletionById(completionId)
        assertNotNull("Completion should exist before undo", completionBefore)

        // Undo the completion
        repository.undoCompletion(context, completionId)

        // Verify completion is deleted
        val completionAfter = completionDao.getCompletionById(completionId)
        assertNull("Completion should be deleted after undo", completionAfter)
    }

    @Test
    fun getTodayCompletionCount_returnsCorrectCount() = runTest {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        // Log multiple completions for today
        repository.logCompletion(context, habitId, 1)
        repository.logCompletion(context, habitId, 2)
        repository.logCompletion(context, habitId, 3)

        val count = repository.getTodayCompletionCount(habitId)

        assertEquals("Should return sum of today's completion values", 6, count)
    }

    @Test
    fun getTodayCompletionCount_returnsZeroForNoCompletions() = runTest {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        val count = repository.getTodayCompletionCount(habitId)

        assertEquals("Should return 0 for no completions", 0, count)
    }

    @Test
    fun getTodayCompletionCount_onlyCountsToday() = runTest {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        // Log completion for today
        repository.logCompletion(context, habitId, 1)

        // Create a completion for yesterday (manually)
        val calendar = Calendar.getInstance()  // Use local timezone
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val yesterdayCompletion = CompletionEntity(
            habitId = habitId,
            date = calendar.timeInMillis,
            value = 5
        )
        completionDao.insert(yesterdayCompletion)

        val count = repository.getTodayCompletionCount(habitId)

        assertEquals("Should only count today's completions", 1, count)
    }

    @Test
    fun getStreakStats_emitsCurrentAndBestStreak() = runTest {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        // Create completions for 3 consecutive days including today
        val calendar = Calendar.getInstance()  // Use local timezone
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        // Today
        completionDao.insert(CompletionEntity(habitId = habitId, date = calendar.timeInMillis, value = 1))

        // Yesterday
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        completionDao.insert(CompletionEntity(habitId = habitId, date = calendar.timeInMillis, value = 1))

        // 2 days ago
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        completionDao.insert(CompletionEntity(habitId = habitId, date = calendar.timeInMillis, value = 1))

        repository.getStreakStats(habitId).test {
            val stats = awaitItem()
            assertEquals("Current streak should be 3", 3, stats.currentStreak)
            assertEquals("Best streak should be 3", 3, stats.bestStreak)
        }
    }

    @Test
    fun getStreakStats_handlesBrokenStreak() = runTest {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        // Create completions with a gap
        val calendar = Calendar.getInstance()  // Use local timezone
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        // Today (current streak of 3)
        completionDao.insert(CompletionEntity(habitId = habitId, date = calendar.timeInMillis, value = 1))

        // Yesterday
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        completionDao.insert(CompletionEntity(habitId = habitId, date = calendar.timeInMillis, value = 1))

        // 2 days ago
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        completionDao.insert(CompletionEntity(habitId = habitId, date = calendar.timeInMillis, value = 1))

        // Gap of 1 day (3 days ago - no completion)

        // 4 days ago (best streak of 2)
        calendar.add(Calendar.DAY_OF_YEAR, -2)
        completionDao.insert(CompletionEntity(habitId = habitId, date = calendar.timeInMillis, value = 1))

        // 5 days ago
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        completionDao.insert(CompletionEntity(habitId = habitId, date = calendar.timeInMillis, value = 1))

        repository.getStreakStats(habitId).test {
            val stats = awaitItem()
            assertEquals("Current streak should be 3", 3, stats.currentStreak)
            assertEquals("Best streak should be 3", 3, stats.bestStreak)
        }
    }

    @Test
    fun getStreakStats_returnsZeroForNoCompletions() = runTest {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        repository.getStreakStats(habitId).test {
            val stats = awaitItem()
            assertEquals("Current streak should be 0", 0, stats.currentStreak)
            assertEquals("Best streak should be 0", 0, stats.bestStreak)
        }
    }

    // ==================== ISCOUNTDOWN PARAMETER TESTS ====================

    @Test
    fun createHabit_defaultIsCountdownFalse() = runTest {
        val habitId = repository.createHabit(
            name = "Countup Habit",
            description = "",
            habitType = HabitType.TIMER,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 30
        )

        val habit = habitDao.getHabitById(habitId)
        assertNotNull("Habit should exist", habit)
        assertEquals("isCountdown should default to false", false, habit?.isCountdown)
    }

    @Test
    fun createHabit_withIsCountdownTrue_savesCorrectly() = runTest {
        val habitId = repository.createHabit(
            name = "Countdown Habit",
            description = "Countdown timer",
            habitType = HabitType.TIMER,
            iconResId = 1,
            colorHex = "#4CAF50",
            schedule = HabitSchedule.Daily,
            targetValue = 25,
            isCountdown = true
        )

        val habit = habitDao.getHabitById(habitId)
        assertNotNull("Habit should exist", habit)
        assertEquals("isCountdown should be true", true, habit?.isCountdown)
        assertEquals("Habit name should match", "Countdown Habit", habit?.name)
        assertEquals("Target value should match", 25, habit?.targetValue)
    }

    @Test
    fun createHabit_withIsCountdownFalse_savesCorrectly() = runTest {
        val habitId = repository.createHabit(
            name = "Explicit Countup Habit",
            description = "",
            habitType = HabitType.TIMER,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 60,
            isCountdown = false
        )

        val habit = habitDao.getHabitById(habitId)
        assertNotNull("Habit should exist", habit)
        assertEquals("isCountdown should be false", false, habit?.isCountdown)
    }
}
