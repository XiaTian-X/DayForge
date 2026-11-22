package com.dayforge.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.CountCommandRecords
import com.dayforge.data.repository.CountCommandStore
import com.dayforge.domain.service.*
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.dashboard.*
import com.dayforge.ui.screens.nested.*
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.UUID
import java.util.TimeZone
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Hilt/Host/Room and both original visible COUNT screens. ADD is the real Compose
 * button; UNDO/floor use each original public VM API and its captured source (not invented
 * minus controls). T2 is accepted model delivery, not a frame-latency or pixel SLA claim. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CountButtonCommandPhysicalTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var completion: HabitCompletionCoordinator
    @Inject lateinit var deletion: HabitDeletionCoordinator
    @Inject lateinit var windows: DashboardTimeWindowTicker
    @Inject lateinit var lifecycle: HabitLifecycleCoordinator
    @Inject lateinit var timers: HabitTimerCoordinator
    @Inject lateinit var metrics: LinkedMetricCoordinator
    @Inject lateinit var counts: CountActionGate
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private var model: ViewModel? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private var dashboard: DashboardViewModel? = null
    private var nested: NestedViewModel? = null
    private lateinit var habit: HabitEntity
    private var originalZone: TimeZone? = null
    private val frames: StateFlow<DatabaseEpochValue<Map<String, CountButtonModel>>?> get() =
        dashboard?.countButtonFrame ?: requireNotNull(nested).countButtonFrame

    @Before fun connect() = runBlocking<Unit> {
        originalZone = TimeZone.getDefault()
        // Exercise original real clock/zone code with a genuine IANA zone whose noon is
        // safely away from this150s case's day boundary. Midnight semantics have separate gates.
        val now = Instant.now()
        val zone = ZoneId.getAvailableZoneIds().asSequence().filter { it.startsWith("Etc/GMT") }.sorted()
            .map(ZoneId::of).first { now.atZone(it).hour == 12 }
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        hilt.inject(); epochs.connect(); epochs.login()
    }
    @After fun finish() = runBlocking<Unit> {
        try {
            activity?.close(); activity = null
            model?.let { original ->
                val job = withContext(Dispatchers.Main.immediate) {
                    requireNotNull(original.viewModelScope.coroutineContext[Job]).also { models.clear() }
                }
                withTimeout(5_000) { job.join() }
            }
        } finally { originalZone?.let(TimeZone::setDefault) }
    }
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    private suspend fun open(child: Boolean) {
        val goal = HabitEntity(id = 801, name = "Count command parent", habitType = HabitType.GOAL,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, uuid = UUID.randomUUID().toString())
        habit = HabitEntity(id = 802, name = "Count command button", habitType = HabitType.COUNTING,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 10,
            uuid = UUID.randomUUID().toString(), parentHabitId = goal.uuid.takeIf { child })
        epochs.current { graph -> graph.database.withTransaction {
            val sql = graph.database.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            if (child) assertEquals(goal.id, graph.habitDao.insert(goal))
            assertEquals(habit.id, graph.habitDao.insert(habit))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        } }
        if (child) preferences.setExpandedParentUuids(setOf(goal.uuid))
        withContext(Dispatchers.Main.immediate) {
            model = if (child) NestedViewModel(context, epochs.host, completion, deletion, preferences, metrics, lifecycle, timers, counts)
                .also { nested = it }
            else DashboardViewModel(context, epochs.host, completion, deletion, windows, lifecycle, timers, preferences, metrics, counts)
                .also { dashboard = it }
            models.put("actual-count-screen", requireNotNull(model))
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { screen ->
            screen.setContent { MaterialTheme {
                val size = WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))
                if (child) NestedScreen(requireNotNull(nested), size, {})
                else DashboardScreen(requireNotNull(dashboard), size, {}, {}, {}, {})
            } }
        } }
        selection()
    }
    private suspend fun selection(): DatabaseObjectSelection = withTimeout(45_000) {
        dashboard?.let { visible ->
            val row = visible.habitsWithStats.first { rows -> rows.any { it.habit.uuid == habit.uuid && it.sourceEpoch?.isCurrentInMemory() == true && it.countActionIdentity != null } }
                .single { it.habit.uuid == habit.uuid }
            requireNotNull(row.renderedSelection())
        } ?: requireNotNull(nested).let { visible ->
            val row = visible.topLevelHabitsWithChildren.first { parents -> parents.flatMap { it.children }.any { it.habit.uuid == habit.uuid && it.sourceEpoch?.isCurrentInMemory() == true && it.countActionIdentity != null } }
                .flatMap { it.children }.single { it.habit.uuid == habit.uuid }
            requireNotNull(row.renderedSelection())
        }
    }
    private suspend fun accepted(kind: CountButtonModel.Kind): CountButtonModel {
        val frame = withTimeout(45_000) { frames.first { it?.value?.get(habit.uuid)?.kind == kind } }!!
        assertTrue(frame.publication.belongsTo(epochs.host)); assertTrue(frame.publication.isCurrentInMemory())
        val result = requireNotNull(frame.value[habit.uuid]); assertNotNull(result.commandId)
        withTimeout(45_000) { counts.busyHabitIds.first { habit.id !in it } }
        return result
    }
    private suspend fun status(id: String): CountCommandRecords.Status = epochs.current { graph ->
        val input = graph.database.withTransaction {
            graph.database.openHelper.writableDatabase.query("SELECT intentJson FROM count_command_intents WHERE commandId=?", arrayOf(id)).use {
                assertTrue(it.moveToFirst()); val bytes = it.getString(0).toByteArray(Charsets.UTF_8)
                assertFalse(it.moveToNext()); CountCommandRecords.Intent.decode(bytes, bytes.size) {}
            }
        }
        CountCommandStore(graph.database, epochs.tokens, epochs.sessions).resolve(CountCommandStore.Reference(input.commandId,
            input.inputHash, input.namespace.hash, input.namespace.dataset)).historical.status
    }
    private suspend fun assertNative(facts: Int, sources: Int) = epochs.current { graph ->
        assertEquals(facts, graph.database.completionDao().getAllCompletionsOnce().size)
        assertEquals(sources, graph.database.syncOutboxDao().getAll().count { it.recordType == "completion" })
        graph.database.openHelper.writableDatabase.query("SELECT count(*) FROM metric_logs").use {
            assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertFalse(it.moveToNext())
        }
    }
    private suspend fun decrement() {
        val original = selection()
        withContext(Dispatchers.Main.immediate) {
            dashboard?.decrementCount(habit.id, original) ?: requireNotNull(nested).decrementCount(habit.id, original)
        }
    }
    private suspend fun visibleAddThenOriginalUndo(child: Boolean) {
        open(child)
        val label = context.getString(R.string.action_check_in)
        compose.waitUntil(45_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).performClick()
        val added = accepted(CountButtonModel.Kind.SAVED_ADD)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.count_action_saved_add))).assertExists()
        assertNative(1, 1)
        assertEquals(CountCommandRecords.Delivery.CLOSED, status(requireNotNull(added.commandId)).delivery)
        decrement()
        val undo = accepted(CountButtonModel.Kind.SAVED_UNDO)
        assertNotEquals(added.commandId, undo.commandId)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.count_action_saved_undo))).assertExists()
        assertNative(0, 2)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, status(requireNotNull(undo.commandId)).delivery)
    }
    private suspend fun originalFloorNoEffect(child: Boolean) {
        open(child)
        val queued = epochs.current { it.database.syncOutboxDao().getAll() }
        decrement(); val noEffect = accepted(CountButtonModel.Kind.NO_EFFECT)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.count_action_no_effect))).assertExists()
        assertNative(0, 0)
        epochs.current { assertEquals(queued, it.database.syncOutboxDao().getAll()) }
        val stored = status(requireNotNull(noEffect.commandId))
        assertEquals(CountCommandRecords.Execution.NO_EFFECT, stored.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, stored.delivery)
    }
    @Test(timeout = 150_000) fun originalVisibleCallbackAfterCredentialReplacementCannotCreateOrDeliverACommand() = runBlocking<Unit> {
        open(false)
        val label = context.getString(R.string.action_check_in)
        compose.waitUntil(45_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        val original = requireNotNull(compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val oldSource = selection()
        compose.mainClock.autoAdvance = false
        try {
            epochs.sessions.exclusive {
                epochs.tokens.saveLoginSession("synthetic-replacement-access", "synthetic-replacement-refresh", "member",
                    "ac490000-0000-4000-8000-000000000001", false)
            }
            assertFalse(oldSource.publication.isCurrentInMemory())
            selection() // Actual new-source data, not a re-signed old callback.
            val before = epochs.exactMain()
            requireNotNull(activity).onActivity { assertFalse(it.isFinishing); assertFalse(it.isDestroyed) }
            val actionChildren = withContext(Dispatchers.Main.immediate) {
                val caller = requireNotNull(requireNotNull(model).viewModelScope.coroutineContext[Job])
                val previous = caller.children.toSet()
                assertTrue(original.invoke())
                caller.children.filter { it !in previous }.toList()
            }
            withTimeout(5_000) { actionChildren.forEach { it.join() } }
            assertTrue(habit.id !in counts.busyHabitIds.value)
            assertEquals(before, epochs.exactMain())
            assertNative(0, 0)
            epochs.current { graph -> graph.database.openHelper.writableDatabase.query("SELECT count(*) FROM count_command_intents").use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertFalse(it.moveToNext())
            } }
            assertNull(frames.value?.value?.get(habit.uuid))
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        compose.onNodeWithText(label).assertIsEnabled().performClick()
        val fresh = accepted(CountButtonModel.Kind.SAVED_ADD)
        assertNative(1, 1); assertEquals(CountCommandRecords.Delivery.CLOSED, status(requireNotNull(fresh.commandId)).delivery)
    }

    @Test(timeout = 150_000) fun dashboardRealButtonAndOriginalUndoConsumeTheirOwnCommittedModels() = runBlocking<Unit> { visibleAddThenOriginalUndo(false) }
    @Test(timeout = 150_000) fun nestedRealButtonAndOriginalUndoConsumeTheirOwnCommittedModels() = runBlocking<Unit> { visibleAddThenOriginalUndo(true) }
    @Test(timeout = 150_000) fun dashboardOriginalFloorNoEffectIsDeliveredWithoutClaimingAnAddition() = runBlocking<Unit> { originalFloorNoEffect(false) }
    @Test(timeout = 150_000) fun nestedOriginalFloorNoEffectIsDeliveredWithoutClaimingAnAddition() = runBlocking<Unit> { originalFloorNoEffect(true) }
}
