package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native corruptions below are rejection inputs only. All positive originals come from
 * the real producer and any acceptance comes through SyncManager/real loopback HTTP. */
@RunWith(AndroidJUnit4::class)
class CountCommandFormalUndoSourceRejectionTest : CountCommandFormalUndoFixture() {
    private suspend fun alteredIntent(change: (JsonObject) -> JsonObject) {
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, revert.operationId))
        mutation("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
            change(Json.parseToJsonElement(origin.intentJson).jsonObject).toString(), NEXT_OPERATION, revert.operationId)
        rejectedObservation()
        mutation("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?", origin.intentJson, NEXT_OPERATION, revert.operationId)
    }
    @Test(timeout = 150_000) fun bareFactDeletionIsMissingDataAndCannotStandInForARevertOriginal() = runBlocking<Unit> {
        add()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("DELETE FROM completions WHERE id=?", arrayOf(originalFact.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(1, count("next_request_origins")); rejectedObservation()
    }
    @Test(timeout = 150_000) fun acceptedBirthAndMissingFactStillNeedAnActualRevert() = runBlocking<Unit> {
        add(ack = true)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("DELETE FROM completions WHERE id=?", arrayOf(originalFact.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(1, count("next_challenge_births")); assertEquals(1, count("next_acceptances")); rejectedObservation()
    }
    @Test(timeout = 150_000) fun missingOrChangedPendingQueueAndOrphanOriginCannotCreateAnEndDecision() = runBlocking<Unit> {
        add(); undoOriginal(durable = false)
        val source = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, revert.operationId))
        mutation("UPDATE sync_outbox SET lastError='unexplained' WHERE id=?", revert.id); rejectedObservation()
        mutation("UPDATE sync_outbox SET lastError=NULL WHERE id=?", revert.id)
        mutation("DELETE FROM next_request_origins WHERE kind=? AND requestId=?", NEXT_OPERATION, revert.operationId); rejectedObservation()
        db.nextRequestDao().insertOrigin(source)
        mutation("DELETE FROM sync_outbox WHERE id=?", revert.id); rejectedObservation()
    }
    @Test(timeout = 150_000) fun wrongAccountEpochAndCapturedDeviceNeverBorrowTheCurrentSession() = runBlocking<Unit> {
        add(); undoOriginal(durable = false)
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, revert.operationId))
        for ((column, old) in listOf("accountId" to origin.accountId, "serverInstanceId" to origin.serverInstanceId, "syncEpoch" to origin.syncEpoch)) {
            mutation("UPDATE next_request_origins SET $column=? WHERE kind=? AND requestId=?", id(990), NEXT_OPERATION, revert.operationId)
            rejectedObservation()
            mutation("UPDATE next_request_origins SET $column=? WHERE kind=? AND requestId=?", old, NEXT_OPERATION, revert.operationId)
        }
        alteredIntent { JsonObject(it + ("captured_device_id" to JsonPrimitive(id(991)))) }
    }
    @Test(timeout = 150_000) fun wrongActivityTargetAndRoundHeadCannotMatchTheOriginalCount() = runBlocking<Unit> {
        add(); undoOriginal(durable = false)
        for (field in listOf("activity_uuid", "reverts_event_uuid")) alteredIntent { root ->
            val operation = root.getValue("operation").jsonObject
            val payload = JsonObject(operation.getValue("payload").jsonObject + (field to JsonPrimitive(id(992))))
            JsonObject(root + ("operation" to JsonObject(operation + ("payload" to payload))))
        }
        alteredIntent { root ->
            val context = root.getValue("context").jsonObject
            val head = JsonObject(context.getValue("head").jsonObject + mapOf(
                "round_uuid" to JsonPrimitive(id(993)), "generation" to JsonPrimitive(1)))
            JsonObject(root + ("context" to JsonObject(context + ("head" to head))))
        }
    }

    @Test(timeout = 150_000) fun duplicateNativeRevertCandidatesAreAmbiguousEvenWhenEachPointsAtTheSameFact() = runBlocking<Unit> {
        add(); undoOriginal(durable = false)
        val original = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, revert.operationId))
        val root = Json.parseToJsonElement(original.intentJson).jsonObject
        val requestId = UUID.randomUUID().toString(); val eventId = UUID.randomUUID().toString()
        // Deliberate hostile duplicate, never used as a positive or accepted proof.
        val duplicate = revert.copy(id = 0, operationId = requestId)
        db.withTransaction {
            val queueId = db.syncOutboxDao().insert(duplicate)
            val operation = root.getValue("operation").jsonObject
            val context = root.getValue("context").jsonObject
            val body = JsonObject(root + mapOf("operation" to JsonObject(operation + mapOf(
                "operation_id" to JsonPrimitive(requestId), "entity_uuid" to JsonPrimitive(eventId))),
                "context" to JsonObject(context + ("source_uuid" to JsonPrimitive(requestId)))))
            val sourceHash = requireNotNull(NextRequestSql.rowHash(db, "sync_outbox", "id=?", arrayOf(queueId)))
            db.nextRequestDao().insertOrigin(original.copy(requestId = requestId, queueId = queueId, sourceHash = sourceHash, intentJson = body.toString()))
        }
        assertEquals(3, count("next_request_origins")); rejectedObservation()
    }
    @Test(timeout = 150_000) fun acceptedUndoStillRequiresExactReceiptAndWireAfterItsQueueIsGone() = runBlocking<Unit> {
        add(ack = true); undoOriginal(durable = false, ack = true)
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, revert.operationId))
        val transmission = requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, revert.operationId))
        mutation("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?", "0".repeat(64), NEXT_OPERATION, revert.operationId)
        rejectedObservation(); mutation("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?", receipt.resultHash, NEXT_OPERATION, revert.operationId)
        val broken = transmission.wireBytes.copyOf(); broken[broken.lastIndex] = '!'.code.toByte()
        mutation("UPDATE next_transmissions SET wireBytes=? WHERE kind=? AND requestId=?", broken, NEXT_OPERATION, revert.operationId)
        rejectedObservation(); mutation("UPDATE next_transmissions SET wireBytes=? WHERE kind=? AND requestId=?", transmission.wireBytes, NEXT_OPERATION, revert.operationId)
        mutation("DELETE FROM next_acceptances WHERE kind=? AND requestId=?", NEXT_OPERATION, revert.operationId); rejectedObservation()
    }
    @Test(timeout = 150_000) fun actualLostUndoResponseRemainsUnknownAndCannotBeCalledAccepted() = runBlocking<Unit> {
        add(ack = true); undoOriginal(durable = false); revertResponse = RevertResponse.LOST
        syncAll(expectSuccess = false)
        assertNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, revert.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, revert.operationId))
        assertNotNull(db.syncOutboxDao().getByOperationId(revert.operationId))
        sessions.exclusive { db.withTransaction { assertEquals(NextRecoveryRequestState.UNKNOWN_RESULT,
            NextOriginalRequestProofStore(db).pending(access(), NEXT_OPERATION, revert.operationId).state) } }
        rejectedObservation()
    }
    @Test(timeout = 150_000) fun actualPermanentUndoRejectionIsNotPositiveRevertEvidence() = runBlocking<Unit> {
        add(ack = true); undoOriginal(durable = true); revertResponse = RevertResponse.REJECT
        syncAll(expectSuccess = false)
        assertEquals(1, count("next_rejections"))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, revert.operationId))
        sessions.exclusive { db.withTransaction { assertEquals(NextRecoveryRequestState.PERMANENT_REJECTION,
            NextOriginalRequestProofStore(db).pending(access(), NEXT_OPERATION, revert.operationId).state) } }
        rejectedObservation()
    }
    @Test(timeout = 150_000) fun anOldNativeIdSubstituteOrMovedOriginalUuidCannotBeTreatedAsAnAbsentTarget() = runBlocking<Unit> {
        add(); undoOriginal(durable = false)
        val replacements = listOf(originalFact.copy(uuid = UUID.randomUUID().toString()),
            originalFact.copy(id = originalFact.id + 1_000_000))
        for (replacement in replacements) {
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                db.completionDao().insert(replacement)
                sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
            rejectedObservation()
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                sql.execSQL("DELETE FROM completions WHERE id=?", arrayOf(replacement.id))
                sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
        }
    }
    @Test(timeout = 150_000) fun malformedRelatedAcceptedOriginCannotHideBehindAnEarlyEventTypeFilter() = runBlocking<Unit> {
        add(ack = true); undoOriginal(durable = false, ack = true)
        val firstTarget = originalFact.uuid
        val selected = editor.habit(habit.id)
        val secondParent = requireNotNull(creatingHabits().logCompletionWithReceipt(app, habit.id,
            authority = requireNotNull(selected.authority), expectedHabitUuid = habit.uuid).command)
        val secondFact = requireNotNull(db.completionDao().getCompletionByUuid(requireNotNull(secondParent.factUuid)))
        assertNotEquals(firstTarget, secondFact.uuid)
        syncAll()
        creatingHabits().undoCompletion(app, secondFact.id, authority = requireNotNull(editor.habit(habit.id).authority))
        val secondRevert = db.syncOutboxDao().getAll().single { it.action == "delete" && it.entityUuid == secondFact.uuid }
        syncAll()
        assertEquals(4, count("next_acceptances")); assertNull(db.syncOutboxDao().getByOperationId(secondRevert.operationId))
        assertNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, secondRevert.operationId))
        val original = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, secondRevert.operationId))
        sessions.exclusive { db.withTransaction {
            val accepted = NextAcceptedFactProofStore(db).read(access(), secondRevert.operationId).first
            assertEquals(JsonPrimitive(secondFact.uuid), accepted.payload["reverts_event_uuid"])
        } }
        decision() // Both genuine, distinct source chains are healthy before this one mutation.
        val root = Json.parseToJsonElement(original.intentJson).jsonObject
        val operation = root.getValue("operation").jsonObject
        val payload = JsonObject(operation.getValue("payload").jsonObject + mapOf(
            "event_type" to JsonPrimitive("count_delta"), "reverts_event_uuid" to JsonPrimitive(firstTarget)))
        val broken = JsonObject(root + ("operation" to JsonObject(operation + ("payload" to payload))))
        // Corrupt only this real ACKed origin. Its actual receipt/wire/shadow remain original;
        // missing live queue cannot hide an explicit target declaration behind event_type.
        mutation("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
            broken.toString(), NEXT_OPERATION, secondRevert.operationId)
        assertNull(db.syncOutboxDao().getById(revert.id))
        assertNull(db.syncOutboxDao().getByOperationId(revert.operationId))
        assertNull(db.syncOutboxDao().getById(original.queueId))
        assertNull(db.syncOutboxDao().getByOperationId(secondRevert.operationId))
        assertEquals(0, count("sync_outbox")) // Neither A nor B can trip the live targetSources guard.
        val before = image(); val prefs = prefImage()
        refused("COMMAND_FOLLOWUP_REVERT_SOURCE_CHANGED") {
            store().obsoleteFollowUp(parent.reference.native(), preferences)
        } // Exact new branch error; a root/global guard or wrong EndAvailable cannot pass.
        sameImage(before, image()); assertEquals(prefs, prefImage()); pending(store())
    }
}
