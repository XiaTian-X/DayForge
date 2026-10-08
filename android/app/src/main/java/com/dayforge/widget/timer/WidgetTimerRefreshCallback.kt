package com.dayforge.widget.timer

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.dayforge.widget.focus.FocusWidget

/** Explicit read retry only; it never dispatches a timer or rewrites a missing original policy. */
class WidgetTimerRefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        when (parameters[ActionParameters.Key<String>("widget")]) {
            "timer" -> TimerWidget().update(context, glanceId)
            "focus" -> FocusWidget().update(context, glanceId)
        }
    }
}
