package com.dayforge.widget.checkin

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.data.repository.WidgetDisplayPublisher
import com.dayforge.data.repository.LegacyWidgetFactClaim
import com.dayforge.widget.bindWidgetSelection
import com.dayforge.widget.clearWidgetSelection
import com.dayforge.widget.counting.CountingWidget
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Replaces the old test-only preference-copy simulation with real widget loading. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CheckInWidgetStateTest {
    @Inject lateinit var themes: DeviceThemeController
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var publisher: WidgetDisplayPublisher
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val widgetId = (System.nanoTime() and 0x3fffffff).toInt() + 1
    private val glanceId get() = requireNotNull(GlanceAppWidgetManager(context).getGlanceIdBy(
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)))
    @Before fun inject() = runBlocking<Unit> {
        hilt.inject()
        sessions.exclusive {
            tokens.clearTokens()
            tokens.saveLoginSession("synthetic-widget-state", "synthetic-refresh", "member",
                "ac160000-0000-4000-8000-000000000001", false)
        }
    }
    @After fun clearBindings() = runBlocking<Unit> {
        if (::themes.isInitialized) themes.close()
        clearWidgetSelection(context, glanceId)
        androidx.glance.appwidget.state.updateAppWidgetState(context, glanceId) { it.clear() }
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
    }
    private suspend fun seed(type: HabitType, target: Int) = storage.database.habitDao().insert(HabitEntity(
        name = "Physical $type", habitType = type, iconResId = 1, colorHex = "#2196F3",
        schedule = HabitSchedule.Daily, targetValue = target))

    @Test fun countingHabitLoadsIdentityAndTargetIntoRealWidgetState() = runTest {
        val habitId = seed(HabitType.COUNTING, 8)
        assertEquals(HabitType.COUNTING, requireNotNull(storage.database.habitDao().getHabitById(habitId)).habitType)
        val id = glanceId
        bindWidgetSelection(context, id, habitId)
        CountingWidget.refreshWidgetData(context, id, habitId)
        val state = CountingWidget().getAppWidgetState<Preferences>(context, id)
        assertEquals(habitId, state[CountingWidget.HABIT_ID_KEY])
        assertEquals("Physical COUNTING", state[CountingWidget.HABIT_NAME_KEY])
        assertEquals(8, state[CountingWidget.TARGET_VALUE_KEY])
        assertEquals(0, state[CountingWidget.COMPLETED_TODAY_KEY])
        assertEquals(false, state[CountingWidget.IS_COMPLETED_KEY])
        val claim = LegacyWidgetFactClaim.decode(requireNotNull(state[CountingWidget.LEGACY_ACTION_PROOF_KEY]))
        assertEquals(habitId, claim.identity.habitId)
        assertEquals(requireNotNull(storage.database.habitDao().getHabitById(habitId)).uuid, claim.identity.habitUuid)
        assertEquals(0L, claim.todayQuantity)
        assertNull(claim.action)
        assertEquals(requireNotNull(tokens.localCoreWriteAccess()).session, claim.identity.session())
    }
    @Test fun checkInHabitLoadsIdentityAndCompletionIntoRealWidgetState() = runTest {
        val habitId = seed(HabitType.CHECK_IN, 1)
        assertEquals(HabitType.CHECK_IN, requireNotNull(storage.database.habitDao().getHabitById(habitId)).habitType)
        val id = glanceId
        bindWidgetSelection(context, id, habitId)
        CheckInWidget.refreshWidgetData(context, id, habitId)
        val state = CheckInWidget().getAppWidgetState<Preferences>(context, id)
        assertEquals(habitId, state[CheckInWidget.HABIT_ID_KEY])
        assertEquals("Physical CHECK_IN", state[CheckInWidget.HABIT_NAME_KEY])
        assertEquals(true, state[CheckInWidget.DATA_LOADED_KEY])
        assertEquals(false, state[CheckInWidget.IS_COMPLETED_KEY])
        val claim = LegacyWidgetFactClaim.decode(requireNotNull(state[CheckInWidget.LEGACY_ACTION_PROOF_KEY]))
        assertEquals(habitId, claim.identity.habitId)
        assertNull(claim.completionUuid)
        assertEquals(requireNotNull(tokens.localCoreWriteAccess()).session, claim.identity.session())
    }
    @Test fun storedCheckInBindingLoadsTheBoundHabit() = runTest {
        val habitId = seed(HabitType.CHECK_IN, 1)
        val prefs = context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE)
        bindWidgetSelection(context, glanceId, habitId)
        val stored = prefs.getLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, -1)
        assertEquals(habitId, stored)
        val id = glanceId
        CheckInWidget.refreshWidgetData(context, id, stored)
        assertEquals(habitId, CheckInWidget().getAppWidgetState<Preferences>(context, id)[CheckInWidget.HABIT_ID_KEY])
    }
    @Test fun countingBindingLoadsWithoutCopyingUnrelatedCheckInPreferences() = runTest {
        val habitId = seed(HabitType.COUNTING, 8)
        val prefs = context.getSharedPreferences(CountingWidget.PREFS_NAME, Context.MODE_PRIVATE)
        bindWidgetSelection(context, glanceId, habitId)
        val id = glanceId
        CountingWidget.refreshWidgetData(context, id, prefs.getLong(CountingWidget.PREF_HABIT_ID_PREFIX + widgetId, -1))
        assertEquals(habitId, prefs.getLong(CountingWidget.PREF_HABIT_ID_PREFIX + widgetId, -1))
        assertEquals(habitId, CountingWidget().getAppWidgetState<Preferences>(context, id)[CountingWidget.HABIT_ID_KEY])
        assertFalse(context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE).contains(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId))
    }
    @Test fun checkInRefreshPreservesExistingBindingAndDeletedHabitBecomesMarked() = runTest {
        val habitId = seed(HabitType.CHECK_IN, 1)
        val prefs = context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE)
        bindWidgetSelection(context, glanceId, habitId)
        val id = glanceId
        CheckInWidget.refreshWidgetData(context, id, habitId)
        val habit = requireNotNull(storage.database.habitDao().getHabitById(habitId))
        assertEquals(HabitType.CHECK_IN, habit.habitType)
        assertEquals(habitId, prefs.getLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, -1))
        val originalPublication = requireNotNull(publisher.capturePublication())
        storage.database.habitDao().delete(habit)
        // Preserve the deleted-source rendering assertion without inventing a new numeric selection.
        CheckInWidget.refreshWidgetDataInScope(context, id, habitId, originalPublication)
        val state = CheckInWidget().getAppWidgetState<Preferences>(context, id)
        assertEquals(habitId, state[CheckInWidget.HABIT_ID_KEY])
        assertEquals(true, state[CheckInWidget.IS_DELETED_KEY])
        assertEquals(true, state[CheckInWidget.DATA_LOADED_KEY])
        assertNull(state[CheckInWidget.LEGACY_ACTION_PROOF_KEY])
        CheckInWidget.refreshWidgetData(context, id)
        val unbound = CheckInWidget().getAppWidgetState<Preferences>(context, id)
        assertNull(unbound[CheckInWidget.HABIT_ID_KEY])
        assertNull(unbound[CheckInWidget.DATA_LOADED_KEY])
        assertNull(unbound[CheckInWidget.LEGACY_RESTART_PROOF_KEY])
        assertNull(unbound[CheckInWidget.LEGACY_ACTION_PROOF_KEY])
        assertEquals(habitId, prefs.getLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, -1))
    }
}
