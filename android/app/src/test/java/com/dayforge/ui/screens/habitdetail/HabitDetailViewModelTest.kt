package com.dayforge.ui.screens.habitdetail

import androidx.room.Room
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.StreakStats
import com.dayforge.data.repository.HabitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class HabitDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setupDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardownDispatcher() {
        Dispatchers.resetMain()
    }

    private lateinit var viewModel: HabitDetailViewModel
    private lateinit var repository: HabitRepository
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: com.dayforge.data.local.dao.CompletionDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var metricDao: MetricDao
    private lateinit var metricLogDao: MetricLogDao
    private lateinit var habitMetricLinkDao: HabitMetricLinkDao
    private lateinit var database: HabitDatabase
    private lateinit var context: Application
    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()
        habitDao = database.habitDao()
        completionDao = database.completionDao()
        timeLogDao = database.timeLogDao()
        metricDao = database.metricDao()
        metricLogDao = database.metricLogDao()
        habitMetricLinkDao = database.habitMetricLinkDao()

        // Create test DataStore for PreferencesManager
        testDataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(context.cacheDir, "test_habit_detail_preferences.preferences_pb") }
        )
        preferencesManager = PreferencesManager(testDataStore)

        repository = HabitRepository(habitDao, completionDao, timeLogDao, database)
        viewModel = HabitDetailViewModel(context, repository, preferencesManager, timeLogDao, completionDao, habitDao, habitMetricLinkDao, metricDao, metricLogDao)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun loadHabit_emitsCorrectHabitData() = runBlocking {
        // Create a test habit
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "Test Description",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        viewModel.loadHabit(habitId)
        testDispatcher.scheduler.advanceUntilIdle()
        delay(100)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Habit ID should match", habitId, state.habitId)
        assertNotNull("Habit should not be null", state.habit)
        assertEquals("Habit name should match", "Test Habit", state.habit?.name)
        assertEquals("Habit description should match", "Test Description", state.habit?.description)
        assertFalse("Should not be loading", state.isLoading)
    }

    @Test
    fun logCompletion_updatesStateWithCompletion() = runBlocking {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        viewModel.loadHabit(habitId)
        testDispatcher.scheduler.advanceUntilIdle()
        delay(100)
        testDispatcher.scheduler.advanceUntilIdle()

        // Log completion
        viewModel.logCompletion(1)
        testDispatcher.scheduler.advanceUntilIdle()
        delay(100)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull("lastCompletionId should be set after logging", state.lastCompletionId)
        assertTrue("lastCompletionId should be greater than 0", state.lastCompletionId!! > 0)
    }

    @Test
    fun undoCompletion_clearsLastCompletionId() = runBlocking {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        viewModel.loadHabit(habitId)
        testDispatcher.scheduler.advanceUntilIdle()
        delay(100)
        testDispatcher.scheduler.advanceUntilIdle()

        // Log completion
        viewModel.logCompletion(1)
        testDispatcher.scheduler.advanceUntilIdle()
        delay(100)
        testDispatcher.scheduler.advanceUntilIdle()

        val loggedState = viewModel.uiState.value
        val completionId = loggedState.lastCompletionId
        assertNotNull("lastCompletionId should be set after logging", completionId)

        // Undo completion
        viewModel.undoCompletion()
        testDispatcher.scheduler.advanceUntilIdle()
        delay(100)
        testDispatcher.scheduler.advanceUntilIdle()

        val undoneState = viewModel.uiState.value
        assertNull("lastCompletionId should be null after undo", undoneState.lastCompletionId)
    }

    @Test
    fun getStreakStats_returnsCurrentStreakStats() = runBlocking {
        val habitId = repository.createHabit(
            name = "Test Habit",
            description = "",
            habitType = HabitType.CHECK_IN,
            iconResId = 1,
            colorHex = "#2196F3",
            schedule = HabitSchedule.Daily,
            targetValue = 1
        )

        // Create completions for 3 consecutive days
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

        viewModel.loadHabit(habitId)
        testDispatcher.scheduler.advanceUntilIdle()
        delay(100)
        testDispatcher.scheduler.advanceUntilIdle()

        // Get streak stats
        val stats = viewModel.getStreakStats()
        assertNotNull("Streak stats should not be null", stats)
        assertEquals("Current streak should be 3", 3, stats?.currentStreak)
        assertEquals("Best streak should be 3", 3, stats?.bestStreak)
    }

    @Test
    fun initialUiState_hasCorrectDefaultValues() {
        val initialState = viewModel.uiState.value

        assertEquals("Initial habitId should be 0", 0, initialState.habitId)
        assertNull("Initial habit should be null", initialState.habit)
        assertNull("Initial streakStats should be null", initialState.streakStats)
        assertTrue("Initial completions should be empty", initialState.completions.isEmpty())
        assertNull("Initial lastCompletionId should be null", initialState.lastCompletionId)
        assertTrue("Initial isLoading should be true", initialState.isLoading)
    }
}
