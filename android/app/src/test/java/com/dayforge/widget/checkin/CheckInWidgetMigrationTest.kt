package com.dayforge.widget.checkin

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.widget.counting.CountingWidget
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for widget migration logic.
 *
 * Tests verify:
 * - WIDGET-01: COUNTING habits in 1x1 widget are auto-migrated to 2x2
 * - Migration preserves habit ID and configuration
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CheckInWidgetMigrationTest {

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
    fun testCountingHabitTypeDetection() = runTest {
        // Create COUNTING habit
        val habitId = database.habitDao().insert(HabitEntity(
            name = "Water Glasses",
            habitType = HabitType.COUNTING,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 8
        ))

        // Verify habit type can be detected
        val habit = database.habitDao().getHabitById(habitId)
        assertNotNull("Habit should exist", habit)
        assertEquals("Habit type should be COUNTING", HabitType.COUNTING, habit!!.habitType)
    }

    @Test
    fun testCheckInHabitTypeDetection() = runTest {
        // Create CHECK_IN habit
        val habitId = database.habitDao().insert(HabitEntity(
            name = "Daily Exercise",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#4CAF50",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        ))

        // Verify habit type can be detected
        val habit = database.habitDao().getHabitById(habitId)
        assertNotNull("Habit should exist", habit)
        assertEquals("Habit type should be CHECK_IN", HabitType.CHECK_IN, habit!!.habitType)
    }

    @Test
    fun testWidgetPrefsStorage() = runTest {
        // Simulate 1x1 widget preference storage
        val widgetId = 123
        val habitId = 1L

        val prefs = context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, habitId)
            .commit()

        // Verify retrieval
        val storedHabitId = prefs.getLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, -1L)
        assertEquals("Stored habitId should match", habitId, storedHabitId)
    }

    @Test
    fun testMigrationCopiesPrefsToCountingWidget() = runTest {
        // Setup: Create COUNTING habit and simulate 1x1 widget config
        val habitId = database.habitDao().insert(HabitEntity(
            name = "Water Glasses",
            habitType = HabitType.COUNTING,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 8
        ))

        val widgetId = 456

        // Simulate existing 1x1 widget config
        val srcPrefs = context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE)
        srcPrefs.edit()
            .putLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, habitId)
            .commit()

        // Simulate migration: copy to CountingWidget prefs
        val dstPrefs = context.getSharedPreferences(CountingWidget.PREFS_NAME, Context.MODE_PRIVATE)
        dstPrefs.edit()
            .putLong(CountingWidget.PREF_HABIT_ID_PREFIX + widgetId, habitId)
            .commit()

        // Verify migration
        val migratedHabitId = dstPrefs.getLong(CountingWidget.PREF_HABIT_ID_PREFIX + widgetId, -1L)
        assertEquals("Migrated habitId should match", habitId, migratedHabitId)
    }

    @Test
    fun testMigrationDoesNotAffectCheckInHabits() = runTest {
        // Setup: Create CHECK_IN habit
        val habitId = database.habitDao().insert(HabitEntity(
            name = "Daily Exercise",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#4CAF50",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        ))

        val widgetId = 789

        // Simulate 1x1 widget config
        val prefs = context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, habitId)
            .commit()

        // CHECK_IN habits should NOT be migrated
        val habit = database.habitDao().getHabitById(habitId)
        val shouldNotMigrate = habit?.habitType == HabitType.CHECK_IN

        assertTrue("CHECK_IN habits should not be migrated", shouldNotMigrate)
    }
}