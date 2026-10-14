package com.dayforge.reminder

import android.content.Context
import android.util.Log
import com.dayforge.di.ReminderEntryPoint
import kotlinx.coroutines.CancellationException

/** Post-commit facade. The controller always reads current account-owned Room data. */
object HabitReminderScheduler {
    suspend fun scheduleReminder(context: Context, habitId: Long) {
        try { ReminderEntryPoint.from(context).schedule(habitId) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            // A presentation failure cannot report an already committed business write as rolled back.
            Log.e("HabitReminder", "Reminder scheduling failed for habitId=$habitId", error)
        }
    }

    suspend fun cancelReminder(context: Context, habitId: Long) {
        // Re-read the row: a late old-account deletion callback must not cancel a reused local ID.
        scheduleReminder(context, habitId)
    }

    suspend fun rescheduleAllReminders(context: Context) {
        ReminderEntryPoint.from(context).rescheduleAll()
    }

    suspend fun cancelAllReminders(context: Context) {
        try { ReminderEntryPoint.from(context).cancelAllNow() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e("HabitReminder", "Post-commit reminder cleanup failed", error)
        }
    }
}
