package com.dayforge.widget.configuration

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.NextObjectCreator
import com.dayforge.data.repository.nextRestartDatabaseProof
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.checkin.CheckInWidgetConfigActivity
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.counting.CountingWidgetConfigActivity
import com.dayforge.widget.timer.TimerWidget
import com.dayforge.widget.timer.TimerWidgetConfigActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.*
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual selectors/ViewModel/workflow/Hilt/Room/Glance. Only launcher discovery and host submission replaced. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class WidgetConfigurationEntryTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val platform = mockk<AppWidgetManager>()
    private val ids = mutableMapOf<Int, GlanceId>()
    private val jobs = mutableListOf<Job>()
    private val names = listOf(CheckInWidget.PREFS_NAME, CountingWidget.PREFS_NAME, TimerWidget.PREFS_NAME)
    private fun id(n: Int) = "ab360000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun prefs(n: Int) = app.getSharedPreferences(names[n], Context.MODE_PRIVATE)
    private suspend fun proof() = storage.database.withTransaction { nextRestartDatabaseProof(storage.database) }
    private suspend fun create(type: HabitType): Long = habits.createHabit("Entry configuration $type", "", type,
        0, "#123456", HabitSchedule.Daily, targetValue = 1, completionPolicy = "recurring",
        appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme"), creationAuthority = creator.capture())
    @Before fun setup() = runBlocking<Unit> {
        hilt.inject()
        sessions.exclusive { tokens.clearTokens(); tokens.saveLoginSession("synthetic-widget", "synthetic-refresh", "member", id(1), false) }
        val real = GlanceAppWidgetManager(app)
        for (n in 0..2) {
            ids[74301 + n] = requireNotNull(real.getGlanceIdBy(Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                (System.nanoTime() and 0x1fffffff).toInt() + 1)))
            assertTrue(prefs(n).edit().clear().commit())
        }
        mockkStatic(AppWidgetManager::class)
        every { AppWidgetManager.getInstance(any()) } returns platform
        every { platform.getAppWidgetIds(any()) } returns intArrayOf()
        mockkConstructor(GlanceAppWidgetManager::class, CheckInWidget::class, CountingWidget::class, TimerWidget::class)
        every { anyConstructed<GlanceAppWidgetManager>().getGlanceIdBy(any<Int>()) } answers { requireNotNull(ids[firstArg()]) }
        coEvery { anyConstructed<CheckInWidget>().update(any(), any()) } just Runs
        coEvery { anyConstructed<CountingWidget>().update(any(), any()) } just Runs
        coEvery { anyConstructed<TimerWidget>().update(any(), any()) } just Runs
    }
    @After fun cleanup() = runBlocking<Unit> {
        jobs.forEach { withTimeout(5000) { it.cancelAndJoin() } }
        unmockkStatic(AppWidgetManager::class)
        unmockkConstructor(GlanceAppWidgetManager::class, CheckInWidget::class, CountingWidget::class, TimerWidget::class)
        ids.values.forEach { updateAppWidgetState(app, it) { state -> state.clear() } }
        names.forEach { assertTrue(app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()) }
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
    }
    private fun launch(n: Int): ActivityScenario<WidgetConfigurationActivity> {
        val component = when (n) { 0 -> CheckInWidgetConfigActivity::class.java; 1 -> CountingWidgetConfigActivity::class.java; else -> TimerWidgetConfigActivity::class.java }
        return ActivityScenario.launchActivityForResult<WidgetConfigurationActivity>(Intent(app, component)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 74301 + n)).also { scenario ->
            scenario.onActivity { activity -> jobs.add(requireNotNull(ViewModelProvider(activity)[WidgetConfigurationViewModel::class.java].viewModelScope.coroutineContext[Job])) }
        }
    }
    private fun waitForText(text: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(20) }

    @Test fun allThreeProductionSelectorsConfigureOnceOnDoubleClickWithActualTypedDisplay() = runBlocking<Unit> {
        val types = listOf(HabitType.CHECK_IN, HabitType.COUNTING, HabitType.TIMER)
        val rows = types.map { create(it) }; val before = proof()
        for (n in 0..2) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            when (n) {
                0 -> coEvery { anyConstructed<CheckInWidget>().update(app, requireNotNull(ids[74301])) } coAnswers { entered.complete(Unit); release.await() }
                1 -> coEvery { anyConstructed<CountingWidget>().update(app, requireNotNull(ids[74302])) } coAnswers { entered.complete(Unit); release.await() }
                else -> coEvery { anyConstructed<TimerWidget>().update(app, requireNotNull(ids[74303])) } coAnswers { entered.complete(Unit); release.await() }
            }
            launch(n).use { scenario ->
                try {
                    val text = "Entry configuration ${types[n]}"; waitForText(text)
                    types.filter { it != types[n] }.forEach { compose.onNodeWithText("Entry configuration $it").assertDoesNotExist() }
                    compose.onNodeWithText(text).performClick()
                    withTimeout(5000) { entered.await() }
                    compose.onNodeWithText(text).performClick()
                    release.complete(Unit); await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                    assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
                    assertEquals(74301 + n, scenario.result.resultData.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                    assertEquals(rows[n], prefs(n).getLong("habit_id_${74301 + n}", -1))
                    val state = CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, requireNotNull(ids[74301 + n]))
                    val nameKey = when (n) { 0 -> CheckInWidget.HABIT_NAME_KEY; 1 -> CountingWidget.HABIT_NAME_KEY; else -> TimerWidget.HABIT_NAME_KEY }
                    assertEquals(text, state[nameKey])
                } finally { release.complete(Unit) }
            }
        }
        coVerify(exactly = 1) { anyConstructed<CheckInWidget>().update(any(), any()) }
        coVerify(exactly = 1) { anyConstructed<CountingWidget>().update(any(), any()) }
        coVerify(exactly = 1) { anyConstructed<TimerWidget>().update(any(), any()) }
        assertEquals(3, widgets.requestCount); assertEquals(before, proof())
    }

    @Test fun actualHostFailureShowsRetryInsteadOfResultOkAndRetryReloadsRealChoices() = runBlocking<Unit> {
        val habitId = create(HabitType.CHECK_IN); val before = proof()
        coEvery { anyConstructed<CheckInWidget>().update(any(), any()) } throws IOException("host rejected")
        launch(0).use { scenario ->
            val text = "Entry configuration CHECK_IN"; waitForText(text); compose.onNodeWithText(text).performClick()
            waitForText(app.getString(R.string.widget_configuration_error))
            assertNotEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
            assertEquals(-1L, prefs(0).getLong("habit_id_74301", -1))
            coEvery { anyConstructed<CheckInWidget>().update(any(), any()) } just Runs
            compose.onNodeWithText(app.getString(R.string.action_retry)).performClick()
            waitForText(text); compose.onNodeWithText(text).performClick()
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertEquals(habitId, prefs(0).getLong("habit_id_74301", -1)); assertEquals(before, proof())
        }
    }

    @Test fun accountChangeDuringConfigurationCancelsOldPageWithoutTouchingNewBindingOrDisplay() = runBlocking<Unit> {
        create(HabitType.CHECK_IN); val before = proof()
        val entered = CompletableDeferred<Unit>()
        coEvery { anyConstructed<CheckInWidget>().update(any(), any()) } coAnswers { entered.complete(Unit); awaitCancellation() }
        launch(0).use { scenario ->
            val text = "Entry configuration CHECK_IN"; waitForText(text); compose.onNodeWithText(text).performClick()
            withTimeout(5000) { entered.await() }
            sessions.exclusive {
                tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(2), false)
                assertTrue(prefs(0).edit().putLong("habit_id_74301", 999).commit())
                updateAppWidgetState(app, requireNotNull(ids[74301])) { it.clear(); it[CheckInWidget.HABIT_NAME_KEY] = "new account" }
            }
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            jobs.forEach { withTimeout(5000) { it.join() } }
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
            assertEquals(999L, prefs(0).getLong("habit_id_74301", -1))
            val state = CheckInWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(app, requireNotNull(ids[74301]))
            assertEquals("new account", state[CheckInWidget.HABIT_NAME_KEY]); assertEquals(before, proof())
        }
    }

    @Test fun closingConfigurationCancelsOwnedWorkAndRestoresPreviousBinding() = runBlocking<Unit> {
        create(HabitType.TIMER); assertTrue(prefs(2).edit().putLong("habit_id_74303", 88).commit())
        val before = proof(); val entered = CompletableDeferred<Unit>()
        coEvery { anyConstructed<TimerWidget>().update(any(), any()) } coAnswers { entered.complete(Unit); awaitCancellation() }
        launch(2).use { scenario ->
            val text = "Entry configuration TIMER"; waitForText(text); compose.onNodeWithText(text).performClick()
            withTimeout(5000) { entered.await() }
            scenario.close()
            jobs.forEach { withTimeout(5000) { it.join() } }
            assertEquals(88L, prefs(2).getLong("habit_id_74303", -1)); assertEquals(before, proof())
        }
    }
}
