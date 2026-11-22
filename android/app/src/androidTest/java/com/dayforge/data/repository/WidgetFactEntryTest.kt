package com.dayforge.data.repository

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.checkin.GoalCompletionActivity
import com.dayforge.widget.checkin.WidgetFactActionActivity
import com.dayforge.widget.checkin.actionIntent
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.focus.FocusWidget
import io.mockk.*
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Hilt/Glance state, foreground Activity and Compose confirmation on physical testbed. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WidgetFactEntryTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    private val onceUiProbe = OnceUiCommitProbe()
    @get:Rule(order = -1) val onceUiRule: org.junit.rules.TestRule = onceUiProbe.widgetRule(storage)
    // Keep the Activity's real Main dispatcher and frame clock. This rule only
    // records lifecycle ownership and drives the actual platform window.
    private val platform = WidgetFactPlatformUi()
    @get:Rule(order = 3) val platformRule: org.junit.rules.TestRule = platform
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var reader: WidgetFactReader
    @Inject lateinit var publisher: WidgetDisplayPublisher
    @Inject lateinit var legacy: LegacyWidgetFactWorkflow
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
    private val boundWidgets = mutableListOf<androidx.glance.GlanceId>()
    private fun id(n: Int) = "ad310000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#123456", "theme")

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        hilt.inject()
        sessions.exclusive {
            tokens.clearTokens()
            tokens.saveLoginSession("synthetic-widget", "synthetic-refresh", "member", id(1), false)
        }
    }

    @After fun cleanup() = runBlocking<Unit> {
        // Include already-destroyed Activities and automatic MetricPrompt successors:
        // their original Jobs were captured at creation, not rediscovered at teardown.
        try { platform.closeAndJoin() }
        catch (error: Throwable) {
            // Unknown observer/Job exit must block the existing outer Host/DB cleanup.
            PhysicalDatabaseRule.preserveProcessCleanupFailure(error)
            throw error
        }
        boundWidgets.forEach { com.dayforge.widget.clearWidgetSelection(app, it) }
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
        com.dayforge.reminder.HabitReminderScheduler.cancelAllReminders(app)
        com.dayforge.widget.FocusWidgetAlarmScheduler.cancelScheduledRefresh(app)

    }

    private suspend fun create(type: HabitType = HabitType.CHECK_IN, once: Boolean = false, cycles: Int? = null): Long =
        habits.createHabit("Widget production $type $once", "", type, 0, "#123456",
            if (once) HabitSchedule.Once() else HabitSchedule.Daily, targetValue = if (type == HabitType.COUNTING) 2 else 1,
            bestTime = if (type == HabitType.COUNTING || cycles != null) 540 else null,
            isCountdown = type == HabitType.COUNTING, targetCycles = cycles, failMode = FailMode.LOOSE,
            appearance = appearance(if (once) "task.custom" else "habit.custom"),
            completionPolicy = if (once) "one_and_done" else "recurring", creationAuthority = creator.capture(),
            context = app.takeIf { cycles != null })
    private suspend fun claim(id: Long) = reader.read(requireNotNull(habits.getHabitById(id))).claim
    private suspend fun await(stage: String = "widget state", block: suspend () -> Boolean) {
        try { withTimeout(5_000) { while (!block()) delay(20) } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out after 5000 ms waiting for $stage", error)
        }
    }
    private fun glanceId() = requireNotNull(GlanceAppWidgetManager(app).getGlanceIdBy(Intent().putExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, ((System.nanoTime() and 0x1fffffff) + 1).toInt())))
    private suspend fun bind(id: androidx.glance.GlanceId, habitId: Long) {
        boundWidgets += id
        com.dayforge.widget.bindWidgetSelection(app, id, habitId)
    }

    @Test fun actualGlanceStatesCarryTheRenderedIdentityAndFrozenCountRule() = runBlocking<Unit> {
        val check = create()
        val checkId = glanceId()
        bind(checkId, check)
        CheckInWidget.refreshWidgetData(app, checkId, check)
        val state = CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, checkId)
        val checkClaim = WidgetFactClaim.decode(requireNotNull(state[CheckInWidget.ACTION_PROOF_KEY]))
        assertEquals(check, checkClaim.habitId)
        assertEquals(false, state[CheckInWidget.IS_COMPLETED_KEY])
        habits.updateIsActive(check, false, app)
        val count = create(HabitType.COUNTING)
        habits.logCompletion(app, count)
        val ticket = habits.getHabitForEditing(count)
        habits.updateHabit(ticket.value!!.copy(targetValue = 20, isCountdown = false), editAuthority = ticket.authority)
        val countId = glanceId()
        val focusId = glanceId()
        bind(countId, count)
        CountingWidget.refreshWidgetData(app, countId, count)
        FocusWidget.refreshWidgetData(app, focusId)
        val counter = CountingWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, countId)
        val focus = FocusWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, focusId)
        assertEquals(2, counter[CountingWidget.TARGET_VALUE_KEY])
        assertEquals(true, counter[CountingWidget.IS_COUNTDOWN_KEY])
        assertEquals(1L, counter[CountingWidget.ACTUAL_COUNT_KEY])
        val counterClaim = WidgetFactClaim.decode(requireNotNull(counter[CountingWidget.ACTION_PROOF_KEY]))
        assertEquals(count, focus[FocusWidget.PRIMARY_HABIT_ID_KEY])
        assertEquals(counterClaim, WidgetFactClaim.decode(requireNotNull(focus[FocusWidget.FACT_ACTION_PROOF_KEY])))
        assertNotEquals(checkClaim.habitUuid, counterClaim.habitUuid)
    }

    @Test fun realWidgetActivityCompletesAndRejectsStaleClickWithoutAnotherToggle() = runBlocking<Unit> {
        val task = create(once = true)
        val widgetId = glanceId()
        bind(widgetId, task)
        suspend fun assertTaskDisplay(completed: Boolean) {
            CheckInWidget.refreshWidgetData(app, widgetId, task)
            val state = CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, widgetId)
            assertEquals(completed, state[CheckInWidget.IS_COMPLETED_KEY])
            assertEquals(habits.getOneTimeStatus(task).canChange, state[CheckInWidget.IS_CHECKIN_ALLOWED_KEY])
            assertEquals(false, state[CheckInWidget.READ_FAILED_KEY])
            assertEquals(task, WidgetFactClaim.decode(requireNotNull(state[CheckInWidget.ACTION_PROOF_KEY])).habitId)
        }
        assertTaskDisplay(false)
        val before = claim(task)
        val host = dagger.hilt.android.EntryPointAccessors.fromApplication(app,
            com.dayforge.data.local.DatabaseEpochFixtureEntryPoint::class.java).host()
        onceUiProbe.bindWidgetHost(host) // Memory identity only; does not open or replace Room.
        platform.onceDiagnostic = onceUiProbe
        val originalWindows = OnceOriginalUiWindows(
            listOf("initial_toggle", "retained_stale_claim", "fresh_undo"))
        suspend fun launch(claim: WidgetFactClaim, phase: String) {
            val diagnosticPhase = when (phase) {
                "initial_toggle" -> OnceUiCommitProbe.Phase.WIDGET_INITIAL_TOGGLE
                "retained_stale_claim" -> OnceUiCommitProbe.Phase.WIDGET_STALE_CLAIM
                "fresh_undo" -> OnceUiCommitProbe.Phase.WIDGET_FRESH_UNDO
                else -> error("ONCE_UI_PROBE_UNKNOWN_PHASE")
            }
            onceUiProbe.arm(diagnosticPhase, task, claim.habitUuid,
                if (diagnosticPhase == OnceUiCommitProbe.Phase.WIDGET_FRESH_UNDO)
                    requireNotNull(claim.oneTimeState).completionEventUuid else null)
            ActivityScenario.launch<WidgetFactActionActivity>(claim.actionIntent(app, "toggle")).use { scenario ->
                try {
                    originalWindows.await(phase) {
                        while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) delay(20)
                    }
                    onceUiProbe.emit(OnceUiCommitProbe.Stage.WINDOW_SATISFIED)
                } catch (failure: Throwable) {
                    onceUiProbe.emit(OnceUiCommitProbe.Stage.ORIGINAL_WINDOW_FAILED)
                    throw failure // Preserve functional failure; never retry or replay the command.
                }
            }
        }
        try {
        launch(before, "initial_toggle")
        assertTrue(habits.getOneTimeStatus(task).completed)
        assertTaskDisplay(true)
        val queued = db.syncOutboxDao().getAll()
        launch(before, "retained_stale_claim")
        assertEquals(queued, db.syncOutboxDao().getAll())
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
        launch(claim(task), "fresh_undo")
        assertFalse(habits.getOneTimeStatus(task).completed)
        assertTaskDisplay(false)
        assertEquals(2, db.completionDao().getByHabitOnce(task).size)
        assertNotNull(habits.getHabitById(task))
        onceUiProbe.emit(OnceUiCommitProbe.Stage.ORIGINAL_TAIL_REACHED)
        onceUiProbe.requireOriginalTailObserved()
        originalWindows.assertOriginalBudgetsAfterFunctionalTail()
        } finally { onceUiProbe.emit(OnceUiCommitProbe.Stage.TEST_BODY_EXIT) }
    }

    @Test fun realFactConsumersHideCorruptSourcesAndExplicitRetryReloadsBeforeRendering() = runBlocking<Unit> {
        assertSame(publisher, com.dayforge.di.WidgetEntryPoint.from(app).displayPublisher())
        val check = create()
        val count = create(HabitType.COUNTING)
        habits.logCompletion(app, check); habits.logCompletion(app, count)
        val checkId = glanceId(); val countId = glanceId(); val focusId = glanceId()
        bind(checkId, check); bind(countId, count)
        suspend fun refresh() {
            CheckInWidget.refreshWidgetData(app, checkId, check)
            CountingWidget.refreshWidgetData(app, countId, count)
            FocusWidget.refreshWidgetData(app, focusId)
        }
        suspend fun assertDisplay(failed: Boolean) {
            val checkState = CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, checkId)
            val countState = CountingWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, countId)
            val focusState = FocusWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, focusId)
            assertEquals(failed, checkState[CheckInWidget.READ_FAILED_KEY])
            assertEquals(failed, countState[CountingWidget.READ_FAILED_KEY])
            assertEquals(failed, focusState[FocusWidget.READ_FAILED_KEY])
            if (failed) {
                assertEquals(false, checkState[CheckInWidget.DATA_LOADED_KEY])
                assertEquals(false, countState[CountingWidget.DATA_LOADED_KEY])
                assertEquals(false, focusState[FocusWidget.DATA_LOADED_KEY])
                assertNull(checkState[CheckInWidget.ACTION_PROOF_KEY]); assertNull(countState[CountingWidget.ACTION_PROOF_KEY])
                assertNull(focusState[FocusWidget.FACT_ACTION_PROOF_KEY]); assertNull(focusState[FocusWidget.TIMER_ACTION_PROOF_KEY])
            } else {
                assertEquals(true, checkState[CheckInWidget.IS_COMPLETED_KEY])
                assertEquals(1L, countState[CountingWidget.ACTUAL_COUNT_KEY])
                assertEquals(2, countState[CountingWidget.TARGET_VALUE_KEY])
                assertEquals(true, countState[CountingWidget.IS_COUNTDOWN_KEY])
                assertEquals(count, focusState[FocusWidget.PRIMARY_HABIT_ID_KEY])
                assertNotNull(checkState[CheckInWidget.ACTION_PROOF_KEY]); assertNotNull(countState[CountingWidget.ACTION_PROOF_KEY])
                assertEquals(countState[CountingWidget.ACTION_PROOF_KEY], focusState[FocusWidget.FACT_ACTION_PROOF_KEY])
            }
        }
        refresh(); assertDisplay(false)
        val retryProof = CountReadQualificationFixtureAssertions(db, tokens, sessions)
        val beforeCorruption = retryProof.capture()
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=CAST(value AS BLOB)")
        val corrupted = db.withTransaction { nextRestartDatabaseProof(db) }
        refresh(); assertDisplay(true)
        assertEquals(corrupted, db.withTransaction { nextRestartDatabaseProof(db) })
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=1")
        val healthy = retryProof.beginAfterActualRepair(beforeCorruption)
        // Only host submission is isolated: callback, Hilt reader, Room, theme and Glance
        // DataStore are real. Synthetic Glance IDs are not installed launcher instances.
        mockkConstructor(CheckInWidget::class, CountingWidget::class, FocusWidget::class)
        try {
            coEvery { anyConstructed<CheckInWidget>().update(app, checkId) } coAnswers {
                assertEquals(false, CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, checkId)[CheckInWidget.READ_FAILED_KEY])
            }
            coEvery { anyConstructed<CountingWidget>().update(app, countId) } coAnswers {
                assertEquals(false, CountingWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, countId)[CountingWidget.READ_FAILED_KEY])
            }
            coEvery { anyConstructed<FocusWidget>().update(app, focusId) } coAnswers {
                assertEquals(false, FocusWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, focusId)[FocusWidget.READ_FAILED_KEY])
            }
            val retry = com.dayforge.widget.timer.WidgetTimerRefreshCallback()
            for ((type, id) in listOf("checkin" to checkId, "counting" to countId, "focus" to focusId)) {
                retry.onAction(app, id, androidx.glance.action.actionParametersOf(
                    androidx.glance.action.ActionParameters.Key<String>("widget") to type))
            }
            assertDisplay(false)
            coVerify(exactly = 1) { anyConstructed<CheckInWidget>().update(app, checkId) }
            coVerify(exactly = 1) { anyConstructed<CountingWidget>().update(app, countId) }
            coVerify(exactly = 1) { anyConstructed<FocusWidget>().update(app, focusId) }
            // First retry is observed before any warming. Only a fully authenticated,
            // exact root/node issuance may differ; every other native/schema cell is fixed.
            val issued = retryProof.verifyFirstRetry(healthy)
            val warmHash = db.withTransaction { nextRestartDatabaseProof(db) }
            for ((type, id) in listOf("checkin" to checkId, "counting" to countId, "focus" to focusId)) {
                retry.onAction(app, id, androidx.glance.action.actionParametersOf(
                    androidx.glance.action.ActionParameters.Key<String>("widget") to type))
            }
            assertDisplay(false)
            coVerify(exactly = 2) { anyConstructed<CheckInWidget>().update(app, checkId) }
            coVerify(exactly = 2) { anyConstructed<CountingWidget>().update(app, countId) }
            coVerify(exactly = 2) { anyConstructed<FocusWidget>().update(app, focusId) }
            retryProof.assertUnchanged(issued)
            assertEquals(warmHash, db.withTransaction { nextRestartDatabaseProof(db) })
        } finally { unmockkConstructor(CheckInWidget::class, CountingWidget::class, FocusWidget::class) }
    }

    @Test fun actualFactAndFocusThemeFailuresPropagateWithoutChangingDisplayOrBusiness() = runBlocking<Unit> {
        val check = create(); val count = create(HabitType.COUNTING)
        val checkId = glanceId(); val countId = glanceId(); val focusId = glanceId()
        bind(checkId, check); bind(countId, count)
        CheckInWidget.refreshWidgetData(app, checkId, check)
        CountingWidget.refreshWidgetData(app, countId, count)
        FocusWidget.refreshWidgetData(app, focusId)
        val checkState = CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, checkId)
        val countState = CountingWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, countId)
        val focusState = FocusWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, focusId)
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        val store = com.dayforge.data.local.DataStoreProvider.get(app)
        val key = androidx.datastore.preferences.core.stringPreferencesKey("appearance_theme_selection_v1")
        val selection = requireNotNull(store.data.first()[key])
        val themes = com.dayforge.di.DeviceThemeControllerEntryPoint.from(app).themeController()
        val original = themes.current()
        try {
            store.edit { it[key] = "damaged fact publication theme" }
            withTimeout(5000) { themes.state.first { it is com.dayforge.data.appearance.DeviceThemeLoadState.Failed } }
            for (refresh in listOf<suspend () -> Unit>(
                { CheckInWidget.refreshWidgetData(app, checkId, check) },
                { CountingWidget.refreshWidgetData(app, countId, count) },
                { FocusWidget.refreshWidgetData(app, focusId) }
            )) {
                assertTrue(runCatching { refresh() }.exceptionOrNull() is com.dayforge.data.appearance.ThemeSelectionException)
                withTimeout(5000) { sessions.exclusive { assertEquals(id(1), tokens.authenticationSnapshot()!!.session.userId) } }
            }
            assertEquals(checkState, CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, checkId))
            assertEquals(countState, CountingWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, countId))
            assertEquals(focusState, FocusWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, focusId))
            assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        } finally {
            store.edit { it[key] = selection }; themes.retry()
            withTimeout(5000) { themes.state.first { it is com.dayforge.data.appearance.DeviceThemeLoadState.Ready && it.theme.saved == original.saved } }
        }
        CheckInWidget.refreshWidgetData(app, checkId, check)
        CountingWidget.refreshWidgetData(app, countId, count)
        FocusWidget.refreshWidgetData(app, focusId)
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
    }

    @Test fun realGoalConfirmationUsesBoundFactAndClosesWhenAccountChanges() = runBlocking<Unit> {
        // This method drives real Host Activities. Its setup and independent oracles use
        // that same current graph, with a short CONTROL lease for each operation only.
        val host = dagger.hilt.android.EntryPointAccessors.fromApplication(app,
            com.dayforge.data.local.DatabaseEpochFixtureEntryPoint::class.java).host()
        val fixtureDatabase = db
        val hostDatabase = host.withGraph(com.dayforge.data.local.DatabaseRuntimeHost.Work.CONTROL) { it.database }
        val hostRuntime = requireNotNull(com.dayforge.data.local.CountTrustRuntime.forDatabase(hostDatabase))
        // Retained only for identity/diagnostic reads, never for business access outside a lease.
        var observedDatabase = hostDatabase
        val fixtureRuntime = requireNotNull(com.dayforge.data.local.CountTrustRuntime.forDatabase(fixtureDatabase))
        assertNotSame("The fixed fixture Room must not supply this method's writers", fixtureDatabase, hostDatabase)
        assertNotSame(fixtureRuntime, hostRuntime)
        suspend fun <T> currentGraph(phase: String,
            block: suspend (com.dayforge.data.local.DatabaseEpochGraph) -> T): T =
            host.withGraph(com.dayforge.data.local.DatabaseRuntimeHost.Work.CONTROL) { graph ->
                assertSame("$phase Room changed", hostDatabase, graph.database)
                assertSame("$phase runtime changed", hostRuntime,
                    com.dayforge.data.local.CountTrustRuntime.forDatabase(graph.database))
                assertFalse("$phase owner retired before operation", hostRuntime.isRetired)
                block(graph).also { assertFalse("$phase owner retired after operation", hostRuntime.isRetired) }
            }
        // Fixed test-only memory samples: no SQL, authority probe, new wait or UI ordering.
        val runtimeObservations = arrayListOf<String>()
        var runtimePhase = "entry"
        fun observeRuntime(phase: String) {
            runtimePhase = phase
            val sample = try {
                val originalDatabase = observedDatabase
                val runtime = com.dayforge.data.local.CountTrustRuntime.forDatabase(originalDatabase)
                val beginJournalFailure = runtime?.beginJournalFailureDiagnostic
                "phase=$phase at_ns=${android.os.SystemClock.elapsedRealtimeNanos()} " +
                    "host=${System.identityHashCode(host)} fixture_db=${System.identityHashCode(fixtureDatabase)} " +
                    "fixture_runtime=${System.identityHashCode(fixtureRuntime)} " +
                    "db=${System.identityHashCode(originalDatabase)} runtime=${runtime?.let(System::identityHashCode)} " +
                    "generation=${runtime?.generation} retired=${runtime?.isRetired} " +
                    "reasons=${runtime?.retirementReasons?.map { it.name }?.sorted()?.joinToString(",")} " +
                    "begin_journal_write=${beginJournalFailure?.write} " +
                    "begin_journal_retired_trim=${beginJournalFailure?.retiredTrim} " +
                    "begin_journal_exception=${beginJournalFailure?.exceptionClass} " +
                    "begin_journal_invariant=${beginJournalFailure?.invariant?.name}"
            } catch (diagnostic: Exception) {
                "phase=$phase sampling_error=${diagnostic.javaClass.name}"
            }
            // Eleven fixed normal sites plus one failure site, no per-frame/history collection.
            runtimeObservations += sample
            try { android.util.Log.i("WidgetGoalRuntime", sample) } catch (_: Exception) { }
        }
        observeRuntime("entry")
        try {
            val rowId = currentGraph("create") { graph ->
                graph.habitRepository.createHabit("Widget production ${HabitType.CHECK_IN} false", "", HabitType.CHECK_IN, 0, "#123456",
                    HabitSchedule.Daily, targetValue = 1, bestTime = 540, isCountdown = false,
                    targetCycles = 1, failMode = FailMode.LOOSE, appearance = appearance("habit.custom"),
                    completionPolicy = "recurring", creationAuthority = graph.nextObjectCreator.capture(), context = app)
            }
            suspend fun currentClaim(phase: String) = currentGraph(phase) { graph ->
                graph.widgetFactReader.read(requireNotNull(graph.habitRepository.getHabitById(rowId))).claim
            }
            observeRuntime("created")
            fun reminder() = android.app.PendingIntent.getBroadcast(app, 0,
                com.dayforge.reminder.AndroidReminderAlarms.alarmIntent(app, rowId),
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
            assertNotNull(reminder())
            currentGraph("completion") { it.habitRepository.logCompletion(app, rowId) }
            observeRuntime("completion_saved")
            val before = currentClaim("initial_claim")
            observeRuntime("initial_claim")
            val row = currentGraph("initial_row") { it.habitRepository.getHabitById(rowId)!! }
            fun intent(claim: WidgetFactClaim) = GoalCompletionActivity.createIntent(app, rowId, row.name, 1, 1, claim)
            ActivityScenario.launch<GoalCompletionActivity>(intent(before)).use { scenario ->
                val confirm = app.getString(R.string.action_confirm_complete)
                platform.awaitConfirm(scenario, confirm)
                scenario.onActivity { assertSame("Goal Activity must use the prepared Host", host, it.host) }
                currentGraph("concurrent_edit") { graph ->
                    val ticket = graph.habitRepository.getHabitForEditing(rowId)
                    graph.habitRepository.updateHabit(ticket.value!!.copy(name = "Changed goal-plan display"), editAuthority = ticket.authority)
                }
                val queues = currentGraph("first_queue_before") { it.syncOutboxDao.getAll() }
                platform.tapConfirm(scenario, confirm)
                await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                assertEquals(queues, currentGraph("first_queue_after") { it.syncOutboxDao.getAll() })
            }
            observeRuntime("first_goal_closed")
            assertTrue(currentGraph("active_oracle") { it.habitRepository.getHabitById(rowId)!!.isActive })
            assertNotNull(reminder())
            ActivityScenario.launch<GoalCompletionActivity>(intent(currentClaim("second_claim"))).use { scenario ->
                val confirm = app.getString(R.string.action_confirm_complete)
                platform.awaitConfirm(scenario, confirm)
                scenario.onActivity { assertSame("Goal Activity must use the prepared Host", host, it.host) }
                platform.tapConfirm(scenario, confirm)
                await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            }
            observeRuntime("second_goal_closed")
            assertFalse(currentGraph("completed_oracle") { it.habitRepository.getHabitById(rowId)!!.isActive })
            assertNull(reminder())
            observeRuntime("before_reactivate")
            currentGraph("reactivate") { it.habitRepository.updateIsActive(rowId, true, app) }
            observeRuntime("after_reactivate")
            assertNotNull(reminder())
            val current = currentClaim("third_claim")
            val queues = currentGraph("third_queue_before") { it.syncOutboxDao.getAll() }
            observeRuntime("before_third_goal")
            ActivityScenario.launch<GoalCompletionActivity>(intent(current)).use { scenario ->
                val confirm = app.getString(R.string.action_confirm_complete)
                platform.awaitConfirm(scenario, confirm)
                scenario.onActivity { assertSame("Goal Activity must use the prepared Host", host, it.host) }
                sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(2), false) }
                observeRuntime("after_account_change")
                await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            }
            // Account changes may legitimately replace a Host graph. Keep the original
            // fact/outbox oracle on the current graph without requiring the previous owner.
            host.withGraph(com.dayforge.data.local.DatabaseRuntimeHost.Work.CONTROL) { graph ->
                observedDatabase = graph.database
                assertTrue(graph.habitRepository.getHabitById(rowId)!!.isActive)
                assertEquals(queues, graph.syncOutboxDao.getAll())
                observeRuntime("original_tail_reached")
            }
        } catch (error: Throwable) {
            val failedAfter = runtimePhase
            try {
                observeRuntime("failure")
                error.addSuppressed(AssertionError("WIDGET_GOAL_RUNTIME failure_after=$failedAfter " +
                    runtimeObservations.joinToString(" | ")))
            } catch (diagnostic: Throwable) {
                if (diagnostic !== error) error.addSuppressed(diagnostic)
            }
            throw error
        }
    }

    @Test fun legacyFocusPublishesOriginalFactAndDropsItAfterAuthenticationChanges() = runBlocking<Unit> {
        val rowId = habits.createHabit("Legacy focus count", "", HabitType.COUNTING, 0, "#123456",
            HabitSchedule.Daily, targetValue = 3, isCountdown = true, bestTime = java.time.LocalTime.now().let {
                (it.hour * 60 + it.minute).toLong()
            })
        habits.logCompletion(app, rowId)
        val focusId = glanceId()
        boundWidgets += focusId
        FocusWidget.refreshWidgetData(app, focusId)
        val state = FocusWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, focusId)
        val original = LegacyWidgetFactClaim.decode(requireNotNull(state[FocusWidget.LEGACY_FACT_ACTION_PROOF_KEY]))
        assertEquals(rowId, original.identity.habitId)
        assertEquals(requireNotNull(habits.getHabitById(rowId)).uuid, original.identity.habitUuid)
        assertEquals(1L, original.todayQuantity)
        assertEquals(original.todayQuantity, state[FocusWidget.PRIMARY_ACTUAL_COUNT_KEY])
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        sessions.exclusive { tokens.saveLoginSession("renewed", "renewed-refresh", "member", id(1), false) }
        com.dayforge.widget.checkin.CheckInActionCallback().onAction(app, focusId,
            androidx.glance.action.actionParametersOf(
                androidx.glance.action.ActionParameters.Key<Long>("habitId") to rowId,
                androidx.glance.action.ActionParameters.Key<String>("action") to "decrement",
                com.dayforge.widget.checkin.CheckInActionCallback.LEGACY_PROOF to original.forAction("decrement").encode()))
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
    }

    @Test fun legacyGoalConfirmationAndLateMetricIntentRetainOriginalAccount() = runBlocking<Unit> {
        val rowId = habits.createHabit("Legacy goal check", "", HabitType.CHECK_IN, 0, "#123456",
            HabitSchedule.Daily, targetCycles = 1, failMode = FailMode.STRICT)
        suspend fun identity() = sessions.exclusive { db.withTransaction {
            LegacyWidgetRestartClaim.displayed(requireNotNull(habits.getHabitById(rowId)), requireNotNull(tokens.localCoreWriteAccess()),
                com.dayforge.data.local.LocalReplicaIdentity.readInTransaction(db))
        } }
        val original = identity()
        assertTrue(legacy.perform(app, legacy.capture(original).forAction("toggle")).result.goalReached)
        fun intent(identity: LegacyWidgetRestartClaim) = GoalCompletionActivity.createIntent(app, rowId, "ignored", 1, 1,
            legacyIdentity = identity)
        val confirm = app.getString(R.string.action_confirm_complete)
        ActivityScenario.launch<GoalCompletionActivity>(intent(original)).use { scenario ->
            platform.awaitConfirm(scenario, confirm)
            sessions.exclusive { tokens.saveLoginSession("new-access", "new-refresh", "member", id(1), false) }
            await("legacy goal dismissal after reauthentication") {
                scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED
            }
        }
        assertTrue(requireNotNull(habits.getHabitById(rowId)).isActive)
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        val staleMetric = com.dayforge.widget.checkin.MetricPromptActivity.createIntent(app, rowId, "old",
            legacyIdentity = original)
        ActivityScenario.launch<com.dayforge.widget.checkin.MetricPromptActivity>(staleMetric).use { scenario ->
            await("stale legacy metric prompt dismissal") {
                // The original LaunchedEffect runs on the real platform Main dispatcher.
                scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED
            }
        }
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        ActivityScenario.launch<GoalCompletionActivity>(intent(identity())).use { scenario ->
            platform.awaitConfirm(scenario, confirm)
            platform.tapConfirm(scenario, confirm)
            await("legacy goal dismissal after confirmed completion") {
                scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED
            }
        }
        assertFalse(requireNotNull(habits.getHabitById(rowId)).isActive)
        assertEquals(1, db.completionDao().getByHabitOnce(rowId).size)
        assertTrue(db.syncOutboxDao().getAll().any { it.recordType == "habit" })
    }
}

