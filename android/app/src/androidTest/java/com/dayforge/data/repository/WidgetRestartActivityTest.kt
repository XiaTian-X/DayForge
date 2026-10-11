package com.dayforge.data.repository

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.RoundSyncBootstrapResponse
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.checkin.ReactivationActivity
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.focus.FocusWidget
import com.dayforge.widget.timer.TimerWidget
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production Hilt, Glance storage, Room and confirmation; no launcher pixel claim. */
@HiltAndroidTest
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetRestartActivityTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var preferences: PreferencesManager
    @Inject internal lateinit var http: NextSyncHttp
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
    private fun id(n: Int) = "ae310000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        hilt.inject()
        sessions.exclusive {
            tokens.clearTokens()
            tokens.saveLoginSession("synthetic-widget-restart", "synthetic-refresh", "member", id(1), false)
        }
    }
    @After fun cleanup() = runBlocking<Unit> {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in androidx.test.runner.lifecycle.Stage.entries)
                monitor.getActivitiesInStage(stage).toList().forEach { it.finish() }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
        com.dayforge.reminder.HabitReminderScheduler.cancelAllReminders(app)
        com.dayforge.widget.FocusWidgetAlarmScheduler.cancelScheduledRefresh(app)
    }
    private suspend fun profile() {
        sessions.exclusive {
            tokens.saveServerIdentity(id(2), id(3))
            tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write", "facts.append", "timer.control"), true, 1)
        }
        val core = NextCoreRequestStore(db, tokens, sessions, http)
        val once = OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences))
        NextSyncMergeStore(db, tokens, sessions, once, NextTimerRequestStore(db, tokens, sessions, core))
            .bootstrap(requireNotNull(tokens.localSyncAccess()), null, RoundSyncBootstrapResponse(emptyList(), 0,
                "2026-10-11T00:00:00Z", emptyList(), 1, emptyList(), emptyList()))
    }
    private suspend fun create(type: HabitType, typed: Boolean = true) = habits.createHabit(
        "Rendered $type", "", type, 0, "#123456", HabitSchedule.Daily, targetValue = 1,
        targetCycles = 3, failMode = FailMode.LOOSE, bestTime = 540,
        appearance = if (typed) ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme") else null,
        completionPolicy = if (typed) "recurring" else null, creationAuthority = if (typed) creator.capture() else null)
    private fun glanceId() = requireNotNull(GlanceAppWidgetManager(app).getGlanceIdBy(Intent().putExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, ((System.nanoTime() and 0x1fffffff) + 1).toInt())))
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun ended(scenario: ActivityScenario<ReactivationActivity>) = withTimeout(5000) {
        while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) delay(20)
    }
    private fun shown(message: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithText(message).fetchSemanticsNodes().size == 1
    }

    @Test fun allRenderedWidgetTypesConfirmNewRoundAndCancellationIsReadOnly() = runBlocking<Unit> {
        profile()
        for (type in listOf(HabitType.CHECK_IN, HabitType.COUNTING, HabitType.TIMER)) {
            val habitId = create(type)
            val row = requireNotNull(habits.getHabitById(habitId))
            val key = glanceId()
            val intent = when (type) {
                HabitType.CHECK_IN -> {
                    CheckInWidget.refreshWidgetData(app, key, habitId)
                    val state = CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, key)
                    assertNull(state[CheckInWidget.LEGACY_RESTART_PROOF_KEY])
                    ReactivationActivity.createIntent(app, habitId, "Untrusted extra", false,
                        factProof = requireNotNull(state[CheckInWidget.ACTION_PROOF_KEY]))
                }
                HabitType.COUNTING -> {
                    CountingWidget.refreshWidgetData(app, key, habitId)
                    val state = CountingWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, key)
                    assertNull(state[CountingWidget.LEGACY_RESTART_PROOF_KEY])
                    ReactivationActivity.createIntent(app, habitId, "Untrusted extra", false,
                        factProof = requireNotNull(state[CountingWidget.ACTION_PROOF_KEY]))
                }
                else -> {
                    TimerWidget.refreshWidgetData(app, key, habitId)
                    val state = TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, key)
                    assertNull(state[TimerWidget.LEGACY_RESTART_PROOF_KEY])
                    ReactivationActivity.createIntent(app, habitId, "Untrusted extra", false,
                        timerProof = requireNotNull(state[TimerWidget.ACTION_PROOF_KEY]))
                }
            }
            val message = app.getString(R.string.restart_message, row.name)
            val original = proof()
            ActivityScenario.launch<ReactivationActivity>(intent).use { scenario ->
                shown(message)
                compose.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
                ended(scenario)
            }
            assertEquals(original, proof())
            val before = db.syncOutboxDao().count()
            ActivityScenario.launch<ReactivationActivity>(intent).use { scenario ->
                shown(message)
                compose.onNodeWithText(app.getString(R.string.dialog_reactivate_confirm)).performClick()
                ended(scenario)
            }
            assertEquals(before + 1, db.syncOutboxDao().count())
            val captured = NextCoreLocalIntentStore(db, tokens, sessions).captureRounds()
            assertEquals(1, captured.pendingRestarts.getValue(row.uuid).head.generation)
            val committed = proof()
            ActivityScenario.launch<ReactivationActivity>(intent).use { ended(it) }
            assertEquals(committed, proof())
        }
    }

    @Test fun idOnlyMalformedAndAccountExpiredIntentsCannotOpenConfirmationOrWrite() = runBlocking<Unit> {
        profile()
        val habitId = create(HabitType.CHECK_IN)
        val key = glanceId()
        CheckInWidget.refreshWidgetData(app, key, habitId)
        val state = CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, key)
        val encoded = requireNotNull(state[CheckInWidget.ACTION_PROOF_KEY])
        val stale = ReactivationActivity.createIntent(app, habitId, "", false, factProof = encoded)
        val missing = ReactivationActivity.createIntent(app, habitId, "", false)
        assertFalse(stale.filterEquals(missing))
        val before = proof()
        for (intent in listOf(missing, ReactivationActivity.createIntent(app, habitId, "", false, factProof = "{}"))) {
            ActivityScenario.launch<ReactivationActivity>(intent).use { ended(it) }
            assertEquals(before, proof())
        }
        sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "member", id(50), false) }
        ActivityScenario.launch<ReactivationActivity>(stale).use { ended(it) }
        assertEquals(before, proof())
    }

    @Test fun legacyFactAndFocusDisplaysCarryOnlyTheirOriginalAccountIdentity() = runBlocking<Unit> {
        val check = create(HabitType.CHECK_IN, typed = false)
        val count = create(HabitType.COUNTING, typed = false)
        val checkKey = glanceId(); val countKey = glanceId(); val focusKey = glanceId()
        CheckInWidget.refreshWidgetData(app, checkKey, check)
        CountingWidget.refreshWidgetData(app, countKey, count)
        FocusWidget.refreshWidgetData(app, focusKey)
        val c = CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, checkKey)
        val n = CountingWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, countKey)
        val f = FocusWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, focusKey)
        assertEquals(check, LegacyWidgetRestartClaim.decode(requireNotNull(c[CheckInWidget.LEGACY_RESTART_PROOF_KEY])).habitId)
        assertEquals(count, LegacyWidgetRestartClaim.decode(requireNotNull(n[CountingWidget.LEGACY_RESTART_PROOF_KEY])).habitId)
        assertEquals(f[FocusWidget.PRIMARY_HABIT_ID_KEY],
            LegacyWidgetRestartClaim.decode(requireNotNull(f[FocusWidget.LEGACY_RESTART_PROOF_KEY])).habitId)
        assertNull(c[CheckInWidget.ACTION_PROOF_KEY]); assertNull(n[CountingWidget.ACTION_PROOF_KEY])
        assertNull(f[FocusWidget.FACT_ACTION_PROOF_KEY]); assertNull(f[FocusWidget.TIMER_ACTION_PROOF_KEY])
        CheckInWidget.refreshWidgetData(app, checkKey, Long.MAX_VALUE)
        assertNull(CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, checkKey)[CheckInWidget.LEGACY_RESTART_PROOF_KEY])
    }
}
