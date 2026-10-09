package com.dayforge.data.repository

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var reader: WidgetFactReader
    @Inject lateinit var publisher: WidgetDisplayPublisher
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
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
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in androidx.test.runner.lifecycle.Stage.entries) monitor.getActivitiesInStage(stage).toList().forEach { it.finish() }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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
    private suspend fun await(block: suspend () -> Boolean) = withTimeout(5_000) { while (!block()) delay(20) }
    private fun glanceId() = requireNotNull(GlanceAppWidgetManager(app).getGlanceIdBy(Intent().putExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, ((System.nanoTime() and 0x1fffffff) + 1).toInt())))

    @Test fun actualGlanceStatesCarryTheRenderedIdentityAndFrozenCountRule() = runBlocking<Unit> {
        val check = create()
        val checkId = glanceId()
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
        suspend fun launch(claim: WidgetFactClaim) {
            ActivityScenario.launch<WidgetFactActionActivity>(claim.actionIntent(app, "toggle")).use { scenario ->
                await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            }
        }
        launch(before)
        assertTrue(habits.getOneTimeStatus(task).completed)
        assertTaskDisplay(true)
        val queued = db.syncOutboxDao().getAll()
        launch(before)
        assertEquals(queued, db.syncOutboxDao().getAll())
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
        launch(claim(task))
        assertFalse(habits.getOneTimeStatus(task).completed)
        assertTaskDisplay(false)
        assertEquals(2, db.completionDao().getByHabitOnce(task).size)
        assertNotNull(habits.getHabitById(task))
    }

    @Test fun realFactConsumersHideCorruptSourcesAndExplicitRetryReloadsBeforeRendering() = runBlocking<Unit> {
        assertSame(publisher, com.dayforge.di.WidgetEntryPoint.from(app).displayPublisher())
        val check = create()
        val count = create(HabitType.COUNTING)
        habits.logCompletion(app, check); habits.logCompletion(app, count)
        val checkId = glanceId(); val countId = glanceId(); val focusId = glanceId()
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
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=CAST(value AS BLOB)")
        val corrupted = db.withTransaction { nextRestartDatabaseProof(db) }
        refresh(); assertDisplay(true)
        assertEquals(corrupted, db.withTransaction { nextRestartDatabaseProof(db) })
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=1")
        val healthy = db.withTransaction { nextRestartDatabaseProof(db) }
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
        } finally { unmockkConstructor(CheckInWidget::class, CountingWidget::class, FocusWidget::class) }
        assertEquals(healthy, db.withTransaction { nextRestartDatabaseProof(db) })
    }

    @Test fun actualFactAndFocusThemeFailuresPropagateWithoutChangingDisplayOrBusiness() = runBlocking<Unit> {
        val check = create(); val count = create(HabitType.COUNTING)
        val checkId = glanceId(); val countId = glanceId(); val focusId = glanceId()
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
        val rowId = create(cycles = 1)
        fun reminder() = android.app.PendingIntent.getBroadcast(app, 0,
            com.dayforge.reminder.AndroidReminderAlarms.alarmIntent(app, rowId),
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(reminder())
        habits.logCompletion(app, rowId)
        val before = claim(rowId)
        val row = habits.getHabitById(rowId)!!
        fun intent(claim: WidgetFactClaim) = GoalCompletionActivity.createIntent(app, rowId, row.name, 1, 1, claim)
        ActivityScenario.launch<GoalCompletionActivity>(intent(before)).use { scenario ->
            val confirm = app.getString(R.string.action_confirm_complete)
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(confirm)).fetchSemanticsNodes().isNotEmpty() }
            val ticket = habits.getHabitForEditing(rowId)
            habits.updateHabit(ticket.value!!.copy(name = "Changed goal-plan display"), editAuthority = ticket.authority)
            val queues = db.syncOutboxDao().getAll()
            compose.onNodeWithText(confirm).performClick()
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertEquals(queues, db.syncOutboxDao().getAll())
        }
        assertTrue(habits.getHabitById(rowId)!!.isActive)
        assertNotNull(reminder())
        ActivityScenario.launch<GoalCompletionActivity>(intent(claim(rowId))).use { scenario ->
            val confirm = app.getString(R.string.action_confirm_complete)
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(confirm)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(confirm).performClick()
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
        assertFalse(habits.getHabitById(rowId)!!.isActive)
        assertNull(reminder())
        habits.updateIsActive(rowId, true, app)
        assertNotNull(reminder())
        val current = claim(rowId)
        val queues = db.syncOutboxDao().getAll()
        ActivityScenario.launch<GoalCompletionActivity>(intent(current)).use { scenario ->
            val confirm = app.getString(R.string.action_confirm_complete)
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(confirm)).fetchSemanticsNodes().isNotEmpty() }
            sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(2), false) }
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
        assertTrue(habits.getHabitById(rowId)!!.isActive)
        assertEquals(queues, db.syncOutboxDao().getAll())
    }
}
