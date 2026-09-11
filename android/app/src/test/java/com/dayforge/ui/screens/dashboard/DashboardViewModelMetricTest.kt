package com.dayforge.ui.screens.dashboard

import android.content.Context
import androidx.lifecycle.viewModelScope
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
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.model.MetricWithLatestValue
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.MetricOverviewProvider
import com.dayforge.domain.service.StructuralEditGuard
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for DashboardViewModel metric-related state and functions.
 * Part of Phase 27-01: Main Screen Integration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DashboardViewModelMetricTest {

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
        // Mock PreferencesManager - must mock dateChangeTrigger for combine to work
        mockPreferencesManager = mockk(relaxed = true) {
            every { hasShownBatteryGuidance } returns flowOf(false)
            every { dateChangeTrigger } returns flowOf(System.currentTimeMillis() / (24 * 60 * 60 * 1000L))
            every { filterMode } returns flowOf("all")
            every { pendingMetricHabits } returns flowOf(emptySet())
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
            DashboardTimeWindowTicker(repository, mockPreferencesManager),
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
    fun metricsWithLatest_emitsEmptyList_whenNoMetricsExist() = runTest {
        val metrics = viewModel.metricsWithLatest.value
        assertTrue("Initial metrics should be empty list", metrics.isEmpty())
    }

    @Test
    fun metricsWithLatest_emitsMetric_whenMetricInserted() = runTest {
        viewModel.metricsWithLatest.test {
            // Skip initial empty emission
            awaitItem()

            // Insert a metric
            metricDao.insert(
                MetricEntity(
                    name = "Weight",
                    unit = "kg",
                    decimalPlaces = 1,
                    targetDirection = "decrease",
                    targetValue = 65.0,
                    iconResId = 1,
                    colorHex = "#2196F3",
                    isActive = true
                )
            )

            // Wait for the metric
            val metrics = awaitItem()
            assertEquals("Should have one metric", 1, metrics.size)
            assertEquals("Metric name should match", "Weight", metrics[0].metric.name)
        }
    }

    @Test
    fun metricsWithLatest_emitsNullLatestValue_whenNoLogsExist() = runTest {
        viewModel.metricsWithLatest.test {
            // Skip initial empty emission
            awaitItem()

            // Insert a metric without any logs
            metricDao.insert(
                MetricEntity(
                    name = "Steps",
                    unit = "steps",
                    decimalPlaces = 0,
                    targetDirection = "increase",
                    targetValue = 10000.0,
                    iconResId = 2,
                    colorHex = "#4CAF50",
                    isActive = true
                )
            )

            // Wait for the metric
            val metrics = awaitItem()
            assertEquals("Should have one metric", 1, metrics.size)
            assertNull("Latest value should be null when no logs exist", metrics[0].latestValue)
            assertNull("Latest log date should be null when no logs exist", metrics[0].latestLogDate)
        }
    }

    @Test
    fun metricsWithLatest_showsOnlyActiveMetrics() = runTest {
        // Verify that the DAO query only returns active metrics
        val activeMetricId = metricDao.insert(
            MetricEntity(
                name = "Active Metric",
                unit = "unit",
                decimalPlaces = 0,
                iconResId = 1,
                colorHex = "#2196F3",
                isActive = true
            )
        )

        val inactiveMetricId = metricDao.insert(
            MetricEntity(
                name = "Inactive Metric",
                unit = "unit",
                decimalPlaces = 0,
                iconResId = 2,
                colorHex = "#FF0000",
                isActive = false
            )
        )

        // Verify the DAO returns only active metrics
        val allMetrics = metricDao.getAllMetrics()
        val activeMetrics = metricDao.getAllActiveMetrics()

        // Use first() to get first emission
        val all = allMetrics.first()
        val active = activeMetrics.first()

        assertEquals("DAO should have 2 total metrics", 2, all.size)
        assertEquals("DAO should return only 1 active metric", 1, active.size)
        assertTrue("Active metric should be in the list", active.any { it.id == activeMetricId })
        assertFalse("Inactive metric should not be in the list", active.any { it.id == inactiveMetricId })
    }

    @Test
    fun todayProgress_countsOnlyHabitsNotMetrics() = runTest {
        viewModel.todayProgress.test {
            // Skip initial emission
            awaitItem()

            // Create a habit
            repository.createHabit(
                name = "Test Habit",
                description = "",
                habitType = HabitType.CHECK_IN,
                iconResId = 1,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )

            // Wait for habit to be added
            val progressWithHabit = awaitItem()
            assertEquals("Total should be 1 (habit)", 1, progressWithHabit.second)
            assertEquals("Completed should be 0", 0, progressWithHabit.first)
        }
    }

    @Test
    fun metricsWithLatest_ordersByCreatedAtDescending() = runTest {
        viewModel.metricsWithLatest.test {
            // Skip initial empty emission
            awaitItem()

            // Insert first metric
            metricDao.insert(
                MetricEntity(
                    name = "First Metric",
                    unit = "unit",
                    decimalPlaces = 0,
                    iconResId = 1,
                    colorHex = "#2196F3",
                    isActive = true
                )
            )

            // Wait for first metric
            awaitItem()

            // Insert second metric (will have later createdAt)
            metricDao.insert(
                MetricEntity(
                    name = "Second Metric",
                    unit = "unit",
                    decimalPlaces = 0,
                    iconResId = 2,
                    colorHex = "#4CAF50",
                    isActive = true
                )
            )

            val metrics = awaitItem()
            assertEquals("Should have two metrics", 2, metrics.size)
            // Most recently created should be first (createdAt DESC)
            assertEquals("First should be Second Metric (newest)", "Second Metric", metrics[0].metric.name)
            assertEquals("Second should be First Metric (older)", "First Metric", metrics[1].metric.name)
        }
    }

    @Test
    fun metricsWithLatest_usesNewestValue_andKeepsOnlyRecentTrendLogs() = runTest {
        val metric = metric("Weight", "kg", 1, uuid = "weight")
        val metricId = metricDao.insert(metric)
        val now = System.currentTimeMillis()
        val oldLogId = metricLogDao.insert(
            MetricLogEntity(
                metricId = metricId,
                date = now - 31L * 24 * 60 * 60 * 1_000,
                value = 73.0,
                unit = metric.unit
            )
        )
        val recentLogId = metricLogDao.insert(
            MetricLogEntity(
                metricId = metricId,
                date = now - 1_000,
                value = 71.3,
                unit = metric.unit
            )
        )

        val overview = awaitMetricOverviews {
            it.singleOrNull()?.latestValue == 71.3
        }.single()

        assertEquals(recentLogId, overview.logs.single().id)
        assertFalse(overview.logs.any { it.id == oldLogId })
        assertEquals(71.3, overview.latestValue ?: Double.NaN, 0.0)
        assertEquals(now - 1_000, overview.latestLogDate)
    }

    @Test
    fun linkedMetricsByHabit_hidesNonDetailLinks_andRefreshesLatestValue() = runTest {
        val habit = habit("Dashboard habit")
        val habitId = habitDao.insert(habit)
        val visibleMetric = metric("Visible", "kg", 1, uuid = "visible")
        val hiddenMetric = metric("Hidden", "cm", 0, uuid = "hidden")
        val visibleMetricId = metricDao.insert(visibleMetric)
        val hiddenMetricId = metricDao.insert(hiddenMetric)
        habitMetricLinkDao.insert(
            link(habitId, habit.uuid, visibleMetricId, visibleMetric.uuid, showInDetail = true)
        )
        habitMetricLinkDao.insert(
            link(habitId, habit.uuid, hiddenMetricId, hiddenMetric.uuid, showInDetail = false)
        )

        val initial = awaitLinkedMetrics { it[habitId]?.size == 1 }
        assertEquals(visibleMetricId, initial.getValue(habitId).single().metricId)
        assertNull(initial.getValue(habitId).single().latestValue)

        metricLogDao.insert(
            MetricLogEntity(
                metricId = visibleMetricId,
                date = 2_000L,
                value = 71.3,
                unit = visibleMetric.unit
            )
        )

        val updated = awaitLinkedMetrics {
            it[habitId]?.singleOrNull()?.latestValue == 71.3
        }
        assertEquals(71.3, updated.getValue(habitId).single().latestValue ?: Double.NaN, 0.0)
    }

    @Test
    fun postCheckInPrompt_marksTemporaryTask_andIncludesOnlyPromptLinks() = runTest {
        val temporaryTask = habit(
            name = "Temporary task",
            iconResId = 53,
            targetCycles = 1,
            failMode = FailMode.LOOSE
        )
        val habitId = habitDao.insert(temporaryTask)
        val promptedMetric = metric("Prompted", "kg", 1, uuid = "prompted")
        val silentMetric = metric("Silent", "cm", 0, uuid = "silent")
        val promptedMetricId = metricDao.insert(promptedMetric)
        val silentMetricId = metricDao.insert(silentMetric)
        habitMetricLinkDao.insert(
            link(
                habitId,
                temporaryTask.uuid,
                promptedMetricId,
                promptedMetric.uuid,
                prompt = true
            )
        )
        habitMetricLinkDao.insert(
            link(
                habitId,
                temporaryTask.uuid,
                silentMetricId,
                silentMetric.uuid,
                prompt = false
            )
        )

        viewModel.checkAndShowPostCheckInDialog(habitId, temporaryTask.name)

        val state = requireNotNull(viewModel.postCheckInState.value)
        assertEquals(habitId, state.habitId)
        assertEquals(temporaryTask.name, state.habitName)
        assertTrue(state.show)
        assertTrue(state.isTempTask)
        assertEquals(listOf(promptedMetricId), state.linkedMetrics.map { it.metricId })
    }

    private suspend fun awaitLinkedMetrics(
        predicate: (Map<Long, List<com.dayforge.ui.components.LinkedMetricInfo>>) -> Boolean
    ): Map<Long, List<com.dayforge.ui.components.LinkedMetricInfo>> =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                viewModel.linkedMetricsByHabit.first(predicate)
            }
        }

    private suspend fun awaitMetricOverviews(
        predicate: (List<MetricWithLatestValue>) -> Boolean
    ): List<MetricWithLatestValue> = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) {
            viewModel.metricsWithLatest.first(predicate)
        }
    }

    private fun habit(
        name: String,
        iconResId: Int = 1,
        targetCycles: Int? = null,
        failMode: FailMode = FailMode.STRICT
    ) = HabitEntity(
        name = name,
        habitType = HabitType.CHECK_IN,
        iconResId = iconResId,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily,
        targetCycles = targetCycles,
        failMode = failMode,
        uuid = "habit-${name.lowercase().replace(' ', '-')}",
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    private fun metric(
        name: String,
        unit: String,
        decimalPlaces: Int,
        uuid: String
    ) = MetricEntity(
        name = name,
        unit = unit,
        decimalPlaces = decimalPlaces,
        iconResId = 1,
        colorHex = "#4CAF50",
        uuid = uuid,
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    private fun link(
        habitId: Long,
        habitUuid: String,
        metricId: Long,
        metricUuid: String,
        showInDetail: Boolean = true,
        prompt: Boolean = true
    ) = HabitMetricLinkEntity(
        habitId = habitId,
        habitUuid = habitUuid,
        metricId = metricId,
        metricUuid = metricUuid,
        showInHabitDetail = showInDetail,
        promptOnComplete = prompt,
        createdAt = 1_000L,
        updatedAt = 1_000L
    )
}
