package com.dayforge.ui.screens.creategoal

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitDraft
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.ui.screens.createhabit.CreateHabitViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GoalDraftTest {
    @get:org.junit.Rule(order = 0) val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    @get:org.junit.Rule(order = 1) val storage = com.dayforge.data.local.PhysicalDatabaseRule()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var db: HabitDatabase
    private lateinit var repository: HabitRepository
    private lateinit var context: Context

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = storage.database
        repository = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db)
    }

    @After fun cleanup() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        db.close()
        Dispatchers.resetMain()
    }

    @Test fun draftRemovalAndRestorationNeverWriteBusinessRows() = runBlocking {
        val handle = SavedStateHandle()
        val vm = CreateGoalViewModel(context, repository, handle)
        store.put("goal", vm)
        vm.updateName("Goal")
        val child = HabitDraft(name = "Count", habitType = HabitType.COUNTING, targetValue = 8,
            isCountdown = true, schedule = HabitSchedule.Weekly(listOf(2, 5)), bestTime = 90,
            description = "Keep all fields", selectedMetricIds = setOf(12L))
        vm.addChildHabit(child)
        val restored = CreateGoalViewModel(context, repository,
            SavedStateHandle(mapOf(CreateGoalViewModel.DRAFT_KEY to handle.get<String>(CreateGoalViewModel.DRAFT_KEY))))
        store.put("restored", restored)
        assertEquals(vm.uiState.value.parentUuid, restored.uiState.value.parentUuid)
        assertEquals(listOf(child), restored.uiState.value.children)
        restored.removeChildHabit(child.id)
        assertTrue(restored.uiState.value.children.isEmpty())
        store.clear() // Leaving/cancelling the creation flow must not require database cleanup.
        assertTrue(db.habitDao().getAllHabitsOnce().isEmpty())
        assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun goalCommitIncludesEveryChildAndLinkAndRetryIsIdempotent() = runBlocking {
        val metricId = db.metricDao().insert(MetricEntity(name = "Weight", unit = "kg",
            iconResId = 1, colorHex = "#2196F3"))
        val goal = HabitDraft(name = "Goal", habitType = HabitType.GOAL)
        val children = listOf(HabitType.CHECK_IN, HabitType.COUNTING, HabitType.TIMER).mapIndexed { i, type ->
            HabitDraft(name = "Child $i", habitType = type, targetValue = 2, bestTime = 60,
                selectedMetricIds = setOf(metricId))
        }
        val id = repository.createGoal(goal, children)
        val pending = db.syncOutboxDao().getAll()
        assertEquals(4, db.habitDao().getAllHabitsOnce().size)
        children.forEach { child ->
            val row = requireNotNull(db.habitDao().getHabitByUuid(child.id))
            assertEquals(goal.id, row.parentHabitId)
            assertEquals(child.bestTime, row.bestTime)
            assertNotNull(db.habitMetricLinkDao().getLink(row.id, metricId))
        }
        assertEquals(8, pending.size) // Metric + goal + three children + three links.
        assertEquals(id, repository.createGoal(goal, children))
        assertEquals(pending, db.syncOutboxDao().getAll())
    }

    @Test fun failedChildRollsBackGoalEarlierChildrenAndOutbox() = runBlocking {
        val goal = HabitDraft(name = "Goal", habitType = HabitType.GOAL)
        val children = listOf(HabitDraft(name = "First", habitType = HabitType.CHECK_IN),
            HabitDraft(name = "Bad link", habitType = HabitType.COUNTING, selectedMetricIds = setOf(Long.MAX_VALUE)))
        assertTrue(runCatching { repository.createGoal(goal, children) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(db.habitDao().getAllHabitsOnce().isEmpty())
        assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun defaultConfirmationStillAddsOnlyDraft() = runBlocking {
        val vm = CreateHabitViewModel(repository, db.habitDao(), db.metricDao(), mockk(relaxed = true), context)
        store.put("child", vm)
        vm.updateName("Timer draft")
        vm.updateHabitType(HabitType.TIMER)
        vm.updateTargetValue(null)
        vm.updateSchedule(HabitSchedule.Monthly(1))
        vm.updateMonthlyInput("")
        var draft: HabitDraft? = null
        val accept: (HabitDraft) -> Unit = { draft = it }
        vm.saveHabit(onSaveDraft = accept)
        assertTrue(vm.uiState.value.showDefaultScheduleDialog)
        vm.confirmDefaultSchedule(accept)
        assertTrue(vm.uiState.value.showDefaultTargetDialog)
        vm.confirmDefaultTarget(accept)
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.savedDraft)
        assertEquals(1, draft?.targetValue)
        assertEquals(HabitSchedule.Monthly(1), draft?.schedule)
        assertTrue(db.habitDao().getAllHabitsOnce().isEmpty())
        assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun failedSaveRetainsDraftAndAllowsRetry() = runBlocking {
        val vm = CreateGoalViewModel(context, repository, SavedStateHandle())
        store.put("goal", vm)
        vm.updateName("Goal")
        val child = HabitDraft(name = "Child", habitType = HabitType.CHECK_IN,
            selectedMetricIds = setOf(Long.MAX_VALUE))
        vm.addChildHabit(child)
        vm.saveGoal()
        withTimeout(10_000) {
            while (vm.uiState.value.isSaving) { dispatcher.scheduler.runCurrent(); delay(10) }
        }
        assertNotNull(vm.uiState.value.errorMessage)
        assertEquals(listOf(child), vm.uiState.value.children)
        assertEquals(0, db.syncOutboxDao().count())
        vm.addChildHabit(child.copy(selectedMetricIds = emptySet()))
        vm.saveGoal()
        withTimeout(10_000) {
            while (vm.uiState.value.isSaving) { dispatcher.scheduler.runCurrent(); delay(10) }
        }
        assertNotNull(vm.uiState.value.savedGoalId)
        assertEquals(2, db.habitDao().getAllHabitsOnce().size)
        assertEquals(1, widgetRefresh.requestCount)
    }
}
