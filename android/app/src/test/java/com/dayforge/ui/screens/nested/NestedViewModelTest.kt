package com.dayforge.ui.screens.nested

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.ActiveTimerStateProvider
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitLifecycleCoordinator
import com.dayforge.domain.service.HabitTimerCoordinator
import com.dayforge.domain.service.TimerManager
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Characterizes the hierarchy and linked-metric behavior that must survive decomposition of
 * [NestedViewModel]. These tests intentionally exercise the current public state instead of its
 * private implementation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class NestedViewModelTest {

    private lateinit var context: Context
    private lateinit var database: HabitDatabase
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: CompletionDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var metricDao: MetricDao
    private lateinit var metricLogDao: MetricLogDao
    private lateinit var linkDao: HabitMetricLinkDao
    private lateinit var habitRepository: HabitRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var viewModel: NestedViewModel
    private lateinit var preferencesFile: File
    private lateinit var dataStoreScope: CoroutineScope

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java).build()
        habitDao = database.habitDao()
        completionDao = database.completionDao()
        timeLogDao = database.timeLogDao()
        metricDao = database.metricDao()
        metricLogDao = database.metricLogDao()
        linkDao = database.habitMetricLinkDao()
        habitRepository = HabitRepository(habitDao, completionDao, timeLogDao, database)

        preferencesFile = File(context.cacheDir, "nested_viewmodel_test.preferences_pb")
        preferencesFile.delete()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { preferencesFile }
        )
        preferencesManager = PreferencesManager(dataStore)

        val metricRepository = MetricRepository(
            database,
            metricDao,
            metricLogDao,
            habitDao,
            linkDao
        )
        val metricCoordinator = LinkedMetricCoordinator(
            context = context,
            preferencesManager = preferencesManager,
            metricRepository = metricRepository,
            habitRepository = habitRepository
        )
        viewModel = NestedViewModel(
            context = context,
            habitDao = habitDao,
            timeLogDao = timeLogDao,
            habitRepository = habitRepository,
            nestedHabitTreeBuilder = NestedHabitTreeBuilder(
                habitDao,
                completionDao,
                timeLogDao,
                FailureChecker(completionDao, timeLogDao)
            ),
            checkInService = CheckInService(habitRepository, completionDao, timeLogDao),
            preferencesManager = preferencesManager,
            metricCoordinator = metricCoordinator,
            lifecycleCoordinator = HabitLifecycleCoordinator(context, habitRepository),
            timerCoordinator = HabitTimerCoordinator(
                TimerManager(context, habitDao, timeLogDao),
                ActiveTimerStateProvider(timeLogDao, habitRepository, context)
            )
        )
    }

    @After
    fun tearDown() = runBlocking {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::dataStoreScope.isInitialized) {
            dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::database.isInitialized) database.close()
        if (::preferencesFile.isInitialized) preferencesFile.delete()
        Dispatchers.resetMain()
    }

    @Test
    fun `hierarchy keeps one parent relationship and hides standalone leaf habits`() = runTest {
        val firstGoal = habit(HabitType.GOAL, "First goal", uuid = "goal-1")
        val secondGoal = habit(HabitType.GOAL, "Second goal", uuid = "goal-2")
        val child = habit(
            HabitType.CHECK_IN,
            "Child",
            uuid = "child-1",
            parentUuid = firstGoal.uuid
        )
        val standaloneLeaf = habit(HabitType.CHECK_IN, "Standalone", uuid = "standalone")

        habitDao.insert(firstGoal)
        habitDao.insert(secondGoal)
        val childId = habitDao.insert(child)
        habitDao.insert(standaloneLeaf)

        val hierarchy = awaitHierarchy { parents ->
            parents.size == 2 && parents.sumOf { it.children.size } == 1
        }
        val firstParent = hierarchy.single { it.habit.uuid == firstGoal.uuid }
        val secondParent = hierarchy.single { it.habit.uuid == secondGoal.uuid }

        assertEquals(listOf(childId), firstParent.children.map { it.habit.id })
        assertEquals(1, firstParent.totalChildren)
        assertEquals(1, firstParent.totalChildrenIncludingNonCheckInDays)
        assertTrue(secondParent.children.isEmpty())
        assertFalse(hierarchy.any { it.habit.uuid == standaloneLeaf.uuid })
    }

    @Test
    fun `child completion updates parent progress without changing hierarchy`() = runTest {
        val parent = habit(HabitType.GOAL, "Goal", uuid = "goal")
        habitDao.insert(parent)
        val childId = habitDao.insert(
            habit(HabitType.CHECK_IN, "Daily child", uuid = "child", parentUuid = parent.uuid)
        )

        val before = awaitHierarchy { parents ->
            parents.singleOrNull()?.totalChildren == 1
        }.single()
        assertEquals(0, before.completedChildren)
        assertFalse(before.children.single().completedToday)

        habitRepository.logCompletion(context, childId)

        val after = awaitHierarchy { parents ->
            parents.singleOrNull()?.completedChildren == 1
        }.single()
        assertEquals(parent.uuid, after.habit.uuid)
        assertEquals(childId, after.children.single().habit.id)
        assertTrue(after.children.single().completedToday)
        assertEquals(1, after.children.single().todayCount)
    }

    @Test
    fun `counting child completes only after today's values reach its target`() = runTest {
        val parent = habit(HabitType.GOAL, "Goal", uuid = "goal")
        habitDao.insert(parent)
        val child = habit(
            HabitType.COUNTING,
            "Counting child",
            uuid = "counting-child",
            parentUuid = parent.uuid,
            targetValue = 3
        )
        val childId = habitDao.insert(child)
        val today = DateTimeUtils.startOfDayMillis()

        completionDao.insert(
            CompletionEntity(
                habitId = childId,
                habitUuid = child.uuid,
                date = today,
                value = 2
            )
        )
        val beforeTarget = awaitHierarchy { parents ->
            parents.singleOrNull()?.children?.singleOrNull()?.todayCount == 2
        }.single().children.single()
        assertFalse(beforeTarget.completedToday)

        completionDao.insert(
            CompletionEntity(
                habitId = childId,
                habitUuid = child.uuid,
                date = today,
                value = 1
            )
        )
        val atTarget = awaitHierarchy { parents ->
            parents.singleOrNull()?.children?.singleOrNull()?.completedToday == true
        }.single().children.single()

        assertEquals(3, atTarget.todayCount)
        assertTrue(atTarget.completedToday)
    }

    @Test
    fun `completed timer child exposes seconds streak and target-cycle progress`() = runTest {
        val parent = habit(HabitType.GOAL, "Goal", uuid = "goal")
        habitDao.insert(parent)
        val child = habit(
            HabitType.TIMER,
            "Timer child",
            uuid = "timer-child",
            parentUuid = parent.uuid,
            targetValue = 1,
            targetCycles = 2
        )
        val childId = habitDao.insert(child)
        val today = DateTimeUtils.startOfDayMillis()

        timeLogDao.insert(
            TimeLogEntity(
                habitId = childId,
                startTime = today,
                endTime = today + 60_000,
                durationSeconds = 60,
                date = today
            )
        )

        val timerStats = awaitHierarchy { parents ->
            parents.singleOrNull()?.children?.singleOrNull()?.todayCount == 60
        }.single().children.single()

        assertTrue(timerStats.completedToday)
        assertEquals(60, timerStats.todayCount)
        assertEquals(1, timerStats.currentStreak)
        assertEquals(1, timerStats.bestStreak)
        assertEquals(1, timerStats.targetProgress)
        assertFalse(timerStats.hasFailed)
    }

    @Test
    fun `metric prompt contains only active prompt links and their latest values`() = runTest {
        val habit = habit(HabitType.CHECK_IN, "Linked habit", uuid = "habit")
        val habitId = habitDao.insert(habit)
        val promptedMetric = metric("Weight", "kg", 1, uuid = "metric-prompted")
        val silentMetric = metric("Steps", "steps", 0, uuid = "metric-silent")
        val inactiveMetric = metric("Archived", "kg", 1, uuid = "metric-inactive")
        val promptedMetricId = metricDao.insert(promptedMetric)
        val silentMetricId = metricDao.insert(silentMetric)
        val inactiveMetricId = metricDao.insert(inactiveMetric)
        metricLogDao.insert(
            MetricLogEntity(
                metricId = promptedMetricId,
                date = 123L,
                value = 68.5,
                unit = promptedMetric.unit
            )
        )
        linkDao.insert(
            link(habitId, habit.uuid, promptedMetricId, promptedMetric.uuid, prompt = true)
        )
        linkDao.insert(
            link(habitId, habit.uuid, silentMetricId, silentMetric.uuid, prompt = false)
        )
        linkDao.insert(
            link(habitId, habit.uuid, inactiveMetricId, inactiveMetric.uuid, prompt = true)
                .copy(isActive = false)
        )

        viewModel.checkAndShowPostCheckInDialog(habitId, habit.name)

        val state = requireNotNull(viewModel.postCheckInState.value)
        assertEquals(habitId, state.habitId)
        assertEquals(habit.name, state.habitName)
        assertTrue(state.show)
        assertEquals(1, state.linkedMetrics.size)
        with(state.linkedMetrics.single()) {
            assertEquals(promptedMetricId, metricId)
            assertEquals(promptedMetric.name, metricName)
            assertEquals(68.5, latestValue ?: Double.NaN, 0.0)
            assertEquals(promptedMetric.unit, unit)
            assertEquals(promptedMetric.decimalPlaces, decimalPlaces)
        }
    }

    @Test
    fun `dismiss and never ask preference prevent a metric prompt from reopening`() = runTest {
        val habit = habit(HabitType.CHECK_IN, "Linked habit", uuid = "habit")
        val habitId = habitDao.insert(habit)
        val metric = metric("Weight", "kg", 1, uuid = "metric")
        val metricId = metricDao.insert(metric)
        linkDao.insert(link(habitId, habit.uuid, metricId, metric.uuid, prompt = true))

        viewModel.checkAndShowPostCheckInDialog(habitId, habit.name)
        assertEquals(habitId, viewModel.postCheckInState.value?.habitId)

        viewModel.dismissPostCheckInDialog()
        assertNull(viewModel.postCheckInState.value)
        viewModel.setNeverAskAgain(habitId, true)
        viewModel.checkAndShowPostCheckInDialog(habitId, habit.name)

        assertNull(viewModel.postCheckInState.value)
    }

    @Test
    fun `linked metric cards refresh when latest value changes`() = runTest {
        val habit = habit(HabitType.CHECK_IN, "Linked habit", uuid = "habit")
        val habitId = habitDao.insert(habit)
        val metric = metric("Weight", "kg", 1, uuid = "metric")
        val metricId = metricDao.insert(metric)
        linkDao.insert(link(habitId, habit.uuid, metricId, metric.uuid, prompt = true))

        val initial = awaitLinkedMetrics { it[habitId]?.singleOrNull() != null }
        assertNull(initial.getValue(habitId).single().latestValue)

        metricLogDao.insert(
            MetricLogEntity(
                metricId = metricId,
                date = 2_000L,
                value = 72.4,
                unit = metric.unit
            )
        )

        val updated = awaitLinkedMetrics {
            it[habitId]?.singleOrNull()?.latestValue == 72.4
        }
        assertEquals(72.4, updated.getValue(habitId).single().latestValue ?: Double.NaN, 0.0)
    }

    @Test
    fun `recording linked values clears only the handled pending habit`() = runTest {
        val firstHabitId = 11L
        val otherHabitId = 12L
        val firstMetricId = metricDao.insert(metric("Weight", "kg", 1, uuid = "weight"))
        val secondMetricId = metricDao.insert(metric("Waist", "cm", 1, uuid = "waist"))
        preferencesManager.addPendingMetricHabit(firstHabitId)
        preferencesManager.addPendingMetricHabit(otherHabitId)

        val recorded = viewModel.recordMetricValues(
            firstHabitId,
            listOf(
                MetricValueInput(firstMetricId, 70.2, "morning"),
                MetricValueInput(secondMetricId, 82.0)
            )
        )

        assertTrue(recorded)
        assertEquals(70.2, metricLogDao.getLatestLog(firstMetricId)?.value ?: Double.NaN, 0.0)
        assertEquals("morning", metricLogDao.getLatestLog(firstMetricId)?.note)
        assertEquals(82.0, metricLogDao.getLatestLog(secondMetricId)?.value ?: Double.NaN, 0.0)
        assertEquals(setOf(otherHabitId), preferencesManager.pendingMetricHabits.first())
    }

    @Test
    fun `invalid linked value submission saves nothing and keeps pending habit`() = runTest {
        val habitId = 11L
        val validMetricId = metricDao.insert(metric("Weight", "kg", 1, uuid = "weight"))
        preferencesManager.addPendingMetricHabit(habitId)

        val recorded = viewModel.recordMetricValues(
            habitId,
            listOf(
                MetricValueInput(validMetricId, 70.2),
                MetricValueInput(Long.MAX_VALUE, 99.9)
            )
        )

        assertFalse(recorded)
        assertNull(metricLogDao.getLatestLog(validMetricId))
        assertEquals(setOf(habitId), preferencesManager.pendingMetricHabits.first())
    }

    @Test
    fun `goal and reactivation dialogs preserve child lifecycle state`() = runTest {
        val parent = habit(HabitType.GOAL, "Goal", uuid = "goal")
        habitDao.insert(parent)
        val childId = habitDao.insert(
            habit(
                HabitType.CHECK_IN,
                "Target child",
                uuid = "target-child",
                parentUuid = parent.uuid,
                targetCycles = 1
            )
        )
        awaitHierarchy { parents ->
            parents.singleOrNull()?.children?.singleOrNull()?.habit?.id == childId
        }

        viewModel.logCompletion(childId)
        awaitGoalDialogVisibility(visible = true)
        assertEquals(childId, viewModel.goalHabitId.value)
        assertEquals(1, viewModel.goalProgress.value)
        assertEquals(1, viewModel.goalTarget.value)

        viewModel.dismissGoalDialog()
        awaitGoalDialogVisibility(visible = false)
        assertEquals(FailMode.LOOSE, habitDao.getHabitById(childId)?.failMode)

        viewModel.showReactivationDialog(childId)
        assertTrue(viewModel.showReactivationDialog.value)
        assertEquals(childId, viewModel.reactivationHabitId.value)
        assertEquals("Target child", viewModel.reactivationHabitName.value)

        viewModel.dismissReactivationDialog()
        assertFalse(viewModel.showReactivationDialog.value)
        assertNull(viewModel.reactivationHabitId.value)
        assertEquals("", viewModel.reactivationHabitName.value)
    }

    private suspend fun awaitGoalDialogVisibility(visible: Boolean) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                viewModel.showGoalDialog.first { it == visible }
            }
        }
    }

    private suspend fun awaitHierarchy(
        predicate: (List<ParentHabitWithChildren>) -> Boolean
    ): List<ParentHabitWithChildren> = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) {
            viewModel.topLevelHabitsWithChildren.first(predicate)
        }
    }

    private suspend fun awaitLinkedMetrics(
        predicate: (Map<Long, List<LinkedMetricInfo>>) -> Boolean
    ): Map<Long, List<LinkedMetricInfo>> =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                viewModel.linkedMetricsByHabit.first(predicate)
            }
        }

    private fun habit(
        type: HabitType,
        name: String,
        uuid: String,
        parentUuid: String? = null,
        targetValue: Int = 1,
        targetCycles: Int? = null
    ) = HabitEntity(
        name = name,
        habitType = type,
        iconResId = 0,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily,
        targetValue = targetValue,
        uuid = uuid,
        parentHabitId = parentUuid,
        targetCycles = targetCycles,
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
        iconResId = 0,
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
        prompt: Boolean
    ) = HabitMetricLinkEntity(
        habitId = habitId,
        habitUuid = habitUuid,
        metricId = metricId,
        metricUuid = metricUuid,
        promptOnComplete = prompt,
        createdAt = 1_000L,
        updatedAt = 1_000L
    )
}
