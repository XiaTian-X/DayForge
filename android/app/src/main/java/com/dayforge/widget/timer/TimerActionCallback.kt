package com.dayforge.widget.timer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.dayforge.data.repository.LegacyWidgetTimerClaim
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.domain.service.TimerElapsedCalculator
import com.dayforge.domain.service.TimerServiceController
import com.dayforge.widget.WidgetRefreshScheduler
import com.dayforge.widget.base.MetricPromptHelper
import kotlinx.coroutines.CancellationException

/** Legacy widget controls retain the original rendered account and exact timer transition. */
class TimerActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val habitId = parameters[ActionParameters.Key<Long>("habitId")] ?: return
        val action = parameters[ActionParameters.Key<String>("action")] ?: return
        val payload = parameters[ActionParameters.Key<String>(LegacyWidgetTimerClaim.PARAMETER)] ?: return
        try {
            val claim = LegacyWidgetTimerClaim.decode(payload)
            require(claim.identity.habitId == habitId)
            dispatch(context, action, claim)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("TimerActionCallback", "Stale widget timer action rejected", error)
            WidgetRefreshScheduler.request(context)
        }
    }

    internal companion object {
        suspend fun dispatch(context: Context, action: String, claim: LegacyWidgetTimerClaim) {
            val writer = WidgetEntryPoint.from(context).timerWriter()
            val habitId = claim.identity.habitId
            val snapshot = writer.requireLegacyWidgetAction(claim)
            val habit = snapshot.habit
            when (action) {
                "start" -> {
                    writer.requireLegacyWidgetStart(claim)
                    if (claim.incumbent != null) {
                        context.startActivity(Intent(context, TimerConfirmationActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(TimerConfirmationActivity.EXTRA_HABIT_ID, habitId)
                            putExtra(TimerConfirmationActivity.EXTRA_TARGET_MINUTES, habit.targetValue)
                            claim.attach(this)
                        })
                    } else TimerServiceController.startTimer(context, habitId, habit.targetValue, habit.isCountdown,
                        legacyWidget = claim)
                }
                "pause", "resume" -> {
                    require(claim.sessionUuid != null)
                    if (action == "pause") TimerServiceController.pauseTimer(context, habitId, habit.targetValue, legacyWidget = claim)
                    else TimerServiceController.resumeTimer(context, habitId, habit.targetValue, legacyWidget = claim)
                }
                "stop" -> {
                    val active = requireNotNull(snapshot.activeLog)
                    val targetSeconds = habit.targetValue * 60
                    val maximum = if (habit.isCountdown) targetSeconds else if (targetSeconds > 0) targetSeconds * 3 else 86_400
                    val elapsed = TimerElapsedCalculator.elapsedSeconds(active, context).coerceAtMost(maximum)
                    if (elapsed < targetSeconds) {
                        context.startActivity(CountdownDiscardActivity.createIntent(context, habitId, habit.targetValue,
                            if (habit.isCountdown) targetSeconds - elapsed else elapsed, habit.isCountdown, legacyWidget = claim))
                    } else {
                        TimerServiceController.stopTimer(context, habitId, habit.targetValue, legacyWidget = claim)
                        writer.afterLegacyWidgetCompletion(claim) { goalReached ->
                            if (!goalReached) MetricPromptHelper.checkAndShowMetricPrompt(context, habitId, legacyIdentity = claim.identity)
                        }
                    }
                }
                else -> error("TIMER_WIDGET_UNKNOWN_ACTION")
            }
            WidgetRefreshScheduler.request(context)
        }
    }
}
