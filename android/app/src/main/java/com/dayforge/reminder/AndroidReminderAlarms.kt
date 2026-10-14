package com.dayforge.reminder

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AndroidReminderAlarms @Inject constructor(@param:ApplicationContext private val context: Context) : ReminderAlarms {
    private val manager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notifications get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val registry = context.getSharedPreferences("habit-reminder-identities-v2", Context.MODE_PRIVATE)

    override fun replace(reading: ReminderReading, wake: HabitReminderWake) {
        val intent = alarmIntent(context, reading.habit.id).apply {
            putExtra("habitUuid", reading.habit.uuid); putExtra("scope", reading.scope); putExtra("stamp", reading.stamp)
            putExtra("date", wake.date.toString()); putExtra("zone", wake.zone.id)
            putExtra("minute", wake.minute ?: -1); putExtra("trigger", wake.trigger.toEpochMilli())
            putExtra("firstSlot", wake.firstSlot); putExtra("lastSlot", wake.lastSlot)
        }
        // Persist the identity first: a failed registry write must not leave an untracked OS alarm.
        persistIds(ids() + reading.habit.id.toString())
        val pending = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val trigger = wake.trigger.toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms())
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            else inexact(trigger, pending)
        } catch (error: SecurityException) { inexact(trigger, pending) }
    }

    private fun inexact(trigger: Long, pending: PendingIntent) {
        // Android 12+ clamps shorter windows to ten minutes; do not promise one-minute precision.
        manager.setWindow(AlarmManager.RTC_WAKEUP, trigger, 600_000L, pending)
    }

    override fun cancel(habitId: Long) {
        PendingIntent.getBroadcast(context, 0, alarmIntent(context, habitId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            manager.cancel(it); it.cancel()
        }
        persistIds(ids() - habitId.toString())
    }

    @SuppressLint("UseKtx") // KTX edit(commit=true) returns Unit and would discard the required commit-success check.
    private fun persistIds(value: Set<String>) {
        check(registry.edit().putStringSet("ids", value).commit()) { "REMINDER_REGISTRY_COMMIT_FAILED" }
    }

    private fun ids(): Set<String> = registry.getStringSet("ids", emptySet())!!.toSet()
    override fun cancelAll() { for (id in ids()) cancel(id.toLong()) }

    @SuppressLint("MissingPermission") // User/app/channel permissions checked; revoked permission is handled by the receiver.
    override fun publish(reading: ReminderReading, wake: HabitReminderWake) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val tag = notificationTag(reading.scope, reading.habit.uuid, wake)
        val notification = ReminderNotificationBuilder.buildReminderNotification(context, reading.habit.id,
            reading.habit.name, reading.habit.bestTime, wake.firstSlot, reading.target ?: 1, reading.quantity,
            navigationIdentity = "${reading.scope}|${reading.habit.uuid}")
        try { notifications.notify(tag, 0, notification) }
        catch (error: SecurityException) {
            // Permission may be revoked after the check; keep the following window scheduled.
            android.util.Log.w("HabitReminder", "Notification permission changed before publication")
        }
    }

    override fun clearPublished(keepScope: String?) {
        for (notification in notifications.activeNotifications)
            if (shouldClearPublished(notification.tag, notification.notification.channelId, keepScope))
                notifications.cancel(notification.tag, notification.id)
    }

    companion object {
        private const val TAG_PREFIX = "dayforge:habit:"
        internal fun shouldClearPublished(tag: String?, channel: String?, keepScope: String?): Boolean {
            val owned = tag?.startsWith(TAG_PREFIX) == true || channel == HabitReminderReceiver.CHANNEL_ID
            val current = keepScope != null && tag?.startsWith("$TAG_PREFIX$keepScope|") == true
            return owned && !current
        }
        internal fun alarmIntent(context: Context, id: Long): Intent = Intent(context, HabitReminderReceiver::class.java).apply {
            action = HabitReminderReceiver.ACTION_HABIT_REMINDER
            data = Uri.Builder().scheme("dayforge-reminder").authority("wake").appendPath(id.toString()).build()
            putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, id)
        }
        internal fun notificationTag(scope: String, uuid: String, wake: HabitReminderWake): String =
            "$TAG_PREFIX$scope|$uuid|${wake.date}|${wake.minute}"

        internal fun decode(intent: Intent): ReminderDelivery? = try {
            val id = intent.getLongExtra(HabitReminderReceiver.EXTRA_HABIT_ID, 0)
            if (id <= 0 || intent.data?.authority != "wake" || intent.data?.scheme != "dayforge-reminder" ||
                intent.data?.pathSegments != listOf(id.toString())) null else {
                val date = LocalDate.parse(requireNotNull(intent.getStringExtra("date")))
                val zone = ZoneId.of(requireNotNull(intent.getStringExtra("zone")))
                val minute = intent.getIntExtra("minute", -2)
                require(minute in -1..1439 && intent.hasExtra("trigger"))
                val trigger = Instant.ofEpochMilli(intent.getLongExtra("trigger", 0))
                require(trigger == if (minute == -1) date.atStartOfDay(zone).toInstant()
                    else date.atTime(minute / 60, minute % 60).atZone(zone).toInstant())
                ReminderDelivery(id, requireNotNull(intent.getStringExtra("habitUuid")),
                    requireNotNull(intent.getStringExtra("scope")), requireNotNull(intent.getStringExtra("stamp")),
                    HabitReminderWake(date, minute.takeIf { it >= 0 }, trigger,
                        zone, intent.getIntExtra("firstSlot", -1), intent.getIntExtra("lastSlot", -1)))
            }
        } catch (error: IllegalArgumentException) { null }
          catch (error: java.time.DateTimeException) { null }
    }
}
