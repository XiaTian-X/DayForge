package com.dayforge.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dayforge.di.ReminderEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Intent extras are wake-up claims, never authoritative count rules or account data. */
class HabitReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_HABIT_REMINDER = "com.dayforge.HABIT_REMINDER"
        const val EXTRA_HABIT_ID = "habitId"
        const val CHANNEL_ID = "habit_reminders"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HABIT_REMINDER) return
        // Legacy numeric-slot intents have no account/date proof. Do not display or resurrect them.
        val delivery = AndroidReminderAlarms.decode(intent) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { ReminderEntryPoint.from(context).deliver(delivery) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("HabitReminder", "Reminder wake failed for habitId=${delivery.habitId}", error)
            } finally { pending.finish() }
        }
    }
}
