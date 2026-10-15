package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.entity.CountDayEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.domain.model.*
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecurringHistoryWorkTest : NextCoreRequestFixture() {
    private val calls = AtomicInteger()
    init { storage.queryCallback = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) { calls.incrementAndGet() }
    } }
    private val sql get() = db.openHelper.writableDatabase
    private suspend fun seed(n: Int, padding: Int = 0): CountDayEntity {
        val day = CountDayEntity(habit.id, habit.uuid, LocalDate.parse("2026-01-01").plusDays(n.toLong()).toString(),
            10, n % 2 == 0, id(1000 + n))
        val body = buildJsonObject {
            put("activity_uuid", habit.uuid); put("local_date", day.localDate); put("event_type", "count_delta")
            put("count_policy", day.policy.toJson()); if (padding > 0) put("padding", "x".repeat(padding))
        }.toString()
        db.syncOutboxDao().upsertState(SyncEntityStateEntity("activity_event", day.firstEventUuid, 1,
            payloadJson = body, payloadHash = syncPayloadHash(body), updatedAt = 1))
        db.countDayDao().insert(day)
        return day
    }

    @Test fun dailyBatchMatchesIndividualReadsAcrossChunksAndDoesNotCacheBetweenCalls() = runBlocking<Unit> {
        db.withTransaction {
            val days = (0..128).map { seed(it) }
            val store = NextCountDayStore(db)
            calls.set(0)
            val expected = days.map { store.read(habit, it.localDate) }
            val separate = calls.get()
            calls.set(0)
            assertEquals(expected, store.readForHabit(habit))
            assertTrue("Batch must remove source query trips", calls.get() < separate)
            val first = days.first()
            sql.execSQL("UPDATE sync_entity_state SET deleted=1 WHERE entityUuid=?", arrayOf(first.firstEventUuid))
            assertTrue(runCatching { store.readForHabit(habit) }.isFailure)
            sql.execSQL("UPDATE sync_entity_state SET deleted=0 WHERE entityUuid=?", arrayOf(first.firstEventUuid))
            assertEquals(days, store.readForHabit(habit))
        }
    }

    @Test fun acceptedChunkOverBudgetRetainsIndividualFallbackAndAllDays() = runBlocking<Unit> {
        db.withTransaction {
            val days = (0..3).map { seed(it, 600_000) }
            assertEquals(days, NextCountDayStore(db).readForHabit(habit))
        }
    }

    @Test fun dailyBatchKeepsRawTypeSourceHashIdentityPolicyAndMissingSourceRejections() = runBlocking<Unit> {
        db.withTransaction {
            val day = seed(0)
            val store = NextCountDayStore(db)
            for (statement in listOf(
                "UPDATE count_days SET isCountdown=2", "UPDATE count_days SET targetValue=1.5",
                "UPDATE count_days SET targetValue=9", "UPDATE count_days SET habitUuid='bad'",
                "UPDATE sync_entity_state SET deleted=2", "UPDATE sync_entity_state SET payloadHash='bad'",
                "DELETE FROM sync_entity_state"
            )) {
                sql.execSQL("SAVEPOINT invalid_day")
                try {
                    sql.execSQL(statement)
                    assertTrue(runCatching { store.read(habit, day.localDate) }.isFailure)
                    assertTrue(runCatching { store.readForHabit(habit) }.isFailure)
                } finally { sql.execSQL("ROLLBACK TO invalid_day"); sql.execSQL("RELEASE invalid_day") }
            }
            assertEquals(listOf(day), store.readForHabit(habit))
        }
        assertTrue(runCatching { NextCountDayStore(db).readForHabit(habit) }.isFailure)
    }

    @Test fun roundIndexIsPurePerSnapshotAndPreservesNamespaceAndFullChainValidation() = runBlocking<Unit> {
        register()
        val initial = ChallengeRoundRecord(initialChallengeRoundHead(habit.uuid), null, null, null)
        val next = ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, id(701), 1), id(4), id(702),
            ChallengeRestartIntent(habit.uuid, id(701), initial.head.roundUuid, 0, 1))
        fun metadata(records: List<ChallengeRoundRecord>) = ChallengeMetadata(1,
            listOf(ChallengeCheckpoint(records.last().head, records)), listOf(
                ChallengeBirth("activity_event", id(900), initial.head),
                ChallengeBirth("timer_session", id(900), records.last().head)))
        val old = NextRoundWriteScope(access(), Json.encodeToString(metadata(listOf(initial))))
        val fresh = NextRoundWriteScope(access(), Json.encodeToString(metadata(listOf(initial, next))))
        assertSame(old.completionRounds, old.completionRounds)
        assertEquals(initial.head, old.completionRounds.head(habit.uuid))
        assertEquals(next.head, fresh.completionRounds.head(habit.uuid))
        assertEquals(initial.head, fresh.completionRounds.birth(id(900)))
        assertEquals(next.head, fresh.completionRounds.timerBirth(id(900)))
        assertEquals(initial.head, old.completionRounds.timerBirth(id(900)))
        assertEquals(setOf(initial.head, next.head), fresh.completionRounds.knownHeads(habit.uuid))
        assertTrue(fresh.completionRounds.knownHeads(timerHabit.uuid).isEmpty())
        assertNull(fresh.completionRounds.timerBirth(id(901)))
        assertTrue(fresh.completionRounds.contains(habit.uuid, initial.head))
        assertFalse(old.completionRounds.contains(habit.uuid, next.head))
        assertNull(fresh.completionRounds.head(timerHabit.uuid)); assertNull(fresh.completionRounds.birth(id(901)))
        val invalid = Json.encodeToString(metadata(listOf(initial, next)))
            .replace("\"generation\":1", "\"generation\":2")
        assertNotEquals(Json.encodeToString(metadata(listOf(initial, next))), invalid)
        assertTrue(runCatching { CompletionRoundIndex(invalid) }.isFailure)
    }
}
