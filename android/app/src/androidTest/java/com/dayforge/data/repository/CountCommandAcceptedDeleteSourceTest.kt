package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.NextSyncOperationResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandAcceptedDeleteSourceTest : CountCommandAcceptedDeleteFixture() {
    @Test(timeout = 150_000) fun realAcceptedDeletionColdQualifiesWithoutRestoringRowsAndOnlyExplicitEndCloses() = runBlocking<Unit> {
        add(ack = true); deleteOriginal(); reopen()
        val owner = store(); val proof = deleteDecision(owner); assertNull(owner.closedFollowUp(parent.reference.native()))
        endAndCold(owner, proof)
        assertEquals(1, count("count_command_results")); assertEquals(3, count("next_acceptances"))
        assertNull(db.habitDao().getHabitByUuid(habit.uuid))
    }
    @Test(timeout = 150_000) fun acceptedDeletionClosesTheWholePreparedChildWithBothOriginalIdsAndNoMetricEffect() = runBlocking<Unit> {
        add(ack = true); val child = childPrepared(); val input = row(child.native()).intent.text()
        deleteOriginal(); reopen(); val owner = store(); endAndCold(owner, deleteDecision(owner), child)
        assertEquals(input, row(child.native()).intent.text()); assertNull(row(child.native()).outcome)
        assertEquals(1, count("count_command_results")); assertEquals(0, count("metric_logs"))
    }
    @Test(timeout = 150_000) fun bareMissingHabitAndFactNeverCreateAnAcceptedDeleteWitness() = runBlocking<Unit> {
        add(ack = true); bareDelete(); rejectedObservation()
        assertEquals(1, count("next_acceptances")); assertNotNull(db.syncOutboxDao().getState("activity_event", originalFact.uuid))
    }
    @Test(timeout = 150_000) fun neverSentOriginalDeleteRemainsPending() = runBlocking<Unit> {
        add(ack = true); deleteOriginal(ack = false)
        sessions.exclusive { db.withTransaction { assertEquals(NextRecoveryRequestState.NEVER_SENT,
            NextOriginalRequestProofStore(db).pending(access(), NEXT_OPERATION, deletion.operationId).state) } }
        rejectedObservation()
    }
    @Test(timeout = 150_000) fun realLostDeleteResponseCannotBorrowItsFrozenWireAsAnAck() = runBlocking<Unit> {
        add(ack = true); deleteOriginal(ack = false); deleteResponse = DeleteResponse.LOST; syncAll(expectSuccess = false)
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, deletion.operationId))
        sessions.exclusive { db.withTransaction { assertEquals(NextRecoveryRequestState.UNKNOWN_RESULT,
            NextOriginalRequestProofStore(db).pending(access(), NEXT_OPERATION, deletion.operationId).state) } }
        rejectedObservation()
    }
    @Test(timeout = 150_000) fun realRejectedDeleteCannotBecomeAnExplicitEnd() = runBlocking<Unit> {
        add(ack = true); deleteOriginal(ack = false); deleteResponse = DeleteResponse.REJECT; syncAll(expectSuccess = false)
        sessions.exclusive { db.withTransaction { assertEquals(NextRecoveryRequestState.PERMANENT_REJECTION,
            NextOriginalRequestProofStore(db).pending(access(), NEXT_OPERATION, deletion.operationId).state) } }
        rejectedObservation()
    }
    @Test(timeout = 150_000) fun acceptedTombstoneStillNeedsExactDeleteReceiptAndWire() = runBlocking<Unit> {
        add(ack = true); deleteOriginal()
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, deletion.operationId))
        val sent = requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, deletion.operationId))
        mutation("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?", "0".repeat(64), NEXT_OPERATION, deletion.operationId)
        rejectedObservation(); mutation("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?", receipt.resultHash, NEXT_OPERATION, deletion.operationId)
        val bytes = sent.wireBytes.copyOf(); bytes[bytes.lastIndex] = '!'.code.toByte()
        mutation("UPDATE next_transmissions SET wireBytes=? WHERE kind=? AND requestId=?", bytes, NEXT_OPERATION, deletion.operationId)
        rejectedObservation(); mutation("UPDATE next_transmissions SET wireBytes=? WHERE kind=? AND requestId=?", sent.wireBytes, NEXT_OPERATION, deletion.operationId)
        mutation("DELETE FROM next_acceptances WHERE kind=? AND requestId=?", NEXT_OPERATION, deletion.operationId); rejectedObservation()
    }
    @Test(timeout = 150_000) fun acceptedDeletionDoesNotReplaceOriginalCountAckOrItsRetainedSnapshot() = runBlocking<Unit> {
        add(ack = true); deleteOriginal()
        val original = requireNotNull(row(parent.reference.native()).outcome).origins.single { it.entityUuid == originalFact.uuid }
        val accepted = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, original.requestId))
        mutation("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?", "0".repeat(64), NEXT_OPERATION, original.requestId)
        rejectedObservation(); mutation("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?", accepted.resultHash, NEXT_OPERATION, original.requestId)
        mutation("DELETE FROM sync_entity_state WHERE entityType='activity_event' AND entityUuid=?", originalFact.uuid); rejectedObservation()
    }
    @Test(timeout = 150_000) fun wrongDeletedSourceAccountEpochDeviceAndMissingAncestryCannotIssueC() = runBlocking<Unit> {
        add(ack = true); deleteOriginal()
        val original = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, deletion.operationId))
        mutation("UPDATE next_request_origins SET accountId=? WHERE kind=? AND requestId=?", id(979), NEXT_OPERATION, deletion.operationId)
        rejectedObservation(); mutation("UPDATE next_request_origins SET accountId=? WHERE kind=? AND requestId=?", original.accountId, NEXT_OPERATION, deletion.operationId)
        mutation("UPDATE next_request_origins SET syncEpoch=? WHERE kind=? AND requestId=?", id(978), NEXT_OPERATION, deletion.operationId)
        rejectedObservation(); mutation("UPDATE next_request_origins SET syncEpoch=? WHERE kind=? AND requestId=?", original.syncEpoch, NEXT_OPERATION, deletion.operationId)
        val sent = requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, deletion.operationId))
        mutation("UPDATE next_transmissions SET deviceId=? WHERE kind=? AND requestId=?", id(977), NEXT_OPERATION, deletion.operationId)
        rejectedObservation(); mutation("UPDATE next_transmissions SET deviceId=? WHERE kind=? AND requestId=?", sent.deviceId, NEXT_OPERATION, deletion.operationId)
        mutation("DELETE FROM next_structural_dependencies WHERE operationId=?", deletion.operationId); rejectedObservation()
    }
    @Test(timeout = 150_000) fun malformedRelatedAcceptedReceiptCannotHideBehindAnUnrelatedOriginIdentity() = runBlocking<Unit> {
        add(ack = true)
        val selected = editor.habit(habit.id)
        val second = requireNotNull(creatingHabits().logCompletionWithReceipt(app, habit.id,
            authority = requireNotNull(selected.authority), expectedHabitUuid = habit.uuid).command)
        syncAll(); deleteOriginal(); deleteDecision()
        val source = requireNotNull(row(second.reference.native()).outcome).origins.single { it.entityUuid == second.factUuid }
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.requestId))
        val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
        val tombstone = Json.parseToJsonElement(requireNotNull(db.syncOutboxDao().getState("plan_node", habit.uuid)?.payloadJson)).jsonObject
        // A second REAL accepted request now contradictorily declares this deletion target;
        // original parent COUNT and its legitimate DELETE proofs remain completely intact.
        for (changedResult in listOf(result.copy(entityType = "plan_node", entityUuid = habit.uuid, entity = tombstone),
            result.copy(entity = tombstone))) {
            val changed = json.encodeToString(changedResult)
            mutation("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                changed, nextRequestHash(changed.toByteArray(Charsets.UTF_8)), NEXT_OPERATION, source.requestId)
            val before = image(); val prefs = prefImage(); val owner = store()
            refused("COMMAND_FOLLOWUP_DELETE_SOURCE_CHANGED") { owner.obsoleteFollowUp(parent.reference.native(), preferences) }
            sameImage(before, image()); assertEquals(prefs, prefImage()); pending(owner)
        }
    }

    @Test(timeout = 150_000) fun realAcceptedRestartBeforeDeleteHasNoPendingFrontierAndColdExplicitEndStillSucceeds() = runBlocking<Unit> {
        add(ack = true)
        val reference = acceptRestartBeforeDelete()
        val originalRestart = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, reference.operationId))
        val originalPlan = requireNotNull(db.nextRestartDao().planProof(reference.operationId))
        val originalCount = row(parent.reference.native())
        deleteOriginal(); assertDeleteHasNoPendingRestartFrontier(reference)
        reopen(); assertDeleteHasNoPendingRestartFrontier(reference)
        val owner = store(); val proof = deleteDecision(owner)
        assertNull(owner.closedFollowUp(parent.reference.native()))
        endAndCold(owner, proof)
        assertEquals(originalRestart, db.nextRequestDao().origin(NEXT_OPERATION, reference.operationId))
        assertEquals(originalPlan, db.nextRestartDao().planProof(reference.operationId))
        assertEquals(originalCount.intent.text(), row(parent.reference.native()).intent.text())
        assertEquals(originalCount.outcome!!.text(), row(parent.reference.native()).outcome!!.text())
        assertEquals(4, count("next_acceptances")); assertEquals(1, count("count_command_results"))
        assertNull(db.habitDao().getHabitByUuid(habit.uuid))
    }

    private suspend fun relatedRestartSourceRejected(reference: NextRestartReference) {
        val metadata = sessions.exclusive { db.withTransaction { NextChallengeStore(db).activeInTransaction(access()).second } }
        val record = metadata.checkpoints.single { it.head.activityUuid == habit.uuid }.records
            .single { it.restartOperationUuid == reference.operationId }
        assertEquals(reference.head, record.head) // Native metadata still declares this exact related restart.
        val before = image(); val prefs = prefImage(); val owner = store()
        refused("COMMAND_FOLLOWUP_DELETE_RESTART_SOURCE_CHANGED") {
            owner.obsoleteFollowUp(parent.reference.native(), preferences)
        }
        sameImage(before, image()); assertEquals(prefs, prefImage()); pending(owner)
    }

    @Test(timeout = 150_000) fun acceptedRestartAccountDeviceAndGenerationRemainRequiredWithoutPendingFrontier() = runBlocking<Unit> {
        add(ack = true); val reference = acceptRestartBeforeDelete()
        deleteOriginal(); assertDeleteHasNoPendingRestartFrontier(reference); reopen(); deleteDecision()
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, reference.operationId))
        mutation("UPDATE next_request_origins SET accountId=? WHERE kind=? AND requestId=?",
            id(976), NEXT_OPERATION, reference.operationId)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_request_origins SET accountId=? WHERE kind=? AND requestId=?",
            origin.accountId, NEXT_OPERATION, reference.operationId)
        deleteDecision()
        val proposal = Json.parseToJsonElement(origin.intentJson).jsonObject
        assertEquals(JsonPrimitive(id(4)), proposal.getValue("deviceId"))
        val changed = JsonObject(proposal + ("deviceId" to JsonPrimitive(id(975))))
        mutation("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
            changed.toString(), NEXT_OPERATION, reference.operationId)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
            origin.intentJson, NEXT_OPERATION, reference.operationId)
        deleteDecision()
        val originalProposal = requireNotNull(restartProposal(origin.intentJson))
        val wrongGeneration = originalProposal.copy(head = originalProposal.head.copy(generation = 2),
            expectedHead = originalProposal.head.copy(roundUuid = id(973)))
        val changedGeneration = json.encodeToString(wrongGeneration)
        assertEquals(2, requireNotNull(restartProposal(changedGeneration)).head.generation)
        // Strictly decodable, same activity; its later generation/predecessor contradict real metadata.
        mutation("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
            changedGeneration, NEXT_OPERATION, reference.operationId)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
            origin.intentJson, NEXT_OPERATION, reference.operationId)
        deleteDecision(); assertDeleteHasNoPendingRestartFrontier(reference)
    }

    @Test(timeout = 150_000) fun acceptedRestartMaterializationAndFrozenWireCannotBeReplacedByCurrentMetadata() = runBlocking<Unit> {
        add(ack = true); val reference = acceptRestartBeforeDelete()
        deleteOriginal(); assertDeleteHasNoPendingRestartFrontier(reference); reopen(); deleteDecision()
        val materialized = requireNotNull(db.nextRestartDao().materialization(reference.operationId))
        val sent = requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, reference.operationId))
        mutation("UPDATE next_restart_materializations SET operationHash=? WHERE operationId=?",
            "0".repeat(64), reference.operationId)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_restart_materializations SET operationHash=? WHERE operationId=?",
            materialized.operationHash, reference.operationId)
        deleteDecision()
        mutation("UPDATE next_restart_materializations SET frontierHash=? WHERE operationId=?",
            "0".repeat(64), reference.operationId)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_restart_materializations SET frontierHash=? WHERE operationId=?",
            materialized.frontierHash, reference.operationId)
        deleteDecision()
        val changed = sent.wireBytes.copyOf(); changed[changed.lastIndex] = '!'.code.toByte()
        mutation("UPDATE next_transmissions SET wireBytes=? WHERE kind=? AND requestId=?",
            changed, NEXT_OPERATION, reference.operationId)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_transmissions SET wireBytes=? WHERE kind=? AND requestId=?",
            sent.wireBytes, NEXT_OPERATION, reference.operationId)
        deleteDecision(); assertDeleteHasNoPendingRestartFrontier(reference)
    }

    @Test(timeout = 150_000) fun acceptedRestartReceiptAndContiguousPlanProofRemainRequiredAfterDeletion() = runBlocking<Unit> {
        add(ack = true); val reference = acceptRestartBeforeDelete()
        deleteOriginal(); assertDeleteHasNoPendingRestartFrontier(reference); reopen(); deleteDecision()
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, reference.operationId))
        val planProof = requireNotNull(db.nextRestartDao().planProof(reference.operationId))
        mutation("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?",
            "0".repeat(64), NEXT_OPERATION, reference.operationId)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?",
            receipt.resultHash, NEXT_OPERATION, reference.operationId)
        deleteDecision()
        mutation("UPDATE next_restart_plan_proofs SET planHash=? WHERE operationId=?",
            "0".repeat(64), reference.operationId)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_restart_plan_proofs SET planHash=? WHERE operationId=?",
            planProof.planHash, reference.operationId)
        deleteDecision(); assertDeleteHasNoPendingRestartFrontier(reference)
    }

    @Test(timeout = 150_000) fun changedAcceptedRestartProofCannotUseAnEarlierQualificationToCloseTheFollowUp() = runBlocking<Unit> {
        add(ack = true); val reference = acceptRestartBeforeDelete()
        deleteOriginal(); assertDeleteHasNoPendingRestartFrontier(reference); reopen()
        val owner = store(); val proof = deleteDecision(owner)
        val planProof = requireNotNull(db.nextRestartDao().planProof(reference.operationId))
        mutation("UPDATE next_restart_plan_proofs SET planHash=? WHERE operationId=?",
            "0".repeat(64), reference.operationId)
        val before = image(); val prefs = prefImage()
        val failure = try { owner.endObsoleteFollowUp(proof, preferences) { true }; null }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: IllegalArgumentException) { error }
            catch (error: IllegalStateException) { error }
        assertTrue("A stale C must fail source proof or explicit revalidation", failure?.message in setOf(
            "COMMAND_FOLLOWUP_DELETE_RESTART_SOURCE_CHANGED", "COMMAND_FOLLOWUP_REVALIDATION_REQUIRED"))
        sameImage(before, image()); assertEquals(prefs, prefImage()); pending(owner)
        relatedRestartSourceRejected(reference) // Fresh qualification must also audit the actual bad restart.
        mutation("UPDATE next_restart_plan_proofs SET planHash=? WHERE operationId=?",
            planProof.planHash, reference.operationId)
        endAndCold(owner, deleteDecision(owner))
    }

    @Test(timeout = 150_000) fun restartIndependentDeclarationsCannotHideEarlierAcceptedOrOrphanLiveMembers() = runBlocking<Unit> {
        add(ack = true); val reference = acceptRestartBeforeDelete()
        val latest = acceptRestartBeforeDelete(expectedAcceptedRequests = 3, expectedRevision = 3)
        assertNotEquals(reference.operationId, latest.operationId)
        deleteOriginal(); assertDeleteHasNoPendingRestartFrontier(latest, expectedAcceptedRequests = 5); reopen(); deleteDecision()
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, reference.operationId))
        val proposal = requireNotNull(restartProposal(origin.intentJson))
        val foreign = id(974)
        assertEquals(0, proposal.expectedHead.generation); assertNull(proposal.restartFrontier)
        // Keep this a structurally decodable proposal for another activity. Native accepted
        // metadata still names the original operation/head, so a target-only origin filter is insufficient.
        val hidden = proposal.copy(head = proposal.head.copy(activityUuid = foreign),
            expectedHead = com.dayforge.domain.model.initialChallengeRoundHead(foreign),
            basePlan = JsonObject(requireNotNull(proposal.basePlan) + ("public_id" to JsonPrimitive(foreign))))
        val hiddenText = json.encodeToString(hidden)
        assertEquals(foreign, requireNotNull(restartProposal(hiddenText)).head.activityUuid)
        mutation("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
            hiddenText, NEXT_OPERATION, reference.operationId)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
            origin.intentJson, NEXT_OPERATION, reference.operationId)
        deleteDecision()
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, reference.operationId))
        val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
        assertEquals("challenge_round", result.entityType); assertEquals(reference.head.roundUuid, result.entityUuid)
        val entity = requireNotNull(result.entity)
        val changedHead = JsonObject(entity.getValue("head").jsonObject + ("activity_uuid" to JsonPrimitive(foreign)))
        val changedIntent = JsonObject(entity.getValue("restart_intent").jsonObject + mapOf(
            "activity_uuid" to JsonPrimitive(foreign),
            "expected_round_uuid" to JsonPrimitive(com.dayforge.domain.model.initialChallengeRoundUuid(foreign))))
        for (changedEntity in listOf(JsonObject(entity + ("head" to changedHead)),
            JsonObject(entity + ("restart_intent" to changedIntent)))) {
            // Deliberate single retained ACK-row corruption, never positive server evidence.
            // Recompute only its byte hash to exercise semantic independent-declaration binding.
            val changed = json.encodeToString(result.copy(entity = changedEntity))
            mutation("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                changed, nextRequestHash(changed.toByteArray(Charsets.UTF_8)), NEXT_OPERATION, reference.operationId)
            relatedRestartSourceRejected(reference)
            mutation("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                receipt.resultJson, receipt.resultHash, NEXT_OPERATION, reference.operationId)
            deleteDecision()
        }
        assertDeleteHasNoPendingRestartFrontier(latest, expectedAcceptedRequests = 5)
        val orphanOperation = java.util.UUID.randomUUID().toString(); val orphanRound = id(972)
        val orphanPayload = json.encodeToString(proposal.copy(operationId = orphanOperation,
            head = proposal.head.copy(roundUuid = orphanRound)))
        assertEquals(orphanOperation, requireNotNull(restartProposal(orphanPayload)).operationId)
        assertEquals(orphanRound, requireNotNull(restartProposal(orphanPayload)).head.roundUuid)
        val orphanId = db.syncOutboxDao().insert(com.dayforge.data.local.entity.SyncOutboxEntity(
            operationId = orphanOperation, recordType = RESTART_RECORD, entityUuid = habit.uuid,
            wireEntityUuid = orphanRound, action = "upsert", payloadJson = orphanPayload))
        assertNull(db.nextRequestDao().origin(NEXT_OPERATION, orphanOperation))
        assertNull(db.syncOutboxDao().getById(orphanId)!!.referenceUuid)
        relatedRestartSourceRejected(reference) // A live related member with no original may not be skipped.
        mutation("DELETE FROM sync_outbox WHERE id=?", orphanId)
        deleteDecision(); assertDeleteHasNoPendingRestartFrontier(latest, expectedAcceptedRequests = 5)
    }

    private suspend fun acceptedTimerFrontiersThenDelete(): Triple<NextRestartReference, com.dayforge.data.local.entity.TimerCommandEntity, String> {
        prepareTimerCountBootstrap(); add(ack = true)
        val terminal = editToTimerAndQueueCancel()
        val planFrontier = queueTimerPlanFrontier()
        val reference = acceptRestartBeforeDelete(expectedAcceptedRequests = 6, expectedRevision = 4,
            expectedTerminal = terminal, expectedPlanFrontier = planFrontier)
        val proposal = requireNotNull(restartProposal(requireNotNull(db.nextRequestDao()
            .origin(NEXT_OPERATION, reference.operationId)).intentJson))
        assertEquals(terminal.commandId, proposal.terminals.single().commandId)
        assertEquals(planFrontier, requireNotNull(proposal.planFrontier).operationId)
        assertNotNull(db.nextRequestDao().acceptance(NEXT_TIMER, terminal.commandId))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, planFrontier))
        deleteOriginal(); assertDeleteHasNoPendingRestartFrontier(reference, expectedAcceptedRequests = 8)
        reopen(); assertDeleteHasNoPendingRestartFrontier(reference, expectedAcceptedRequests = 8)
        deleteDecision()
        return Triple(reference, terminal, planFrontier)
    }

    @Test(timeout = 150_000) fun realTimerTerminalAndPlanFrontiersRemainCompleteThroughColdAcceptedDeleteEnd() = runBlocking<Unit> {
        val (reference, terminal, planFrontier) = acceptedTimerFrontiersThenDelete()
        val terminalReceipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_TIMER, terminal.commandId))
        val planReceipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, planFrontier))
        val restartProof = requireNotNull(db.nextRestartDao().planProof(reference.operationId))
        val owner = store(); endAndCold(owner, deleteDecision(owner))
        assertEquals(terminalReceipt, db.nextRequestDao().acceptance(NEXT_TIMER, terminal.commandId))
        assertEquals(planReceipt, db.nextRequestDao().acceptance(NEXT_OPERATION, planFrontier))
        assertEquals(restartProof, db.nextRestartDao().planProof(reference.operationId))
        assertEquals(8, count("next_acceptances")); assertEquals(0, count("timer_command_outbox"))
        assertEquals(0, count("timelogs")); assertEquals(0, count("timer_segments"))
        assertEquals(1, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun timerTerminalReceiptConflictsAndCapturedPlanFrontierCannotBeHiddenByRestartAck() = runBlocking<Unit> {
        val (reference, terminal, planFrontier) = acceptedTimerFrontiersThenDelete()
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_TIMER, terminal.commandId))
        mutation("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?",
            "0".repeat(64), NEXT_TIMER, terminal.commandId)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?",
            receipt.resultHash, NEXT_TIMER, terminal.commandId)
        deleteDecision()

        // A negative, conflicting row for the REAL terminal ID. No acceptance or journal is fabricated.
        val rejected = json.encodeToString(com.dayforge.data.api.dto.TimerCommandResult(terminal.commandId,
            terminal.sessionUuid, "rejected", errorCode = "INVALID_TRANSITION"))
        mutation("INSERT INTO next_rejections(kind,requestId,originHash,transmissionHash,resultHash,resultJson) VALUES(?,?,?,?,?,?)",
            NEXT_TIMER, terminal.commandId, receipt.originHash, receipt.transmissionHash,
            nextRequestHash(rejected.toByteArray(Charsets.UTF_8)), rejected)
        assertNotNull(db.nextSyncStateDao().rejection(NEXT_TIMER, terminal.commandId))
        relatedRestartSourceRejected(reference)
        mutation("DELETE FROM next_rejections WHERE kind=? AND requestId=?", NEXT_TIMER, terminal.commandId)
        deleteDecision()

        // FK-complete MINIMAL hostile abandonment presence, not a claimed valid recovery audit.
        // New C must reject the terminal side membership before treating its original ACK as usable.
        assertEquals(0, count("next_recovery_audits")); assertEquals(0, count("next_abandoned_requests"))
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_TIMER, terminal.commandId))
        val audit = java.util.UUID.randomUUID().toString()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("INSERT INTO next_recovery_audits(recoveryId,accountId,serverInstanceId,syncEpoch,deviceId,rootKind,rootRequestId,graphHash,checkpointHash,manifestHash,selectedCount) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(audit, origin.accountId, origin.serverInstanceId, origin.syncEpoch, id(4), NEXT_TIMER,
                    terminal.commandId, "0".repeat(64), "0".repeat(64), "0".repeat(64), 1))
            sql.execSQL("INSERT INTO next_abandoned_requests(kind,requestId,recoveryId,disposition,sourceHash,sourceSnapshotHash,originHash,transmissionHash,rejectionHash) VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(NEXT_TIMER, terminal.commandId, audit, NextRecoveryRequestState.NEVER_SENT.name, origin.sourceHash,
                    "0".repeat(64), receipt.originHash, receipt.transmissionHash, null))
        }
        assertEquals(1, count("next_abandoned_requests"))
        relatedRestartSourceRejected(reference)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("DELETE FROM next_abandoned_requests WHERE kind=? AND requestId=?",
                arrayOf<Any?>(NEXT_TIMER, terminal.commandId))
            db.openHelper.writableDatabase.execSQL("DELETE FROM next_recovery_audits WHERE recoveryId=?", arrayOf(audit))
        }
        deleteDecision()

        val device = db.openHelper.writableDatabase.query("SELECT capturedDeviceId FROM next_structural_dependencies WHERE operationId=?",
            arrayOf(planFrontier)).use { c -> assertTrue(c.moveToFirst()); c.getString(0).also { assertFalse(c.moveToNext()) } }
        assertEquals(id(4), device)
        mutation("UPDATE next_structural_dependencies SET capturedDeviceId=? WHERE operationId=?", id(971), planFrontier)
        relatedRestartSourceRejected(reference)
        mutation("UPDATE next_structural_dependencies SET capturedDeviceId=? WHERE operationId=?", device, planFrontier)
        deleteDecision(); assertDeleteHasNoPendingRestartFrontier(reference, expectedAcceptedRequests = 8)
    }

}
