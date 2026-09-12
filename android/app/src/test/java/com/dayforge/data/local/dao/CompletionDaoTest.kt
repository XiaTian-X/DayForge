package com.dayforge.data.local.dao

import androidx.room.Room
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CompletionDaoTest {

    private lateinit var completionDao: CompletionDao
    private lateinit var habitDao: HabitDao
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
        completionDao = database.completionDao()
        habitDao = database.habitDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertCompletion_returnsGeneratedIdGreaterThanZero() = runTest {
        val habitId = createTestHabit()
        val completion = createTestCompletion(habitId = habitId)

        val insertedId = completionDao.insert(completion)

        assertTrue("Inserted ID should be greater than 0", insertedId > 0)
    }

    @Test
    fun getCompletionsByHabit_emitsListContainingInsertedCompletion() = runTest {
        val habitId = createTestHabit()
        val completion = createTestCompletion(habitId = habitId)

        completionDao.insert(completion)

        completionDao.getCompletionsByHabit(habitId).test {
            val completions = awaitItem()
            assertEquals("Should have one completion", 1, completions.size)
            assertEquals("Completion habitId should match", habitId, completions[0].habitId)
        }
    }

    @Test
    fun getCompletionsByHabit_returnsCompletionsOrderedByDateDescending() = runTest {
        val habitId = createTestHabit()
        val olderCompletion = createTestCompletion(habitId = habitId, date = 1000L)
        val newerCompletion = createTestCompletion(habitId = habitId, date = 2000L)

        completionDao.insert(olderCompletion)
        completionDao.insert(newerCompletion)

        completionDao.getCompletionsByHabit(habitId).test {
            val completions = awaitItem()
            assertEquals("Should have two completions", 2, completions.size)
            assertEquals("First completion should be newer", 2000L, completions[0].date)
            assertEquals("Second completion should be older", 1000L, completions[1].date)
        }
    }

    @Test
    fun getCompletionsInRange_returnsCompletionsWithinDateRange() = runTest {
        val habitId = createTestHabit()
        val start = LocalDate.of(2026, 3, 8)
        fun day(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val beforeRange = createTestCompletion(habitId = habitId, date = day(start.minusDays(1)))
        val inRange1 = createTestCompletion(habitId = habitId, date = day(start))
        val inRange2 = createTestCompletion(habitId = habitId, date = day(start.plusDays(1)))
        val afterRange = createTestCompletion(habitId = habitId, date = day(start.plusDays(2)))

        completionDao.insert(beforeRange)
        completionDao.insert(inRange1)
        completionDao.insert(inRange2)
        completionDao.insert(afterRange)

        val completions = completionDao.getCompletionsInRange(
            habitId = habitId,
            start = start,
            end = start.plusDays(2)
        )

        assertEquals("Should have two completions in range", 2, completions.size)
        assertTrue("Should include inRange1", completions.any { it.uuid == inRange1.uuid })
        assertTrue("Should include inRange2", completions.any { it.uuid == inRange2.uuid })
    }

    @Test
    fun insertWithReplace_sameHabitIdAndDate_replacesExistingCompletion() = runTest {
        val habitId = createTestHabit()
        val completion1 = createTestCompletion(habitId = habitId, date = 1000L, value = 1)
        val insertedId = completionDao.insert(completion1)

        val completion2 = createTestCompletion(id = insertedId, habitId = habitId, date = 1000L, value = 5)
        completionDao.insert(completion2)

        val completions = completionDao.getCompletionsByHabit(habitId).test {
            val list = awaitItem()
            assertEquals("Should still have one completion", 1, list.size)
            assertEquals("Value should be replaced", 5, list[0].value)
        }
    }

    @Test
    fun deleteCompletion_removesFromDatabase() = runTest {
        val habitId = createTestHabit()
        val completion = createTestCompletion(habitId = habitId)
        val insertedId = completionDao.insert(completion)

        val fetchedCompletion = completionDao.getCompletionById(insertedId)
        assertNotNull("Completion should exist before delete", fetchedCompletion)

        completionDao.delete(fetchedCompletion!!)

        val deletedCompletion = completionDao.getCompletionById(insertedId)
        assertNull("Completion should be null after delete", deletedCompletion)
    }

    @Test
    fun getCompletionById_returnsInsertedCompletion() = runTest {
        val habitId = createTestHabit()
        val completion = createTestCompletion(habitId = habitId, value = 3)

        val insertedId = completionDao.insert(completion)
        val retrievedCompletion = completionDao.getCompletionById(insertedId)

        assertNotNull("Retrieved completion should not be null", retrievedCompletion)
        assertEquals("Completion ID should match", insertedId, retrievedCompletion?.id)
        assertEquals("Completion habitId should match", habitId, retrievedCompletion?.habitId)
        assertEquals("Completion value should match", 3, retrievedCompletion?.value)
    }

    @Test
    fun deleteHabit_cascadesToCompletions() = runTest {
        // Create a habit
        val habitId = createTestHabit()

        // Create multiple completions for this habit
        val completion1 = createTestCompletion(habitId = habitId, date = 1000L)
        val completion2 = createTestCompletion(habitId = habitId, date = 2000L)
        val completion3 = createTestCompletion(habitId = habitId, date = 3000L)

        completionDao.insert(completion1)
        completionDao.insert(completion2)
        completionDao.insert(completion3)

        // Verify completions exist
        completionDao.getCompletionsByHabit(habitId).test {
            val list = awaitItem()
            assertEquals("Should have 3 completions before delete", 3, list.size)
        }

        // Delete the habit
        val habit = habitDao.getHabitById(habitId)
        assertNotNull("Habit should exist", habit)
        habitDao.delete(habit!!)

        // Verify completions are cascade deleted
        val completionsAfter = completionDao.getCompletionsInRange(habitId, LocalDate.of(1960, 1, 1), LocalDate.of(2100, 1, 1))
        assertTrue("Completions should be cascade deleted", completionsAfter.isEmpty())
    }

    private suspend fun createTestHabit(): Long {
        val habit = HabitEntity(
            name = "Test Habit",
            description = "Test Description",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )
        return habitDao.insert(habit)
    }

    private fun createTestCompletion(
        id: Long = 0,
        habitId: Long,
        date: Long = System.currentTimeMillis(),
        value: Int = 1
    ) = com.dayforge.data.local.entity.CompletionEntity(
        id = id,
        habitId = habitId,
        date = date,
        value = value
    )

    // === Wave 0: TDD Tests for getDistinctDayCount (Wave 1 implementation) ===

    @Test
    fun testDistinctDayCount() = runTest {
        // Given: One completion exists for habit
        val habitId = createTestHabit()
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
        completionDao.insert(createTestCompletion(habitId = habitId, date = today, value = 1))

        // When: Query distinct day count
        val count = completionDao.getDistinctDayCount(habitId)

        // Then: Should return 1
        assertEquals(1, count)
    }

    @Test
    fun testDistinctDayCountMultipleSameDay() = runTest {
        // Given: Multiple completions on same day for same habit
        val habitId = createTestHabit()
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
        completionDao.insert(createTestCompletion(habitId = habitId, date = today, value = 1))
        completionDao.insert(createTestCompletion(habitId = habitId, date = today, value = 2))

        // When: Query distinct day count
        val count = completionDao.getDistinctDayCount(habitId)

        // Then: Should return 1 (same day counts as 1)
        assertEquals(1, count)
    }

    @Test
    fun testDistinctDayCountZero() = runTest {
        // Given: No completions for habit
        val habitId = createTestHabit()

        // When: Query distinct day count
        val count = completionDao.getDistinctDayCount(habitId)

        // Then: Should return 0
        assertEquals(0, count)
    }
}
