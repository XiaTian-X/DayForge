package com.dayforge.widget.checkin

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AppWidgetProvider for 1x1 check-in widget.
 * Handles widget lifecycle (add, update, delete).
 */
class CheckInWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: CheckInWidget = CheckInWidget()

    companion object {
        private const val TAG = "CheckInWidgetReceiver"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Log.d(TAG, "onUpdate: appWidgetIds=${appWidgetIds.toList()}")

        for (appWidgetId in appWidgetIds) {
            val prefs = context.getSharedPreferences(CheckInWidget.PREFS_NAME, Context.MODE_PRIVATE)
            val habitId = prefs.getLong(CheckInWidget.PREF_HABIT_ID_PREFIX + appWidgetId, -1L)

            if (habitId != -1L) {
                val scope = CoroutineScope(Dispatchers.IO)
                scope.launch {
                    try {
                        val manager = GlanceAppWidgetManager(context)
                        val glanceId = manager.getGlanceIdBy(appWidgetId)
                        CheckInWidget.refreshWidgetData(context, glanceId, habitId)
                        glanceAppWidget.update(context, glanceId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Update failed for widget $appWidgetId", e)
                    }
                }
            }
        }
    }
}
