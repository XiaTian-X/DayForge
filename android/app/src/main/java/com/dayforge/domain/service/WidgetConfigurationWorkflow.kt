package com.dayforge.domain.service

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.updateAppWidgetState
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.WidgetConfigurationRepository
import com.dayforge.data.repository.WidgetConfigurationSnapshot
import com.dayforge.data.repository.WidgetConfigurationSpec
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.checkin.CheckInWidgetReceiver
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.counting.CountingWidgetReceiver
import com.dayforge.widget.timer.TimerWidget
import com.dayforge.widget.timer.TimerWidgetReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal enum class WidgetConfigurationKind { CHECK_IN, COUNTING, TIMER }

class WidgetConfigurationWorkflow @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: WidgetConfigurationRepository
) {
    internal val changes get() = repository.changes
    internal suspend fun current(snapshot: WidgetConfigurationSnapshot) = repository.isCurrent(snapshot)
    internal suspend fun load(kind: WidgetConfigurationKind, widgetId: Int): WidgetConfigurationSnapshot =
        repository.load(spec(kind), widgetId)

    internal suspend fun bound(kind: WidgetConfigurationKind, id: GlanceId) =
        repository.bound(spec(kind), GlanceAppWidgetManager(context).getAppWidgetId(id))

    /** All ordinary refreshes resolve the stable selection first, including explicit retry. */
    internal suspend fun refresh(kind: WidgetConfigurationKind, id: GlanceId, expectedHabitId: Long? = null) {
        val bound = bound(kind, id) ?: return
        val habit = bound.habit
        if (habit == null) {
            bound.publication { updateAppWidgetState(context, id) { it.clear(); bound.publication.stampFrame(it) } }
            return
        }
        if (expectedHabitId != null && habit.id != expectedHabitId) return
        when (kind) {
            WidgetConfigurationKind.CHECK_IN -> CheckInWidget.refreshWidgetDataInScope(context, id, habit.id, bound.publication)
            WidgetConfigurationKind.COUNTING -> CountingWidget.refreshWidgetDataInScope(context, id, habit.id, bound.publication)
            WidgetConfigurationKind.TIMER -> TimerWidget.refreshWidgetDataInScope(context, id, habit.id, bound.publication)
        }
    }

    private fun spec(kind: WidgetConfigurationKind) = when (kind) {
            WidgetConfigurationKind.CHECK_IN -> WidgetConfigurationSpec(HabitType.CHECK_IN,
                CheckInWidget.PREFS_NAME, CheckInWidget.PREF_HABIT_ID_PREFIX, CheckInWidgetReceiver::class.java)
            WidgetConfigurationKind.COUNTING -> WidgetConfigurationSpec(HabitType.COUNTING,
                CountingWidget.PREFS_NAME, CountingWidget.PREF_HABIT_ID_PREFIX, CountingWidgetReceiver::class.java)
            WidgetConfigurationKind.TIMER -> WidgetConfigurationSpec(HabitType.TIMER,
                TimerWidget.PREFS_NAME, TimerWidget.PREF_HABIT_ID_PREFIX, TimerWidgetReceiver::class.java)
        }

    internal suspend fun configure(snapshot: WidgetConfigurationSnapshot, habit: HabitEntity) {
        repository.configure(snapshot, habit) { publication ->
            val id = GlanceAppWidgetManager(context).getGlanceIdBy(snapshot.widgetId)
            val widget = when (snapshot.spec.type) {
                HabitType.CHECK_IN -> CheckInWidget().also {
                    check(CheckInWidget.refreshWidgetDataInScope(context, id, habit.id, publication))
                }
                HabitType.COUNTING -> CountingWidget().also {
                    check(CountingWidget.refreshWidgetDataInScope(context, id, habit.id, publication))
                }
                HabitType.TIMER -> TimerWidget().also {
                    check(TimerWidget.refreshWidgetDataInScope(context, id, habit.id, publication))
                }
                else -> error("WIDGET_CONFIGURATION_TYPE")
            }
            // Glance/host may start readers: never hold the non-reentrant account lock here.
            // It reads current display state; final scope validation precedes RESULT_OK.
            widget.update(context, id)
        }
    }
}
