package com.dayforge.data.repository

import android.database.Cursor
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic retained-origin/shadow absence counterexamples. Every donor row comes
 * unmodified from a real count→outbox→send→ACK terminal accepted by the original reader.
 * Restoring only P and the old marker is a deliberate hostile fixture, not an admitted
 * writer or a claim of rollback resistance. Ordinary integrity/index/row-type checks pass.
 */
@RunWith(AndroidJUnit4::class)
class G0CountRootFirstBirthCounterexampleTest : G0CountRootWriteFixture() {
    @Test fun orphanedLegitimateOriginForAnUnstartedDayMustPreventASecondBirth() = runBlocking<Unit> {
        retainedDayEvidenceMustReject("next_request_origins")
    }

    @Test fun orphanedLegitimateAcceptedShadowForAnUnstartedDayMustPreventASecondBirth() = runBlocking<Unit> {
        retainedDayEvidenceMustReject("sync_entity_state")
    }

    private suspend fun retainedDayEvidenceMustReject(table: String) {
        require(table in setOf("next_request_origins", "sync_entity_state"))
        val seed = seedHouseholdAccepted(0); val row = seed.habits[0]
        val (http, _) = channel { successReply(it) }
        issueWriteRoot(http)
        val initial = head()
        val initialQueueId = nativeRoot { raw -> raw.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='sync_outbox'", null).use {
            if (!it.moveToFirst()) 0L else it.getLong(0).also { _ -> check(!it.moveToNext()) }
        } }
        val pristine = rootImage()
        val donor = openWriter().store
        // Use the SECOND real queue row. The first queue ID would equal the upcoming
        // cold writer's next ID and be rejected by its unrelated queue-ID absence check.
        writerAck(donor, http, donor.increase(row.uuid))
        val second = donor.increase(row.uuid)
        val delivered = writerAck(donor, http, second)
        val columns = G0DomainSqlCatalog.columns.getValue(table)
        val where = if (table == "next_request_origins") "kind=? AND requestId=?" else "entityType=? AND entityUuid=?"
        val args = if (table == "next_request_origins") arrayOf(NEXT_OPERATION, second.requestId)
            else arrayOf("activity_event", second.factUuid)
        val native = nativeRoot { sql ->
            sql.rawQuery("SELECT ${columns.joinToString(",") { "`$it`" }} FROM `$table` WHERE $where", args).use { cursor ->
                check(cursor.moveToFirst())
                List<Any?>(cursor.columnCount) { column -> when (cursor.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).copyOf()
                    else -> error("unexpected donor native type")
                } }.also { check(!cursor.moveToNext()) }
            }
        }
        if (table == "next_request_origins") assertEquals(Math.addExact(initialQueueId, 2L), native[columns.indexOf("queueId")])
        assertWriterMatchesOriginal(donor, seed.today)
        val donorTerminal = pageNativeState()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivered))
        assertEquals(donorTerminal, pageNativeState())

        restoreRootImage(pristine)
        editRootFile { raw ->
            raw.execSQL("INSERT INTO `$table` (${columns.joinToString(",") { "`$it`" }}) VALUES (${columns.joinToString(",") { "?" }})",
                native.toTypedArray())
            restoreMarkerForFault(raw, initial.marker)
        }
        // Source/shadow tables do not allocate a fact or queue ID. Only one complete donor
        // row was inserted, so C/Q, native facts/day/queue remain empty and the original allocator is unchanged.
        assertEquals(0, pageRowCount("completions")); assertEquals(0, pageRowCount("count_days"))
        assertEquals(0, pageRowCount("sync_outbox")); assertEquals(1, pageRowCount(table))
        val signed = head()
        assertEquals(initial.root, signed.root); assertEquals(initial.marker, signed.marker)
        assertArrayEquals(initial.packet.bodyBytes(), signed.packet.bodyBytes())
        assertArrayEquals(initial.packet.authenticationBytes(), signed.packet.authenticationBytes())
        val corrupted = pageNativeState()

        // Same exact native image, independent original predicate and actual producer.
        // They reject the known day evidence; serializer/account validity is not the reason.
        reopenReference()
        val account = local().authentication.userId
        val original = runCatching { sessions.exclusive { db.withTransaction {
            NextCountDayStore(db).hasUnboundHistory(requireNotNull(db.habitDao().getHabitById(row.id)),
                seed.today.toString(), account)
        } } }.exceptionOrNull()
        assertNotNull(original); assertTrue(hasCause(requireNotNull(original), "COUNT_DAY_INVALID"))
        assertEquals(corrupted, pageNativeState())
        val producerFailure = runCatching { originalHouseholdFact(
            requireNotNull(db.habitDao().getHabitById(row.id)), seed.today, seed.zone) }.exceptionOrNull()
        assertNotNull(producerFailure); assertTrue(hasCause(requireNotNull(producerFailure), "COUNT_DAY_INVALID"))
        assertEquals(corrupted, pageNativeState())

        val writer = openWriter().store // Negative premise: current/allocator cold checks pass.
        val command = writer.newCommand(row.uuid)
        assertNotEquals(second.requestId, command.requestId); assertNotEquals(second.factUuid, command.factUuid)
        val failure = runCatching { writer.increase(command) }.exceptionOrNull()
        val after = pageNativeState()
        val facts = pageRowCount("completions"); val days = pageRowCount("count_days"); val queues = pageRowCount("sync_outbox")
        val diagnostic = "table=$table original=COUNT_DAY_INVALID candidateRejected=${failure != null} " +
            "nativeUnchanged=${after == corrupted} facts=$facts days=$days outbox=$queues"
        Log.i("G0_FIRST_BIRTH_COUNTEREXAMPLE", diagnostic)
        assertTrue("First birth must reject retained day evidence without writes: $diagnostic",
            failure != null && after == corrupted && facts == 0 && days == 0 && queues == 0)
    }
}
