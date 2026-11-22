package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Adapter/full-merger differential plus native write effects. The fixture builds membership
 * from the whole test snapshot; it does NOT claim that these ordinary lists are certificates.
 * Production root authority and closure are exercised separately by the kernel boundary suite.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustAckTest : NextCoreRequestFixture() {
    private data class Request(val queue: SyncOutboxEntity, val operation: SyncV2Operation,
        val change: SyncV2Change, val device: String)
    private suspend fun request(row: SyncOutboxEntity): Request {
        val (_, delivery) = success(row)
        val operation = decodeNextOperationIntent(originalIntent(row).intentJson)
        val device = requireNotNull(delivery.access.deviceId)
        return Request(row, operation, NextOrdinaryResultMapper.validate(operation, delivery.result.results.single(), device), device)
    }
    private suspend fun adapter(): CountTrustAck {
        check(db.inTransaction())
        val native = CountTrustRows(db.openHelper.writableDatabase)
        val all = CountTrustSchema.BUSINESS_TABLES.flatMap { native.read(it) }
        val expected = linkedMapOf<String, MutableList<CountTrustRows.Row>>()
        for (row in all) for (key in CountTrustMembership.memberships(row).keys)
            expected.getOrPut(key) { mutableListOf() }.add(row)
        return CountTrustAck(db) { key, table, where, args ->
            val members = expected[key].orEmpty()
            val actual = if (where.isEmpty()) members.map { requireNotNull(native.byIdentity(table, it.identity)) }
                else native.read(table, where, args)
            assertEquals(members.associate { it.name to it.value }, actual.associate { it.name to it.value })
            actual
        }
    }
    private fun snapshot(id: String): Map<String, List<JsonObject>> {
        val native = CountTrustRows(db.openHelper.writableDatabase)
        return CountTrustSchema.BUSINESS_TABLES.associateWith { table -> native.read(table).map { row ->
            // Only the oracle's local receipt timestamp is intentionally nondeterministic.
            if (table == "sync_entity_state" && row.text("entityType") == "activity_event" && row.text("entityUuid") == id)
                JsonObject(row.value - "updatedAt") else row.value
        } }
    }
    private class Rollback(val result: Map<String, List<JsonObject>>) : RuntimeException()
    private suspend fun compare(request: Request) {
        val before = db.withTransaction { snapshot(request.change.entityUuid) }
        val expected = try {
            db.withTransaction {
                assertTrue(CountTrustAck.globalCommonHealth(db))
                val adapter = adapter()
                val plan = requireNotNull(adapter.prepare(request.change, request.operation, request.device, request.queue.id, 123456))
                val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
                val boundary = runtime.maintenance(db) { runtime.captureChangeBoundary() }
                // Match the production owner: ordinary Room writes do not borrow proof-maintenance
                // authority. Both journal observations retain this outer transaction and caller Job.
                val effects = adapter.apply(plan)
                val actual = runtime.maintenance(db) { runtime.pendingChanges(boundary) }
                assertEquals(effects.size, actual.size)
                val native = CountTrustRows(db.openHelper.writableDatabase)
                effects.zip(actual).forEach { (effect, change) ->
                    val table = native.table(change.table)
                    val fields = CountTrustJournal.COLUMNS.getValue(change.table)
                    fun values(cells: List<CountTrustJournal.Cell>?) = cells?.let {
                        CountTrustRows.Row(table, table.columns.map { column -> it[fields.indexOf(column)] }).value
                    }
                    assertEquals(effect.table, change.table)
                    assertEquals(effect.before?.value, values(change.old))
                    assertEquals(effect.after?.value, values(change.new))
                }
                throw Rollback(snapshot(request.change.entityUuid))
            }
        } catch (rollback: Rollback) { rollback.result }
        assertEquals(before, db.withTransaction { snapshot(request.change.entityUuid) })
        db.withTransaction {
            NextCommonFactStore(db).restoreInTransaction(listOf(request.change), request.device, request.queue.id, request.operation)
            assertEquals(expected, snapshot(request.change.entityUuid))
        }
    }

    @Test fun deltaAdapterMatchesAllBusinessRowsAndExactNativeEffects() = runBlocking<Unit> {
        compare(request(completion()))
    }
    @Test fun snapshotAdapterMatchesAllBusinessRowsAndExactNativeEffects() = runBlocking<Unit> {
        val request = request(completion(countdown = true))
        assertEquals(JsonPrimitive("count_snapshot"), request.change.payload["event_type"])
        compare(request)
    }
    @Test fun checkInRetainedAfterCountModeChangeMatchesOriginal() = runBlocking<Unit> {
        val request = request(completion(type = HabitType.CHECK_IN))
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(habitType = HabitType.COUNTING)
            db.habitDao().update(habit)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(JsonPrimitive("check_in"), request.change.payload["event_type"])
        compare(request)
    }
    @Test fun lateAckAfterUndoKeepsDeletionAndOriginalDay() = runBlocking<Unit> {
        val request = request(completion())
        producer().write(local()) { db.completionDao().delete(requireNotNull(db.completionDao().getCompletionByUuid(request.change.entityUuid))) }
        compare(request)
        assertNull(db.completionDao().getCompletionByUuid(request.change.entityUuid))
        assertNotNull(db.countDayDao().get(habit.id, "2026-10-06"))
    }
    @Test fun newlyAcceptedTargetMustCloseExistingRevertEdgesWithinTheSameActivity() = runBlocking<Unit> {
        val request = request(completion())
        producer().write(local()) {
            db.completionDao().delete(requireNotNull(db.completionDao().getCompletionByUuid(request.change.entityUuid)))
        }
        val revertId = id(903)
        val payload = JsonObject((request.change.payload - "count_policy") + mapOf(
            "public_id" to JsonPrimitive(revertId), "activity_uuid" to JsonPrimitive(timerHabit.uuid),
            "event_type" to JsonPrimitive("revert"), "value" to JsonNull,
            "reverts_event_uuid" to JsonPrimitive(request.change.entityUuid)))
        val revert = request.change.copy(entityUuid = revertId, payload = payload)
        NextCommonFactMapper.validateOrdinaryFactSnapshot(revert)
        db.withTransaction {
            db.syncOutboxDao().upsertState(SyncEntityStateEntity("activity_event", revertId, revert.revision,
                payloadJson = payload.toString(), payloadHash = syncPayloadHash(payload.toString())))
            // Before this ACK the target shadow is absent, so the old graph is valid.
            assertNull(db.syncOutboxDao().getState("activity_event", request.change.entityUuid))
            assertTrue(CountTrustAck.globalCommonHealth(db))
        }
        val before = db.withTransaction { snapshot(request.change.entityUuid) }
        val fast = rejected { db.withTransaction {
            adapter().prepare(request.change, request.operation, request.device, request.queue.id, 123456)
        } }
        val original = rejected { db.withTransaction {
            NextCommonFactStore(db).restoreInTransaction(listOf(request.change), request.device,
                request.queue.id, request.operation)
        } }
        assertTrue(fast is IllegalArgumentException)
        assertEquals(original.javaClass, fast.javaClass)
        assertEquals(before, db.withTransaction { snapshot(request.change.entityUuid) })
        // A legitimate same-activity dangling edge remains supported: accepting its
        // target must retain the shadow without resurrecting the undone local fact.
        val matching = JsonObject(payload + ("activity_uuid" to JsonPrimitive(habit.uuid)))
        db.withTransaction {
            db.syncOutboxDao().upsertState(SyncEntityStateEntity("activity_event", revertId, revert.revision,
                payloadJson = matching.toString(), payloadHash = syncPayloadHash(matching.toString())))
        }
        compare(request)
        assertNull(db.completionDao().getCompletionByUuid(request.change.entityUuid))
        assertNotNull(db.syncOutboxDao().getState("activity_event", request.change.entityUuid))
    }
    @Test fun revertUsesOriginalFallbackWithoutAdapterMutation() = runBlocking<Unit> {
        val first = completion()
        val (core, accepted) = success(first)
        assertEquals(NextOperationAcceptance.COMMITTED, core.acceptOperation(accepted))
        producer().write(local()) { db.completionDao().delete(requireNotNull(db.completionDao().getCompletionByUuid(first.entityUuid))) }
        val request = request(db.syncOutboxDao().getAll().single())
        db.withTransaction {
            val before = snapshot(request.change.entityUuid)
            assertNull(adapter().prepare(request.change, request.operation, request.device, request.queue.id, 123456))
            assertEquals(before, snapshot(request.change.entityUuid))
        }
    }
    @Test fun globalHealthIsReadOnlyRejectsUnrelatedHeaderAndRetainsMaterializationFallback() = runBlocking<Unit> {
        val request = request(completion())
        db.withTransaction { NextCommonFactStore(db).restoreInTransaction(listOf(request.change), request.device, request.queue.id, request.operation) }
        db.withTransaction {
            val before = snapshot(request.change.entityUuid)
            assertTrue(CountTrustAck.globalCommonHealth(db))
            assertEquals(before, snapshot(request.change.entityUuid))
        }
        val malformed = JsonObject(request.change.payload + mapOf("public_id" to JsonPrimitive(id(900)), "revision" to JsonPrimitive(2)))
        val failure = rejected { db.withTransaction {
            db.syncOutboxDao().upsertState(SyncEntityStateEntity("activity_event", id(900), 1,
                payloadJson = malformed.toString(), payloadHash = syncPayloadHash(malformed.toString())))
            CountTrustAck.globalCommonHealth(db)
        } }
        assertTrue(failure is IllegalArgumentException)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("DELETE FROM count_days WHERE habitId=?", arrayOf(habit.id))
            val before = snapshot(request.change.entityUuid)
            assertFalse(CountTrustAck.globalCommonHealth(db))
            assertEquals(before, snapshot(request.change.entityUuid))
            NextCommonFactStore(db).restoreInTransaction(listOf(request.change), request.device, request.queue.id, request.operation)
            assertTrue(CountTrustAck.globalCommonHealth(db))
        }
    }
}
