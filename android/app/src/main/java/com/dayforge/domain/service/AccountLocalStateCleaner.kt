package com.dayforge.domain.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.dayforge.widget.FocusWidgetAlarmScheduler
import com.dayforge.widget.WidgetRefreshScheduler
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            if (clearWidgetState(appContext) > 0) WidgetRefreshScheduler.request(appContext)
        } catch (error: CancellationException) {
            // Stores may already be cleared. Recovery reads CURRENT bindings/data, never
            // replays an old account's display or an unconditional deferred binding purge.
            WidgetRefreshScheduler.request(appContext)
            throw error
        } catch (error: Exception) {
            // Room and credentials are authoritative. A launcher implementation error
            // must not leave the application half-switched between two accounts.
            Log.w(TAG, "Failed to clear launcher widget state", error)
            WidgetRefreshScheduler.request(appContext)
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

    private suspend fun clearWidgetState(context: Context): Int {
        var failures = 0
        // Once authoritative stores are cleared, finish binding invalidation even when
        // the caller is cancelled; cancellation still propagates before launcher IO.
        withContext(NonCancellable + Dispatchers.IO) {
            listOf(
                CheckInWidget.PREFS_NAME,
                CountingWidget.PREFS_NAME,
                TimerWidget.PREFS_NAME
            ).forEach { name ->
                try {
                    check(clearBindingPreferences(context, name)) {
                        "Widget binding cleanup was not persisted: $name"
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures++
                    Log.w(TAG, "Failed to persist widget binding cleanup: $name", error)
                }
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
            currentCoroutineContext().ensureActive()
            val ids = try {
                appWidgetManager.getAppWidgetIds(ComponentName(context, target.receiverClass))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures++
                Log.w(TAG, "Failed to discover widgets for ${target.receiverClass.simpleName}", error)
                intArrayOf()
            }
            ids.forEach { appWidgetId ->
                currentCoroutineContext().ensureActive()
                try {
                    val glanceId = glanceManager.getGlanceIdBy(appWidgetId)
                    updateAppWidgetState(context, glanceId) { preferences ->
                        preferences.clear()
                    }
                    target.widget.update(context, glanceId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures++
                    Log.w(TAG, "Failed to clear widget $appWidgetId", error)
                }
            }
        }
        return failures
    }

    // KTX edit(commit=true) returns Unit and discards the persistence result required here.
    @SuppressLint("UseKtx")
    private fun clearBindingPreferences(context: Context, name: String): Boolean =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()

    private data class WidgetTarget(
        val receiverClass: Class<*>,
        val widget: GlanceAppWidget
    )
}
