package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production edits, frozen loopback replies and receipts; no hand-built ancestry/ACK. */
@RunWith(AndroidJUnit4::class)
class NextRecoveryStructuralLineageTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private var failRequests = false
    private var loseReply = false
    private fun metadata() = ChallengeMetadata(1, listOf(habit.uuid, timerHabit.uuid).map {
        val first = ChallengeRoundRecord(initialChallengeRoundHead(it), null, null, null)
        ChallengeCheckpoint(first.head, listOf(first))
    }, emptyList())
    private fun canonical(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun respond(input: MaterialSocketServer.Input): MaterialSocketServer.Reply? {
        if (input.path.endsWith("/identity")) return reply(input)
        assertEquals("/api/v2/sync/rounds/push", input.path)
        if (loseReply) return null
        val operation = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8)).operations.single()
        val results = if (failRequests) listOf(NextSyncOperationResult(operation.operationId, operation.entityType,
            operation.entityUuid, "rejected", errorCode = "INVALID_PAYLOAD")) else if (operation.action == "delete") {
            require(operation.entityType == "metric" && operation.entityUuid == metric.uuid)
            val body = canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)).payload
            val revision = (operation.baseRevision ?: 0) + 1
            listOf(NextSyncOperationResult(operation.operationId, "metric", metric.uuid, "applied", revision,
                entity = JsonObject(body + mapOf("revision" to JsonPrimitive(revision), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonPrimitive(time)))))
        } else json.decodeFromString<NextSyncPushResponse>(
                successReply(input, (operation.baseRevision ?: 0) + 1) { body ->
                    if (operation.entityUuid == metric.uuid) JsonObject(body + ("created_at" to JsonPrimitive(time))) else body
                }.bytes.toString(Charsets.UTF_8)).results
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(results, 1, meta.checkpoints, meta.births)).toByteArray())
    }
    private fun merger(http: NextSyncHttp): NextSyncMergeStore {
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        return NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
    }
    private suspend fun initialize(): Pair<NextSyncHttp, MaterialSocketServer> {
        register(); val (http, server) = channel(::respond)
        val merge = merger(http)
        val meta = metadata()
        merge.bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), 20, time, emptyList(), 1, meta.checkpoints, meta.births))
        return http to server
    }
    private suspend fun edit(name: String): SyncOutboxEntity {
        val draft = editor.metric(metric.id)
        editingMetrics().updateMetric(requireNotNull(draft.value).copy(name = name), draft.authority)
        return db.syncOutboxDao().getAll().last()
    }
    private suspend fun lineage(id: String) = db.withTransaction { NextStructuralCausalStore(db).recoveryLineage(id, access()) }
    private suspend fun refuse(http: NextSyncHttp, id: String): String {
        failRequests = true
        val delivery = requireNotNull(sender(http).sendOperation(access(), id))
        sender(http).recordRejection(access(), NEXT_OPERATION, delivery.requestId, delivery.transmissionProof,
            json.encodeToString(delivery.result.results.single()), delivery.challengeMetadata)
        return delivery.requestId
    }
    private suspend fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
    }

    @Test fun offlineOriginalChainRetainsExactEdgesAndNeverSentClassificationAcrossColdReopen() = runBlocking<Unit> {
        val (_, server) = initialize(); val a = edit("A"); val b = edit("B"); val c = edit("C")
        val before = lineage(c.operationId)
        assertEquals(listOf(c.operationId, b.operationId, a.operationId), before.links.map { it.logicalKey.requestId })
        assertEquals(listOf(b.operationId, a.operationId, null), before.links.map { it.predecessorId })
        assertTrue(before.links.all { it.state == NextRecoveryRequestState.NEVER_SENT && it.logicalKey == it.actualKey &&
            it.dependencyHash != null && it.supersessionHash == null && it.acceptanceHash == null && it.acceptedResultHash == null })
        val prefs = dataStore.data.first(); val calls = server.requests.size
        reopen(); val after = lineage(c.operationId)
        assertEquals(before.evidenceHash, after.evidenceHash); assertEquals(before.links, after.links)
        assertTrue(prefs == dataStore.data.first()); assertEquals(calls, server.requests.size)
        assertEquals(listOf(a, b, c), db.syncOutboxDao().getAll()); assertEquals(0, count("next_transmissions")); assertEquals(0, count("next_acceptances"))
        assertTrue(rejected { (after.links as MutableList<NextRecoveryStructuralLink>).clear() } is UnsupportedOperationException)
    }
    @Test fun originalPermanentRootAndNeverSentSuccessorsRemainDifferentRequestStatesWithoutRepairWrites() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A"); val b = edit("B"); val c = edit("C"); refuse(http, a.operationId)
        val queue = db.syncOutboxDao().getAll(); val calls = server.requests.size
        val proof = lineage(c.operationId)
        assertEquals(listOf(NextRecoveryRequestState.NEVER_SENT, NextRecoveryRequestState.NEVER_SENT,
            NextRecoveryRequestState.PERMANENT_REJECTION), proof.links.map { it.state })
        assertEquals(listOf(c.operationId, b.operationId, a.operationId), proof.links.map { it.actualKey.requestId })
        assertEquals(calls, server.requests.size); assertEquals(queue, db.syncOutboxDao().getAll())
        assertEquals(1, count("next_rejections")); assertEquals(0, count("next_structural_supersessions")); assertEquals(0, count("next_recovery_audits"))
    }
    @Test fun actualAcceptedAncestorAndRejectedReplacementKeepLogicalPredecessorNotCurrentQueueOrder() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val b = edit("B"); val c = edit("C")
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), a.operationId))
        val replacement = refuse(http, b.operationId); assertNotEquals(b.operationId, replacement)
        assertNull(db.syncOutboxDao().getById(a.id)); assertNull(db.syncOutboxDao().getById(b.id))
        val chain = lineage(c.operationId)
        assertEquals(listOf(c.operationId, b.operationId, a.operationId), chain.links.map { it.logicalKey.requestId })
        assertEquals(listOf(c.operationId, replacement, a.operationId), chain.links.map { it.actualKey.requestId })
        assertEquals(b.operationId, chain.links.first().predecessorId)
        val converted = chain.links[1]; assertNotNull(converted.supersessionHash)
        assertEquals(NextRecoveryRequestState.PERMANENT_REJECTION, converted.state)
        val accepted = chain.links.last(); assertEquals(NextRecoveryRequestState.ACCEPTED, accepted.state)
        assertNotNull(accepted.acceptanceHash)
        assertEquals(nextRequestHash(db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId)!!.resultJson.toByteArray(Charsets.UTF_8)), accepted.acceptedResultHash)
        assertEquals(lineage(b.operationId).evidenceHash, lineage(replacement).evidenceHash)
        reopen(); assertEquals(chain.evidenceHash, lineage(c.operationId).evidenceHash)
        assertEquals(1, count("next_acceptances")); assertEquals(1, count("next_rejections")); assertEquals(1, count("next_structural_supersessions"))
    }
    @Test fun frozenUnknownReplacementCannotLookUnsentOrBecomeAnotherReplacementDuringInspection() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A"); val b = edit("B")
        sender(http).sendAndAcceptOperation(access(), a.operationId); loseReply = true
        rejected { sender(http).sendOperation(access(), b.operationId) }
        val converted = requireNotNull(db.nextStructuralCausalDao().supersession(b.operationId))
        val frozen = transmission(NEXT_OPERATION, converted.replacementId).wireBytes.copyOf(); val calls = server.requests.size
        val result = lineage(b.operationId)
        assertEquals(converted.replacementId, result.actualRequestId)
        assertEquals(NextRecoveryRequestState.UNKNOWN_RESULT, result.links.first().state)
        assertEquals(NextRecoveryRequestState.ACCEPTED, result.links.last().state)
        reopen(); assertEquals(result.evidenceHash, lineage(converted.replacementId).evidenceHash)
        assertEquals(calls, server.requests.size); assertArrayEquals(frozen, transmission(NEXT_OPERATION, converted.replacementId).wireBytes)
        assertEquals(converted, db.nextStructuralCausalDao().supersession(b.operationId)); assertEquals(0, count("next_rejections"))
    }
    @Test fun receiptAndDependencyCorruptionCannotSupplyAcceptedAncestorOrIncompleteChain() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val b = edit("B")
        sender(http).sendAndAcceptOperation(access(), a.operationId)
        val store = NextStructuralCausalStore(db)
        suspend fun read() = db.withTransaction { store.recoveryLineage(b.operationId, access()) }
        val before = read(); val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId))
        val dep = requireNotNull(db.nextStructuralCausalDao().dependency(b.operationId)); val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE next_acceptances SET resultHash='bad' WHERE requestId=?", arrayOf(a.operationId)); rejected { read() }
        sql.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?", arrayOf(receipt.resultHash, a.operationId))
        sql.execSQL("UPDATE next_structural_dependencies SET predecessorOriginHash='bad' WHERE operationId=?", arrayOf(b.operationId)); rejected { read() }
        sql.execSQL("UPDATE next_structural_dependencies SET predecessorOriginHash=? WHERE operationId=?", arrayOf(dep.predecessorOriginHash, b.operationId))
        assertEquals(before.evidenceHash, read().evidenceHash); assertEquals(b, db.syncOutboxDao().getById(b.id))
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId))
    }
    @Test fun missingLiveSourceIsNotAnAcceptanceAndMissingOrdinaryAncestryIsNotARoot() = runBlocking<Unit> {
        initialize(); val a = edit("A"); val sql = db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM sync_outbox WHERE id=?", arrayOf(a.id)); rejected { lineage(a.operationId) }
        db.syncOutboxDao().insert(a)
        sql.execSQL("DELETE FROM next_structural_dependencies WHERE operationId=?", arrayOf(a.operationId)); rejected { lineage(a.operationId) }
        assertEquals(a, db.syncOutboxDao().getById(a.id)); assertEquals(0, count("next_acceptances"))
    }
    @Test fun permissionLossDoesNotEraseReadOnlyEvidenceButSenderStillRefuses() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val b = edit("B")
        sender(http).sendAndAcceptOperation(access(), a.operationId)
        val before = lineage(b.operationId)
        register(permissions = setOf("sync.read"), revision = 2)
        assertEquals(before.evidenceHash, lineage(b.operationId).evidenceHash)
        db.withTransaction {
            val shared = NextStructuralCausalStore(db)
            shared.recoveryLineage(b.operationId, access())
            assertEquals(NextRequestException.Reason.PERMISSION_DENIED,
                (rejected { shared.resolve(b.operationId, access()) } as NextRequestException).reason)
        }
        assertEquals(NextRequestException.Reason.PERMISSION_DENIED,
            (rejected { sender(http).sendOperation(access(), b.operationId) } as NextRequestException).reason)
        assertEquals(listOf(b), db.syncOutboxDao().getAll()); assertEquals(1, count("next_transmissions"))
    }
    @Test fun accountDeviceAndEpochChangesCannotReuseEvenStructurallyValidAncestry() = runBlocking<Unit> {
        initialize(); val row = edit("A"); val current = access()
        for (changed in listOf(current.copy(deviceId = id(99)), current.copy(session = current.session.copy(syncEpoch = id(99))),
            current.copy(session = current.session.copy(authentication = current.session.authentication.copy(userId = id(99)))))) {
            rejected { db.withTransaction { NextStructuralCausalStore(db).recoveryLineage(row.operationId, changed) } }
        }
        assertEquals(NextRecoveryRequestState.NEVER_SENT, lineage(row.operationId).links.single().state)
    }
    @Test fun cancelledReadCannotLeaveReusableProofOrChangeStorage() = runBlocking<Unit> {
        initialize(); val row = edit("A")
        coroutineScope { launch { cancel(); lineage(row.operationId) }.join() }
        assertEquals(NextRecoveryRequestState.NEVER_SENT, lineage(row.operationId).links.single().state)
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(0, count("next_transmissions"))
    }
    @Test fun metricDeletionUsesItsActualSourceAndFullTombstoneReceiptWithoutInventedAncestry() = runBlocking<Unit> {
        val (http, _) = initialize(); val draft = editor.metric(metric.id)
        editingMetrics().deleteMetric(requireNotNull(draft.value), draft.authority)
        val row = db.syncOutboxDao().getAll().single()
        assertEquals("delete", row.action); assertNull(db.nextStructuralCausalDao().dependency(row.operationId))
        val pending = lineage(row.operationId).links.single()
        assertNull(pending.dependencyHash); assertEquals(NextRecoveryRequestState.NEVER_SENT, pending.state)
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        val accepted = lineage(row.operationId).links.single()
        assertEquals(NextRecoveryRequestState.ACCEPTED, accepted.state); assertNotNull(accepted.acceptanceHash)
        val body = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId)).resultJson
        assertEquals(nextRequestHash(body.toByteArray(Charsets.UTF_8)), accepted.acceptedResultHash)
        assertTrue(Json.parseToJsonElement(body).jsonObject.getValue("entity")
            .jsonObject.getValue("deleted_at") != JsonNull)
        assertNull(db.syncOutboxDao().getById(row.id))
    }
    @Test fun acceptedAndRejectedContradictionAndRetirementMarkerCannotBecomeAnotherAncestorProof() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); sender(http).sendAndAcceptOperation(access(), a.operationId)
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId))
        db.nextSyncStateDao().insertRejection(NextRejectionEntity(NEXT_OPERATION, a.operationId, receipt.originHash,
            receipt.transmissionHash, "bad", "bad"))
        rejected { lineage(a.operationId) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_rejections WHERE requestId=?", arrayOf(a.operationId))
        assertEquals(NextRecoveryRequestState.ACCEPTED, lineage(a.operationId).links.single().state)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO next_recovery_audits VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(id(990), id(1), id(2), id(3), id(4),
            NEXT_OPERATION, a.operationId, "a".repeat(64), "b".repeat(64), "c".repeat(64), 1))
        sql.execSQL("INSERT INTO next_abandoned_requests VALUES(?,?,?,'never_sent','bad','bad','bad',NULL,NULL)", arrayOf(NEXT_OPERATION, a.operationId, id(990)))
        rejected { lineage(a.operationId) }; assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId))
        assertEquals(0, count("sync_outbox")); assertEquals(1, count("next_abandoned_requests"))
    }
    @Test fun acceptedPlanCannotBorrowCoherentMetadataThatLostItsOriginalRound() = runBlocking<Unit> {
        val (http, _) = initialize(); val draft = editor.habit(habit.id)
        editingHabits().updateHabit(requireNotNull(draft.value).copy(description = "Accepted plan"), editAuthority = draft.authority)
        val row = db.syncOutboxDao().getAll().single()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(NextRecoveryRequestState.ACCEPTED, lineage(row.operationId).links.single().state)
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        val reduced = metadata().copy(checkpoints = metadata().checkpoints.filter { it.head.activityUuid != habit.uuid })
        val sql = db.openHelper.writableDatabase
        // Corruption fixture: even a coherent marker cannot lend another activity's round to this ACK.
        sql.execSQL("DELETE FROM next_challenge_rounds WHERE activityUuid=?", arrayOf(habit.uuid))
        sql.execSQL("UPDATE next_challenge_state SET metadataHash=?", arrayOf(syncPayloadHash(json.encodeToString(reduced))))
        db.withTransaction { assertEquals(reduced, NextChallengeStore(db).activeInTransaction(access()).second) }
        assertEquals("SYNC_RECOVERY_ACCEPTED_ROUND_MISSING", rejected { lineage(row.operationId) }.message)
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_recovery_audits"))
    }
    @Test fun unrelatedDamagedHistoricalOriginIsNotScannedOrCountedAsThisAncestry() = runBlocking<Unit> {
        val (_, server) = initialize(); val row = edit("A")
        db.nextRequestDao().insertOrigin(NextRequestOriginEntity(NEXT_OPERATION, id(914), 99999, 5,
            id(1), id(2), id(3), "unrelated-source", "unrelated-intent"))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE next_request_origins SET protocol=5.5 WHERE requestId=?", arrayOf(id(914)))
        val calls = server.requests.size
        val proof = lineage(row.operationId)
        assertEquals(listOf(row.operationId), proof.links.map { it.logicalKey.requestId })
        assertEquals(NextRecoveryRequestState.NEVER_SENT, proof.links.single().state)
        sql.query("SELECT typeof(protocol) FROM next_request_origins WHERE requestId=?", arrayOf(id(914))).use {
            assertTrue(it.moveToFirst()); assertEquals("real", it.getString(0))
        }
        assertEquals(calls, server.requests.size); assertEquals(listOf(row), db.syncOutboxDao().getAll())
    }
    @Test fun realPullCursorChangeInvalidatesEvidenceWithoutChangingTheLogicalChain() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A"); val b = edit("B")
        val before = lineage(b.operationId); val prefs = dataStore.data.first(); val calls = server.requests.size
        val merge = merger(http); val state = requireNotNull(merge.state(access(), challengeProfile = true)); val meta = metadata()
        val next = merge.page(access(), state, RoundSyncPullResponse(emptyList(), state.cursor + 1, false, time,
            1, meta.checkpoints, meta.births))
        assertEquals(state.cursor + 1, next.cursor)
        val after = lineage(b.operationId)
        assertEquals(before.links, after.links); assertNotEquals(before.checkpointHash, after.checkpointHash)
        assertNotEquals(before.evidenceHash, after.evidenceHash)
        assertEquals(listOf(a, b), db.syncOutboxDao().getAll()); assertEquals(prefs, dataStore.data.first())
        assertEquals(calls, server.requests.size); assertEquals(0, count("next_transmissions"))
    }
}
