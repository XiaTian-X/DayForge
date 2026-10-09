package com.dayforge.widget.timer

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import com.dayforge.widget.focus.FocusWidget
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.progress.ProgressWidget
import com.dayforge.widget.motivation.MotivationWidget

/** Explicit read retry only; it never dispatches a timer or rewrites a missing original policy. */
class WidgetTimerRefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        when (parameters[ActionParameters.Key<String>("widget")]) {
            "progress" -> {
                ProgressWidget.refreshWidgetData(context, glanceId)
                ProgressWidget().update(context, glanceId)
            }
            "motivation" -> {
                MotivationWidget.refreshWidgetData(context, glanceId)
                MotivationWidget().update(context, glanceId)
            }
            "timer" -> {
                val state = TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(context, glanceId)
                state[TimerWidget.HABIT_ID_KEY]?.let { TimerWidget.refreshWidgetData(context, glanceId, it) }
                TimerWidget().update(context, glanceId)
            }
            "focus" -> {
                FocusWidget.refreshWidgetData(context, glanceId)
                FocusWidget().update(context, glanceId)
            }
            "checkin" -> {
                val widget = CheckInWidget()
                val state = widget.getAppWidgetState<androidx.datastore.preferences.core.Preferences>(context, glanceId)
                state[CheckInWidget.HABIT_ID_KEY]?.let { CheckInWidget.refreshWidgetData(context, glanceId, it) }
                widget.update(context, glanceId)
            }
            "counting" -> {
                val widget = CountingWidget()
                val state = widget.getAppWidgetState<androidx.datastore.preferences.core.Preferences>(context, glanceId)
                state[CountingWidget.HABIT_ID_KEY]?.let { CountingWidget.refreshWidgetData(context, glanceId, it) }
                widget.update(context, glanceId)
            }
        }
    }
}
