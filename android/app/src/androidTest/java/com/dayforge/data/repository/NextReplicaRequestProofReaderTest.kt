package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual original writes/TCP deliveries/ACKs and physical reopen; not invented acceptance rows. */
@RunWith(AndroidJUnit4::class)
class NextReplicaRequestProofReaderTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private lateinit var http: NextSyncHttp
    private lateinit var peer: MaterialSocketServer
    private var loseReply = false
    private var refuse = false
    private fun canonical(kind: String, uuid: String, body: JsonObject) = SyncV2Change(0, kind, uuid, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun metadata() = ChallengeMetadata(1, listOf(habit.uuid, timerHabit.uuid).map {
        val head = initialChallengeRoundHead(it)
        ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
    }, emptyList())
    private suspend fun initialize() {
        register()
        val channel = channel { input ->
            if (input.path.endsWith("/identity")) reply(input)
            else if (loseReply) null
            else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                val operation = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8)).operations.single()
                val results = if (refuse) listOf(NextSyncOperationResult(operation.operationId, operation.entityType,
                    operation.entityUuid, "rejected", errorCode = "INVALID_PAYLOAD")) else
                    json.decodeFromString<NextSyncPushResponse>(successReply(input, (operation.baseRevision ?: 0) + 1) { body ->
                        if (operation.entityUuid == metric.uuid) JsonObject(body + ("created_at" to JsonPrimitive(time))) else body
                    }.bytes.toString(Charsets.UTF_8)).results
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(results, 1, meta.checkpoints, meta.births)).toByteArray())
            }
        }
        http = channel.first; peer = channel.second
        val core = sender(http); val timers = NextTimerRequestStore(db, tokens, sessions, core)
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
            .bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(
                canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
                canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
                canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), 20, time, emptyList(), 1,
                metadata().checkpoints, metadata().births))
    }
    private suspend fun queuedObservation(): SyncOutboxEntity {
        val writer = producer()
        writer.writeRounds(writer.captureRounds()) { metrics().recordValue(metric.id, 21.125, "original note", millis) }
        return db.syncOutboxDao().getAll().last()
    }
    private suspend fun edit(name: String): SyncOutboxEntity {
        editor = NextObjectEditor(db, tokens, sessions, icons)
        val draft = editor.metric(metric.id)
        editingMetrics().updateMetric(requireNotNull(draft.value).copy(name = name), draft.authority)
        return db.syncOutboxDao().getAll().last()
    }
    private suspend fun schema(): ReplicaSchemaProof {
        val point = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }
        return NextReplicaSchemaValidator(app, db, tokens, sessions).validate(access(), point)
    }
    private fun reader() = NextReplicaRequestProofReader(db, tokens, sessions, preferences, http)
    private suspend fun retire(root: String) {
        val runtime = NextSyncRuntime(db, tokens, sessions, http, preferences)
        val preview = runtime.readRecoveryPreview(NextRecoveryKey(NEXT_OPERATION, root))
        assertTrue(preview.canConfirm)
        runtime.confirmRecovery(runtime.prepareRecoveryConfirmation(preview, preview.affected.map { it.key }.toSet()))
    }
    private suspend fun refuseOperation(original: String): String {
        refuse = true
        val core = sender(http); val delivery = requireNotNull(core.sendOperation(access(), original))
        core.recordRejection(access(), NEXT_OPERATION, delivery.requestId, delivery.transmissionProof,
            json.encodeToString(delivery.result.results.single()), delivery.challengeMetadata)
        return delivery.requestId
    }
    private suspend fun unchanged(block: suspend () -> Unit) {
        val image = storage.snapshotClosedDatabase(); val prefs = dataStore.data.first(); val original = access()
        val calls = peer.requests.size
        block()
        assertArrayEquals(image, storage.snapshotClosedDatabase()); assertEquals(prefs, dataStore.data.first())
        assertEquals(original, access()); assertEquals(calls, peer.requests.size)
    }

    @Test fun emptyLedgerIsReadOnlyNotActivationAndColdReopenPreservesProof() = runBlocking<Unit> {
        initialize(); val proof = schema()
        unchanged {
            val result = reader().read(proof)
            assertTrue(result.states.isEmpty()); assertFalse(result.hasUnsettledRequests); assertFalse(result.activationAuthorized)
            storage.reopen(); assertEquals(result.states, reader().read(proof).states)
        }
    }
    @Test fun actualUnreferencedAckIsFullyAuditedEvenWithEmptyQueue() = runBlocking<Unit> {
        initialize(); val row = queuedObservation(); sender(http).sendAndAcceptOperation(access(), row.operationId)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val proof = schema()
        unchanged {
            val result = reader().read(proof)
            assertEquals(mapOf(NextRecoveryKey(NEXT_OPERATION, row.operationId) to NextRecoveryRequestState.ACCEPTED), result.states)
            assertFalse(result.hasUnsettledRequests); assertFalse(result.activationAuthorized)
            assertTrue(runCatching { (result.states as MutableMap<*, *>).clear() }.isFailure)
            storage.reopen(); assertEquals(result.states, reader().read(proof).states)
        }
    }
    @Test fun unknownTransportIsNotUnsentAndNeverGeneratesRepairOrHttp() = runBlocking<Unit> {
        initialize(); val row = queuedObservation(); loseReply = true
        rejected { sender(http).sendOperation(access(), row.operationId) }
        val proof = schema(); val wire = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        unchanged {
            val result = reader().read(proof)
            assertEquals(NextRecoveryRequestState.UNKNOWN_RESULT, result.states[NextRecoveryKey(NEXT_OPERATION, row.operationId)])
            assertTrue(result.hasUnsettledRequests); assertFalse(result.activationAuthorized)
            assertArrayEquals(wire, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        }
    }
    @Test fun neverSentAndBoundPermanentRejectionRemainDifferentBlockers() = runBlocking<Unit> {
        initialize(); val first = queuedObservation(); val second = queuedObservation(); refuse = true
        val delivery = requireNotNull(sender(http).sendOperation(access(), first.operationId))
        sender(http).recordRejection(access(), NEXT_OPERATION, first.operationId, delivery.transmissionProof,
            json.encodeToString(delivery.result.results.single()), delivery.challengeMetadata)
        val proof = schema()
        unchanged {
            val result = reader().read(proof)
            assertEquals(NextRecoveryRequestState.PERMANENT_REJECTION, result.states[NextRecoveryKey(NEXT_OPERATION, first.operationId)])
            assertEquals(NextRecoveryRequestState.NEVER_SENT, result.states[NextRecoveryKey(NEXT_OPERATION, second.operationId)])
            assertTrue(result.hasUnsettledRequests); assertFalse(result.activationAuthorized)
        }
    }
    @Test fun acceptedStructuralReplacementKeepsLogicalOriginalAndAllAncestorReceipts() = runBlocking<Unit> {
        initialize(); val a = edit("A"); val b = edit("B"); val c = edit("C")
        val core = sender(http)
        core.sendAndAcceptOperation(access(), a.operationId)
        core.sendAndAcceptOperation(access(), b.operationId)
        core.sendAndAcceptOperation(access(), c.operationId)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val proof = schema()
        unchanged {
            val result = reader().read(proof)
            assertEquals(3, result.states.values.count { it == NextRecoveryRequestState.ACCEPTED })
            assertEquals(2, result.states.values.count { it == NextRecoveryRequestState.SUPERSEDED })
            assertFalse(result.hasUnsettledRequests); assertFalse(result.activationAuthorized)
            storage.reopen(); assertEquals(result.states, reader().read(proof).states)
        }
    }
    @Test fun orphanAcceptanceIsRejectedWithoutDiscardingItsOriginalBytes() = runBlocking<Unit> {
        initialize(); val row = queuedObservation(); sender(http).sendAndAcceptOperation(access(), row.operationId)
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET requestId=? WHERE requestId=?", arrayOf(id(90), row.operationId))
        val proof = schema()
        unchanged { assertEquals("SYNC_RECOVERY_REQUEST_ORPHAN", rejected { reader().read(proof) }.message) }
    }
    @Test fun actualCompleteRetirementRemainsTerminalWithoutInventingAcceptances() = runBlocking<Unit> {
        initialize(); val a = edit("A"); val b = edit("B"); val c = edit("C")
        retire(refuseOperation(a.operationId))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val proof = schema()
        unchanged {
            val result = reader().read(proof)
            assertEquals(listOf(a, b, c).associate { NextRecoveryKey(NEXT_OPERATION, it.operationId) to
                NextRecoveryRequestState.ABANDONED }, result.states)
            assertFalse(result.hasUnsettledRequests); assertFalse(result.activationAuthorized)
            assertTrue(result.states.values.none { it == NextRecoveryRequestState.ACCEPTED })
            storage.reopen(); assertEquals(result.states, reader().read(proof).states)
        }
    }
    @Test fun retiredActualReplacementKeepsLogicalAliasAndPredecessorAckDistinct() = runBlocking<Unit> {
        initialize(); val a = edit("A"); val b = edit("B"); val c = edit("C")
        sender(http).sendAndAcceptOperation(access(), a.operationId)
        val replacement = refuseOperation(b.operationId); assertNotEquals(b.operationId, replacement)
        retire(replacement)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val proof = schema()
        unchanged {
            val result = reader().read(proof)
            assertEquals(mapOf(NextRecoveryKey(NEXT_OPERATION, a.operationId) to NextRecoveryRequestState.ACCEPTED,
                NextRecoveryKey(NEXT_OPERATION, b.operationId) to NextRecoveryRequestState.SUPERSEDED,
                NextRecoveryKey(NEXT_OPERATION, replacement) to NextRecoveryRequestState.ABANDONED,
                NextRecoveryKey(NEXT_OPERATION, c.operationId) to NextRecoveryRequestState.ABANDONED), result.states)
            assertFalse(result.hasUnsettledRequests); assertFalse(result.activationAuthorized)
            storage.reopen(); assertEquals(result.states, reader().read(proof).states)
        }
        // Even with a fresh raw/schema proof, a membership row or hash cannot replace
        // the original complete audit. Corruption must be refused, never repaired here.
        db.openHelper.writableDatabase.execSQL("UPDATE next_recovery_audit_payloads SET bytes=X'00' WHERE payloadKey='manifest' AND part=0")
        val damaged = schema()
        unchanged { rejected { reader().read(damaged) } }
    }
    @Test fun damagedActualAckBodyCannotBecomeValidByRehashingResult() = runBlocking<Unit> {
        initialize(); val row = queuedObservation(); sender(http).sendAndAcceptOperation(access(), row.operationId)
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        val result = json.decodeFromString<NextSyncOperationResult>(receipt.resultJson).copy(operationId = id(91))
        val text = json.encodeToString(result)
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf(text, nextRequestHash(text.toByteArray()), row.operationId))
        val proof = schema()
        unchanged { rejected { reader().read(proof) } }
    }
    @Test fun outOfRangeOriginalProtocolIsRejectedBeforeRoomIntegerCoercion() = runBlocking<Unit> {
        initialize(); val row = queuedObservation(); sender(http).sendAndAcceptOperation(access(), row.operationId)
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET protocol=4294967301 WHERE requestId=?", arrayOf(row.operationId))
        val proof = schema()
        unchanged { assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
            (rejected { reader().read(proof) } as NextRequestException).reason) }
    }
    @Test fun changedRawContentsOrCapturedPermissionInvalidatesEarlierSchemaProof() = runBlocking<Unit> {
        initialize(); val proof = schema(); queuedObservation()
        unchanged { assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { reader().read(proof) }.message) }
        val refreshed = schema(); register(revision = 2)
        unchanged { assertEquals("SYNC_ACCOUNT_CHANGED", rejected { reader().read(refreshed) }.message) }
    }
}
