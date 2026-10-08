package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.reminder.*
import com.dayforge.util.DateTimeUtils
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real account/Room/day proofs with only the OS publication boundary replaced. */
@RunWith(AndroidJUnit4::class)
class CountReminderWorkflowTest : NextObjectEditorFixture() {
    private data class Pending(val reading: ReminderReading, val wake: HabitReminderWake) {
        val delivery get() = ReminderDelivery(reading.habit.id, reading.habit.uuid, reading.scope, reading.stamp, wake)
    }
    private data class State(val alarms: Map<Long, Pending> = emptyMap(), val shown: List<Pending> = emptyList(),
        val replacements: Int = 0, val clears: Int = 0)
    private inner class FakeAlarms : ReminderAlarms {
        val state = MutableStateFlow(State())
        private fun outsideCommit() { assertFalse("No OS publication inside Room", db.inTransaction()) }
        override fun replace(reading: ReminderReading, wake: HabitReminderWake) {
            outsideCommit(); state.update { it.copy(alarms = it.alarms + (reading.habit.id to Pending(reading, wake)),
                replacements = it.replacements + 1) }
        }
        override fun cancel(habitId: Long) { outsideCommit(); state.update { it.copy(alarms = it.alarms - habitId) } }
        override fun cancelAll() { outsideCommit(); state.update { it.copy(alarms = emptyMap()) } }
        override fun publish(reading: ReminderReading, wake: HabitReminderWake) {
            outsideCommit(); state.update { it.copy(shown = it.shown + Pending(reading, wake)) }
        }
        override fun clearPublished(keepScope: String?) {
            outsideCommit(); state.update { state -> state.copy(shown = state.shown.filter { it.reading.scope == keepScope },
                clears = state.clears + 1) }
        }
        fun pending(id: Long = habit.id) = requireNotNull(state.value.alarms[id])
    }
    private fun controller(alarms: FakeAlarms) = HabitReminderController(db, tokens, sessions,
        CountHistoryReader(db, tokens, sessions), preferences, dataStore, alarms)
    private fun at(hour: Int, minute: Int = 0) = DateTimeUtils.today().atTime(hour, minute).atZone(ZoneId.systemDefault())
    private suspend fun configure(target: Int = 10, countdown: Boolean = false, best: Long = 540) =
        producer().write(local()) {
            val row = requireNotNull(db.habitDao().getHabitById(habit.id))
            db.habitDao().update(row.copy(targetValue = target, isCountdown = countdown, bestTime = best,
                planMetadata = requireNotNull(row.planMetadata).copy(preferredLocalTime =
                    java.time.LocalTime.of((best / 60).toInt(), (best % 60).toInt()).toString())))
        }

