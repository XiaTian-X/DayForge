package com.dayforge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.service.DeviceCalendar
import com.dayforge.widget.WidgetRefreshScheduler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Only protected OS broadcasts; never a self-broadcast or a background polling worker. */
internal class CalendarObservation(private val context: Context, private val calendar: DeviceCalendar) : AutoCloseable {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action in actions) calendar.refresh() // Re-read the clock, don't trust Intent extras.
        }
    }
    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply { actions.forEach(::addAction) },
            ContextCompat.RECEIVER_EXPORTED)
    }
    override fun close() { context.unregisterReceiver(receiver) }
    companion object {
        private val actions = setOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}

@Composable
internal fun CalendarRefreshEffect(preferences: PreferencesManager) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(context, lifecycle, preferences) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                val calendar = preferences.calendar
                val previous = calendar.changes.value
                CalendarObservation(context, calendar).use {
                    calendar.refresh() // Recovers missed midnight/zone changes while stopped or killed.
                    launch {
                        var prior = previous
                        calendar.changes.collect { reading ->
                            val changed = try {
                                preferences.updateLastSeenDate(reading.date.toEpochDay())
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                // This presentation checkpoint must not crash the activity or stop
                                // observing future clock changes when preferences are temporarily unavailable.
                                Log.e("CalendarRefresh", "Cannot persist presentation date; observation remains active", error)
                                prior.date != reading.date
                            }
                            if (changed || prior.zone != reading.zone) WidgetRefreshScheduler.request(context)
                            prior = reading
                        }
                    }
                    calendar.runMidnights()
                }
            }
        }
    }
}
