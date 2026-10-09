package com.dayforge.widget.timer

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import com.dayforge.widget.focus.FocusWidget

/** Explicit read retry only; it never dispatches a timer or rewrites a missing original policy. */
class WidgetTimerRefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        when (parameters[ActionParameters.Key<String>("widget")]) {
            "timer" -> {
                val state = TimerWidget().getAppWidgetState<androidx.datastore.preferences.core.Preferences>(context, glanceId)
                state[TimerWidget.HABIT_ID_KEY]?.let { TimerWidget.refreshWidgetData(context, glanceId, it) }
                TimerWidget().update(context, glanceId)
            }
            "focus" -> FocusWidget().update(context, glanceId)
            "checkin" -> com.dayforge.widget.checkin.CheckInWidget().update(context, glanceId)
            "counting" -> com.dayforge.widget.counting.CountingWidget().update(context, glanceId)
        }
    }
}
