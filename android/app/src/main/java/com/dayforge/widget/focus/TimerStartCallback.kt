package com.dayforge.widget.focus

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import com.dayforge.data.repository.LegacyWidgetTimerClaim
import com.dayforge.widget.timer.TimerActionCallback

/** Focus widgets use the same original-scope legacy start and switch-confirmation boundary. */
class TimerStartCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val habitId = parameters[ActionParameters.Key<Long>("habitId")] ?: return
        val claim = parameters[ActionParameters.Key<String>(LegacyWidgetTimerClaim.PARAMETER)] ?: return
        TimerActionCallback().onAction(context, glanceId, actionParametersOf(
            ActionParameters.Key<Long>("habitId") to habitId,
            ActionParameters.Key<String>("action") to "start",
            ActionParameters.Key<String>(LegacyWidgetTimerClaim.PARAMETER) to claim))
    }
}
