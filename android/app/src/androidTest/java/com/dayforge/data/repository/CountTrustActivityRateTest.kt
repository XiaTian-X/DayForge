package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.ReplicaSwitchPhase
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.replicaStageFixture
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.CheckInService
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The real repository's derived habit UPDATE is part of the incremental count transaction.
 * Independent full-row arithmetic remains the oracle; query observations are reset before it.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustActivityRateTest : NextObjectEditorFixture() {
    private val fullDates = AtomicInteger()
    private val fullProof = AtomicInteger()
    private val exactDates = AtomicInteger()
    init {
        storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                val query = sqlQuery.lowercase().replace("`", "").replace(Regex("\\s+"), " ").trim()
                if (query.startsWith("select * from completions where habitid = ? order by")) fullDates.incrementAndGet()
                if (query.startsWith("select value,habituuid,onetimeaction,recordedlocaldate,")) fullProof.incrementAndGet()
                if (query.startsWith("select exists(select 1 from completions where habitid=? and recordedlocaldate=?)"))
                    exactDates.incrementAndGet()
            }
        }) }
    }
    private val sql get() = db.openHelper.writableDatabase
    private fun dirty() = sql.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private fun root() = requireNotNull(CountTrustSeal.read(sql))
    private suspend fun current() = requireNotNull(db.habitDao().getHabitById(habit.id))
    private fun actions() = CheckInService(creatingHabits(), db.completionDao(), db.timeLogDao())
    private fun resetCounts() { fullDates.set(0); fullProof.set(0); exactDates.set(0) }
    private fun fact(number: Int, date: LocalDate): CompletionEntity {
        val zone = ZoneId.systemDefault()
        val at = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(61_000 + number),
            date = date.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = at,
            recordedTimezone = zone.id, recordedLocalDate = date.toString(), createdAt = at)
    }
    private suspend fun seed(number: Int, date: LocalDate): CompletionEntity {
        val row = fact(number, date)
        producer().write(local()) { NextCountDayStore(db).capture(current(), row); db.completionDao().insert(row) }
        return requireNotNull(db.completionDao().getCompletionByUuid(row.uuid))
    }
    private suspend fun qualify() {
        CountHistoryReader(db, tokens, sessions).readSummary(current())
        assertEquals(CountTrustKernel.lastQualificationFailure, 0L, dirty())
        resetCounts()
    }
    private suspend fun assertOriginalRate() {
        val saved = current()
        val rows = db.completionDao().getCompletionsByHabit(saved.id).first()
        assertEquals(ActivityRateCalculator.calculate(saved.schedule, saved.createdAt,
            rows.map { it.businessDate }, saved.activityRateUpdatedAt, ZoneId.systemDefault()), saved.activityRate)
    }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }

    @Test fun ratePreparationCannotEscapeItsCallbackJobOrTransaction() = runBlocking<Unit> {
        register(); qualify(); val before = root()
        assertNull(CountTrustKernel.preparedActivityRate(db, current()))
        producer().write(local()) {
            val row = current()
            assertNull(withContext(Dispatchers.Default) { CountTrustKernel.preparedActivityRate(db, row) })
            assertNotNull(CountTrustKernel.preparedActivityRate(db, row))
        }
        assertNull(CountTrustKernel.preparedActivityRate(db, current()))
        assertEquals(before, root()); assertEquals(0L, dirty())
    }

    @Test fun realServiceIncrementAndUndoMaintainDerivedHabitAndNeverReloadAllFactDates() = runBlocking<Unit> {
        register()
        val today = DateTimeUtils.today()
        seed(1, today.minusYears(1)); seed(2, today.minusDays(3)); seed(3, today.minusDays(1))
        qualify(); val initial = root()
        assertTrue(actions().incrementCount(app, habit.id) is CheckInResult.Success)
        assertEquals(0L, dirty()); val incremented = root(); assertTrue(incremented.revision > initial.revision)
        assertEquals(0, fullDates.get()); assertEquals(0, fullProof.get()); assertTrue(exactDates.get() > 0)
        assertOriginalRate()
        resetCounts()
        assertTrue(actions().decrementCount(app, habit.id) is CheckInResult.Success)
        assertEquals(0L, dirty()); assertTrue(root().revision > incremented.revision)
        assertEquals(0, fullDates.get()); assertEquals(0, fullProof.get()); assertTrue(exactDates.get() > 0)
        assertOriginalRate()
        val original = RecurringCompletionEvidenceReader(db, tokens, sessions).read(current())
        assertTrue(original.completions.none { it.businessDate == today })
        assertEquals(3, original.completions.size)
    }

    @Test fun legitimateReactivationMaintainsOnlyExactPlanSuccessorAndDerivedRate() = runBlocking<Unit> {
        register()
        producer().write(local()) { db.habitDao().updateIsActive(habit.id, false) }
        habit = current(); assertFalse(habit.isActive)
        qualify(); val initial = root()
        assertTrue(actions().incrementCount(app, habit.id) is CheckInResult.Success)
        val saved = current(); assertTrue(saved.isActive)
        assertEquals(0L, dirty()); assertTrue(root().revision > initial.revision)
        assertEquals(0, fullDates.get()); assertEquals(0, fullProof.get()); assertTrue(exactDates.get() > 0)
        val successor = db.syncOutboxDao().getAll().filter { it.recordType == "habit" }.maxBy { it.id }
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, successor.operationId))
        assertEquals(NextStructureMapper.writePlan(saved), decodeNextOperationIntent(origin.intentJson).payload)
        assertOriginalRate()
    }

    @Test fun forgedRateCannotAdvanceAnOtherwiseValidCountRoot() = runBlocking<Unit> {
        register(); qualify(); val before = root()
        val added = fact(10, DateTimeUtils.today())
        producer().write(local()) {
            NextCountDayStore(db).capture(current(), added); db.completionDao().insert(added)
            val derived = requireNotNull(CountTrustKernel.preparedActivityRate(db, current()))
            db.habitDao().updateActivityRate(habit.id, if (derived.rate == 100) 0 else 100, derived.instant)
        }
        assertEquals(before, root()); assertEquals(1L, dirty())
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION,
            db.syncOutboxDao().getAll().single { it.entityUuid == added.uuid }.operationId))
        qualify()
        assertTrue(root().revision > before.revision)
    }

    @Test fun unrelatedHabitRateCannotHideBesideTheCorrectTargetRate() = runBlocking<Unit> {
        register(); qualify(); val before = root()
        val added = fact(14, DateTimeUtils.today())
        producer().write(local()) {
            NextCountDayStore(db).capture(current(), added); db.completionDao().insert(added)
            val derived = requireNotNull(CountTrustKernel.preparedActivityRate(db, current()))
            db.habitDao().updateActivityRate(habit.id, derived.rate, derived.instant)
            db.habitDao().updateActivityRate(timerHabit.id, 12)
        }
        assertEquals(before, root()); assertEquals(1L, dirty())
        assertEquals(12, db.habitDao().getHabitById(timerHabit.id)!!.activityRate)
        assertOriginalRate()
    }

    @Test fun factInsertedAfterRatePreparationInvalidatesTheDerivedResultAndRollsBack() = runBlocking<Unit> {
        register(); qualify(); val before = image(); val beforeRoot = root()
        val first = fact(15, DateTimeUtils.today()); val late = fact(16, DateTimeUtils.today().minusDays(1))
        val failure = runCatching { producer().write(local()) {
            NextCountDayStore(db).capture(current(), first); db.completionDao().insert(first)
            val derived = requireNotNull(CountTrustKernel.preparedActivityRate(db, current()))
            db.habitDao().updateActivityRate(habit.id, derived.rate, derived.instant)
            NextCountDayStore(db).capture(current(), late); db.completionDao().insert(late)
        } }.exceptionOrNull()
        assertEquals("COUNT_TRUST_RATE_CHANGED", failure?.message)
        assertEquals(before, image()); assertEquals(beforeRoot, root()); assertEquals(0L, dirty())
    }

    @Test fun structuralChangeDuringCountNeverBecomesAValidatedDerivedHabitUpdate() = runBlocking<Unit> {
        register(); qualify(); val before = root()
        val added = fact(11, DateTimeUtils.today())
        producer().write(local()) {
            NextCountDayStore(db).capture(current(), added); db.completionDao().insert(added)
            val derived = requireNotNull(CountTrustKernel.preparedActivityRate(db, current()))
            db.habitDao().update(current().copy(name = "Unrelated structural successor", activityRate = derived.rate,
                activityRateUpdatedAt = derived.instant))
        }
        assertEquals(before, root()); assertEquals(1L, dirty())
        assertEquals("Unrelated structural successor", current().name)
        assertTrue(db.syncOutboxDao().getAll().any { it.recordType == "habit" })
    }

    @Test fun unqualifiedMalformedHistoricalDateStillRejectsAndRollsBackTheRealRepositoryWrite() = runBlocking<Unit> {
        register()
        val broken = fact(12, DateTimeUtils.today().minusYears(1)).copy(recordedLocalDate = "invalid-old-date")
        db.completionDao().insert(broken)
        assertNull(CountTrustSeal.read(sql))
        val before = image()
        val failure = runCatching { creatingHabits().logCompletion(app, habit.id) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is DateTimeParseException)
        assertTrue(fullDates.get() > 0); assertEquals(0, exactDates.get())
        assertEquals(before, image())
    }

    @Test fun qualifiedCallbackCannotHideMalformedDateOutsideTheRateWindow() = runBlocking<Unit> {
        register(); val old = seed(13, DateTimeUtils.today().minusYears(1)); qualify()
        val before = image(); val beforeRoot = root()
        val failure = runCatching { producer().write(local()) {
            sql.execSQL("UPDATE completions SET recordedLocalDate=? WHERE id=?", arrayOf<Any>("invalid-old-date", old.id))
            CountTrustKernel.preparedActivityRate(db, current())
        } }.exceptionOrNull()
        assertTrue(failure.toString(), failure is DateTimeParseException)
        assertEquals(before, image()); assertEquals(beforeRoot, root()); assertEquals(0L, dirty())
    }

    @Test fun closedFenceOnlyQualifiesAfterTheMatchingPlainRecoveryCancellationIsComplete() = runBlocking<Unit> {
        register(); qualify(); val initial = root()
        val access = requireNotNull(tokens.localCoreWriteAccess())
        val stages = NextReplicaStageStore(db, tokens, sessions)
        // This exercises actual pre-COMMIT cancellation participants. The candidate/input
        // sentinels are never claimed as archive verification or destination COMMIT evidence.
        val source = db.withTransaction { NextReplicaSourceClosure.storage(
            NextReplicaStorageProof(db).readInTransaction(), db.nextReplicaFenceDao().state()) }
        val beginning = stages.begin(tokens.replicaStageFixture(source))
        NextReplicaWriteFence(db).acquire(beginning.fence)
        val frozen = stages.recordFrozen(beginning)
        suspend fun cannotQualify() {
            assertFalse(ReplicaPresentationRepository(db, tokens, sessions).isOrdinaryOpen())
            sessions.exclusive { db.withTransaction { CountTrustKernel(db, tokens, sessions).prepareInTransaction(access) } }
            assertEquals(initial, root()); assertEquals(1L, dirty())
        }
        cannotQualify()
        NextReplicaWriteFence(db).closeBeforeCommit(frozen.fence)
        cannotQualify() // CLOSED SQL alone cannot bypass the pending pointer.
        assertEquals(ReplicaSwitchPhase.ABORTED, stages.cancel(frozen).phase)
        val previousDatabase = db
        storage.reopen().openHelper.writableDatabase
        assertNotSame(previousDatabase, db)
        // Recreate the real dependency graph after closing its database. Reusing the
        // fixture's editor would mix fresh DAOs with an already closed Room instance.
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
        assertTrue(ReplicaPresentationRepository(db, tokens, sessions).isOrdinaryOpen())
        assertNotNull(tokens.localCoreWriteAccess())
        qualify(); val restored = root(); assertTrue(restored.revision > initial.revision)
        assertTrue(actions().incrementCount(app, habit.id) is CheckInResult.Success)
        assertEquals(0L, dirty()); assertTrue(root().revision > restored.revision)
        assertEquals(0, fullProof.get()); assertEquals(0, fullDates.get())
        assertOriginalRate()
    }
}
