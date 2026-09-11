package com.dayforge.ui.screens.edithabit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import app.cash.turbine.test
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class EditHabitViewModelScheduleDaysTest {

    private lateinit var viewModel: EditHabitViewModel
    private lateinit var repository: HabitRepository
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: com.dayforge.data.local.dao.CompletionDao
    private lateinit var metricDao: MetricDao
    private lateinit var habitMetricLinkDao: HabitMetricLinkDao
    private lateinit var database: HabitDatabase
    private lateinit var context: Context
    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var preferencesManager: PreferencesManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).allowMainThreadQueries().build()
        habitDao = database.habitDao()
        completionDao = database.completionDao()
        metricDao = database.metricDao()
        habitMetricLinkDao = database.habitMetricLinkDao()

        // Create test DataStore for PreferencesManager
        testDataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(context.cacheDir, "test_edit_habit_preferences.preferences_pb") }
        )
        preferencesManager = PreferencesManager(testDataStore)

        repository = HabitRepository(habitDao, completionDao, database.timeLogDao(), database)
        viewModel = EditHabitViewModel(repository, habitDao, database.timeLogDao(), completionDao, metricDao, habitMetricLinkDao, preferencesManager, context)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        database.close()
    }

    // Test: getScheduleDays(Daily) returns 1
    @Test
    fun getScheduleDays_daily_returns1() {
        val result = viewModel.getScheduleDays(HabitSchedule.Daily)
        assertEquals("Daily schedule should return 1 day", 1, result)
    }

    // Test: getScheduleDays(Weekly(any)) returns 7
    @Test
    fun getScheduleDays_weekly_returns7() {
        val weeklyWithSomeDays = HabitSchedule.Weekly(listOf(1, 3, 5))
        val result = viewModel.getScheduleDays(weeklyWithSomeDays)
        assertEquals("Weekly schedule should return 7 days", 7, result)
    }

    @Test
    fun getScheduleDays_weeklyEmpty_returns7() {
        val weeklyEmpty = HabitSchedule.Weekly(emptyList())
        val result = viewModel.getScheduleDays(weeklyEmpty)
        assertEquals("Weekly schedule with empty days should return 7 days", 7, result)
    }

    // Test: getScheduleDays(Monthly(any)) returns 30
    @Test
    fun getScheduleDays_monthly_returns30() {
        val monthly = HabitSchedule.Monthly(15)
        val result = viewModel.getScheduleDays(monthly)
        assertEquals("Monthly schedule should return 30 days", 30, result)
    }

    @Test
    fun getScheduleDays_monthlyDay1_returns30() {
        val monthly = HabitSchedule.Monthly(1)
        val result = viewModel.getScheduleDays(monthly)
        assertEquals("Monthly schedule on day 1 should return 30 days", 30, result)
    }

    // Test: getScheduleDays(Custom(N)) returns N
    @Test
    fun getScheduleDays_custom14_returns14() {
        val custom = HabitSchedule.Custom(14)
        val result = viewModel.getScheduleDays(custom)
        assertEquals("Custom(14) schedule should return 14 days", 14, result)
    }

    @Test
    fun getScheduleDays_custom7_returns7() {
        val custom = HabitSchedule.Custom(7)
        val result = viewModel.getScheduleDays(custom)
        assertEquals("Custom(7) schedule should return 7 days", 7, result)
    }

    @Test
    fun getScheduleDays_custom1_returns1() {
        val custom = HabitSchedule.Custom(1)
        val result = viewModel.getScheduleDays(custom)
        assertEquals("Custom(1) schedule should return 1 day", 1, result)
    }

    // Test: originalScheduleDays is populated correctly when habit loads
    @Test
    fun loadHabit_dailyHabit_setsOriginalScheduleDaysTo1() = runTest {
        // Create a Daily habit
        val habit = HabitEntity(
            id = 0,
            uuid = UUID.randomUUID().toString(),
            name = "Daily Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 0,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1,
            isCountdown = false,
            parentHabitId = null,
            targetCycles = null,
            failMode = com.dayforge.data.model.FailMode.STRICT
        )
        val habitId = habitDao.insert(habit)

        viewModel.uiState.test {
            skipItems(1) // Skip initial state

            viewModel.loadHabit(habitId)

            // Wait for loading to complete
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }

            assertEquals("originalScheduleDays should be 1 for Daily habit", 1, state.originalScheduleDays)
        }
    }

    @Test
    fun loadHabit_weeklyHabit_setsOriginalScheduleDaysTo7() = runTest {
        val habit = HabitEntity(
            id = 0,
            uuid = UUID.randomUUID().toString(),
            name = "Weekly Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 0,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Weekly(listOf(1, 2, 3)),
            targetValue = 1,
            isCountdown = false,
            parentHabitId = null,
            targetCycles = null,
            failMode = com.dayforge.data.model.FailMode.STRICT
        )
        val habitId = habitDao.insert(habit)

        viewModel.uiState.test {
            skipItems(1) // Skip initial state

            viewModel.loadHabit(habitId)

            // Wait for loading to complete
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }

            assertEquals("originalScheduleDays should be 7 for Weekly habit", 7, state.originalScheduleDays)
        }
    }

    @Test
    fun loadHabit_monthlyHabit_setsOriginalScheduleDaysTo30() = runTest {
        val habit = HabitEntity(
            id = 0,
            uuid = UUID.randomUUID().toString(),
            name = "Monthly Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 0,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Monthly(15),
            targetValue = 1,
            isCountdown = false,
            parentHabitId = null,
            targetCycles = null,
            failMode = com.dayforge.data.model.FailMode.STRICT
        )
        val habitId = habitDao.insert(habit)

        viewModel.uiState.test {
            skipItems(1) // Skip initial state

            viewModel.loadHabit(habitId)

            // Wait for loading to complete
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }

            assertEquals("originalScheduleDays should be 30 for Monthly habit", 30, state.originalScheduleDays)
        }
    }

    @Test
    fun loadHabit_customHabit_setsOriginalScheduleDaysToCustomValue() = runTest {
        val habit = HabitEntity(
            id = 0,
            uuid = UUID.randomUUID().toString(),
            name = "Custom Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 0,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Custom(14),
            targetValue = 1,
            isCountdown = false,
            parentHabitId = null,
            targetCycles = null,
            failMode = com.dayforge.data.model.FailMode.STRICT
        )
        val habitId = habitDao.insert(habit)

        viewModel.uiState.test {
            skipItems(1) // Skip initial state

            viewModel.loadHabit(habitId)

            // Wait for loading to complete
            var state = awaitItem()
            while (state.isLoading) {
                state = awaitItem()
            }

            assertEquals("originalScheduleDays should be 14 for Custom(14) habit", 14, state.originalScheduleDays)
        }
    }
}
