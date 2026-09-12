package com.dayforge.ui.screens.habitdetail

import androidx.room.Room
import androidx.lifecycle.ViewModelStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(ExperimentalCoroutinesApi::class)
class HabitDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val viewModelStore = ViewModelStore()
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStoreFile: File

    @Before
    fun setupDispatcher() {
        Dispatchers.setMain(testDispatcher)
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
        dataStoreFile = File(context.cacheDir, "habit_detail_${java.util.UUID.randomUUID()}.preferences_pb")
        testDataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { dataStoreFile })
        preferencesManager = PreferencesManager(testDataStore)

        repository = HabitRepository(habitDao, completionDao, timeLogDao, database)
        viewModel = HabitDetailViewModel(context, repository, preferencesManager, timeLogDao, completionDao, habitDao, habitMetricLinkDao, metricDao, metricLogDao)
        viewModelStore.put("detail", viewModel)
    }

    @After
    fun teardown() {
        viewModelStore.clear()
        testDispatcher.scheduler.runCurrent()
        runBlocking { dataStoreScope.coroutineContext.job.cancelAndJoin() }
        database.close()
        dataStoreFile.delete()
        Dispatchers.resetMain()
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
        awaitState { !it.isLoading && it.habit?.id == habitId }

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
        awaitState { !it.isLoading && it.habit?.id == habitId }

        // Log completion
        viewModel.logCompletion(1)
        awaitState { it.lastCompletionId != null }

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
        awaitState { !it.isLoading && it.habit?.id == habitId }

        // Log completion
        viewModel.logCompletion(1)
        awaitState { it.lastCompletionId != null }

        val loggedState = viewModel.uiState.value
        val completionId = loggedState.lastCompletionId
        assertNotNull("lastCompletionId should be set after logging", completionId)

        // Undo completion
        viewModel.undoCompletion()
        awaitState { it.lastCompletionId == null && it.completions.isEmpty() }

        val undoneState = viewModel.uiState.value
        assertNull("lastCompletionId should be null after undo", undoneState.lastCompletionId)
        assertNull("Undo must also delete the persisted record", completionDao.getCompletionById(completionId!!))
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
        awaitState { !it.isLoading && it.completions.size == 3 }

        // Get streak stats
        val stats = viewModel.getStreakStats()
        assertNotNull("Streak stats should not be null", stats)
        assertEquals("Current streak should be 3", 3, stats?.currentStreak)
        assertEquals("Best streak should be 3", 3, stats?.bestStreak)
    }

    private suspend fun awaitState(predicate: (HabitDetailUiState) -> Boolean) = withTimeout(10_000) {
        while (true) {
            testDispatcher.scheduler.runCurrent()
            if (predicate(viewModel.uiState.value)) break
            // Room/DataStore use real I/O threads; wait for observable completion, not a fixed guess.
            delay(10)
        }
    }

    @Test
    fun switchingHabitCancelsPreviousSubscription() = runBlocking {
        val firstId = 1L
        val secondId = 2L
        val first = MutableStateFlow<HabitEntity?>(HabitEntity(id = firstId, name = "First", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#2196F3", schedule = HabitSchedule.Daily))
        val second = MutableStateFlow(first.value!!.copy(id = secondId, name = "Second"))
        val observingRepository = mockk<HabitRepository>()
        every { observingRepository.getHabit(firstId) } returns first
        every { observingRepository.getHabit(secondId) } returns second
        every { observingRepository.getAllCompletions() } returns MutableStateFlow(emptyList())
        viewModel = HabitDetailViewModel(context, observingRepository, preferencesManager, timeLogDao, completionDao,
            habitDao, habitMetricLinkDao, metricDao, metricLogDao)
        viewModelStore.put("detail", viewModel)
        viewModel.loadHabit(firstId)
        awaitState { !it.isLoading && it.habit?.id == firstId }
        assertEquals(1, first.subscriptionCount.value)
        viewModel.loadHabit(secondId)
        awaitState { !it.isLoading && it.habit?.id == secondId }
        assertEquals(0, first.subscriptionCount.value)
        assertEquals(1, second.subscriptionCount.value)
        first.value = first.value!!.copy(name = "Stale update")
        testDispatcher.scheduler.runCurrent()
        assertEquals(secondId, viewModel.uiState.value.habitId)
    }

    @Test
    fun queuedCompletionKeepsOriginalHabitAndDoesNotInstallUndoOnNewSelection() = runBlocking {
        val firstId = repository.createHabit(name = "First", description = "", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#2196F3", schedule = HabitSchedule.Daily)
        val secondId = repository.createHabit(name = "Second", description = "", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#2196F3", schedule = HabitSchedule.Daily)
        viewModel.loadHabit(firstId)
        awaitState { !it.isLoading && it.habit?.id == firstId }
        viewModel.logCompletion()
        // The action has been queued but has not started on StandardTestDispatcher.
        viewModel.loadHabit(secondId)
        awaitState { !it.isLoading && it.habit?.id == secondId }
        withTimeout(10_000) {
            while (completionDao.getAllCompletions().first().none { it.habitId == firstId }) {
                testDispatcher.scheduler.runCurrent()
                delay(10)
            }
        }
        testDispatcher.scheduler.runCurrent()
        assertTrue(completionDao.getAllCompletions().first().none { it.habitId == secondId })
        assertNull(viewModel.uiState.value.lastCompletionId)
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
