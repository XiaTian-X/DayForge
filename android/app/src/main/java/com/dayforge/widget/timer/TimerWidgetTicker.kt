package com.dayforge.widget.timer

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.dayforge.widget.WidgetRefreshScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Foreground-service-owned conflated display updates; no broadcast lifetime or unbounded queue. */
internal class TimerWidgetTicker(
    scope: CoroutineScope,
    private val refresh: suspend (Long) -> Boolean,
    private val requestFull: () -> Unit,
    private val onFailure: (Exception) -> Unit
) {
    constructor(context: Context, scope: CoroutineScope) : this(scope,
        { habitId -> refresh(context.applicationContext, habitId) },
        { WidgetRefreshScheduler.request(context.applicationContext); Unit },
        { Log.w("TimerWidgetTicker", "Timer display refresh failed", it) })

    private val ticks = Channel<Long>(Channel.CONFLATED)
    init {
        scope.launch {
            var previousHabit: Long? = null
            var fullRequested = false
            try {
                for (habitId in ticks) {
                    if (habitId != previousHabit) { previousHabit = habitId; fullRequested = false }
                    try {
                        if (refresh(habitId)) fullRequested = false
                        else if (!fullRequested) { requestFull(); fullRequested = true }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        if (!fullRequested) { onFailure(error); requestFull(); fullRequested = true }
                    }
                }
            } finally { ticks.cancel() }
        }
    }

    fun tick(habitId: Long) { if (habitId > 0) ticks.trySend(habitId) }

    private companion object {
        suspend fun refresh(context: Context, habitId: Long): Boolean {
            val manager = GlanceAppWidgetManager(context)
            val widget = TimerWidget()
            val bindings = context.getSharedPreferences(TimerWidget.PREFS_NAME, Context.MODE_PRIVATE)
            var complete = true
            var failure: Exception? = null
            for (id in manager.getGlanceIds(TimerWidget::class.java)) {
                val widgetId = manager.getAppWidgetId(id)
                if (bindings.getLong(TimerWidget.PREF_HABIT_ID_PREFIX + widgetId, -1L) != habitId) continue
                try {
                    if (!TimerWidget.refreshElapsedWidgetData(context, id, habitId)) complete = false
                    widget.update(context, id)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    complete = false
                    // Visit later instances, then report once through the consumer's bounded recovery.
                    if (failure == null) failure = error
                }
            }
            failure?.let { throw it }
            return complete
        }
    }
}
