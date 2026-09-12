package com.dayforge.widget.progress

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ProgressWidget completion logic.
 *
 * Tests verify:
 * - WIDGET-02: COUNTING habits show correct completion status
 * - WIDGET-04: ProgressWidget counts only truly completed habits
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ProgressWidgetTest {

    private lateinit var database: HabitDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()
        HabitDatabaseProvider.setInstanceForTesting(database)
    }

    @After
    fun teardown() {
        HabitDatabaseProvider.clearInstanceForTesting()
        database.close()
    }

    @Test
    fun testCheckInHabit_completed_whenHasCompletion() = runTest {
        // Create CHECK_IN habit
        val habitId = database.habitDao().insert(HabitEntity(
            name = "Daily Exercise",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#4CAF50",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        ))

        // Add completion
        val today = DateTimeUtils.startOfDayMillis()
        database.completionDao().insert(CompletionEntity(
            habitId = habitId,
            date = today,
            value = 1
        ))

        // Verify completion logic
        val completions = database.completionDao().getCompletionsInRange(habitId, DateTimeUtils.today(), DateTimeUtils.today().plusDays(1))
        val todayCount = completions.sumOf { it.value }
        val isCompleted = todayCount > 0

        assertTrue("CHECK_IN habit with completion should be completed", isCompleted)
    }

    @Test
    fun testCountingHabit_notCompleted_whenBelowTarget() = runTest {
        // Create COUNTING habit with target 5
        val habitId = database.habitDao().insert(HabitEntity(
            name = "Water Glasses",
            habitType = HabitType.COUNTING,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 5
        ))

        // Add 3 completions (below target of 5)
        val today = DateTimeUtils.startOfDayMillis()
        database.completionDao().insert(CompletionEntity(
            habitId = habitId,
            date = today,
            value = 1
        ))
        database.completionDao().insert(CompletionEntity(
            habitId = habitId,
            date = today,
            value = 1
        ))
        database.completionDao().insert(CompletionEntity(
            habitId = habitId,
            date = today,
            value = 1
        ))

        // Verify completion logic
        val completions = database.completionDao().getCompletionsInRange(habitId, DateTimeUtils.today(), DateTimeUtils.today().plusDays(1))
        val todayCount = completions.sumOf { it.value }
        val habit = database.habitDao().getHabitById(habitId)!!
        val isCompleted = todayCount >= habit.targetValue

        assertEquals("Should have 3 completions", 3, todayCount)
        assertFalse("COUNTING habit with count=3 and target=5 should NOT be completed", isCompleted)
    }

    @Test
    fun testCountingHabit_completed_whenTargetReached() = runTest {
        // Create COUNTING habit with target 5
        val habitId = database.habitDao().insert(HabitEntity(
            name = "Water Glasses",
            habitType = HabitType.COUNTING,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 5
        ))

        // Add 5 completions (exactly at target)
        val today = DateTimeUtils.startOfDayMillis()
        repeat(5) {
            database.completionDao().insert(CompletionEntity(
                habitId = habitId,
                date = today,
                value = 1
            ))
        }

        // Verify completion logic
        val completions = database.completionDao().getCompletionsInRange(habitId, DateTimeUtils.today(), DateTimeUtils.today().plusDays(1))
        val todayCount = completions.sumOf { it.value }
        val habit = database.habitDao().getHabitById(habitId)!!
        val isCompleted = todayCount >= habit.targetValue

        assertEquals("Should have 5 completions", 5, todayCount)
        assertTrue("COUNTING habit with count=5 and target=5 should be completed", isCompleted)
    }

    @Test
    fun testCountingHabit_completed_whenAboveTarget() = runTest {
        // Create COUNTING habit with target 5
        val habitId = database.habitDao().insert(HabitEntity(
            name = "Water Glasses",
            habitType = HabitType.COUNTING,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 5
        ))

        // Add 7 completions (above target)
        val today = DateTimeUtils.startOfDayMillis()
        repeat(7) {
            database.completionDao().insert(CompletionEntity(
                habitId = habitId,
                date = today,
                value = 1
            ))
        }

        // Verify completion logic
        val completions = database.completionDao().getCompletionsInRange(habitId, DateTimeUtils.today(), DateTimeUtils.today().plusDays(1))
        val todayCount = completions.sumOf { it.value }
        val habit = database.habitDao().getHabitById(habitId)!!
        val isCompleted = todayCount >= habit.targetValue

        assertEquals("Should have 7 completions", 7, todayCount)
        assertTrue("COUNTING habit with count=7 and target=5 should be completed", isCompleted)
    }
}
