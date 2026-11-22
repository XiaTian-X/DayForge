package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.ui.screens.nested.NestedHabitTreeBuilder
import com.dayforge.util.DateTimeUtils
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real state-machine DAO transitions under the original writer; full TimerHistory remains
 * the independent oracle. The positively calibrated counter is ONE detail DAO, not all SQL. */
@RunWith(AndroidJUnit4::class)
class TimerProjectionConsumerTest : NextObjectEditorFixture() {
    private val alias = "dayforge.test.timer-projection.${UUID.randomUUID()}"
    private val watched = AtomicLong(-1)
    private val details = AtomicInteger()
    private val decrypts = AtomicInteger()
    private var rejectAt = -1
    init {
        storage.queryCallback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                val sql = sqlQuery.replace("`", "").lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
                if (Regex("^select \\* from timelogs where habitid\\s*=\\s*\\? order by starttime desc$").matches(sql) &&
                    (bindArgs.firstOrNull() as? Number)?.toLong() == watched.get()) details.incrementAndGet()
            }
        }
    }
    override fun tokenCipher(): TokenCipher {
        val actual = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = actual.encrypt(value)
            override fun decrypt(value: String): String? {
                val original = actual.decrypt(value) // Real Keystore remains before the explicit null seam.
                return if (decrypts.incrementAndGet() == rejectAt) null else original
            }
        }
    }
    @After fun drainBeforeInheritedCleanup() = runBlocking<Unit> {
        storage.recoveryCleanup {
            rejectAt = -1
            storage.afterDatabaseClose { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
        }
    }
    private fun writer() = NextTimerWriter(db, tokens, sessions)
    private fun status() = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao()),
        db.completionDao(), db.timeLogDao(), onceRepository(), timerWriter = writer(),
        countHistoryReader = CountHistoryReader(db, tokens, sessions), checkHistoryReader = CheckHistoryReader(db, tokens, sessions))
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun current() = requireNotNull(db.habitDao().getVisibleHabitById(timerHabit.id))
    private suspend fun configure() {
        register(); watched.set(timerHabit.id)
        val repository = editingHabits()
        val ticket = repository.getHabitForEditing(timerHabit.id)
        repository.updateHabit(requireNotNull(ticket.value).copy(targetCycles = 3, failMode = FailMode.STRICT), editAuthority = ticket.authority)
        timerHabit = current()
    }
    private suspend fun startAt(n: Int, day: LocalDate, splitAtMidnight: Boolean = false): TimerActionAuthority {
        val zone = ZoneId.systemDefault().id
        val began = if (splitAtMidnight) day.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli() - 30_500L
            else day.atTime(12, 0).plusMinutes((n % 100).toLong()).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        writer().write(timerHabit.id, writer().capture(timerHabit.id)) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, uuid = id(n), startTime = began,
                endTime = null, durationSeconds = 0, date = began, timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = began, timerTimezone = zone),
                TimerCommandEntity(commandId = id(n + 1), sessionUuid = id(n), sequence = 1, commandType = "start",
                    occurredAt = began, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = zone),
                TimerSegmentEntity(sessionUuid = id(n), sequence = 1, startedAt = began))
        }
        return requireNotNull(writer().capture(timerHabit.id))
    }
    private suspend fun complete(n: Int, day: LocalDate, durationMillis: Long, splitAtMidnight: Boolean = false) {
        val source = startAt(n, day, splitAtMidnight)
        val log = requireNotNull(db.timeLogDao().getActiveTimeLogForHabit(timerHabit.id))
        val ended = Math.addExact(log.startTime, durationMillis)
        writer().write(timerHabit.id, source) {
            db.timeLogDao().finishTimerAndQueue(log.id, ended, (durationMillis / 1000).toInt(), 0, 3, durationMillis,
                TimerCommandEntity(commandId = id(n + 2), sessionUuid = log.uuid, sequence = 2, commandType = "stop",
                    occurredAt = ended, expectedControlGeneration = 1, activeElapsedMillis = durationMillis), false)
            db.timeLogDao().replaceDayAllocations(log.uuid, DurationDayAllocator.allocate(log.uuid, timerHabit.id,
                requireNotNull(log.timerTimezone), db.timeLogDao().getTimerSegments(log.uuid), durationMillis))
        }
        assertEquals(ended, db.timeLogDao().getTimeLogByUuid(log.uuid)!!.endTime)
        assertEquals(durationMillis, db.timeLogDao().getDayAllocations(log.uuid).sumOf { it.durationMillis })
    }
    private fun originalStats(row: HabitEntity, original: WidgetTimerReadSnapshot): HabitWithStats {
        val history = original.history
        val allowed = ScheduleValidator.isCheckInAllowedToday(row.schedule, row.createdAt)
        return HabitWithStats(row, history.completedToday,
            history.todaySeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), null,
            StreakCalculator.currentFromBusinessDates(history.qualifiedDates, history.today),
            StreakCalculator.bestFromBusinessDates(history.qualifiedDates),
            activityRate = ActivityRateCalculator.calculate(row.schedule, row.createdAt, history.qualifiedDates.toList()),
            isCheckInAllowed = allowed,
            nextCheckInDate = if (allowed) null else ScheduleValidator.getNextCheckInDate(row.schedule, row.createdAt),
            targetProgress = if (row.targetCycles == null) 0 else history.qualifiedDates.size,
            hasFailed = FailureCheckerUtils.timerFailure(row, history) == StrictFailureState.FAILED,
            timerAuthority = original.authority)
    }
    private suspend fun full(row: HabitEntity): WidgetTimerReadSnapshot {
        details.set(0)
        val original = requireNotNull(writer().statusSnapshot(row))
        assertTrue("Calibrate the real full-history detail DAO before claiming its absence", details.get() > 0)
        return original
    }
    private suspend fun refused(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { if (error is CancellationException) throw error; return error }
        throw AssertionError("Original refusal required")
    }

    @Test(timeout = 150_000)
    fun realCompletedAllocationsAndActiveOriginalPolicyMatchTheFullStatusWithoutDetailScans() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(); val today = DateTimeUtils.today()
        // Legal completed session >= its original 60s target; each midnight side is only 30.5s.
        // A sub-target completed session itself would violate the original stop proof.
        complete(200, today.minusDays(2), 61_000, splitAtMidnight = true)
        complete(210, today.minusDays(1), 90_500)
        complete(220, today, 60_500)
        val row = current(); val original = full(row)
        assertEquals(today.minusDays(2), original.history.firstDate)
        assertEquals(30_500L, original.history.completedMillis[today.minusDays(2)])
        assertEquals(121_000L, original.history.completedMillis[today.minusDays(1)])
        assertEquals(60L, original.history.todaySeconds)
        assertEquals(2, original.history.qualifiedDates.size)
        assertEquals(StrictFailureState.FAILED, FailureCheckerUtils.timerFailure(row, original.history))
        val finite = requireNotNull(writer().statusDisplaySnapshot(row))
        assertEquals(original.history.firstDate, finite.projection.firstDate)
        assertEquals(original.authority, finite.authority)
        val before = proof(); details.set(0)
        assertEquals(originalStats(row, original), status().calculate(row, emptyList(), emptyList()))
        assertEquals(0, details.get()); assertEquals(before, proof())

        val activeAuthority = startAt(230, today)
        val edit = editingHabits().getHabitForEditing(timerHabit.id)
        editingHabits().updateHabit(requireNotNull(edit.value).copy(targetValue = 2, isCountdown = true), editAuthority = edit.authority)
        val edited = current(); val activeFull = full(edited)
        assertEquals(60, requireNotNull(activeFull.policy).targetSeconds)
        assertFalse(activeFull.policy!!.isCountdown)
        val activeDisplay = requireNotNull(writer().statusDisplaySnapshot(edited))
        assertEquals(activeFull.authority, activeDisplay.authority)
        assertEquals(activeAuthority.sessionUuid, activeDisplay.authority.sessionUuid)
        assertEquals(activeFull.policy, activeDisplay.policy)
        assertEquals(activeFull.activeLog, activeDisplay.activeLog)
        assertEquals(activeFull.activeLogHash, activeDisplay.activeLogHash)
        val stable = proof(); details.set(0)
        assertEquals(originalStats(edited, activeFull), status().calculate(edited))
        assertEquals(0, details.get()); assertEquals(stable, proof())
        // Exact stop/source and complete detail APIs are not replaced with a finite projection.
        assertEquals(activeFull.history, TimerHistoryReader(db, tokens, sessions).read(edited))
        assertEquals(4, activeFull.history.logs.size)
    }

    @Test(timeout = 150_000)
    fun realNestedTimerUsesTheSameFiniteCountsAndOriginalActionWhileKeepingTheFullHistoryApi() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure()
        val repo = creatingHabits()
        val parentId = repo.createHabit("Timer parent", "", HabitType.GOAL, 0, "#123456", HabitSchedule.Daily,
            appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
            completionPolicy = null, creationAuthority = creator.capture())
        val parent = requireNotNull(db.habitDao().getHabitById(parentId))
        val edit = repo.getHabitForEditing(timerHabit.id)
        repo.updateHabit(requireNotNull(edit.value).copy(parentHabitId = parent.uuid), editAuthority = edit.authority)
        timerHabit = current()
        complete(300, DateTimeUtils.today(), 60_500)
        val row = current(); val original = full(row); val expected = originalStats(row, original)
        val tree = NestedHabitTreeBuilder(db.habitDao(), db.completionDao(), db.timeLogDao(),
            FailureChecker(db.completionDao(), db.timeLogDao()), onceRepository(), timerWriter = writer())
        requireNotNull(writer().statusDisplaySnapshot(row))
        val before = proof(); details.set(0)
        val built = tree.build(listOf(parent), db.completionDao().getAllCompletionsOnce()).single()
        val child = built.children.single()
        assertEquals(expected.completedToday, child.completedToday); assertEquals(expected.todayCount, child.todayCount)
        assertEquals(expected.currentStreak, child.currentStreak); assertEquals(expected.bestStreak, child.bestStreak)
        assertEquals(expected.activityRate, child.activityRate); assertEquals(expected.isCheckInAllowed, child.isCheckInAllowed)
        assertEquals(expected.nextCheckInDate, child.nextCheckInDate); assertEquals(expected.targetProgress, child.targetProgress)
        assertEquals(expected.hasFailed, child.hasFailed); assertEquals(expected.timerAuthority, child.timerAuthority)
        assertNull(child.lastCompletionId); assertNull(child.countHistory)
        assertEquals(1, built.completedChildren); assertEquals(1, built.totalChildren)
        assertEquals(0, details.get()); assertEquals(before, proof())
        val detail = RecurringHabitReader(db, tokens, sessions, editor).read(row)
        assertEquals(original.history, detail.timerHistory)
        assertEquals(1, requireNotNull(detail.timerHistory).logs.size)
        assertEquals(before, proof())
    }

    @Test(timeout = 150_000)
    fun stalePlanAndRealFinalAccessFailureCannotPublishAProjectionOrMutateBusinessState() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(); val today = DateTimeUtils.today(); val authority = startAt(400, today)
        val old = current(); requireNotNull(writer().statusDisplaySnapshot(old))
        val before = proof()
        decrypts.set(0); rejectAt = 2
        try {
            assertEquals("RECURRING_READ_SESSION_CHANGED", refused { status().calculate(old) }.message)
            assertEquals(2, decrypts.get())
        } finally { rejectAt = -1 }
        assertEquals(before, proof())
        assertEquals(authority, requireNotNull(writer().statusDisplaySnapshot(old)).authority)
        val ticket = editingHabits().getHabitForEditing(timerHabit.id)
        editingHabits().updateHabit(requireNotNull(ticket.value).copy(name = "Changed timer display"), editAuthority = ticket.authority)
        val changed = proof()
        val stale = status().calculate(old)
        assertNull(stale.timerAuthority); assertFalse(stale.isCheckInAllowed)
        assertEquals(changed, proof())
        val freshRow = current(); val fresh = requireNotNull(writer().statusDisplaySnapshot(freshRow))
        sessions.exclusive { tokens.saveLoginSession("synthetic-new", "synthetic-refresh", "member", id(1), false) }
        val afterAuth = proof()
        refused { writer().requireAction(timerHabit.id, fresh.authority) }
        assertEquals(afterAuth, proof())
        assertEquals(fresh.activeLog, db.timeLogDao().getActiveTimeLogForHabit(timerHabit.id))
        assertEquals(0, db.metricLogDao().countAll())
    }

    @Test(timeout = 150_000)
    fun finiteWidgetPublicationKeepsTheAccountLockAndPropagatesRealRendererFailureAndCancellation() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(); startAt(500, DateTimeUtils.today())
        val row = current(); val writer = writer()
        requireNotNull(writer.statusDisplaySnapshot(row))
        val before = proof()
        val original = java.io.IOException("private display storage failure")
        assertSame(original, refused { writer.renderWidgetDisplaySnapshot(row) {
            assertFalse(db.inTransaction()); throw original
        } })
        assertEquals(before, proof())
        val entered = CompletableDeferred<Unit>()
        val attempted = CompletableDeferred<Unit>()
        val order = mutableListOf<String>() // Original account mutex serializes these writes.
        val rendering = launch(Dispatchers.IO) {
            writer.renderWidgetDisplaySnapshot(row) { snapshot ->
                assertFalse(db.inTransaction()); assertEquals(row, snapshot.habit)
                assertEquals(id(500), snapshot.authority.sessionUuid)
                order += "render"; entered.complete(Unit)
                awaitCancellation()
            }
        }
        val transition = launch(Dispatchers.IO) {
            entered.await(); attempted.complete(Unit)
            sessions.exclusive {
                order += "reauth"
                tokens.saveLoginSession("synthetic-new", "synthetic-refresh", "member", id(1), false)
            }
        }
        try {
            withTimeout(45_000) { attempted.await() }
            assertFalse(transition.isCompleted); assertFalse(rendering.isCompleted)
            withTimeout(5_000) { rendering.cancelAndJoin() }
            assertTrue(rendering.isCancelled)
            withTimeout(5_000) { transition.join() }
            assertEquals(listOf("render", "reauth"), order)
            assertEquals(before, proof())
            assertEquals(id(500), db.timeLogDao().getActiveTimeLogForHabit(timerHabit.id)?.uuid)
            assertEquals(1, db.timeLogDao().getPendingTimerCommands().size)
        } finally {
            withTimeout(5_000) { rendering.cancelAndJoin(); transition.cancelAndJoin() }
        }
    }
}
