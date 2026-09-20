package com.dayforge.domain.service

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import com.dayforge.widget.WidgetRefreshScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for CheckInService.
 *
 * Tests cover:
 * - CHECK-01: Toggle check-in creates completion when empty
 * - CHECK-02: Toggle check-in undoes completion when already done
 * - CHECK-04: Increment count for counting habits
 * - CHECK-05: Decrement count with minimum of 0
 * - CHECK-06: isCompleted returns true when target reached
 * - CHECK-07: isCompleted returns false when below target
 * - CHECK-08: Continue adjusting count after reaching target
 */
@RunWith(AndroidJUnit4::class)
class CheckInServiceTest {
    @get:org.junit.Rule val storage = com.dayforge.data.local.PhysicalDatabaseRule()

    private lateinit var service: CheckInService
    private lateinit var repository: HabitRepository
    private lateinit var database: HabitDatabase
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: CompletionDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(WidgetRefreshScheduler)
        every { WidgetRefreshScheduler.request(context) } returns null
        database = storage.database
        habitDao = database.habitDao()
        completionDao = database.completionDao()
        timeLogDao = database.timeLogDao()
        repository = HabitRepository(habitDao, completionDao, timeLogDao, database)
        service = CheckInService(repository, completionDao, timeLogDao)
    }

    @After
    fun teardown() {
        unmockkObject(WidgetRefreshScheduler)
        database.close()
    }

    // ==================== CHECK-01: Toggle check-in creates completion ====================

    @Test
    fun testToggleCheckIn_completesHabit() = runBlocking {
        // Create a CHECK_IN habit
        val habitId = createTestHabit(HabitType.CHECK_IN, targetValue = 1)

        // Initial state: not completed
        val initialCount = repository.getTodayCompletionCount(habitId)
        assertEquals("Initial count should be 0", 0, initialCount)

        // Toggle check-in
        val result = service.toggleCheckIn(context, habitId)

        // Verify: completed (true)
        assertTrue("toggleCheckIn should return Success", result is CheckInResult.Success)
        val success = result as CheckInResult.Success
        assertTrue("completed should be true for new completion", success.completed)

        // Verify: count is now 1
        val afterCount = repository.getTodayCompletionCount(habitId)
        assertEquals("Count should be 1 after check-in", 1, afterCount)
    }

    // ==================== CHECK-02: Toggle check-in undoes completion ====================

    @Test
    fun testToggleCheckIn_undoesCompletion() = runBlocking {
        // Create a CHECK_IN habit
        val habitId = createTestHabit(HabitType.CHECK_IN, targetValue = 1)

        // First check-in
        val firstResult = service.toggleCheckIn(context, habitId)
        assertTrue("First check-in should return Success", firstResult is CheckInResult.Success)
        val firstSuccess = firstResult as CheckInResult.Success
        assertTrue("First check-in completed should be true", firstSuccess.completed)

        // Verify completed
        val afterFirstCount = repository.getTodayCompletionCount(habitId)
        assertEquals("Count should be 1 after first check-in", 1, afterFirstCount)

        // Toggle again to undo
        val secondResult = service.toggleCheckIn(context, habitId)

        // Verify: undone (false)
        assertTrue("Second toggle should return Success", secondResult is CheckInResult.Success)
        val secondSuccess = secondResult as CheckInResult.Success
        assertFalse("completed should be false when undoing", secondSuccess.completed)

        // Verify: count is now 0
        val afterUndoCount = repository.getTodayCompletionCount(habitId)
        assertEquals("Count should be 0 after undo", 0, afterUndoCount)
    }

    // ==================== CHECK-04: Increment count ====================

    @Test
    fun testIncrementCount_increasesByOne() = runBlocking {
        // Create a COUNTING habit with target of 5
        val habitId = createTestHabit(HabitType.COUNTING, targetValue = 5)

        // Initial state: count is 0
        val initialCount = repository.getTodayCompletionCount(habitId)
        assertEquals("Initial count should be 0", 0, initialCount)

        // Increment
        val result1 = service.incrementCount(context, habitId)
        assertTrue("First increment should return Success", result1 is CheckInResult.Success)
        val success1 = result1 as CheckInResult.Success
        assertTrue("First increment completed should be false (target 5)", !success1.completed)

        // Increment again
        val result2 = service.incrementCount(context, habitId)
        assertTrue("Second increment should return Success", result2 is CheckInResult.Success)
        val success2 = result2 as CheckInResult.Success
        assertTrue("Second increment completed should be false (target 5)", !success2.completed)

        // Verify total count
        val finalCount = repository.getTodayCompletionCount(habitId)
        assertEquals("Final count should be 2", 2, finalCount)
    }

    // ==================== CHECK-05: Decrement count (minimum 0) ====================

    @Test
    fun testDecrementCount_decreasesButNotBelowZero() = runBlocking {
        // Create a COUNTING habit
        val habitId = createTestHabit(HabitType.COUNTING, targetValue = 5)

        // Add some counts first
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)

        // Verify we have 3
        val beforeDecrement = repository.getTodayCompletionCount(habitId)
        assertEquals("Should have 3 after increments", 3, beforeDecrement)

        // Decrement
        val result1 = service.decrementCount(context, habitId)
        assertTrue("First decrement should return Success", result1 is CheckInResult.Success)
        val success1 = result1 as CheckInResult.Success
        assertTrue("First decrement completed should be false", !success1.completed)

        // Decrement again
        val result2 = service.decrementCount(context, habitId)
        assertTrue("Second decrement should return Success", result2 is CheckInResult.Success)

        // Decrement to 0
        val result3 = service.decrementCount(context, habitId)
        assertTrue("Third decrement should return Success", result3 is CheckInResult.Success)
        val success3 = result3 as CheckInResult.Success
        assertEquals("Third decrement progress should be 0", 0, success3.progress)
    }

    @Test
    fun testDecrementCount_atZeroReturnsZero() = runBlocking {
        // Create a COUNTING habit
        val habitId = createTestHabit(HabitType.COUNTING, targetValue = 5)

        // Initial state: count is 0
        val initialCount = repository.getTodayCompletionCount(habitId)
        assertEquals("Initial count should be 0", 0, initialCount)

        // Try to decrement when already at 0
        val result = service.decrementCount(context, habitId)

        // Should return Success with progress 0
        assertTrue("Decrement at 0 should return Success", result is CheckInResult.Success)
        val success = result as CheckInResult.Success
        assertEquals("Decrement at 0 should have progress 0", 0, success.progress)

        // Verify count is still 0
        val afterCount = repository.getTodayCompletionCount(habitId)
        assertEquals("Count should still be 0", 0, afterCount)
    }

    // ==================== CHECK-06: isCompleted returns true when target reached ====================

    @Test
    fun testIsCompleted_returnsTrueWhenTargetReached() = runBlocking {
        // Create a COUNTING habit with target of 3
        val habitId = createTestHabit(HabitType.COUNTING, targetValue = 3)

        // Add 3 completions
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)

        // Check completion status
        val isCompleted = service.isCompleted(habitId, targetValue = 3)

        assertTrue("Should be completed when count equals target", isCompleted)
    }

    @Test
    fun testIsCompleted_returnsTrueWhenExceedsTarget() = runBlocking {
        // Create a COUNTING habit with target of 3
        val habitId = createTestHabit(HabitType.COUNTING, targetValue = 3)

        // Add 4 completions (exceeds target)
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)

        // Check completion status
        val isCompleted = service.isCompleted(habitId, targetValue = 3)

        assertTrue("Should be completed when count exceeds target", isCompleted)
    }

    // ==================== CHECK-07: isCompleted returns false when below target ====================

    @Test
    fun testIsCompleted_returnsFalseWhenBelowTarget() = runBlocking {
        // Create a COUNTING habit with target of 5
        val habitId = createTestHabit(HabitType.COUNTING, targetValue = 5)

        // Add only 2 completions
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)

        // Check completion status
        val isCompleted = service.isCompleted(habitId, targetValue = 5)

        assertFalse("Should not be completed when count is below target", isCompleted)
    }

    // ==================== CHECK-08: Continue adjusting after target reached ====================

    @Test
    fun testIncrementCount_worksAfterTargetReached() = runBlocking {
        // Create a COUNTING habit with target of 2
        val habitId = createTestHabit(HabitType.COUNTING, targetValue = 2)

        // Reach the target
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)

        // Verify at target
        val atTarget = service.isCompleted(habitId, targetValue = 2)
        assertTrue("Should be completed at target", atTarget)

        // Continue incrementing (user wants to exceed target)
        val result = service.incrementCount(context, habitId)
        assertTrue("Should be able to increment past target", result is CheckInResult.Success)
        val success = result as CheckInResult.Success
        assertTrue("Should still be completed after exceeding target", success.completed)

        // Verify still completed
        val afterIncrement = service.isCompleted(habitId, targetValue = 2)
        assertTrue("Should still be completed after exceeding target", afterIncrement)
    }

    @Test
    fun testDecrementCount_worksAfterTargetReached() = runBlocking {
        // Create a COUNTING habit with target of 2
        val habitId = createTestHabit(HabitType.COUNTING, targetValue = 2)

        // Exceed the target (count = 4)
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)
        service.incrementCount(context, habitId)

        // Verify count is 4
        val beforeCount = repository.getTodayCompletionCount(habitId)
        assertEquals("Should have 4 completions", 4, beforeCount)

        // Decrement
        val result = service.decrementCount(context, habitId)
        assertTrue("Decrement should return Success", result is CheckInResult.Success)
        val success = result as CheckInResult.Success
        assertTrue("Should still be completed after decrement", success.completed)

        // Verify still completed
        val afterDecrement = service.isCompleted(habitId, targetValue = 2)
        assertTrue("Should still be completed after decrement", afterDecrement)
    }

    // ==================== Helper Methods ====================

    private suspend fun createTestHabit(
        habitType: HabitType,
        targetValue: Int = 1,
        targetCycles: Int? = null
    ): Long {
        return repository.createHabit(
            name = "Test Habit",
            description = "Test Description",
            habitType = habitType,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = targetValue,
            targetCycles = targetCycles
        )
    }

    // === Wave 0: TDD Tests for CheckInResult and goal detection (Wave 1-2 implementation) ===

    @Test
    fun testToggleCheckInReturnsResult() = runBlocking {
        // Given: A habit exists
        val habitId = createTestHabit(habitType = HabitType.CHECK_IN, targetCycles = 10)

        // When: Toggle check-in
        val result = service.toggleCheckIn(context, habitId)

        // Then: Should return CheckInResult.Success
        assertTrue(result is CheckInResult.Success)
        val success = result as CheckInResult.Success
        assertTrue(success.completed)
    }

    @Test
    fun testGoalReachedTrue() = runBlocking {
        // Given: A habit with targetCycles = 2, and 1 completion for yesterday
        // When we toggle check-in today, we get 2 distinct days → goalReached = true
        val habitId = createTestHabit(habitType = HabitType.CHECK_IN, targetCycles = 2)

        val yesterday = LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        completionDao.insert(CompletionEntity(habitId = habitId, date = yesterday, value = 1))

        // When: Toggle check-in for today (creates new completion)
        val result = service.toggleCheckIn(context, habitId)

        // Then: goalReached should be true (progress = 2 >= targetCycles = 2)
        val success = result as CheckInResult.Success
        assertTrue("completed should be true for new check-in", success.completed)
        assertEquals("progress should be 2", 2, success.progress)
        assertTrue("goalReached should be true when progress >= targetCycles", success.goalReached)
    }

    @Test
    fun testGoalReachedFalseNullTarget() = runBlocking {
        // Given: A habit with targetCycles = null (infinite tracking)
        val habitId = createTestHabit(habitType = HabitType.CHECK_IN, targetCycles = null)

        // When: Check in
        val result = service.toggleCheckIn(context, habitId)

        // Then: goalReached should be false (null target = no goal)
        val success = result as CheckInResult.Success
        assertFalse(success.goalReached)
    }

    @Test
    fun testGoalReachedAfterUndo() = runBlocking {
        // Given: A habit with targetCycles = 1, already at goal (1 day completed)
        val habitId = createTestHabit(habitType = HabitType.CHECK_IN, targetCycles = 1)

        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        completionDao.insert(CompletionEntity(habitId = habitId, date = today, value = 1))

        // When: Undo the check-in (toggle back)
        val result = service.toggleCheckIn(context, habitId)

        // Then: goalReached should be false (progress now 0)
        val success = result as CheckInResult.Success
        assertFalse("goalReached should be false after undo", success.goalReached)
        assertEquals("progress should be 0 after undo", 0, success.progress)
    }
}
