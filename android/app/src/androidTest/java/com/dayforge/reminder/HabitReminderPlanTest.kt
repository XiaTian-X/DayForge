package com.dayforge.reminder

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.LocalFactAccess
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitReminderPlanTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 8)
    private fun now(hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone)
    private fun habit(type: HabitType = HabitType.COUNTING, target: Int = 8, best: Long = 540) =
        HabitEntity(id = 1, uuid = "a3110000-0000-4000-8000-000000000001", name = "Reminder", habitType = type,
            iconResId = 0, colorHex = "#123456", schedule = HabitSchedule.Daily, bestTime = best, targetValue = target,
            createdAt = date.atStartOfDay(zone).toInstant().toEpochMilli())
    private fun reading(habit: HabitEntity) = ReminderReading(LocalFactAccess(LocalDataSession(
        AuthenticationSession("a3110000-0000-4000-8000-000000000002", "a3110000-0000-4000-8000-000000000003"),
        null, null), true), habit, habit.targetValue, false, 0L, false, true)

    @Test fun nextAlarmSkipsFinishedGroupsAndKeepsDistinctWindowsThenSilentNewDate() {
        val row = habit()
        assertEquals(525, HabitReminderPlan.next(row, 8, 0, now(8))!!.minute)
        assertEquals(645, HabitReminderPlan.next(row, 8, 1, now(8))!!.minute)
        assertEquals(645, HabitReminderPlan.next(row, 8, 0, now(8, 45))!!.minute)
        val finished = HabitReminderPlan.next(row, 8, 8, now(8))!!
        assertNull(finished.minute); assertEquals(date.plusDays(1), finished.date)
        assertNull(HabitReminderPlan.next(row, null, 0, now(8))!!.minute)
        assertNull(HabitReminderPlan.next(row.copy(isActive = false), 8, 0, now(8)))
        assertNull(HabitReminderPlan.next(row.copy(bestTime = 1440), 8, 0, now(8)))
        assertNull(HabitReminderPlan.next(habit(HabitType.GOAL), 8, 0, now(8)))
    }

    @Test fun targetOneAndNonCountKeepOriginalBestTimeRatherThanWindowStart() {
        for (type in listOf(HabitType.COUNTING, HabitType.CHECK_IN, HabitType.TIMER)) {
            val row = habit(type, 1, 300)
            assertEquals(300, HabitReminderPlan.next(row, 1, 0, now(4))!!.minute)
            assertEquals(date.plusDays(1), HabitReminderPlan.next(row, 1, 0, now(5))!!.date)
        }
    }

    @Test fun weeklyMonthlyCustomAndDaylightSavingUseCapturedDeviceCivilDateNotServerZone() {
        val row = habit()
        val weekly = row.copy(schedule = HabitSchedule.Weekly(daysOfWeek = listOf(1)))
        assertEquals(LocalDate.of(2026, 10, 12), HabitReminderPlan.next(weekly, 8, 0, now(8))!!.date)
        assertEquals(date.plusDays(3), HabitReminderPlan.nextEligible(row.copy(schedule = HabitSchedule.Custom(3)), date.plusDays(1), zone))
        val monthly = row.copy(schedule = HabitSchedule.Monthly(31))
        assertEquals(LocalDate.of(2026, 2, 28), HabitReminderPlan.nextEligible(monthly, LocalDate.of(2026, 2, 1), zone))
        val ny = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 3, 7, 23, 30, 0, 0, ny)
        val midnight = HabitReminderPlan.next(row, 8, 0, before)!!
        assertNull(midnight.minute); assertEquals(LocalDate.of(2026, 3, 8).atStartOfDay(ny).toInstant(), midnight.trigger)
        val morning = HabitReminderPlan.next(row, 8, 0, midnight.trigger.atZone(ny))!!
        assertEquals(LocalDate.of(2026, 3, 8).atTime(8, 45).atZone(ny).toInstant(), morning.trigger)
    }

    @Test fun fullLongPendingIntentIdentitiesDoNotCollideAndColdCancellationDoesNotCreateAny() {
        val backend = AndroidReminderAlarms(app)
        val a = reading(habit().copy(id = 1)); val b = reading(habit().copy(id = 1L + (1L shl 32)))
        val wake = HabitReminderPlan.next(a.habit, 8, 0, now(8).plusYears(70))!!
        fun existing(id: Long) = PendingIntent.getBroadcast(app, 0, AndroidReminderAlarms.alarmIntent(app, id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        try {
            backend.cancel(a.habit.id); backend.cancel(b.habit.id)
            assertNull(existing(a.habit.id)); assertNull(existing(b.habit.id))
            backend.replace(a, wake); backend.replace(b, wake)
            assertNotNull(existing(a.habit.id)); assertNotNull(existing(b.habit.id))
            assertNotEquals(existing(a.habit.id), existing(b.habit.id))
            backend.cancel(a.habit.id)
            assertNull(existing(a.habit.id)); assertNotNull(existing(b.habit.id))
            AndroidReminderAlarms(app).cancelAll() // Persisted registry survives backend recreation.
            assertNull(existing(b.habit.id))
            val one = ReminderNotificationBuilder.createHabitDetailPendingIntent(app, a.habit.id, "account-A|habit-A")
            val two = ReminderNotificationBuilder.createHabitDetailPendingIntent(app, a.habit.id, "account-B|habit-A")
            assertNotEquals(one, two); one.cancel(); two.cancel()
        } finally { backend.cancelAll() }
    }

    @Test fun notificationIdentitySeparatesScopeDateAndWindowAndTextPreservesLongProgress() {
        val wake = HabitReminderPlan.next(habit(), 8, 0, now(8))!!
        val tag = AndroidReminderAlarms.notificationTag("account-A", habit().uuid, wake)
        assertNotEquals(tag, AndroidReminderAlarms.notificationTag("account-B", habit().uuid, wake))
        assertNotEquals(tag, AndroidReminderAlarms.notificationTag("account-A", habit().uuid, wake.copy(date = date.plusDays(1))))
        assertNotEquals(tag, AndroidReminderAlarms.notificationTag("account-A", habit().uuid, wake.copy(minute = 645)))
        val notification = ReminderNotificationBuilder.buildReminderNotification(app, 1, "Count", 540,
            0, Int.MAX_VALUE, 4_294_967_294L, "test-identity")
        try {
            val text = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
            assertTrue(text.contains("4294967294")); assertTrue(text.contains(Int.MAX_VALUE.toString()))
            assertTrue(notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        } finally { notification.contentIntent.cancel() }
    }

    @Test fun malformedOrLegacyBroadcastsCannotClaimAnyReminder() {
        assertNull(AndroidReminderAlarms.decode(Intent(HabitReminderReceiver.ACTION_HABIT_REMINDER)
            .putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, 1L)))
        val base = AndroidReminderAlarms.alarmIntent(app, 1).putExtra("habitUuid", habit().uuid)
            .putExtra("scope", "test").putExtra("stamp", "test").putExtra("date", date.toString())
            .putExtra("zone", zone.id).putExtra("minute", 525).putExtra("trigger", now(8, 45).toInstant().toEpochMilli())
        assertNotNull(AndroidReminderAlarms.decode(base))
        assertNull(AndroidReminderAlarms.decode(Intent(base).putExtra("zone", "invalid-zone")))
        assertNull(AndroidReminderAlarms.decode(Intent(base).putExtra("date", "invalid-date")))
        assertNull(AndroidReminderAlarms.decode(Intent(base).putExtra("trigger", 1L)))
        assertNull(AndroidReminderAlarms.decode(Intent(base).putExtra(HabitReminderReceiver.EXTRA_HABIT_ID, 2L)))
    }

    @Test fun detailIntentCarriesFullScopeAndUuidAndRejectsLegacyOrMalformedClaims() {
        val scope = reading(habit()).scope
        val intent = ReminderNotificationBuilder.habitDetailIntent(app, 1L + (1L shl 32), "$scope|${habit().uuid}")
        val request = requireNotNull(ReminderDetailRequest.decode(intent))
        assertEquals(1L + (1L shl 32), request.habitId); assertEquals(scope, request.scope)
        assertEquals(habit().uuid, request.habitUuid); assertEquals(request, ReminderDetailRequest.restore(request.save()))
        assertNull(ReminderDetailRequest.restore(null))
        assertNull(ReminderDetailRequest.decode(ReminderNotificationBuilder.habitDetailIntent(app, 1, "1")))
        assertNull(ReminderDetailRequest.decode(ReminderNotificationBuilder.habitDetailIntent(app, 1, "$scope|bad-uuid")))
        assertNull(ReminderDetailRequest.decode(Intent(intent).putExtra(ReminderNotificationBuilder.EXTRA_HABIT_ID, 0L)))
        assertNull(ReminderDetailRequest.decode(Intent(intent).putExtra(ReminderNotificationBuilder.EXTRA_NAVIGATION_DESTINATION, "edit")))
        assertNull(ReminderDetailRequest.decode(Intent(intent).setData(intent.data!!.buildUpon().appendPath("extra").build())))
    }

    @Test fun currentScopeUnreadTagsSurviveProcessStartWhileOldAndUnprovedRemindersAreCleared() {
        val wake = HabitReminderPlan.next(habit(), 8, 0, now(8))!!
        val tag = AndroidReminderAlarms.notificationTag("account-A|generation-A|null|null", habit().uuid, wake)
        val channel = HabitReminderReceiver.CHANNEL_ID
        assertFalse(AndroidReminderAlarms.shouldClearPublished(tag, channel, "account-A|generation-A|null|null"))
        assertTrue(AndroidReminderAlarms.shouldClearPublished(tag, channel, "account-A|generation-B|null|null"))
        assertTrue(AndroidReminderAlarms.shouldClearPublished(tag, channel, "account-B|generation-A|null|null"))
        assertTrue(AndroidReminderAlarms.shouldClearPublished(tag, channel, null))
        assertTrue(AndroidReminderAlarms.shouldClearPublished(null, channel, "account-A|generation-A|null|null"))
        assertFalse(AndroidReminderAlarms.shouldClearPublished("timer:running", "timer", null))
    }
}
