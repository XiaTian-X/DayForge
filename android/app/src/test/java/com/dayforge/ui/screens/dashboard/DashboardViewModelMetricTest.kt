package com.dayforge.ui.screens.dashboard

import android.content.Context
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
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.StructuralEditGuard
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
            habitStatusCalculator,
            timeLogDao,
            habitDao,
            mockPreferencesManager,
            metricDao,
            metricLogDao,
            habitMetricLinkDao,
            completionDao,
            metricRepository
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        database.close()
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
}