    @Test fun notificationTapUsesVerifiedScopeAndUuidAfterCommitOnMainWithoutWritingFacts() = runBlocking<Unit> {
        configure(); creatingHabits().logCompletion(app, habit.id, 2)
        val request = ReminderDetailRequest(habit.id, habit.uuid, reminderScope(requireNotNull(tokens.localFactAccess()).session))
        val checkedTokens = com.dayforge.data.local.TokenManager(dataStore, object : com.dayforge.data.local.TokenCipher {
            override fun encrypt(value: String) = value
            override fun decrypt(value: String): String {
                assertNotEquals("Reminder verification must not decrypt with Keystore on Main",
                    android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                return value
            }
        })
        val controller = HabitReminderController(db, checkedTokens, sessions, CountHistoryReader(db, checkedTokens, sessions),
            preferences, dataStore, FakeAlarms())
        val facts = db.completionDao().getByHabitOnce(habit.id); val queue = db.syncOutboxDao().getAll()
        var opened: Long? = null
        assertTrue(controller.openDetail(request) {
            assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
            assertFalse(db.inTransaction()); opened = it
        })
        assertEquals(habit.id, opened)
        assertEquals(facts, db.completionDao().getByHabitOnce(habit.id)); assertEquals(queue, db.syncOutboxDao().getAll())
        assertFalse(controller.openDetail(request.copy(habitUuid = id(99))) { fail("Wrong UUID navigated") })
        assertFalse(controller.openDetail(request.copy(habitId = Long.MAX_VALUE)) { fail("Missing habit navigated") })
        storage.reopen()
        assertTrue(controller(FakeAlarms()).openDetail(request) { assertEquals(habit.id, it) })
    }

    @Test fun notificationTapCannotCrossReauthenticationReplicaDeletionOrLogout() = runBlocking<Unit> {
        val controller = controller(FakeAlarms())
        fun rejected() = { _: Long -> fail("Stale notification navigated") }
        val old = ReminderDetailRequest(habit.id, habit.uuid, reminderScope(requireNotNull(tokens.localFactAccess()).session))
        tokens.saveLoginSession("new-access", "new-refresh", "member", id(1), false)
        assertFalse(controller.openDetail(old, rejected()))
        val renewed = old.copy(scope = reminderScope(requireNotNull(tokens.localFactAccess()).session))
        tokens.saveServerIdentity(id(2), id(3))
        assertFalse(controller.openDetail(renewed, rejected()))
        val replica = old.copy(scope = reminderScope(requireNotNull(tokens.localFactAccess()).session))
        creatingHabits().deleteHabit(requireNotNull(db.habitDao().getHabitById(habit.id)), app)
        assertFalse(controller.openDetail(replica, rejected()))
        tokens.clearTokens()
        assertFalse(controller.openDetail(replica, rejected()))
    }

    @Test fun accountSwitchWaitsUntilVerifiedMainThreadNavigationHasFinished() = runBlocking<Unit> {
        val controller = controller(FakeAlarms())
        val request = ReminderDetailRequest(habit.id, habit.uuid, reminderScope(requireNotNull(tokens.localFactAccess()).session))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        var navigationFinished = false
        var switch: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            assertTrue(controller.openDetail(request) {
                switch = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    sessions.exclusive {
                        assertTrue("Account transition cannot run inside navigation", navigationFinished)
                        tokens.saveLoginSession("new-access", "new-refresh", "member", id(1), false)
                    }
                }
                assertFalse(requireNotNull(switch).isCompleted)
                navigationFinished = true
            })
            withTimeout(5000) { requireNotNull(switch).await() }
            assertFalse(controller.openDetail(request) { fail("Old claim must not survive the queued switch") })
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
    }

    @Test fun originalDayRuleProgressAndNextWindowSurviveConfigEditAndColdReopen() = runBlocking<Unit> {
        configure(); creatingHabits().logCompletion(app, habit.id, 6); configure(3, true)
        val alarms = FakeAlarms(); val controller = controller(alarms)
        controller.schedule(habit.id, at(8))
        val pending = alarms.pending()
        assertEquals(10, pending.reading.target); assertFalse(pending.reading.countdown!!)
        assertEquals(6L, pending.reading.quantity); assertEquals(6, pending.wake.firstSlot)
        assertEquals(1101, pending.wake.minute) // Original ten-slot rule: 18:21, not mutable three-slot rule.
        val queues = db.syncOutboxDao().getAll(); val facts = db.completionDao().getByHabitOnce(habit.id)
        controller.deliver(pending.delivery, pending.wake.trigger.atZone(pending.wake.zone).plusSeconds(1))
        assertEquals(1, alarms.state.value.shown.size)
        assertEquals(6L, alarms.state.value.shown.single().reading.quantity)
        assertEquals(7, alarms.pending().wake.firstSlot)
        assertEquals(queues, db.syncOutboxDao().getAll()); assertEquals(facts, db.completionDao().getByHabitOnce(habit.id))
        storage.reopen()
        val cold = FakeAlarms(); controller(cold).schedule(habit.id, at(8))
        assertEquals(pending.wake, cold.pending().wake); assertEquals(10, cold.pending().reading.target)
    }

