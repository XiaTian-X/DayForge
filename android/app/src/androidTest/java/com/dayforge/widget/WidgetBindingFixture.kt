package com.dayforge.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.WidgetConfigurationRepository
import com.dayforge.data.repository.WidgetConfigurationSpec
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.checkin.CheckInWidgetReceiver
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.counting.CountingWidgetReceiver
import com.dayforge.widget.timer.TimerWidget
import com.dayforge.widget.timer.TimerWidgetReceiver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetBindingFixtureEntryPoint {
    fun configuration(): WidgetConfigurationRepository
    fun habits(): HabitRepository
}

/** Explicit user selection through the real configuration repository, not a Long-to-UUID migration.
 * Call once BEFORE the scenario's delayed reads/account changes; do not silently repair its binding.
 * Launcher registration is not claimed; callers already own the isolated Glance ID and cleanup.
 */
internal suspend fun bindWidgetSelection(context: Context, glanceId: GlanceId, habitId: Long) {
    val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetBindingFixtureEntryPoint::class.java)
    val habit = requireNotNull(entry.habits().getHabitById(habitId))
    val spec = when (habit.habitType) {
        HabitType.CHECK_IN -> WidgetConfigurationSpec(habit.habitType, CheckInWidget.PREFS_NAME,
            CheckInWidget.PREF_HABIT_ID_PREFIX, CheckInWidgetReceiver::class.java)
        HabitType.COUNTING -> WidgetConfigurationSpec(habit.habitType, CountingWidget.PREFS_NAME,
            CountingWidget.PREF_HABIT_ID_PREFIX, CountingWidgetReceiver::class.java)
        HabitType.TIMER -> WidgetConfigurationSpec(habit.habitType, TimerWidget.PREFS_NAME,
            TimerWidget.PREF_HABIT_ID_PREFIX, TimerWidgetReceiver::class.java)
        else -> error("No fixed goal widget")
    }
    val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    val snapshot = entry.configuration().load(spec, id)
    entry.configuration().configure(snapshot, habit) { publication -> check(publication { }) }
}

internal fun clearWidgetSelection(context: Context, glanceId: GlanceId) {
    val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    listOf(CheckInWidget.PREFS_NAME, CountingWidget.PREFS_NAME, TimerWidget.PREFS_NAME).forEach { name ->
        check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .remove("habit_id_$id").remove("binding_v1_$id").commit())
    }
}
