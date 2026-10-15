package com.dayforge.widget.checkin

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.dayforge.data.repository.LegacyWidgetFactClaim
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.widget.WidgetRefreshScheduler
import com.dayforge.widget.base.MetricPromptHelper
import kotlinx.coroutines.CancellationException

/** Original rendered v4 actions only. ID-only cached callbacks never acquire fresh authority. */
class CheckInActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val habitId = parameters[ActionParameters.Key<Long>("habitId")] ?: return
        var lease: com.dayforge.domain.service.CountActionGate.Lease? = null
        var gate: com.dayforge.domain.service.CountActionGate? = null
        try {
            val claim = LegacyWidgetFactClaim.decode(requireNotNull(parameters[LEGACY_PROOF]))
            val action = parameters[ActionParameters.Key<String>("action")] ?: "toggle"
            require(claim.identity.habitId == habitId && claim.action == action) { "LEGACY_WIDGET_ACTION_CHANGED" }
            if (action == "increment" || action == "decrement") {
                gate = WidgetEntryPoint.from(context).countActionGate()
                lease = gate.tryAcquire(claim.identity.session(), claim.identity.habitUuid, habitId) ?: return
            }
            val workflow = WidgetEntryPoint.from(context).legacyFactWorkflow()
            val committed = workflow.perform(context, claim)
            if (committed.result.goalReached) {
                workflow.publish(committed.identity) { habit ->
                    context.startActivity(GoalCompletionActivity.createIntent(context, habit.id, habit.name,
                        committed.result.progress, requireNotNull(habit.targetCycles), legacyIdentity = committed.identity))
                }
            }
            try {
                CheckInWidget.refreshWidgetData(context, glanceId, habitId)
                CheckInWidget().update(context, glanceId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Widget display refresh unavailable", error)
            }
            if (committed.added && !committed.result.goalReached)
                MetricPromptHelper.checkAndShowMetricPrompt(context, habitId, committed.identity)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Expired legacy widget action", error)
            WidgetRefreshScheduler.request(context)
        } finally {
            lease?.let { gate?.release(it) }
        }
    }

    companion object {
        private const val TAG = "CheckInActionCallback"
        val LEGACY_PROOF = ActionParameters.Key<String>("legacyFactActionProof")
    }
}