/** Private driver for this class only. No Compose test clock, semantics callback or
 * authority call: read the current app accessibility window and inject a physical tap. */
private class WidgetFactPlatformUi : org.junit.rules.ExternalResource() {
    var onceDiagnostic: OnceUiCommitProbe? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as android.app.Application
    private data class Seen(val activity: android.app.Activity, val job: kotlinx.coroutines.Job,
        var destroyed: Boolean = false)
    // Access only on real Main; retain every original Job, including destroyed parents.
    private val seen = arrayListOf<Seen>()
    private var closing = false
    private var observationFailure: Throwable? = null
    private val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {
            try {
                check(seen.none { it.activity === activity }) { "Duplicate observed Activity creation" }
                val owner = activity as? androidx.lifecycle.LifecycleOwner
                check(owner != null) { "Observed Activity has no lifecycle owner" }
                val originalJob = requireNotNull(owner.lifecycleScope.coroutineContext[kotlinx.coroutines.Job])
                seen += Seen(activity, originalJob)
                onceDiagnostic?.let { probe ->
                    probe.lifecycle(activity, OnceUiCommitProbe.Life.CREATED)
                    originalJob.invokeOnCompletion { failure -> probe.lifecycle(activity, OnceUiCommitProbe.Life.JOB_COMPLETED, failure) }
                }
                if (closing) activity.finish()
            } catch (error: Throwable) {
                // Observer bookkeeping must not throw on Android Main; JUnit refuses cleanup.
                if (observationFailure == null) observationFailure = error
            }
        }
        override fun onActivityDestroyed(activity: android.app.Activity) {
            try {
                seen.single { it.activity === activity }.destroyed = true
                onceDiagnostic?.lifecycle(activity, OnceUiCommitProbe.Life.DESTROYED)
            }
            catch (error: Throwable) { if (observationFailure == null) observationFailure = error }
        }
        override fun onActivityStarted(activity: android.app.Activity) { onceDiagnostic?.lifecycle(activity, OnceUiCommitProbe.Life.STARTED) }
        override fun onActivityResumed(activity: android.app.Activity) { onceDiagnostic?.lifecycle(activity, OnceUiCommitProbe.Life.RESUMED) }
        override fun onActivityPaused(activity: android.app.Activity) { onceDiagnostic?.lifecycle(activity, OnceUiCommitProbe.Life.PAUSED) }
        override fun onActivityStopped(activity: android.app.Activity) { onceDiagnostic?.lifecycle(activity, OnceUiCommitProbe.Life.STOPPED) }
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
    }
    override fun before() {
        check(instrumentation.targetContext.packageName == "com.dayforge.testbed")
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
    }
    override fun after() {
        instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }
    suspend fun closeAndJoin() = withTimeout(5_000) {
        onceDiagnostic?.emit(OnceUiCommitProbe.Stage.CLEANUP_ENTER)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            observationFailure?.let { throw IllegalStateException("Widget lifecycle observation failed", it) }
            closing = true
            seen.toList().forEach { if (!it.destroyed && !it.activity.isFinishing) it.activity.finish() }
        }
        while (true) {
            val original = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                observationFailure?.let { throw IllegalStateException("Widget lifecycle observation failed", it) }
                seen.toList()
            }
            original.forEach { it.job.join() }
            val complete = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                observationFailure?.let { throw IllegalStateException("Widget lifecycle observation failed", it) }
                seen.size == original.size && seen.all { it.destroyed && it.job.isCompleted }
            }
            if (complete) break
            delay(20)
        }
        // No account/Host cleanup begins until all observed original lifecycle work exited.
    }
    private fun owner(scenario: ActivityScenario<GoalCompletionActivity>): GoalCompletionActivity {
        var result: GoalCompletionActivity? = null
        scenario.onActivity { result = it }
        return requireNotNull(result)
    }
    private suspend fun isResumed(expected: GoalCompletionActivity): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filter { it.packageName == "com.dayforge.testbed" }
            resumed.size == 1 && resumed.single() === expected && !expected.isFinishing &&
                !expected.isDestroyed && seen.any { it.activity === expected && !it.destroyed }
        }
    private var confirmStage = "not_observed"
    private var visitedNodes = 0
    private var exactLabels = 0
    private var visibleLabels = 0
    private var clickableCandidates = 0
    private var inViewportCandidates = 0
    @Suppress("DEPRECATION")
    private suspend fun confirmBounds(expected: GoalCompletionActivity, label: String): android.graphics.Rect? {
        confirmStage = "owner"; visitedNodes = 0; exactLabels = 0; visibleLabels = 0
        clickableCandidates = 0; inViewportCandidates = 0
        if (!isResumed(expected)) return null
        confirmStage = "active_root"
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val retrieved = java.util.IdentityHashMap<android.view.accessibility.AccessibilityNodeInfo, Boolean>()
        retrieved[root] = true
        val found = arrayListOf<android.graphics.Rect>()
        try {
            confirmStage = "root_package"
            if (root.packageName?.toString() != "com.dayforge.testbed") return null
            confirmStage = "root_visibility"
            if (!root.isVisibleToUser) return null
            val viewport = android.graphics.Rect().also(root::getBoundsInScreen)
            // Compose 1.7.6 implements virtual createNodeInfo/children, but inherits
            // findAccessibilityNodeInfosByText's null default. Traverse the real tree.
            confirmStage = "child_tree"
            val matches = arrayListOf<android.view.accessibility.AccessibilityNodeInfo>()
            val pending = ArrayDeque<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()
            pending.addLast(root to 0)
            while (pending.isNotEmpty()) {
                val (node, depth) = pending.removeLast()
                check(++visitedNodes <= 256 && depth <= 32) { "WIDGET_CONFIRM_TREE_LIMIT" }
                if (node.text?.toString() == label) { exactLabels++; matches += node }
                val children = node.childCount
                check(children <= 256 && visitedNodes + pending.size + children <= 256) { "WIDGET_CONFIRM_TREE_LIMIT" }
                for (index in 0 until children) {
                    val child = node.getChild(index)
                    if (child == null) { confirmStage = "child_unavailable"; return null }
                    retrieved[child] = true; pending.addLast(child to (depth + 1))
                }
            }
            confirmStage = "button_qualification"
            for (text in matches) {
                if (!text.isVisibleToUser || !text.isEnabled) continue
                visibleLabels++
                var button: android.view.accessibility.AccessibilityNodeInfo? = text
                var ancestors = 0
                while (button != null && !button.isClickable) {
                    check(++ancestors <= 32) { "WIDGET_CONFIRM_PARENT_LIMIT" }
                    button = button.parent
                    button?.let { retrieved[it] = true }
                }
                if (button == null || !button.isVisibleToUser || !button.isEnabled ||
                    button.packageName?.toString() != "com.dayforge.testbed" || button.windowId != root.windowId) continue
                clickableCandidates++
                val bounds = android.graphics.Rect().also(button::getBoundsInScreen)
                if (!bounds.isEmpty && viewport.contains(bounds)) { inViewportCandidates++; found += bounds }
            }
        } finally { retrieved.keys.forEach { it.recycle() } }
        confirmStage = "owner_recheck"
        if (!isResumed(expected)) return null
        val distinct = found.distinct()
        check(distinct.size <= 1) { "Ambiguous actual Goal confirmation button" }
        confirmStage = if (distinct.isEmpty()) "no_qualified_button" else "qualified_button"
        return distinct.singleOrNull()
    }
    suspend fun awaitConfirm(scenario: ActivityScenario<GoalCompletionActivity>, label: String) {
        val expected = owner(scenario)
        try { withTimeout(5_000) { while (confirmBounds(expected, label) == null) delay(20) } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            // Fixed stage/counts only; no node text, account data or window payload.
            // Keep the original cancellation object/cause instead of swallowing it.
            error.addSuppressed(AssertionError("WIDGET_CONFIRM_WAIT stage=$confirmStage nodes=$visitedNodes " +
                "labels=$exactLabels visible=$visibleLabels clickable=$clickableCandidates inside=$inViewportCandidates"))
            throw error
        }
    }
    suspend fun tapConfirm(scenario: ActivityScenario<GoalCompletionActivity>, label: String) {
        // Re-read visibility and the actual owner; never use the node captured by await.
        val bounds = requireNotNull(confirmBounds(owner(scenario), label)) { "Actual Goal confirmation not visible" }
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        val up = android.view.MotionEvent.obtain(now, now + 1, android.view.MotionEvent.ACTION_UP,
            bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        try {
            check(instrumentation.uiAutomation.injectInputEvent(down, true))
            check(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
    }
}
