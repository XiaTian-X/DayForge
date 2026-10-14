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
                TimerWidget.refreshWidgetData(context, glanceId)
                TimerWidget().update(context, glanceId)
            }
            "focus" -> {
                FocusWidget.refreshWidgetData(context, glanceId)
                FocusWidget().update(context, glanceId)
            }
            "checkin" -> {
                val widget = CheckInWidget()
                CheckInWidget.refreshWidgetData(context, glanceId)
                widget.update(context, glanceId)
            }
            "counting" -> {
                val widget = CountingWidget()
                CountingWidget.refreshWidgetData(context, glanceId)
                widget.update(context, glanceId)
            }
        }
    }
}
