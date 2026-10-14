package com.dayforge.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-evaluate local civil-time windows after reboot, clock/zone changes and permission grants. */
class HabitReminderClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { HabitReminderScheduler.rescheduleAllReminders(context.applicationContext) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("HabitReminder", "Clock reminder recovery failed", error)
            } finally { pending.finish() }
        }
    }
}
