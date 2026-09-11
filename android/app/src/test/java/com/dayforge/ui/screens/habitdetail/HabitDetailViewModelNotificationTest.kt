package com.dayforge.ui.screens.habitdetail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
import java.io.File

/**
 * Tests for HabitDetailViewModel notification settings (NOTIFY-04).
 * Verifies per-habit notification toggle state and persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class HabitDetailViewModelNotificationTest {

    private lateinit var viewModel: HabitDetailViewModel
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: CompletionDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var metricDao: MetricDao
    private lateinit var metricLogDao: MetricLogDao
    private lateinit var habitMetricLinkDao: HabitMetricLinkDao
    private lateinit var database: HabitDatabase
    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var habitRepository: HabitRepository
    private lateinit var context: android.content.Context
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()

        // Create test DataStore
        testDataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(context.cacheDir, "test_habit_notification.preferences_pb") }
        )
        preferencesManager = PreferencesManager(testDataStore)

        // Create in-memory database
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

        habitRepository = HabitRepository(habitDao, completionDao, timeLogDao, database)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        database.close()
        File(context.cacheDir, "test_habit_notification.preferences_pb").delete()
    }

    /**
     * Test 1: notificationEnabled is true by default for habit with bestTime.
     * Per NOTIFY-04: Default notification setting is enabled.
     */
    @Test
    fun notificationEnabled_defaultTrueForHabitWithBestTime() = runTest {
        // Create habit with bestTime
        val habitId = habitDao.insert(
            HabitEntity(
                name = "Morning Exercise",
                habitType = HabitType.CHECK_IN,
                schedule = HabitSchedule.Daily,
                bestTime = 480, // 8:00 AM (480 minutes)
                iconResId = 1,
                colorHex = "#2196F3",
                targetValue = 1
            )
        )

        viewModel = HabitDetailViewModel(
            context = context,
            habitRepository = habitRepository,
            timeLogDao = timeLogDao,
            completionDao = completionDao,
            habitDao = habitDao,
            habitMetricLinkDao = habitMetricLinkDao,
            metricDao = metricDao,
            metricLogDao = metricLogDao,
            preferencesManager = preferencesManager
        )

        viewModel.loadHabit(habitId)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("notificationEnabled should be true by default for habit with bestTime", state.notificationEnabled)
    }

    /**
     * Test 2: notificationEnabled is true by default for habit without bestTime.
     * Per NOTIFY-04: Even habits without bestTime have default enabled state.
     */
    @Test
    fun notificationEnabled_defaultTrueForHabitWithoutBestTime() = runTest {
        // Create habit without bestTime (null)
        val habitId = habitDao.insert(
            HabitEntity(
                name = "Anytime Habit",
                habitType = HabitType.CHECK_IN,
                schedule = HabitSchedule.Daily,
                bestTime = null, // No bestTime
                iconResId = 1,
                colorHex = "#2196F3",
                targetValue = 1
            )
        )

        viewModel = HabitDetailViewModel(
            context = context,
            habitRepository = habitRepository,
            timeLogDao = timeLogDao,
            completionDao = completionDao,
            habitDao = habitDao,
            habitMetricLinkDao = habitMetricLinkDao,
            metricDao = metricDao,
            metricLogDao = metricLogDao,
            preferencesManager = preferencesManager
        )

        viewModel.loadHabit(habitId)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        // UI will only show toggle for habits with bestTime, but state still has default
        assertTrue("notificationEnabled should still be true by default", state.notificationEnabled)
    }

    /**
     * Test 3: toggleNotificationEnabled(false) disables notification and persists.
     * Per NOTIFY-04: User can disable notifications for specific habit.
     */
    @Test
    fun toggleNotificationEnabled_disablesAndPersists() = runBlocking {
        // Create habit with bestTime
        val habitId = habitDao.insert(
            HabitEntity(
                name = "Evening Reading",
                habitType = HabitType.CHECK_IN,
                schedule = HabitSchedule.Daily,
                bestTime = 1200, // 20:00 (1200 minutes)
                iconResId = 1,
                colorHex = "#2196F3",
                targetValue = 1
            )
        )

        viewModel = HabitDetailViewModel(
            context = context,
            habitRepository = habitRepository,
            timeLogDao = timeLogDao,
            completionDao = completionDao,
            habitDao = habitDao,
            habitMetricLinkDao = habitMetricLinkDao,
            metricDao = metricDao,
            metricLogDao = metricLogDao,
            preferencesManager = preferencesManager
        )

        viewModel.loadHabit(habitId)
        testDispatcher.scheduler.advanceUntilIdle()

        // Toggle to disable
        viewModel.toggleNotificationEnabled(habitId, false)
        val state = awaitNotificationState(false)
        assertFalse("notificationEnabled should be false after toggle", state.notificationEnabled)

        // Verify persisted in PreferencesManager
        val persisted = preferencesManager.getHabitNotificationEnabled(habitId).first()
        assertFalse("Notification setting should persist in PreferencesManager", persisted)
    }

    /**
     * Test 4: toggleNotificationEnabled(true) re-enables after disable.
     * Per NOTIFY-04: User can re-enable notifications for specific habit.
     */
    @Test
    fun toggleNotificationEnabled_reEnablesAfterDisable() = runBlocking {
        // Create habit with bestTime
        val habitId = habitDao.insert(
            HabitEntity(
                name = "Afternoon Walk",
                habitType = HabitType.CHECK_IN,
                schedule = HabitSchedule.Daily,
                bestTime = 900, // 15:00 (900 minutes)
                iconResId = 1,
                colorHex = "#2196F3",
                targetValue = 1
            )
        )

        viewModel = HabitDetailViewModel(
            context = context,
            habitRepository = habitRepository,
            timeLogDao = timeLogDao,
            completionDao = completionDao,
            habitDao = habitDao,
            habitMetricLinkDao = habitMetricLinkDao,
            metricDao = metricDao,
            metricLogDao = metricLogDao,
            preferencesManager = preferencesManager
        )

        viewModel.loadHabit(habitId)
        testDispatcher.scheduler.advanceUntilIdle()

        // First disable
        viewModel.toggleNotificationEnabled(habitId, false)
        assertFalse(
            "Should be disabled",
            awaitNotificationState(false).notificationEnabled
        )

        // Then re-enable
        viewModel.toggleNotificationEnabled(habitId, true)
        assertTrue(
            "notificationEnabled should be true after re-enable",
            awaitNotificationState(true).notificationEnabled
        )

        // Verify persisted
        val persisted = preferencesManager.getHabitNotificationEnabled(habitId).first()
        assertTrue("Notification setting should persist as enabled", persisted)
    }

    private suspend fun awaitNotificationState(enabled: Boolean): HabitDetailUiState {
        repeat(200) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            if (state.notificationEnabled == enabled) return state
            kotlinx.coroutines.delay(10)
        }
        error("Timed out waiting for notificationEnabled=$enabled")
    }
}