    @Test fun undoAllRetainsTodayRuleButMidnightSilentlyReadsNewDayConfiguration() = runBlocking<Unit> {
        configure(); creatingHabits().logCompletion(app, habit.id, 6); configure(3, true)
        for (fact in db.completionDao().getByHabitOnce(habit.id)) creatingHabits().undoCompletion(app, fact.id)
        val alarms = FakeAlarms(); val controller = controller(alarms)
        controller.schedule(habit.id, at(8))
        assertEquals(10, alarms.pending().reading.target); assertEquals(0L, alarms.pending().reading.quantity)
        controller.schedule(habit.id, at(23, 1))
        val midnight = alarms.pending()
        assertNull(midnight.wake.minute); assertEquals(DateTimeUtils.today().plusDays(1), midnight.wake.date)
        controller.deliver(midnight.delivery, midnight.wake.trigger.atZone(midnight.wake.zone))
        assertTrue(alarms.state.value.shown.isEmpty())
        assertEquals(3, alarms.pending().reading.target); assertTrue(alarms.pending().reading.countdown!!)
        assertEquals(midnight.wake.date, alarms.pending().wake.date); assertEquals(525, alarms.pending().wake.minute)
        assertEquals(0, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(10, db.countDayDao().forHabit(habit.id).single().targetValue)
    }

    @Test fun fiveThousandSlotsMergeIntoOneReminderAndCompletionSkipsIt() = runBlocking<Unit> {
        configure(5000); creatingHabits().logCompletion(app, habit.id, 20)
        val alarms = FakeAlarms(); val controller = controller(alarms)
        controller.schedule(habit.id, at(8))
        val pending = alarms.pending()
        assertEquals(0, pending.wake.firstSlot); assertEquals(4999, pending.wake.lastSlot)
        assertEquals(1, alarms.state.value.alarms.size)
        controller.deliver(pending.delivery, pending.wake.trigger.atZone(pending.wake.zone))
        assertEquals(1, alarms.state.value.shown.size); assertEquals(20L, alarms.state.value.shown.single().reading.quantity)
        assertNull(alarms.pending().wake.minute)
        creatingHabits().logCompletion(app, habit.id, 4980)
        controller.schedule(habit.id, at(8)); assertNull(alarms.pending().wake.minute)
        val before = alarms.state.value.shown
        controller.deliver(pending.delivery, pending.wake.trigger.atZone(pending.wake.zone))
        assertEquals(before, alarms.state.value.shown)
    }

    @Test fun staleAuthenticationReplicaAndHabitIdentityCannotPublishOrReplaceNewAlarm() = runBlocking<Unit> {
        configure()
        val alarms = FakeAlarms(); val controller = controller(alarms)
        controller.schedule(habit.id, at(8)); val pending = alarms.pending()
        tokens.saveLoginSession("new-access", "new-refresh", "member", id(1), false)
        controller.schedule(habit.id, at(8)); val latest = alarms.pending()
        controller.deliver(pending.delivery, pending.wake.trigger.atZone(pending.wake.zone))
        assertEquals(latest, alarms.pending()); assertTrue(alarms.state.value.shown.isEmpty())
        tokens.saveServerIdentity(id(2), id(3)); controller.schedule(habit.id, at(8))
        val replica = alarms.pending()
        controller.deliver(latest.delivery, latest.wake.trigger.atZone(latest.wake.zone))
        assertEquals(replica, alarms.pending()); assertTrue(alarms.state.value.shown.isEmpty())
        controller.deliver(replica.delivery.copy(habitUuid = id(99)), replica.wake.trigger.atZone(replica.wake.zone))
        assertEquals(replica, alarms.pending()); assertTrue(alarms.state.value.shown.isEmpty())
    }

    @Test fun oldDateZoneAndBestTimeClaimsReplanWithoutDisplayingStaleNotification() = runBlocking<Unit> {
        configure()
        val alarms = FakeAlarms(); val controller = controller(alarms)
        controller.schedule(habit.id, at(8)); val pending = alarms.pending()
        controller.deliver(pending.delivery, pending.wake.trigger.atZone(pending.wake.zone).plusDays(1))
        assertTrue(alarms.state.value.shown.isEmpty()); assertEquals(DateTimeUtils.today().plusDays(1), alarms.pending().wake.date)
        controller.deliver(pending.delivery, pending.wake.trigger.atZone(ZoneId.of("Pacific/Honolulu")))
        assertTrue(alarms.state.value.shown.isEmpty())
        configure(best = 600)
        controller.deliver(pending.delivery, pending.wake.trigger.atZone(pending.wake.zone))
        assertTrue(alarms.state.value.shown.isEmpty()); assertEquals(585, alarms.pending().wake.minute)
    }

    @Test fun disabledPreferencesUnknownHistoryAndInvalidTimeNeverFabricateProgress() = runBlocking<Unit> {
        configure()
        val alarms = FakeAlarms(); val controller = controller(alarms)
        controller.schedule(habit.id, at(8)); assertNotNull(alarms.pending())
        preferences.setHabitNotificationEnabled(habit.id, false)
        controller.schedule(habit.id, at(8)); assertTrue(alarms.state.value.alarms.isEmpty())
        preferences.setHabitNotificationEnabled(habit.id, true); preferences.setGlobalNotificationsEnabled(false)
        controller.schedule(habit.id, at(8)); assertTrue(alarms.state.value.alarms.isEmpty())
        preferences.setGlobalNotificationsEnabled(true)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.completionDao().insertForSync(com.dayforge.data.local.entity.CompletionEntity(
                habitId = habit.id, habitUuid = habit.uuid, date = DateTimeUtils.today().toDisplayMillis(), value = 2))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        controller.schedule(habit.id, at(8)); assertNull(alarms.pending().reading.target); assertNull(alarms.pending().wake.minute)
        // Corrupt projection fixture, not a newly admitted invalid editing operation.
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habits SET bestTime=1440 WHERE id=?", arrayOf(habit.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        controller.schedule(habit.id, at(8)); assertTrue(alarms.state.value.alarms.isEmpty())
    }

    @Test fun observationRebuildsAfterBusinessChangesAndClearsPublishedStateOnLogout() = runBlocking<Unit> {
        configure()
        val alarms = FakeAlarms(); val controller = controller(alarms)
        controller.start(scope)
        withTimeout(5000) { while (alarms.state.value.replacements == 0) delay(10) }
        val before = alarms.state.value.replacements
        configure(5000)
        withTimeout(5000) { while (alarms.state.value.replacements <= before) delay(10) }
        assertEquals(5000, alarms.pending().reading.target)
        controller.schedule(habit.id, at(8)); val pending = alarms.pending()
        controller.deliver(pending.delivery, pending.wake.trigger.atZone(pending.wake.zone))
        assertTrue(alarms.state.value.shown.isNotEmpty())
        val clears = alarms.state.value.clears
        tokens.clearTokens()
        withTimeout(5000) { while (alarms.state.value.clears <= clears) delay(10) }
        assertTrue(alarms.state.value.shown.isEmpty()); assertTrue(alarms.state.value.alarms.isEmpty())
    }

    @Test fun onceLifecycleKeepsTheExistingContractWithoutInventingPreferredTimeReminders() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once)
        val task = repo.createHabit("Once reminder", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
            failMode = com.dayforge.data.model.FailMode.LOOSE, completionPolicy = "one_and_done", appearance =
                com.dayforge.domain.model.ObjectAppearance(com.dayforge.domain.model.IconReference.Role("task.shopping"), "#123456", "theme"),
            creationAuthority = creator.capture())
        val alarms = FakeAlarms(); val controller = controller(alarms)
        controller.schedule(task, at(8)); assertNull(alarms.state.value.alarms[task])
        repo.logCompletion(app, task); controller.schedule(task, at(8)); assertNull(alarms.state.value.alarms[task])
        repo.undoCompletion(app, requireNotNull(repo.getOneTimeStatus(task).completionId))
        controller.schedule(task, at(8)); assertNull(alarms.state.value.alarms[task])
        assertFalse(repo.getOneTimeStatus(task).completed)
    }

    @Test fun processStartKeepsCurrentScopeUnreadRemindersButReauthenticationClearsOldOnes() = runBlocking<Unit> {
        configure(5000)
        val alarms = FakeAlarms(); val controller = controller(alarms)
        controller.schedule(habit.id, at(8)); val pending = alarms.pending()
        controller.deliver(pending.delivery, pending.wake.trigger.atZone(pending.wake.zone))
        val shown = alarms.state.value.shown; assertEquals(1, shown.size)
        val replacements = alarms.state.value.replacements
        controller.start(scope)
        withTimeout(5000) { while (alarms.state.value.clears == 0 || alarms.state.value.replacements <= replacements) delay(10) }
        assertEquals(shown, alarms.state.value.shown)
        val clears = alarms.state.value.clears
        tokens.saveLoginSession("reauth-access", "reauth-refresh", "member", id(1), false)
        withTimeout(5000) { while (alarms.state.value.clears <= clears) delay(10) }
        assertTrue(alarms.state.value.shown.isEmpty())
        controller.deliver(pending.delivery, pending.wake.trigger.atZone(pending.wake.zone))
        assertTrue(alarms.state.value.shown.isEmpty())
    }
}
