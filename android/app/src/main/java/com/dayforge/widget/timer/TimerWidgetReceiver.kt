package com.dayforge.widget.timer

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.dayforge.domain.service.TimerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AppWidgetProvider for 2x2 timer widget.
 * Handles widget lifecycle (add, update, delete) and real-time updates.
 */
class TimerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: TimerWidget = TimerWidget()

    companion object {
        private const val TAG = "TimerWidgetReceiver"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Log.d(TAG, "onUpdate: appWidgetIds=${appWidgetIds.toList()}")

        for (appWidgetId in appWidgetIds) {
            val prefs = context.getSharedPreferences(TimerWidget.PREFS_NAME, Context.MODE_PRIVATE)
            val habitId = prefs.getLong(TimerWidget.PREF_HABIT_ID_PREFIX + appWidgetId, -1L)

            if (habitId != -1L) {
                val scope = CoroutineScope(Dispatchers.IO)
                scope.launch {
                    try {
                        val manager = GlanceAppWidgetManager(context)
                        val glanceId = manager.getGlanceIdBy(appWidgetId)
                        TimerWidget.refreshWidgetData(context, glanceId, habitId)
                        glanceAppWidget.update(context, glanceId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Update failed for widget $appWidgetId", e)
                    }
                }
            }
        }
    }

    /**
     * Handle broadcast from TimerService for real-time widget updates.
     * Per D-14: widget refreshes every second while timer is running.
     * Per D-16: broadcasts stop when timer stops (handled by TimerService).
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == TimerService.ACTION_WIDGET_UPDATE) {
            val habitId = intent.getLongExtra(TimerService.EXTRA_HABIT_ID, -1L)
            if (habitId != -1L) {
                // Find widget(s) for this habitId and refresh them
                refreshWidgetsForHabit(context, habitId)
            }
        }
    }

    /**
     * Refreshes all widgets configured for a specific habit.
     * Called when TimerService broadcasts a widget update.
     */
    private fun refreshWidgetsForHabit(context: Context, habitId: Long) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, TimerWidgetReceiver::class.java)
        )

        val prefs = context.getSharedPreferences(TimerWidget.PREFS_NAME, Context.MODE_PRIVATE)
        val scope = CoroutineScope(Dispatchers.IO)

        for (widgetId in widgetIds) {
            val widgetHabitId = prefs.getLong(TimerWidget.PREF_HABIT_ID_PREFIX + widgetId, -1L)
            if (widgetHabitId == habitId) {
                scope.launch {
                    try {
                        val manager = GlanceAppWidgetManager(context)
                        val glanceId = manager.getGlanceIdBy(widgetId)
                        TimerWidget.refreshWidgetData(context, glanceId, habitId)
                        glanceAppWidget.update(context, glanceId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to refresh widget $widgetId", e)
                    }
                }
            }
        }
    }
}
