package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production origins, effective rows and account snapshots; faults never modify the formal app. */
@RunWith(AndroidJUnit4::class)
class CountFactEvidenceTest : NextObjectEditorFixture() {
    private suspend fun current() = requireNotNull(db.habitDao().getHabitById(habit.id))
    private suspend fun history() = CountHistoryReader(db, tokens, sessions).read(current())
    private suspend fun fault(block: suspend () -> Unit) = db.withTransaction {
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        block()
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
    }
    private fun durable(): Map<String, List<List<String?>>> {
        val sql = db.openHelper.readableDatabase
        return listOf("completions", "count_days", "sync_outbox", "next_request_origins", "sync_entity_state").associateWith { table ->
            sql.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
            }
        }
    }
    private suspend fun rejectsWithoutWrites() {
        val before = durable()
        val error = runCatching { history() }.exceptionOrNull()
        assertNotNull("Damaged effective quantity must not enter statistics", error)
        assertEquals(before, durable())
    }

    @Test fun validRangeQuantityDateZoneAndTimestampChangesCannotMasqueradeAsTheOriginalFact() = runBlocking<Unit> {
        val repo = creatingHabits()
        repo.logCompletion(app, habit.id, 2); repo.logCompletion(app, habit.id, 3)
        val original = db.completionDao().getByHabitOnce(habit.id).last()
        val otherZone = if (original.recordedTimezone == "Pacific/Honolulu") "UTC" else "Pacific/Honolulu"
        val queue = db.syncOutboxDao().getAll()
        assertEquals(5L, history().todayQuantity)
        for (assignment in listOf("value=4", "actualCompletedAt=actualCompletedAt+1", "date=date+1",
            "recordedLocalDate='2030-01-01'", "recordedTimezone='$otherZone'", "actualCompletedAt=NULL",
            "actualCompletedAt=actualCompletedAt+0.5", "createdAt=createdAt+0.5", "oneTimeExpectedVersion=1")) {
            fault { db.openHelper.writableDatabase.execSQL("UPDATE completions SET $assignment WHERE id=?", arrayOf(original.id)) }
            rejectsWithoutWrites()
            fault { db.completionDao().upsert(original) }
            assertEquals(5L, history().todayQuantity)
        }
        assertEquals(queue, db.syncOutboxDao().getAll())
    }

    @Test fun everyKnownFactNeedsItsOwnOriginalAndAnotherOwnersOriginalCannotAuthorizeIt() = runBlocking<Unit> {
        creatingHabits().logCompletion(app, habit.id, 2); creatingHabits().logCompletion(app, habit.id, 3)
        val fact = db.completionDao().getByHabitOnce(habit.id).last()
        val queued = db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid }
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, queued.operationId))
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_request_origins WHERE kind=? AND requestId=?",
            arrayOf(NEXT_OPERATION, origin.requestId))
        rejectsWithoutWrites()
        db.nextRequestDao().insertOrigin(origin)
        assertEquals(5L, history().todayQuantity)
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE kind=? AND requestId=?",
            arrayOf(id(99), NEXT_OPERATION, origin.requestId))
        rejectsWithoutWrites()
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE kind=? AND requestId=?",
            arrayOf(origin.accountId, NEXT_OPERATION, origin.requestId))
        storage.reopen()
        assertEquals(5L, history().todayQuantity)
        assertEquals(CountDayPolicy(10, false), history().todayPolicy)
    }

    @Test fun duplicateEffectiveUuidCannotDoubleTheSameQuantity() = runBlocking<Unit> {
        creatingHabits().logCompletion(app, habit.id, 2)
        val fact = db.completionDao().getByHabitOnce(habit.id).single()
        var duplicate = 0L
        fault { duplicate = db.completionDao().insertForSync(fact.copy(id = 0)) }
        rejectsWithoutWrites()
        fault { db.completionDao().delete(requireNotNull(db.completionDao().getCompletionById(duplicate))) }
        assertEquals(2L, history().todayQuantity)
    }

    @Test fun legacyUnknownAndCheckInsBeforeATrackingModeEditRemainUnknownActualHistory() = runBlocking<Unit> {
        val yesterday = DateTimeUtils.today().minusDays(1)
        val stamp = yesterday.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        producer().write(local()) {
            db.habitDao().update(current().copy(habitType = HabitType.CHECK_IN))
            db.completionDao().insertForSync(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
                date = yesterday.toDisplayMillis(), actualCompletedAt = stamp))
        }
        producer().write(local()) { db.habitDao().update(current().copy(habitType = HabitType.COUNTING)) }
        fault { db.completionDao().insertForSync(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
            date = yesterday.toDisplayMillis(), value = 4, recordedLocalDate = yesterday.toString(),
            timeMetadataSource = "legacy_device_fallback")) }
        val before = durable()
        val result = history()
        assertEquals(5L, result.quantities[yesterday])
        assertEquals(setOf(yesterday), result.unknownDates); assertTrue(result.qualifiedDates.isEmpty())
        assertTrue(result.policies.isEmpty()); assertEquals(before, durable())
    }

    @Test fun originBatchesCrossTheirAllocationBoundaryWithoutTruncatingEffectiveHistory() = runBlocking<Unit> {
        val h = current()
        val today = DateTimeUtils.today()
        val stamp = today.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        repeat(129) {
            producer().write(local()) {
                val fact = CompletionEntity(habitId = h.id, habitUuid = h.uuid, date = today.toDisplayMillis(),
                    actualCompletedAt = stamp)
                NextCountDayStore(db).capture(h, fact); db.completionDao().insertForSync(fact)
            }
        }
        val before = durable()
        val result = history()
        assertEquals(129L, result.todayQuantity); assertEquals(129, result.completions.size)
        assertEquals(CountDayPolicy(10, false), result.todayPolicy); assertTrue(result.completedToday)
        assertEquals(before, durable())
    }
}
