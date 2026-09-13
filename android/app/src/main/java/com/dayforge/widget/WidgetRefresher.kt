package com.dayforge.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.focus.FocusWidget
import com.dayforge.widget.motivation.MotivationWidget
import com.dayforge.widget.progress.ProgressWidget
import com.dayforge.widget.timer.TimerWidget
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Discovery failures are isolated by type, refresh failures by installed instance. */
internal class WidgetRefresher(
    private val targets: List<Target>,
    private val onFailure: (String, Exception) -> Unit
) {
    data class Target(val name: String, val instances: suspend () -> List<suspend () -> Unit>)

    constructor(context: Context) : this(
        targets(context.applicationContext),
        { name, error -> Log.e("WidgetRefresher", "Failed to refresh $name", error) }
    )

    suspend fun refresh(): Int {
        var failures = 0
        for (target in targets) {
            coroutineContext.ensureActive()
            try {
                for (refresh in target.instances()) {
                    coroutineContext.ensureActive()
                    try {
                        refresh()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failures++
                        onFailure(target.name, error)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures++
                onFailure(target.name, error)
            }
        }
        return failures
    }

    companion object {
        private fun targets(context: Context): List<Target> {
            val manager = GlanceAppWidgetManager(context)
            fun target(widget: GlanceAppWidget, load: suspend (GlanceId) -> Unit) =
                Target(widget.javaClass.simpleName) {
                    manager.getGlanceIds(widget.javaClass).map { id ->
                        suspend {
                            load(id)
                            widget.update(context, id)
                        }
                    }
                }

            fun bound(widget: GlanceAppWidget, prefsName: String, prefix: String,
                      load: suspend (GlanceId, Long) -> Unit) = target(widget) { id ->
                val widgetId = manager.getAppWidgetId(id)
                val habitId = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .getLong(prefix + widgetId, -1L)
                // Unconfigured widgets still render their setup state. Deleted habits go
                // through the existing loader, which writes the deleted-state marker.
                if (habitId != -1L) load(id, habitId)
            }

            return listOf(
                bound(CheckInWidget(), CheckInWidget.PREFS_NAME, CheckInWidget.PREF_HABIT_ID_PREFIX) {
                    id, habit -> CheckInWidget.refreshWidgetData(context, id, habit)
                },
                bound(CountingWidget(), CountingWidget.PREFS_NAME, CountingWidget.PREF_HABIT_ID_PREFIX) {
                    id, habit -> CountingWidget.refreshWidgetData(context, id, habit)
                },
                bound(TimerWidget(), TimerWidget.PREFS_NAME, TimerWidget.PREF_HABIT_ID_PREFIX) {
                    id, habit -> TimerWidget.refreshWidgetData(context, id, habit)
                },
                target(ProgressWidget()) { ProgressWidget.refreshWidgetData(context, it) },
                target(MotivationWidget()) { MotivationWidget.refreshWidgetData(context, it) },
                target(FocusWidget()) { FocusWidget.refreshWidgetData(context, it) },
                Target("FocusWidgetAlarm") { listOf(suspend { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }) }
            )
        }
    }
}
