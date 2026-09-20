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
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.widget.counting.CountingWidget
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
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
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val widgetId = (System.nanoTime() and 0x3fffffff).toInt() + 1
    private val glanceId get() = requireNotNull(GlanceAppWidgetManager(context).getGlanceIdBy(
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)))
    @Before fun inject() { hilt.inject() }
    @After fun clearBindings() {
        listOf(CheckInWidget.PREFS_NAME, CountingWidget.PREFS_NAME).forEach { name ->
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().remove("habit_id_$widgetId").commit())
        }
    }
    private suspend fun seed(type: HabitType, target: Int) = storage.database.habitDao().insert(HabitEntity(
        name = "Physical $type", habitType = type, iconResId = 1, colorHex = "#2196F3",
        schedule = HabitSchedule.Daily, targetValue = target))

    @Test fun countingHabitLoadsIdentityAndTargetIntoRealWidgetState() = runTest {
        val habitId = seed(HabitType.COUNTING, 8)
        assertEquals(HabitType.COUNTING, requireNotNull(storage.database.habitDao().getHabitById(habitId)).habitType)
        val id = glanceId
        CountingWidget.refreshWidgetData(context, id, habitId)
        val state = CountingWidget().getAppWidgetState<Preferences>(context, id)
        assertEquals(habitId, state[CountingWidget.HABIT_ID_KEY])
        assertEquals("Physical COUNTING", state[CountingWidget.HABIT_NAME_KEY])
        assertEquals(8, state[CountingWidget.TARGET_VALUE_KEY])
        assertEquals(0, state[CountingWidget.COMPLETED_TODAY_KEY])
        assertEquals(false, state[CountingWidget.IS_COMPLETED_KEY])
    }
    @Test fun checkInHabitLoadsIdentityAndCompletionIntoRealWidgetState() = runTest {
        val habitId = seed(HabitType.CHECK_IN, 1)
        assertEquals(HabitType.CHECK_IN, requireNotNull(storage.database.habitDao().getHabitById(habitId)).habitType)
        val id = glanceId
        CheckInWidget.refreshWidgetData(context, id, habitId)
        val state = CheckInWidget().getAppWidgetState<Preferences>(context, id)
        assertEquals(habitId, state[CheckInWidget.HABIT_ID_KEY])
        assertEquals("Physical CHECK_IN", state[CheckInWidget.HABIT_NAME_KEY])
        assertEquals(true, state[CheckInWidget.DATA_LOADED_KEY])
        assertEquals(false, state[CheckInWidget.IS_COMPLETED_KEY])
    }
    @Test fun storedCheckInBindingLoadsTheBoundHabit() = runTest {
        val habitId = seed(HabitType.CHECK_IN, 1)
        val prefs = context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, habitId).commit())
        val stored = prefs.getLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, -1)
        assertEquals(habitId, stored)
        val id = glanceId
        CheckInWidget.refreshWidgetData(context, id, stored)
        assertEquals(habitId, CheckInWidget().getAppWidgetState<Preferences>(context, id)[CheckInWidget.HABIT_ID_KEY])
    }
    @Test fun countingBindingLoadsWithoutCopyingUnrelatedCheckInPreferences() = runTest {
        val habitId = seed(HabitType.COUNTING, 8)
        val prefs = context.getSharedPreferences(CountingWidget.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putLong(CountingWidget.PREF_HABIT_ID_PREFIX + widgetId, habitId).commit())
        val id = glanceId
        CountingWidget.refreshWidgetData(context, id, prefs.getLong(CountingWidget.PREF_HABIT_ID_PREFIX + widgetId, -1))
        assertEquals(habitId, prefs.getLong(CountingWidget.PREF_HABIT_ID_PREFIX + widgetId, -1))
        assertEquals(habitId, CountingWidget().getAppWidgetState<Preferences>(context, id)[CountingWidget.HABIT_ID_KEY])
        assertFalse(context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE).contains(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId))
    }
    @Test fun checkInRefreshPreservesExistingBindingAndDeletedHabitBecomesMarked() = runTest {
        val habitId = seed(HabitType.CHECK_IN, 1)
        val prefs = context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, habitId).commit())
        val id = glanceId
        CheckInWidget.refreshWidgetData(context, id, habitId)
        val habit = requireNotNull(storage.database.habitDao().getHabitById(habitId))
        assertEquals(HabitType.CHECK_IN, habit.habitType)
        assertEquals(habitId, prefs.getLong(CheckInWidget.PREF_HABIT_ID_PREFIX + widgetId, -1))
        storage.database.habitDao().delete(habit)
        CheckInWidget.refreshWidgetData(context, id, habitId)
        val state = CheckInWidget().getAppWidgetState<Preferences>(context, id)
        assertEquals(habitId, state[CheckInWidget.HABIT_ID_KEY])
        assertEquals(true, state[CheckInWidget.IS_DELETED_KEY])
        assertEquals(true, state[CheckInWidget.DATA_LOADED_KEY])
    }
}
