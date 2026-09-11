package com.dayforge.ui.screens.dashboard

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.model.FilterMode
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.MetricOverviewProvider
import com.dayforge.domain.service.StructuralEditGuard
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.util.DateTimeUtils
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DashboardViewModelTest {

    private lateinit var viewModel: DashboardViewModel
    private lateinit var repository: HabitRepository
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: CompletionDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var metricDao: MetricDao
    private lateinit var metricLogDao: MetricLogDao
    private lateinit var habitMetricLinkDao: HabitMetricLinkDao
    private lateinit var database: HabitDatabase
    private lateinit var context: Context
    private lateinit var checkInService: CheckInService
    private lateinit var mockPreferencesManager: PreferencesManager
    private lateinit var filterModeFlow: MutableStateFlow<String>
    private lateinit var pendingMetricHabitsFlow: MutableStateFlow<Set<Long>>
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        // Use in-memory database for test isolation
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
        repository = HabitRepository(habitDao, completionDao, timeLogDao, database)
        filterModeFlow = MutableStateFlow(FilterMode.ALL.value)
        pendingMetricHabitsFlow = MutableStateFlow(emptySet())
        // Mock PreferencesManager - must mock dateChangeTrigger for combine to work
        mockPreferencesManager = mockk(relaxed = true) {
            every { hasShownBatteryGuidance } returns flowOf(false)
            every { dateChangeTrigger } returns flowOf(System.currentTimeMillis() / (24 * 60 * 60 * 1000L))
            every { filterMode } returns filterModeFlow
            every { pendingMetricHabits } returns pendingMetricHabitsFlow
            every { getNeverAskAgain(any()) } returns flowOf(false)
        }
        // Create a real CheckInService that uses the repository
        checkInService = CheckInService(repository, completionDao, timeLogDao)
        // Create HabitStatusCalculator
        val failureChecker = FailureChecker(completionDao, timeLogDao)
        val habitStatusCalculator = HabitStatusCalculator(failureChecker, completionDao, timeLogDao)
        val metricRepository = MetricRepository(
            database,
            metricDao,
            metricLogDao,
            habitDao,
            habitMetricLinkDao,
            mockk<StructuralEditGuard>(relaxed = true)
        )
        viewModel = DashboardViewModel(
            context,
            repository,
            checkInService,
            DashboardHabitListBuilder(habitStatusCalculator),
            timeLogDao,
            habitDao,
            mockPreferencesManager,
            completionDao,
            metricRepository,
            LinkedMetricCoordinator(context, mockPreferencesManager, metricRepository),
            MetricOverviewProvider(metricRepository)
        )
    }

    @After
    fun teardown() = runBlocking {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::database.isInitialized) database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun initialHabits_isEmptyList() = runTest {
        val initialHabits = viewModel.habitsWithStats.value

        assertTrue("Initial habits should be empty list", initialHabits.isEmpty())
    }

    @Test
    fun whenRepositoryEmitsHabits_viewModelHabitsEmitsUpdatedList() = runTest {
        viewModel.habitsWithStats.test {
            // Initial emission should be empty list
            val initialHabits = awaitItem()
            assertTrue("Initial habits should be empty", initialHabits.isEmpty())

            // Insert a habit via repository
            repository.createHabit(
                name = "Test Habit",
                description = "Test Description",
                habitType = HabitType.CHECK_IN,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )

            // Should receive updated emission
            val updatedHabits = awaitItem()
            assertEquals("Should have one habit after insert", 1, updatedHabits.size)
            assertEquals("Habit name should match", "Test Habit", updatedHabits[0].habit.name)
        }
    }

    @Test
    fun viewModelHabitsFlow_usesWhileSubscribedSharing() = runTest {
        viewModel.habitsWithStats.test {
            // Initial emission should be empty list
            val initialHabits = awaitItem()
            assertTrue("Initial habits should be empty", initialHabits.isEmpty())

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

            // Should receive updated emission
            val updatedHabits = awaitItem()
            assertEquals("Should have one habit", 1, updatedHabits.size)
        }
    }

    @Test
    fun multipleHabits_areOrderedCorrectly() = runTest {
        viewModel.habitsWithStats.test {
            // Skip initial empty emission
            awaitItem()

            // Create multiple habits
            repository.createHabit(
                name = "Habit B",
                description = "",
                habitType = HabitType.CHECK_IN,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )

            // Wait for first habit
            awaitItem()

            repository.createHabit(
                name = "Habit A",
                description = "",
                habitType = HabitType.CHECK_IN,
                iconResId = 1,
                colorHex = "#FF0000",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )

            // Wait for second habit
            val habits = awaitItem()
            assertEquals("Should have two habits", 2, habits.size)
            // Habits are ordered by createdAt DESC, so Habit B (created first) should be second
            assertEquals("First habit should be Habit A (most recently created)", "Habit A", habits[0].habit.name)
            assertEquals("Second habit should be Habit B (created first)", "Habit B", habits[1].habit.name)
        }
    }

    @Test
    fun initialTodayProgress_isZero() = runTest {
        val progress = viewModel.todayProgress.value
        assertEquals("Initial completed should be 0", 0, progress.first)
        assertEquals("Initial total should be 0", 0, progress.second)
    }

    @Test
    fun todayProgress_updatesWhenCompletionLogged() = runTest {
        viewModel.todayProgress.test {
            // Initial progress should be (0, 0)
            var progress = awaitItem()
            assertEquals("Initial completed should be 0", 0, progress.first)
            assertEquals("Initial total should be 0", 0, progress.second)

            // Create a habit
            val habitId = repository.createHabit(
                name = "Test Habit",
                description = "",
                habitType = HabitType.CHECK_IN,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )

            // Progress should show 0 completed, 1 total
            progress = awaitItem()
            assertEquals("Completed should be 0 initially", 0, progress.first)
            assertEquals("Total should be 1", 1, progress.second)

            // Log completion
            repository.logCompletion(context, habitId, 1)

            // Progress should now show 1 completed
            progress = awaitItem()
            assertEquals("Completed should be 1 after logging", 1, progress.first)
            assertEquals("Total should still be 1", 1, progress.second)
        }
    }

    @Test
    fun todayProgress_countsOnlyHabitsWithCompletions() = runTest {
        viewModel.todayProgress.test {
            // Skip initial emission
            awaitItem()

            // Create two habits
            val habitId1 = repository.createHabit(
                name = "Habit 1",
                description = "",
                habitType = HabitType.CHECK_IN,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )

            awaitItem() // Wait for first habit

            repository.createHabit(
                name = "Habit 2",
                description = "",
                habitType = HabitType.CHECK_IN,
                iconResId = 1,
                colorHex = "#FF0000",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )

            // Total should be 2, completed 0
            var progress = awaitItem()
            assertEquals("Total should be 2", 2, progress.second)

            // Log completion for only one habit
            repository.logCompletion(context, habitId1, 1)

            progress = awaitItem()
            assertEquals("Completed should be 1 (only one habit completed)", 1, progress.first)
            assertEquals("Total should be 2", 2, progress.second)
        }
    }

    @Test
    fun todayProgress_countsCountingHabitAsCompletedOnlyWhenTargetReached() = runTest {
        viewModel.habitsWithStats.test {
            // Skip initial empty emission
            awaitItem()

            // Create a counting habit with targetValue=3
            val habitId = repository.createHabit(
                name = "Counting Habit",
                description = "",
                habitType = HabitType.COUNTING,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 3
            )

            // Wait for habit to be emitted
            var habits = awaitItem()
            assertEquals("Should have one habit", 1, habits.size)
            assertEquals("Initial todayCount should be 0", 0, habits[0].todayCount)
            assertFalse("Should not be completed initially", habits[0].completedToday)

            // Log 2 completions (count = 2, target = 3, not yet completed)
            repository.logCompletion(context, habitId, 1)
            do {
                habits = awaitItem()
            } while (habits[0].todayCount < 1)
            assertEquals("todayCount should be 1", 1, habits[0].todayCount)
            assertFalse("Should not be completed with count 1", habits[0].completedToday)

            repository.logCompletion(context, habitId, 1)
            do {
                habits = awaitItem()
            } while (habits[0].todayCount < 2)
            assertEquals("todayCount should be 2", 2, habits[0].todayCount)
            assertFalse("Should not be completed with count 2 < target 3", habits[0].completedToday)

            // Log 1 more completion (count = 3, target = 3, now completed)
            repository.logCompletion(context, habitId, 1)
            do {
                habits = awaitItem()
            } while (habits[0].todayCount < 3)
            assertEquals("todayCount should be 3", 3, habits[0].todayCount)
            assertTrue("Should be completed with count 3 >= target 3", habits[0].completedToday)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun todayProgress_countsCheckInHabitAsCompletedWithAnyCompletion() = runTest {
        viewModel.todayProgress.test {
            // Skip initial emission
            awaitItem()

            // Create a check-in habit
            val habitId = repository.createHabit(
                name = "Check-in Habit",
                description = "",
                habitType = HabitType.CHECK_IN,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )

            // Initial progress: 0 completed, 1 total
            var progress = awaitItem()
            assertEquals("Completed should be 0 initially", 0, progress.first)
            assertEquals("Total should be 1", 1, progress.second)

            // Log 1 completion for check-in habit
            repository.logCompletion(context, habitId, 1)

            progress = awaitItem()
            assertEquals("Completed should be 1 after logging", 1, progress.first)
            assertEquals("Total should be 1", 1, progress.second)
        }
    }

    @Test
    fun habitsWithStats_includesTodayCount() = runTest {
        viewModel.habitsWithStats.test {
            // Skip initial empty emission
            awaitItem()

            // Create a counting habit
            val habitId = repository.createHabit(
                name = "Counting Habit",
                description = "",
                habitType = HabitType.COUNTING,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 5
            )

            // Wait for habit to be emitted
            var habits = awaitItem()
            assertEquals("Initial todayCount should be 0", 0, habits[0].todayCount)

            // Log 3 completions
            repository.logCompletion(context, habitId, 1)
            repository.logCompletion(context, habitId, 1)
            repository.logCompletion(context, habitId, 1)

            // Verify todayCount is 3
            do {
                habits = awaitItem()
            } while (habits[0].todayCount < 3)
            assertEquals("todayCount should be 3 after 3 completions", 3, habits[0].todayCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeHabitsSortedFirst() = runTest {
        viewModel.habitsWithStats.test {
            // Skip initial empty emission
            awaitItem()

            // Create an inactive habit first
            habitDao.insert(
                com.dayforge.data.local.entity.HabitEntity(
                    name = "Inactive Habit",
                    description = "",
                    habitType = HabitType.CHECK_IN,
                    iconResId = 1,
                    colorHex = "#FF0000",
                    schedule = HabitSchedule.Daily,
                    targetValue = 1,
                    isActive = false
                )
            )

            awaitItem()

            // Create an active habit after
            habitDao.insert(
                com.dayforge.data.local.entity.HabitEntity(
                    name = "Active Habit",
                    description = "",
                    habitType = HabitType.CHECK_IN,
                    iconResId = 1,
                    colorHex = "#00FF00",
                    schedule = HabitSchedule.Daily,
                    targetValue = 1,
                    isActive = true
                )
            )

            val habits = awaitItem()
            assertEquals("Should have two habits", 2, habits.size)
            assertTrue("First habit should be active", habits[0].habit.isActive)
            assertFalse("Second habit should be inactive", habits[1].habit.isActive)
            assertEquals("Active habit should be first", "Active Habit", habits[0].habit.name)
            assertEquals("Inactive habit should be last", "Inactive Habit", habits[1].habit.name)
        }
    }

    @Test
    fun pendingMetricChange_recomputesCheckableTimerVisibility() = runTest {
        filterModeFlow.value = FilterMode.CHECKABLE.value

        viewModel.habitsWithStats.test {
            assertTrue(awaitItem().isEmpty())

            val habitId = repository.createHabit(
                name = "Timer Habit",
                description = "",
                habitType = HabitType.TIMER,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )
            assertEquals(listOf(habitId), awaitItem().map { it.habit.id })

            val now = System.currentTimeMillis()
            timeLogDao.insert(
                TimeLogEntity(
                    habitId = habitId,
                    startTime = now - 60_000,
                    endTime = now,
                    durationSeconds = 60,
                    date = DateTimeUtils.startOfDayMillis()
                )
            )
            assertTrue(awaitItem().isEmpty())

            pendingMetricHabitsFlow.value = setOf(habitId)
            assertEquals(listOf(habitId), awaitItem().map { it.habit.id })
        }
    }
}
