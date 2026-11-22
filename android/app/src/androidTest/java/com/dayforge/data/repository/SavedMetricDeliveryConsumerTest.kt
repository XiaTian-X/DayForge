package com.dayforge.data.repository

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.service.*
import com.dayforge.ui.components.MetricSubmissionPhase
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.FactMetricPrompt
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.metrics.LinkedMetricPromptState
import com.dayforge.ui.screens.dashboard.DashboardScreen
import com.dayforge.ui.screens.dashboard.DashboardTimeWindowTicker
import com.dayforge.ui.screens.dashboard.DashboardViewModel
import com.dayforge.ui.screens.habitdetail.HabitDetailScreen
import com.dayforge.ui.screens.habitdetail.HabitDetailViewModel
import com.dayforge.ui.screens.nested.NestedScreen
import com.dayforge.ui.screens.nested.NestedViewModel
import com.dayforge.widget.IsolatedWidgetRefreshRule
import com.dayforge.widget.checkin.MetricPromptActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Functional entry coverage. Actual Screens/Activity consume the original formal receipt;
 * only a fixed SQLite TEMP ABORT is injected. No UI phase, saved value or authority is seeded.
 * This is not a replacement for the original visible-journey latency/overlap acceptance. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SavedMetricDeliveryConsumerTest {
    private enum class Entry { DASHBOARD, NESTED, DETAIL, WIDGET }
    private enum class Action { CONTINUE, LATER }
    private val native = SavedMetricDeliveryConsumerNative()
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { it.nativeFactory = native }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var injectedCoordinator: LinkedMetricCoordinator
    private lateinit var coordinator: LinkedMetricCoordinator
    @Inject lateinit var sync: SyncManager
    @Inject lateinit var completion: HabitCompletionCoordinator
    @Inject lateinit var deletion: HabitDeletionCoordinator
    @Inject lateinit var windows: DashboardTimeWindowTicker
    @Inject lateinit var lifecycle: HabitLifecycleCoordinator
    @Inject lateinit var timers: HabitTimerCoordinator
    @Inject lateinit var counts: CountActionGate
    private val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var peer: FormalCountJourneyServer? = null
    private val models = ViewModelStore()
    private var model: ViewModel? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private data class Seen(val activity: ComponentActivity, val job: Job, val destroyed: AtomicBoolean = AtomicBoolean())
    private val seen = CopyOnWriteArrayList<Seen>()
    private val activityChanged = MutableStateFlow(0)
    private var callbacksInstalled = false
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity !is MetricPromptActivity) return
            seen += Seen(activity, requireNotNull(activity.lifecycleScope.coroutineContext[Job]))
            activityChanged.value++
        }
        override fun onActivityDestroyed(activity: Activity) {
            seen.singleOrNull { it.activity === activity }?.destroyed?.set(true)
            activityChanged.value++
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
    private lateinit var habit: HabitEntity
    private lateinit var source: WidgetFactSnapshot
    private lateinit var parent: CountCommandReceipt
    private lateinit var frame: DatabaseEpochValue<LinkedMetricPromptState?>
    private val prompt: FactMetricPrompt get() = requireNotNull(frame.value?.factPrompt)
    private fun label(id: Int) = app.getString(id)
    private val inPrompt get() = hasAnyAncestor(isDialog())
    private fun action(id: Int) = compose.onNode(hasText(label(id)) and hasClickAction() and inPrompt)
    private fun field(name: String, unit: String) = compose.onNode(hasText("$name ($unit)") and inPrompt)

    @After fun finish() = runBlocking<Unit> {
        var error: Throwable? = null
        suspend fun clean(block: suspend () -> Unit) {
            try { block() } catch (failure: Throwable) {
                if (error == null) error = failure else error!!.addSuppressed(failure)
            }
        }
        var activityJob: Job? = null
        clean { activity?.onActivity { activityJob = requireNotNull(it.lifecycleScope.coroutineContext[Job]) } }
        clean { activity?.close(); activity = null }
        clean { activityJob?.let { withTimeout(5_000) { it.join() } } }
        clean { withContext(Dispatchers.Main.immediate) { seen.forEach { if (!it.activity.isFinishing) it.activity.finish() } } }
        for (item in seen) clean { withTimeout(5_000) { item.job.join() } }
        clean { model?.let { original ->
            val job = withContext(Dispatchers.Main.immediate) {
                requireNotNull(original.viewModelScope.coroutineContext[Job]).also { models.clear() }
            }
            withTimeout(5_000) { job.join() }
        } }
        clean { if (callbacksInstalled) (app.applicationContext as Application).unregisterActivityLifecycleCallbacks(callbacks) }
        clean { peer?.close(); peer = null }
        error?.let { throw it }
    }

    private suspend fun prepare(entry: Entry) {
        hilt.inject(); coordinator = injectedCoordinator; epochs.connect(); epochs.login()
        val server = FormalCountJourneyServer(entry == Entry.NESTED).also { peer = it }
        preferences.setServerUrl(server.server.origin.toString()); preferences.setRemoteServerUrl(null)
        sync.sync().getOrThrow()
        epochs.current { graph ->
            habit = requireNotNull(graph.habitDao.getHabitByUuid(server.selected.uuid))
            assertNotNull(habit.appearance); assertNotNull(habit.planMetadata)
            assertEquals(1, graph.database.nextSyncStateDao().rows().single().challengeContract)
            preferences.setNeverAskAgain(requireNotNull(epochs.tokens.localCoreWriteAccess()).session, habit.uuid, false)
            parent = requireNotNull(graph.habitRepository.logCompletionWithReceipt(app, habit.id,
                authority = requireNotNull(graph.nextObjectEditor.habit(habit.id).authority), expectedHabitUuid = habit.uuid).command)
            source = graph.widgetFactReader.read(requireNotNull(graph.habitDao.getVisibleHabitById(habit.id)))
            preferences.addPendingMetricHabit(source.claim.session(), habit.uuid)
            if (entry != Entry.WIDGET)
                coordinator.showCompletionPrompt(source, coordinator.captureCompletionPrompt(), command = parent)
        }
        if (entry == Entry.NESTED) preferences.setExpandedParentUuids(setOf(server.goal.uuid))
        open(entry)
        // Wait on the actual consumer while allowing Compose to process pending frames.
        // The wall-clock deadline and original command identity remain unchanged.
        compose.waitUntil(45_000) {
            coordinator.postCheckInFrames.value?.value?.factPrompt?.command?.commandId == parent.commandId
        }
        frame = requireNotNull(coordinator.postCheckInFrames.value)
        assertEquals(2, requireNotNull(frame.value).linkedMetrics.size)
        assertTrue(frame.publication.belongsTo(epochs.host)); assertTrue(frame.publication.isCurrentInMemory())
        assertNull(requireNotNull(prompt.submissionPhase).value); assertNull(prompt.submittedCommand)
        compose.waitUntil(45_000) {
            compose.onAllNodes(hasText(label(R.string.post_checkin_record)) and hasClickAction() and inPrompt)
                .fetchSemanticsNodes().size == 1
        }
        action(R.string.post_checkin_record).assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    private suspend fun open(entry: Entry) {
        if (entry == Entry.WIDGET) {
            (app.applicationContext as Application).registerActivityLifecycleCallbacks(callbacks); callbacksInstalled = true
            val intent = MetricPromptActivity.createIntent(app, habit.id, habit.name,
                factClaim = source.claim, countCommand = parent.reference)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.startActivity(intent) }
            withTimeout(45_000) { activityChanged.first { seen.size == 1 } }
            // This unscoped dependency belongs to the real Activity, not the test injection.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                coordinator = (seen.single().activity as MetricPromptActivity).metricCoordinator
            }
            assertNotSame(injectedCoordinator, coordinator)
            return
        }
        withContext(Dispatchers.Main.immediate) {
            model = when (entry) {
                Entry.DASHBOARD -> DashboardViewModel(app, epochs.host, completion, deletion, windows, lifecycle, timers, preferences, coordinator, counts)
                Entry.NESTED -> NestedViewModel(app, epochs.host, completion, deletion, preferences, coordinator, lifecycle, timers, counts)
                Entry.DETAIL -> HabitDetailViewModel(app, epochs.host, preferences, counts, coordinator)
                Entry.WIDGET -> error("Widget uses its actual Activity")
            }
            models.put("saved-metric-consumer", requireNotNull(model))
        }
        val selected = if (entry == Entry.DETAIL) epochs.habitSelection(habit.id) else null
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { screen ->
            screen.setContent { MaterialTheme {
                val size = WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))
                when (entry) {
                    Entry.DASHBOARD -> DashboardScreen(model as DashboardViewModel, size, {}, {}, {}, {})
                    Entry.NESTED -> NestedScreen(model as NestedViewModel, size, {})
                    Entry.DETAIL -> HabitDetailScreen(habit.id, {}, viewModel = model as HabitDetailViewModel, sourceSelection = selected)
                    Entry.WIDGET -> error("Widget uses its actual Activity")
                }
            } }
        } }
    }

    private suspend fun assertSameOwner(database: HabitDatabase, runtime: CountTrustRuntime, creates: Int) {
        assertTrue(frame.publication.isCurrentInMemory()); assertTrue(frame.publication.belongsTo(epochs.host))
        assertSame(frame, coordinator.postCheckInFrames.value)
        assertEquals(creates, epochs.realRoomCreates)
        epochs.current { graph ->
            assertSame(database, graph.database); assertSame(runtime, CountTrustRuntime.forDatabase(graph.database))
            assertFalse(runtime.isRetired); assertTrue(runtime.retirementReasons.isEmpty())
        }
    }

    private suspend fun originalGroup(child: CountCommandReference, pending: Boolean) = epochs.current { graph ->
        val store = CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
        val p = store.resolve(parent.reference.native()).historical
        val c = store.resolve(child.native()).historical
        assertEquals(child.commandId, p.status.child); assertEquals(parent.commandId, c.status.parent)
        assertEquals(CountCommandRecords.Execution.APPLIED, p.status.execution)
        assertEquals(CountCommandRecords.Execution.APPLIED, c.status.execution)
        assertEquals(pending, p.status.pending); assertEquals(pending, c.status.pending)
        assertEquals(pending, graph.countCommandPendingRepository.isPending(parent.reference))
        assertEquals(pending, graph.countCommandPendingRepository.isPending(child))
        assertEquals(if (pending) CountCommandRecords.Delivery.METRICS_COMMITTED else CountCommandRecords.Delivery.DELIVERED, p.status.delivery)
    }

    private suspend fun runScene(entry: Entry, decision: Action) {
        prepare(entry)
        val (database, runtime) = epochs.current { it.database to requireNotNull(CountTrustRuntime.forDatabase(it.database)) }
        val creates = epochs.realRoomCreates
        assertFalse(runtime.isRetired); assertTrue(runtime.retirementReasons.isEmpty())
        native.expectParent(parent.reference)
        val infos = requireNotNull(frame.value).linkedMetrics
        val values = infos.mapIndexed { index, info -> MetricValueInput(info.metricId, 41.125 + index, "") }
        for ((index, info) in infos.withIndex()) field(info.metricName, info.unit).performTextReplacement(values[index].value.toString())
        compose.onNode(isToggleable() and inPrompt).performClick()
        action(R.string.post_checkin_record).performClick()
        withTimeout(45_000) { native.failed.await() }
        withTimeout(45_000) { requireNotNull(prompt.submissionPhase).first { it == MetricSubmissionPhase.SAVED_PENDING } }
        val child = requireNotNull(prompt.submittedCommand).reference
        native.requireRollback(parent.reference, child)
        assertSameOwner(database, runtime, creates)
        val saved = requireNotNull(prompt.savedSubmissionForDisplay())
        assertEquals(values, saved.values); assertTrue(saved.neverAskAgain)
        assertFalse(preferences.getNeverAskAgain(source.claim.session(), habit.uuid).first())
        originalGroup(child, pending = true)
        // Original preference publication precedes T2. A T2 rollback cannot undo DataStore.
        val pendingHint = preferences.pendingMetricHabitUuids(source.claim.session(), setOf(habit.uuid)).first()
        assertFalse(habit.uuid in pendingHint)
        compose.waitUntil(45_000) {
            compose.onAllNodes(hasText(label(R.string.metric_submission_continue)) and hasClickAction() and isEnabled() and inPrompt)
                .fetchSemanticsNodes().size == 1
        }
        for ((index, info) in infos.withIndex())
            field(info.metricName, info.unit).assertTextContains(values[index].value.toString()).assertIsNotEnabled()
        compose.onAllNodes(hasText(label(R.string.post_checkin_record)) and hasClickAction() and inPrompt).assertCountEquals(0)
        epochs.current { graph -> graph.database.withTransaction {
            graph.database.openHelper.writableDatabase.query("SELECT metricId,value,unit,note FROM metric_logs ORDER BY metricId").use { cursor ->
                val ordered = values.sortedBy { it.metricId }
                for (value in ordered) {
                    assertTrue(cursor.moveToNext()); assertEquals(value.metricId, cursor.getLong(0))
                    assertEquals(value.value.toRawBits(), cursor.getDouble(1).toRawBits())
                    assertEquals(infos.single { it.metricId == value.metricId }.unit, cursor.getString(2))
                    assertEquals(value.note, cursor.getString(3))
                }
                assertFalse(cursor.moveToNext())
            }
        } }
        compose.onNode(isToggleable() and inPrompt).assertIsNotEnabled().assertIsOn()
        action(R.string.metric_submission_continue).assertIsEnabled().assertIsDisplayed()
        action(R.string.metric_submission_later).assertIsEnabled().assertIsDisplayed()
        val before = epochs.exactMain()
        if (decision == Action.CONTINUE) {
            action(R.string.metric_submission_continue).performClick()
            withTimeout(45_000) { native.trace.delivered.await() }
            withTimeout(45_000) { preferences.getNeverAskAgain(source.claim.session(), habit.uuid).first { it } }
            originalGroup(child, pending = false)
            val after = epochs.exactMain()
            for (table in listOf("completions", "metric_logs", "sync_outbox", "next_request_origins", "count_command_intents", "count_command_results"))
                assertEquals("Continuation changed original $table", before.contents.getValue("tables").jsonObject.getValue(table),
                    after.contents.getValue("tables").jsonObject.getValue(table))
            assertEquals(1, native.trace.ends.count { it.kind == CountJourneyNativeTrace.Kind.T2 })
        } else {
            action(R.string.metric_submission_later).performClick()
        }
        if (entry == Entry.WIDGET) {
            val item = seen.single()
            withTimeout(45_000) { activityChanged.first { item.destroyed.get() } }
            withTimeout(5_000) { item.job.join() }
        } else {
            withTimeout(45_000) { coordinator.postCheckInFrames.first { it?.value == null } }
        }
        if (entry != Entry.WIDGET)
            compose.onAllNodes(hasText(label(R.string.metric_submission_continue)) and hasClickAction() and inPrompt).assertCountEquals(0)
        if (decision == Action.LATER) {
            assertEquals(before, epochs.exactMain())
            assertFalse(preferences.getNeverAskAgain(source.claim.session(), habit.uuid).first())
            assertEquals(pendingHint, preferences.pendingMetricHabitUuids(source.claim.session(), setOf(habit.uuid)).first())
            originalGroup(child, pending = true)
            assertFalse(native.trace.delivered.isCompleted)
        }
        assertEquals(1, native.trace.ends.count { it.kind == CountJourneyNativeTrace.Kind.METRIC_T1 })
        assertEquals(1, native.trace.ends.count { it.kind == CountJourneyNativeTrace.Kind.T0 })
        assertTrue(native.trace.observationFailures.isEmpty())
        native.requireHealthy()
    }

    @Test(timeout = 150_000) fun dashboardContinuesOriginalSavedMetricChild() = runBlocking { runScene(Entry.DASHBOARD, Action.CONTINUE) }
    @Test(timeout = 150_000) fun dashboardDefersOriginalSavedMetricChild() = runBlocking { runScene(Entry.DASHBOARD, Action.LATER) }
    @Test(timeout = 150_000) fun nestedContinuesOriginalSavedMetricChild() = runBlocking { runScene(Entry.NESTED, Action.CONTINUE) }
    @Test(timeout = 150_000) fun nestedDefersOriginalSavedMetricChild() = runBlocking { runScene(Entry.NESTED, Action.LATER) }
    @Test(timeout = 150_000) fun detailContinuesOriginalSavedMetricChild() = runBlocking { runScene(Entry.DETAIL, Action.CONTINUE) }
    @Test(timeout = 150_000) fun detailDefersOriginalSavedMetricChild() = runBlocking { runScene(Entry.DETAIL, Action.LATER) }
    @Test(timeout = 150_000) fun widgetContinuesOriginalSavedMetricChild() = runBlocking { runScene(Entry.WIDGET, Action.CONTINUE) }
    @Test(timeout = 150_000) fun widgetDefersOriginalSavedMetricChild() = runBlocking { runScene(Entry.WIDGET, Action.LATER) }
}
