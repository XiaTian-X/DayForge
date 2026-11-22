package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.domain.model.CountDayPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The SQL/full-history implementation remains the differential oracle. These tests do not
 * certify the ordinary Input lists or replace the persistent directory/closure gate.
 */
@RunWith(AndroidJUnit4::class)
class CountEvidenceRulesDifferentialTest : NextCoreRequestFixture() {
    private suspend fun captured(row: SyncOutboxEntity, delivery: NextCoreDelivery<NextSyncPushResponse>): CountAckFactRules.Input =
        db.withTransaction {
            val operation = decodeNextOperationIntent(originalIntent(row).intentJson)
            val device = requireNotNull(delivery.access.deviceId)
            val change = NextOrdinaryResultMapper.validate(operation, delivery.result.results.single(), device)
            val current = requireNotNull(db.habitDao().getHabitByUuid(habit.uuid))
            val allPending = db.syncOutboxDao().getAll() + db.syncOutboxDao().getDeadLetters()
            val allEvents = db.syncOutboxDao().getStatesForType("activity_event")
            val live = allEvents.filter { !it.deleted }.associate { it.entityUuid to body(it) }
            val ordinaryReverts = live.filterValues { it["event_type"] == JsonPrimitive("revert") &&
                it["one_time"].let { value -> value == null || value == JsonNull } }
            val id = change.entityUuid
            val date = change.payload.text("local_date")
            val countEvents = live.filter { (key, value) -> key != id &&
                value["event_type"] in setOf(JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot")) &&
                value["activity_uuid"] == JsonPrimitive(habit.uuid) && value["local_date"] == JsonPrimitive(date) }
            val isRevert = change.payload["event_type"] == JsonPrimitive("revert")
            val revert = if (!isRevert) null else {
                val target = change.payload.text("reverts_event_uuid")
                val path = mutableListOf<CountAckFactRules.RevertStep>()
                val seen = hashSetOf<String>()
                var cursor: String? = target
                while (cursor != null && cursor in live) {
                    check(seen.add(cursor))
                    val value = live.getValue(cursor)
                    val next = ordinaryReverts[cursor]?.text("reverts_event_uuid")
                    path += CountAckFactRules.RevertStep(cursor, value.text("activity_uuid"), next)
                    cursor = next
                }
                CountAckFactRules.RevertInput(
                    ordinaryReverts.filterValues { it["reverts_event_uuid"] == JsonPrimitive(target) }.keys.singleOrNull(),
                    path, cursor, db.completionDao().getCompletionByUuid(target), db.timeLogDao().getTimeLogByUuid(target),
                    db.syncOutboxDao().getState("activity_event", target), allPending,
                    db.timeLogDao().getPendingTimerCommands(Int.MAX_VALUE) + db.timeLogDao().getRejectedTimerCommands())
            }
            CountAckFactRules.Input(change, operation, device, row.id, 123456L, current,
                db.completionDao().getCompletionByUuid(id), db.timeLogDao().getTimeLogByUuid(id),
                db.syncOutboxDao().getState("activity_event", id), allPending, db.syncConflictDao().getUnresolved(),
                ordinaryReverts.values.any { it["reverts_event_uuid"] == JsonPrimitive(id) },
                if (change.payload["event_type"] in setOf(JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot")))
                    CountAckFactRules.DayInput(db.countDayDao().get(current.id, date),
                        countEvents.values.map { NextCommonFactMapper.countPolicy(it) }.toSet(), countEvents.keys.firstOrNull())
                else null, revert)
        }

    private suspend fun applyOracle(input: CountAckFactRules.Input) = db.withTransaction {
        NextCommonFactStore(db).restoreInTransaction(listOf(input.change), input.deviceId,
            input.acceptedQueueId, input.operation)
    }

    private suspend fun compare(input: CountAckFactRules.Input): CountAckFactRules.Plan {
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        val plan = CountAckFactRules.prepare(input)
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        val beforeFact = db.completionDao().getCompletionByUuid(input.change.entityUuid)
        val beforeShadow = db.syncOutboxDao().getState("activity_event", input.change.entityUuid)
        val beforeDay = db.countDayDao().get(input.habit.id, input.change.payload.text("local_date"))
        applyOracle(input)
        val actual = db.completionDao().getCompletionByUuid(input.change.entityUuid)
        if (plan.completionUpsert == null) assertEquals(beforeFact, actual)
        else {
            assertNotNull(actual)
            assertEquals(plan.completionUpsert.copy(id = requireNotNull(actual).id), actual)
        }
        plan.completionDelete?.let { assertNull(db.completionDao().getCompletionByUuid(it.uuid)) }
        plan.timerDelete?.let { assertNull(db.timeLogDao().getTimeLogByUuid(it.uuid)) }
        val shadow = db.syncOutboxDao().getState("activity_event", input.change.entityUuid)
        if (plan.shadowUpsert == null) assertEquals(beforeShadow, shadow)
        else {
            assertNotNull(shadow)
            // The original merger timestamps its write at execution; the pure plan receives an
            // explicit frozen local timestamp. All server/business fields must match exactly.
            assertEquals(plan.shadowUpsert.copy(updatedAt = requireNotNull(shadow).updatedAt), shadow)
        }
        assertEquals(plan.dayInsert ?: beforeDay,
            db.countDayDao().get(input.habit.id, input.change.payload.text("local_date")))
        db.openHelper.writableDatabase.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        return plan
    }

    @Test fun firstQuantityAckMatchesFullStoreAndPreservesNormalizedDetail() = runBlocking<Unit> {
        val row = completion()
        val (_, delivery) = success(row)
        val input = captured(row, delivery)
        val plan = compare(input)
        val mapped = requireNotNull(plan.completionUpsert)
        val day = requireNotNull(input.day?.existing)
        assertEquals(3, mapped.value)
        assertNotEquals(requireNotNull(input.completion).createdAt, mapped.createdAt)
        assertNull(plan.dayInsert)
        assertEquals(CountDayPolicy(10, false), day.policy)
        val fact = requireNotNull(db.completionDao().getCompletionByUuid(input.change.entityUuid))
        CountFactEvidenceRules.verify(habit, fact, day.policy,
            input.operation.payload, input.change, false)
    }

    @Test fun knownMatchingShadowPreservesReceiptBaseAndMatchesFullStoreAgain() = runBlocking<Unit> {
        val row = completion()
        val (_, delivery) = success(row)
        compare(captured(row, delivery))
        val second = captured(row, delivery)
        val prior = requireNotNull(second.shadow)
        val plan = compare(second)
        assertNull(plan.shadowUpsert)
        assertEquals(prior, db.syncOutboxDao().getState("activity_event", row.entityUuid))
    }

    @Test fun lateQuantityAckAfterLocalUndoDoesNotRecreateCompletion() = runBlocking<Unit> {
        val row = completion()
        val (_, delivery) = success(row)
        val fact = requireNotNull(db.completionDao().getCompletionByUuid(row.entityUuid))
        producer().write(local()) { db.completionDao().delete(fact) }
        val input = captured(row, delivery)
        assertTrue(input.entityPending.any { it.entityUuid == row.entityUuid && it.action == "delete" })
        val plan = compare(input)
        assertNull(plan.completionUpsert)
        assertNull(db.completionDao().getCompletionByUuid(row.entityUuid))
        assertNotNull(db.countDayDao().get(habit.id, fact.recordedLocalDate))
    }

    private suspend fun revertCase(): Pair<SyncOutboxEntity, NextCoreDelivery<NextSyncPushResponse>> {
        val first = completion()
        val (core, accepted) = success(first)
        assertEquals(NextOperationAcceptance.COMMITTED, core.acceptOperation(accepted))
        val fact = requireNotNull(db.completionDao().getCompletionByUuid(first.entityUuid))
        producer().write(local()) { db.completionDao().delete(fact) }
        val row = db.syncOutboxDao().getAll().single()
        return row to success(row).second
    }

    @Test fun revertAckMatchesFullStoreAndKeepsFrozenDayAfterZero() = runBlocking<Unit> {
        val (row, delivery) = revertCase()
        val input = captured(row, delivery)
        val day = db.countDayDao().get(habit.id, "2026-10-06")
        assertTrue(requireNotNull(input.revert).targetPath.isNotEmpty())
        compare(input)
        assertNull(db.completionDao().getCompletionByUuid(input.change.payload.text("reverts_event_uuid")))
        assertEquals(day, db.countDayDao().get(habit.id, "2026-10-06"))
    }

    @Test fun changedNativeFactAndDayPolicyRejectLikeTheOriginalStoreWithoutWrites() = runBlocking<Unit> {
        val row = completion()
        val (_, delivery) = success(row)
        val input = captured(row, delivery)
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        val badFact = input.copy(completion = requireNotNull(input.completion).copy(value = 4))
        assertEquals(NextFactMergeException.Reason.INVALID_LOCAL_STATE,
            (runCatching { CountAckFactRules.prepare(badFact) }.exceptionOrNull() as NextFactMergeException).reason)
        val originalFactError = rejected { db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.completionDao().upsert(requireNotNull(badFact.completion))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            NextCommonFactStore(db).restoreInTransaction(listOf(input.change), input.deviceId, row.id, input.operation)
        } }
        assertEquals(NextFactMergeException.Reason.INVALID_LOCAL_STATE, (originalFactError as NextFactMergeException).reason)
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        val day = requireNotNull(input.day)
        val retainedDay = requireNotNull(day.existing)
        val wrongDay = day.copy(existing = retainedDay.copy(targetValue = 99))
        // Native day damage is a SOURCE-proof rejection, before either business merger may
        // consume a DayInput. A plain copied DTO is not a certified source-closure fixture.
        val originalDayError = rejected { db.withTransaction {
            db.countDayDao().update(requireNotNull(wrongDay.existing))
            val original = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, requireNotNull(retainedDay.originRequestId)))
            assertEquals(CountDayPolicy(10, false), NextCommonFactMapper.countPolicy(decodeNextOperationIntent(original.intentJson).payload))
            assertEquals(CountDayPolicy(99, false), db.countDayDao().get(habit.id, "2026-10-06")!!.policy)
            val sourceError = rejected { NextCountDayStore(db).read(input.habit, "2026-10-06", allowUnbound = true) }
            assertTrue(sourceError is IllegalArgumentException)
            assertEquals("Failed requirement.", sourceError.message)
            assertTrue(sourceError.stackTrace.any { it.className == NextCountDayStore::class.java.name &&
                it.methodName == "readWithAcceptedRows" })
            NextCommonFactStore(db).restoreInTransaction(listOf(input.change), input.deviceId, row.id, input.operation)
        } }
        assertTrue(originalDayError is IllegalArgumentException)
        assertEquals("Failed requirement.", originalDayError.message)
        assertTrue(originalDayError.stackTrace.any { it.className == NextCountDayStore::class.java.name &&
            it.methodName == "readWithAcceptedRows" })
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })

        // The retained day and its original source are now unchanged and internally coherent.
        // Supply a contradictory incoming policy to the two BUSINESS validators; this does not
        // claim that the outer frozen-request/result binder accepts such a server response.
        assertEquals(day.existing, db.withTransaction { NextCountDayStore(db).read(input.habit, "2026-10-06") })
        val conflicting = input.copy(change = input.change.copy(payload = JsonObject(input.change.payload +
            ("count_policy" to CountDayPolicy(99, false).toJson()))))
        assertEquals("COUNT_DAY_POLICY_CONFLICT", runCatching { CountAckFactRules.prepare(conflicting) }.exceptionOrNull()?.message)
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        assertEquals("COUNT_DAY_POLICY_CONFLICT", rejected { applyOracle(conflicting) }.message)
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
    }

    @Test fun unresolvedOwnConflictRejectsLikeTheFullStoreAndLeavesAllRowsUnchanged() = runBlocking<Unit> {
        val row = completion()
        val (_, delivery) = success(row)
        val input = captured(row, delivery)
        val conflict = SyncConflictEntity(operationId = id(903), recordType = "completion",
            localEntityUuid = row.entityUuid, wireEntityUuid = row.entityUuid, entityType = "activity_event",
            action = "upsert", referenceUuid = habit.uuid, baseRevision = 0, serverRevision = 1,
            basePayloadJson = null, localPayloadJson = input.operation.payload.toString(),
            serverPayloadJson = input.change.payload.toString(), conflictingFieldsJson = "[]",
            conflictKind = null, errorCode = null, message = null)
        val failure = runCatching { CountAckFactRules.prepare(input.copy(entityConflicts = listOf(conflict))) }.exceptionOrNull()
        assertEquals(NextFactMergeException.Reason.LOCAL_FACT_PENDING, (failure as NextFactMergeException).reason)
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        val original = rejected { db.withTransaction {
            db.syncConflictDao().insert(conflict)
            NextCommonFactStore(db).restoreInTransaction(listOf(input.change), input.deviceId, row.id, input.operation)
        } }
        assertEquals(NextFactMergeException.Reason.LOCAL_FACT_PENDING, (original as NextFactMergeException).reason)
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
    }

    @Test fun unrelatedAcceptedCountOnSameDayStillParticipatesInPolicyConflict() = runBlocking<Unit> {
        val row = completion()
        val (_, delivery) = success(row)
        val input = captured(row, delivery)
        val other = id(901)
        val conflicting = JsonObject(input.change.payload + mapOf("public_id" to JsonPrimitive(other),
            "count_policy" to CountDayPolicy(99, false).toJson()))
        val shadow = SyncEntityStateEntity("activity_event", other, 1, payloadJson = conflicting.toString(),
            payloadHash = syncPayloadHash(conflicting.toString()))
        val wrongDay = requireNotNull(input.day).copy(otherAcceptedPolicies = setOf(CountDayPolicy(99, false)),
            firstOtherAcceptedEventUuid = other)
        assertEquals("COUNT_DAY_POLICY_CONFLICT", runCatching { CountAckFactRules.prepare(input.copy(day = wrongDay)) }.exceptionOrNull()?.message)
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        assertEquals("COUNT_DAY_POLICY_CONFLICT", rejected { db.withTransaction {
            db.syncOutboxDao().upsertState(shadow)
            NextCommonFactStore(db).restoreInTransaction(listOf(input.change), input.deviceId, row.id, input.operation)
        } }.message)
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
    }

    @Test fun duplicateRevertTargetAndCycleRejectLikeFullGraphValidation() = runBlocking<Unit> {
        val (row, delivery) = revertCase()
        val input = captured(row, delivery)
        val revert = requireNotNull(input.revert)
        val otherId = id(902)
        val duplicateBody = JsonObject(input.change.payload + ("public_id" to JsonPrimitive(otherId)))
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        assertNotNull(runCatching { CountAckFactRules.prepare(input.copy(revert = revert.copy(existingRevertForTarget = otherId))) }.exceptionOrNull())
        rejected { db.withTransaction {
            db.syncOutboxDao().upsertState(SyncEntityStateEntity("activity_event", otherId, 1,
                payloadJson = duplicateBody.toString(), payloadHash = syncPayloadHash(duplicateBody.toString())))
            NextCommonFactStore(db).restoreInTransaction(listOf(input.change), input.deviceId, row.id, input.operation)
        } }
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        val target = input.change.payload.text("reverts_event_uuid")
        val cyclic = revert.copy(targetPath = listOf(CountAckFactRules.RevertStep(target, habit.uuid, input.change.entityUuid)),
            absentPathTerminal = input.change.entityUuid)
        assertNotNull(runCatching { CountAckFactRules.prepare(input.copy(revert = cyclic)) }.exceptionOrNull())
        val oldTarget = requireNotNull(revert.targetShadow)
        val cycleBody = JsonObject(body(oldTarget) + mapOf("event_type" to JsonPrimitive("revert"),
            "reverts_event_uuid" to JsonPrimitive(input.change.entityUuid)))
        rejected { db.withTransaction {
            db.syncOutboxDao().upsertState(oldTarget.copy(payloadJson = cycleBody.toString(), payloadHash = syncPayloadHash(cycleBody.toString())))
            NextCommonFactStore(db).restoreInTransaction(listOf(input.change), input.deviceId, row.id, input.operation)
        } }
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
    }

    @Test fun extractedEvidenceRulesKeepUnknownLegacyAndImmutableSourceRejections() = runBlocking<Unit> {
        val row = completion()
        val fact = requireNotNull(db.completionDao().getCompletionByUuid(row.entityUuid))
        val original = decodeNextOperationIntent(originalIntent(row).intentJson).payload
        val policy = requireNotNull(db.countDayDao().get(habit.id, fact.businessDate.toString())).policy
        CountFactEvidenceRules.verify(habit, fact, policy, original, null, false)
        CountFactEvidenceRules.verify(habit, fact, null, null, null, false)
        assertEquals("COUNT_FACT_INVALID", runCatching { CountFactEvidenceRules.verify(habit, fact, policy, null, null, false) }.exceptionOrNull()?.message)
        assertEquals("COUNT_FACT_INVALID", runCatching { CountFactEvidenceRules.verify(habit, fact, policy, original, null, true) }.exceptionOrNull()?.message)
        assertEquals("COUNT_FACT_INVALID", runCatching { CountFactEvidenceRules.verify(habit, fact.copy(value = 4), policy, original, null, false) }.exceptionOrNull()?.message)
        assertEquals("COUNT_FACT_INVALID", runCatching { CountFactEvidenceRules.verify(habit, fact, CountDayPolicy(99, false), original, null, false) }.exceptionOrNull()?.message)
    }

    private fun body(row: SyncEntityStateEntity) = Json.parseToJsonElement(requireNotNull(row.payloadJson)).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
