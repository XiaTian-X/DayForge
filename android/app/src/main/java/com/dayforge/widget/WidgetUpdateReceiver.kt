package com.dayforge.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.dayforge.widget.checkin.CheckInWidgetReceiver
import com.dayforge.widget.counting.CountingWidgetReceiver
import com.dayforge.widget.focus.FocusWidget
import com.dayforge.widget.focus.FocusWidgetReceiver
import com.dayforge.widget.motivation.MotivationWidget
import com.dayforge.widget.motivation.MotivationWidgetReceiver
import com.dayforge.widget.progress.ProgressWidget
import com.dayforge.widget.progress.ProgressWidgetReceiver
import com.dayforge.widget.timer.TimerWidget
import com.dayforge.widget.timer.TimerWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives LocalBroadcast when app data changes and triggers widget updates.
 * Action: "com.dayforge.DATA_CHANGED"
 */
class WidgetUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WidgetUpdateReceiver"
        const val ACTION_DATA_CHANGED = "com.dayforge.DATA_CHANGED"
        const val EXTRA_HABIT_ID = "habitId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DATA_CHANGED) {
            Log.d(TAG, "Received DATA_CHANGED broadcast, triggering widget update")

            // Use goAsync to extend broadcast lifecycle
            val pendingResult = goAsync()
            val appContext = context.applicationContext

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    updateWidgetsSafely(appContext)
                    Log.d(TAG, "Widget update completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update widgets", e)
                } finally {
                    try {
                        pendingResult.finish()
                    } catch (e: Exception) {
                        Log.d(TAG, "pendingResult.finish() threw exception (safe to ignore)")
                    }
                }
            }
        }
    }

    private suspend fun updateWidgetsSafely(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)

        // Force an update using Glance's updateAll
        try {
            ProgressWidget.refreshWidgetData(context)
            ProgressWidget().updateAll(context)

            MotivationWidget.refreshWidgetData(context)
            MotivationWidget().updateAll(context)

            // FocusWidget - refreshes priority data (SYS-05)
            // Must call refreshWidgetData before updateAll to reload data
            FocusWidget.refreshWidgetData(context)
            FocusWidget().updateAll(context)

            // Reschedule FocusWidget alarm after data change (SYS-05)
            FocusWidgetAlarmScheduler.scheduleNextRefresh(context)

            // Update TimerWidget - will show "习惯已删除" if habit was deleted
            TimerWidget().updateAll(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Progress/Motivation/Focus/Timer widgets", e)
        }

        // Send traditional broadcast for CheckIn and Counting widgets to hit their onUpdate
        updateWidgetType(context, appWidgetManager, CheckInWidgetReceiver::class.java, "CheckInWidget")
        updateWidgetType(context, appWidgetManager, CountingWidgetReceiver::class.java, "CountingWidget")
        updateWidgetType(context, appWidgetManager, TimerWidgetReceiver::class.java, "TimerWidget")
        updateWidgetType(context, appWidgetManager, MotivationWidgetReceiver::class.java, "MotivationWidget")
        updateWidgetType(context, appWidgetManager, ProgressWidgetReceiver::class.java, "ProgressWidget")
        updateWidgetType(context, appWidgetManager, FocusWidgetReceiver::class.java, "FocusWidget") // Add FocusWidget
    }

    private fun updateWidgetType(
        context: Context,
        appWidgetManager: AppWidgetManager,
        receiverClass: Class<*>,
        widgetName: String
    ) {
        try {
            val componentName = ComponentName(context, receiverClass)
            val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (widgetIds.isNotEmpty()) {
                Log.d(TAG, "Updating $widgetName widgets: ${widgetIds.toList()}")
                val updateIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
                    setClass(context, receiverClass)
                }
                context.sendBroadcast(updateIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update $widgetName", e)
        }
    }
}