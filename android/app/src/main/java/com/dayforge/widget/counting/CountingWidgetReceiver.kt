package com.dayforge.widget.counting

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AppWidgetProvider for 2x2 counting widget.
 * Handles widget lifecycle (add, update, delete).
 */
class CountingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CountingWidget()

    companion object {
        private const val TAG = "CountingWidgetReceiver"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate: appWidgetIds=${appWidgetIds.toList()}")

        for (appWidgetId in appWidgetIds) {
            val prefs = context.getSharedPreferences(CountingWidget.PREFS_NAME, Context.MODE_PRIVATE)
            val habitId = prefs.getLong(CountingWidget.PREF_HABIT_ID_PREFIX + appWidgetId, -1L)

            if (habitId != -1L) {
                val scope = CoroutineScope(Dispatchers.IO)
                scope.launch {
                    try {
                        val manager = GlanceAppWidgetManager(context)
                        val glanceId = manager.getGlanceIdBy(appWidgetId)
                        CountingWidget.refreshWidgetData(context, glanceId, habitId)
                        glanceAppWidget.update(context, glanceId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Update failed for widget $appWidgetId", e)
                    }
                }
            }
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}