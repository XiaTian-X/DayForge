package com.dayforge.domain.service

import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.dayforge.widget.FocusWidgetAlarmScheduler
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.checkin.CheckInWidgetReceiver
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.counting.CountingWidgetReceiver
import com.dayforge.widget.focus.FocusWidget
import com.dayforge.widget.focus.FocusWidgetReceiver
import com.dayforge.widget.motivation.MotivationWidget
import com.dayforge.widget.motivation.MotivationWidgetReceiver
import com.dayforge.widget.progress.ProgressWidget
import com.dayforge.widget.progress.ProgressWidgetReceiver
import com.dayforge.widget.timer.TimerWidget
import com.dayforge.widget.timer.TimerWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Clears Android state that lives outside Room when an account is removed or replaced. */
object AccountLocalStateCleaner {
    private const val TAG = "AccountStateCleaner"

    /**
     * Stops account-owned services before local stores are cleared, then invalidates
     * launcher widget state. Failures in optional widget cleanup must not reactivate
     * credentials or leave the database only partially cleared.
     */
    suspend fun clear(
        context: Context,
        clearLocalStores: suspend () -> Unit
    ) {
        val appContext = context.applicationContext
        stopTimerState(appContext)
        clearLocalStores()
        try {
            clearWidgetState(appContext)
        } catch (error: Exception) {
            // Room and credentials are authoritative. A launcher implementation error
            // must not leave the application half-switched between two accounts.
            Log.w(TAG, "Failed to clear launcher widget state", error)
        }
    }

    private fun stopTimerState(context: Context) {
        runCatching {
            context.stopService(Intent(context, TimerService::class.java))
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            listOf(
                TimerService.NOTIFICATION_ID,
                TimerService.TARGET_NOTIFICATION_ID,
                TimerService.THRESHOLD_NOTIFICATION_ID,
                TimerService.COUNTDOWN_COMPLETE_NOTIFICATION_ID
            ).forEach(notificationManager::cancel)
        }.onFailure { Log.w(TAG, "Failed to stop timer state during account cleanup", it) }

        runCatching { FocusWidgetAlarmScheduler.cancelScheduledRefresh(context) }
            .onFailure { Log.w(TAG, "Failed to cancel Focus widget alarm", it) }
    }

    private suspend fun clearWidgetState(context: Context) {
        withContext(Dispatchers.IO) {
            listOf(
                CheckInWidget.PREFS_NAME,
                CountingWidget.PREFS_NAME,
                TimerWidget.PREFS_NAME
            ).forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit(commit = true) { clear() }
            }
        }

        val targets = listOf(
            WidgetTarget(CheckInWidgetReceiver::class.java, CheckInWidget()),
            WidgetTarget(CountingWidgetReceiver::class.java, CountingWidget()),
            WidgetTarget(TimerWidgetReceiver::class.java, TimerWidget()),
            WidgetTarget(ProgressWidgetReceiver::class.java, ProgressWidget()),
            WidgetTarget(MotivationWidgetReceiver::class.java, MotivationWidget()),
            WidgetTarget(FocusWidgetReceiver::class.java, FocusWidget())
        )
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val glanceManager = GlanceAppWidgetManager(context)

        targets.forEach { target ->
            appWidgetManager.getAppWidgetIds(ComponentName(context, target.receiverClass))
                .forEach { appWidgetId ->
                    runCatching {
                        val glanceId = glanceManager.getGlanceIdBy(appWidgetId)
                        updateAppWidgetState(context, glanceId) { preferences ->
                            preferences.clear()
                        }
                        target.widget.update(context, glanceId)
                    }.onFailure {
                        Log.w(TAG, "Failed to clear widget $appWidgetId", it)
                    }
                }
        }
    }

    private data class WidgetTarget(
        val receiverClass: Class<*>,
        val widget: GlanceAppWidget
    )
}
