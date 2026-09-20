package com.dayforge.widget.progress

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production calculation and Glance state; no launcher rendering claim. */
@RunWith(AndroidJUnit4::class)
class ProgressWidgetTest {
    @get:Rule val storage = PhysicalDatabaseRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val widgetId = (System.nanoTime() and 0x3fffffff).toInt() + 1
    private val glanceId get() = requireNotNull(GlanceAppWidgetManager(context).getGlanceIdBy(
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)))

    private suspend fun seed(type: HabitType, count: Int, target: Int): Long {
        val id = storage.database.habitDao().insert(HabitEntity(
            name = "Physical $type", habitType = type, iconResId = 1, colorHex = "#2196F3",
            schedule = HabitSchedule.Daily, targetValue = target))
        repeat(count) { storage.database.completionDao().insert(CompletionEntity(
            habitId = id, date = DateTimeUtils.startOfDayMillis(), value = 1)) }
        return id
    }

    private suspend fun assertProgress(completed: Int, total: Int, progress: Float) {
        val id = glanceId
        ProgressWidget.refreshWidgetData(context, id)
        val state = ProgressWidget().getAppWidgetState<Preferences>(context, id)
        assertEquals(completed, state[ProgressWidget.COMPLETED_COUNT_KEY])
        assertEquals(total, state[ProgressWidget.TOTAL_COUNT_KEY])
        assertEquals(progress, requireNotNull(state[ProgressWidget.PROGRESS_KEY]), 0f)
    }

    @Test fun testCheckInHabit_completed_whenHasCompletion() = runTest {
        seed(HabitType.CHECK_IN, 1, 1)
        assertProgress(1, 1, 1f)
    }
    @Test fun testCountingHabit_notCompleted_whenBelowTarget() = runTest {
        val id = seed(HabitType.COUNTING, 3, 5)
        assertEquals(3, storage.database.completionDao().getCompletionsInRange(id,
            DateTimeUtils.today(), DateTimeUtils.today().plusDays(1)).sumOf { it.value })
        assertProgress(0, 1, 0f)
    }
    @Test fun testCountingHabit_completed_whenTargetReached() = runTest {
        val id = seed(HabitType.COUNTING, 5, 5)
        assertEquals(5, storage.database.completionDao().getCompletionsInRange(id,
            DateTimeUtils.today(), DateTimeUtils.today().plusDays(1)).sumOf { it.value })
        assertProgress(1, 1, 1f)
    }
    @Test fun testCountingHabit_completed_whenAboveTarget() = runTest {
        val id = seed(HabitType.COUNTING, 7, 5)
        assertEquals(7, storage.database.completionDao().getCompletionsInRange(id,
            DateTimeUtils.today(), DateTimeUtils.today().plusDays(1)).sumOf { it.value })
        assertProgress(1, 1, 1f)
    }
    @Test fun emptyDatabaseWritesFiniteZeroProgress() = runTest { assertProgress(0, 0, 0f) }
    @Test fun mixedHabitsRefreshStoredFractionAfterCompletion() = runTest {
        seed(HabitType.CHECK_IN, 1, 1)
        val counter = seed(HabitType.COUNTING, 0, 5)
        assertProgress(1, 2, 0.5f)
        storage.database.completionDao().insert(CompletionEntity(
            habitId = counter, date = DateTimeUtils.startOfDayMillis(), value = 5))
        assertProgress(2, 2, 1f)
    }
}
