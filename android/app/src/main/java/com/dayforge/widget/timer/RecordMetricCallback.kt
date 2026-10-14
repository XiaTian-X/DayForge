package com.dayforge.widget.timer

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.dayforge.data.repository.LegacyWidgetRestartClaim
import com.dayforge.widget.base.MetricPromptHelper
import kotlinx.coroutines.CancellationException

/**
 * Action callback for record metric button in timer widget.
 * Launches MetricPromptActivity to record linked metrics.
 */
class RecordMetricCallback : ActionCallback {

    companion object {
        private const val TAG = "RecordMetricCallback"
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val habitId = parameters[ActionParameters.Key<Long>("habitId")] ?: return
        try {
            val identity = LegacyWidgetRestartClaim.decode(requireNotNull(parameters[ActionParameters.Key<String>("legacyWidgetIdentity")]))
            require(identity.habitId == habitId)
            MetricPromptHelper.checkAndShowMetricPrompt(context, habitId, identity)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Expired legacy metric button", error)
            com.dayforge.widget.WidgetRefreshScheduler.request(context)
        }
    }
}
