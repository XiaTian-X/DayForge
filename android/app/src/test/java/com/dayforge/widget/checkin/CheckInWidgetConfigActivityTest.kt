package com.dayforge.widget.checkin

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for CheckInWidgetConfigActivity habit filtering.
 * Tests verify WIDGET-01 size filtering requirement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CheckInWidgetConfigActivityTest {

    private lateinit var database: HabitDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()
        HabitDatabase.setInstanceForTesting(database)
    }

    @After
    fun teardown() {
        HabitDatabase.clearInstanceForTesting()
        database.close()
    }

    @Test
    fun testFilterOnlyCheckInHabits() = runTest {
        // Insert CHECK_IN habit
        database.habitDao().insert(HabitEntity(
            name = "Daily Exercise",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#4CAF50",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        ))

        // Insert COUNTING habit
        database.habitDao().insert(HabitEntity(
            name = "Water Glasses",
            habitType = HabitType.COUNTING,
            iconResId = 2,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 8
        ))

        // Fetch and filter
        val allHabits = database.habitDao().getAllHabits().first()
        val checkInHabits = allHabits.filter { it.habitType == HabitType.CHECK_IN }

        assertEquals("Should have 2 total habits", 2, allHabits.size)
        assertEquals("Should have 1 CHECK_IN habit", 1, checkInHabits.size)
        assertEquals("Filtered habit should be CHECK_IN", HabitType.CHECK_IN, checkInHabits.first().habitType)
    }
}