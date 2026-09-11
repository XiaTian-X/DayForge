package com.dayforge.widget.timer

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.dayforge.widget.checkin.MetricPromptActivity

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
        val habitName = parameters[ActionParameters.Key<String>("habitName")] ?: "Habit"

        Log.d(TAG, "onAction: habitId=$habitId, habitName=$habitName")

        // Launch MetricPromptActivity
        val intent = MetricPromptActivity.createIntent(context, habitId, habitName)
        context.startActivity(intent)

        // Widget will be refreshed by MetricPromptActivity after metric is recorded
    }
}