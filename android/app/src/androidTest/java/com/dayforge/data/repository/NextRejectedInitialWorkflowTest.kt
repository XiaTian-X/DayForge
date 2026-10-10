package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production creation → frozen HTTP rejection → formal writes/runtime; no synthetic birth. */
@RunWith(AndroidJUnit4::class)
class NextRejectedInitialWorkflowTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private val heads = linkedSetOf<String>()
    private val accepted = mutableListOf<SyncV2Change>()
    private var rejectId: String? = null
    private var code = "INVALID_PAYLOAD"
    private var loseResponse = false
    private fun initial(uuid: String) = ChallengeRoundRecord(initialChallengeRoundHead(uuid), null, null, null)
    private fun metadata() = ChallengeMetadata(1, heads.map { ChallengeCheckpoint(initial(it).head, listOf(initial(it))) }, emptyList())
    private fun canonical(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to (body["created_at"] ?: JsonPrimitive(time)), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun respond(input: MaterialSocketServer.Input): MaterialSocketServer.Reply? {
        if (input.path.endsWith("/identity")) return reply(input)
        if (input.path.endsWith("/changes")) {
            val cursor = input.target.substringAfter("cursor=").substringBefore('&').toLong()
            val meta = metadata()
            return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(accepted.filter { it.sequence > cursor },
                20L + accepted.size, false, time, 1, meta.checkpoints, meta.births)).toByteArray())
        }
        assertEquals("/api/v2/sync/rounds/push", input.path)
        val operation = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8)).operations.single()
        val failed = operation.operationId == rejectId
        if (failed && loseResponse) return null
        val results = if (failed) listOf(NextSyncOperationResult(operation.operationId, operation.entityType,
            operation.entityUuid, "rejected", errorCode = code)) else json.decodeFromString<NextSyncPushResponse>(
                successReply(input, (operation.baseRevision ?: 0) + 1) { body ->
                    if (operation.entityType == "metric" && operation.entityUuid == metric.uuid)
                        JsonObject(body + ("created_at" to JsonPrimitive(time))) else body
                }.bytes.toString(Charsets.UTF_8)).results
        if (!failed) for (result in results) {
            if (result.entityType == "plan_node") heads += result.entityUuid
            accepted += SyncV2Change(20L + accepted.size + 1, result.entityType, result.entityUuid, "upsert",
                requireNotNull(result.revision), requireNotNull(result.entity),
                result.entity.getValue("updated_at").jsonPrimitive.content)
        }
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(results, 1, meta.checkpoints, meta.births)).toByteArray())
    }
    private suspend fun initialize(): Pair<NextSyncHttp, MaterialSocketServer> {
        register(); heads += listOf(habit.uuid, timerHabit.uuid)
        val (http, server) = channel(::respond)
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        val merge = NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
        val meta = metadata()
        merge.bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), 20, time, emptyList(), 1, meta.checkpoints, meta.births))
        return http to server
    }
    private suspend fun create(name: String = "Rejected initial"): SyncOutboxEntity {
        val value = creatingHabits().createHabit(name, "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Daily,
            appearance = ObjectAppearance(IconReference.Role("habit.exercise"), "#123456", "theme"),
            completionPolicy = "recurring", creationAuthority = creator.capture())
        val uuid = requireNotNull(db.habitDao().getHabitById(value)).uuid
        return db.syncOutboxDao().getEntityIntents("habit", uuid).single()
    }
    private suspend fun refuse(http: NextSyncHttp, row: SyncOutboxEntity) {
        rejectId = row.operationId
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val result = json.encodeToString(delivery.result.results.single())
        sender(http).recordRejection(access(), NEXT_OPERATION, row.operationId, delivery.transmissionProof, result, delivery.challengeMetadata)
    }
    private suspend fun initials() = db.withTransaction { NextRoundPendingInitialStore(db).read(access(), metadata()) }
    private suspend fun proof(row: SyncOutboxEntity) = db.withTransaction { NextOriginalRequestProofStore(db).pending(access(), NEXT_OPERATION, row.operationId) }
    private suspend fun editIndependentMetric() {
        val snapshot = editor.metric(metric.id)
        editingMetrics().updateMetric(requireNotNull(snapshot.value).copy(name = "Independent updated"), snapshot.authority)
    }

    @Test fun permanentInitialCannotSupplyBirthButUnrelatedProductionEditAndRepeatedSyncStillProceed() = runBlocking<Unit> {
        val (http, server) = initialize(); val row = create(); refuse(http, row)
        val frozen = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        assertEquals(NextRecoveryRequestState.PERMANENT_REJECTION, proof(row).state)
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
        assertFalse(initials().containsKey(row.entityUuid))
        val scope = producer().captureRounds(); assertFalse(scope.pendingInitials.containsKey(row.entityUuid))
        rejected { producer().writeRounds(scope) { db.completionDao().insert(CompletionEntity(habitId = db.habitDao().getHabitByUuid(row.entityUuid)!!.id,
            habitUuid = row.entityUuid, uuid = id(900), date = millis, actualCompletedAt = millis,
            recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06")) } }
        assertNull(db.completionDao().getCompletionByUuid(id(900)))
        editIndependentMetric()
        val independent = db.syncOutboxDao().getAll().single { it.recordType == "metric" }
        val runtime = NextSyncRuntime(db, tokens, sessions, http, preferences)
        repeat(2) {
            val failure = rejected { runtime.syncRounds() }
            if (failure !is NextSyncAttention) throw AssertionError("Expected pending rejected creation, not another sync failure", failure)
        }
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, independent.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertArrayEquals(frozen, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertNotNull(db.habitDao().getHabitByUuid(row.entityUuid)); assertEquals(1, db.nextSyncStateDao().rejections().size)
        assertNull(preferences.lastSyncTimestamp.first())
        assertEquals(1, server.requests.count { it.path.endsWith("/push") && Json.parseToJsonElement(it.body.toString(Charsets.UTF_8))
            .jsonObject.getValue("operations").jsonArray.single().jsonObject.getValue("operation_id") == JsonPrimitive(row.operationId) })
    }
    @Test fun fullyBoundTransientFailuresRemainPendingNotPermanentlyRejectedBirths() = runBlocking<Unit> {
        val (http, _) = initialize()
        for ((index, error) in listOf("MISSING_PREDECESSOR", "OPERATION_IN_PROGRESS").withIndex()) {
            code = error; val row = create("Transient $index"); refuse(http, row)
            assertEquals(NextRecoveryRequestState.UNKNOWN_RESULT, proof(row).state)
            assertTrue(initials().containsKey(row.entityUuid))
            assertFalse(db.withTransaction { NextOriginalRequestProofStore(db).rejection(access(), NEXT_OPERATION, row.operationId) }.isPermanent)
        }
        assertEquals(2, db.syncOutboxDao().getAll().size); assertEquals(0, count("next_acceptances"))
    }
    @Test fun lostResponseRetainsOriginalFrozenBytesAndCannotBecomeNeverSentOrPermanentRejection() = runBlocking<Unit> {
        val (http, _) = initialize(); val row = create(); rejectId = row.operationId; loseResponse = true
        rejected { sender(http).sendOperation(access(), row.operationId) }
        val wire = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        assertEquals(NextRecoveryRequestState.UNKNOWN_RESULT, proof(row).state)
        assertTrue(initials().containsKey(row.entityUuid)); assertEquals(0, count("next_rejections")); assertEquals(0, count("next_acceptances"))
        editIndependentMetric()
        assertArrayEquals(wire, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertEquals(row, db.syncOutboxDao().getById(row.id))
    }
    @Test fun absentJournalNeedsFullOriginalSourceNotAttemptsOrQueueAppearance() = runBlocking<Unit> {
        initialize(); val row = create(); assertEquals(NextRecoveryRequestState.NEVER_SENT, proof(row).state)
        val origin = originalIntent(row)
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET protocol=5.5 WHERE requestId=?", arrayOf(row.operationId))
        rejected { proof(row) }
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET protocol=5 WHERE requestId=?", arrayOf(row.operationId))
        assertEquals(origin, originalIntent(row))
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET attemptCount=1 WHERE id=?", arrayOf(row.id))
        rejected { proof(row) }
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET attemptCount=0 WHERE id=?", arrayOf(row.id))
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_structural_dependencies WHERE operationId=?", arrayOf(row.operationId))
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_request_origins WHERE requestId=?", arrayOf(row.operationId))
        rejected { proof(row) }; assertFalse(initials().containsKey(row.entityUuid))
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(0, count("next_transmissions"))
    }
    @Test fun corruptedSourceDependencyResultAndWireCannotBeSkippedAsAFailedCreation() = runBlocking<Unit> {
        val (http, _) = initialize(); val row = create(); refuse(http, row)
        val sql = db.openHelper.writableDatabase
        val failed = db.nextSyncStateDao().rejections().single(); val sent = transmission(NEXT_OPERATION, row.operationId)
        val dependency = requireNotNull(db.nextStructuralCausalDao().dependency(row.operationId))
        val changes = listOf(
            "UPDATE next_rejections SET originHash='bad'" to ("UPDATE next_rejections SET originHash=?" to failed.originHash),
            "UPDATE next_rejections SET resultHash='bad'" to ("UPDATE next_rejections SET resultHash=?" to failed.resultHash),
            "UPDATE next_transmissions SET wireBytes=X'00'" to ("UPDATE next_transmissions SET wireBytes=?" to sent.wireBytes),
            "UPDATE next_structural_dependencies SET originHash='bad'" to ("UPDATE next_structural_dependencies SET originHash=?" to dependency.originHash))
        for ((damage, restore) in changes) {
            sql.execSQL(damage); rejected { initials() }; sql.execSQL(restore.first, arrayOf<Any>(restore.second))
            assertFalse(initials().containsKey(row.entityUuid))
        }
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(failed, db.nextSyncStateDao().rejections().single())
        assertEquals(0, count("next_acceptances"))
    }
    @Test fun blobResultHashIsRejectedBeforeDaoCoercionAndCannotSuppressTheOriginalProblem() = runBlocking<Unit> {
        val (http, _) = initialize(); val row = create(); refuse(http, row)
        db.openHelper.writableDatabase.execSQL("UPDATE next_rejections SET resultHash=CAST(resultHash AS BLOB)")
        rejected { initials() }; rejected { proof(row) }; assertEquals(row, db.syncOutboxDao().getById(row.id))
        assertEquals(0, count("next_acceptances"))
    }
    @Test fun accountDeviceEpochOrSuppliedMetadataChangeCannotHideARejectedBirth() = runBlocking<Unit> {
        val (http, _) = initialize(); val row = create(); refuse(http, row)
        val current = access()
        for (changed in listOf(current.copy(deviceId = id(5)), current.copy(session = current.session.copy(syncEpoch = id(99))),
            current.copy(session = current.session.copy(authentication = current.session.authentication.copy(userId = id(99)))))) {
            rejected { db.withTransaction { NextRoundPendingInitialStore(db).read(changed, metadata()) } }
        }
        val forged = metadata().copy(checkpoints = metadata().checkpoints + ChallengeCheckpoint(initial(row.entityUuid).head, listOf(initial(row.entityUuid))))
        rejected { db.withTransaction { NextRoundPendingInitialStore(db).read(current, forged) } }
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertFalse(initials().containsKey(row.entityUuid))
    }
    @Test fun partialAbandonmentMemberRefusesFirstSendAndLateAcceptanceNeverBecomesAnAck() = runBlocking<Unit> {
        val (http, _) = initialize(); editIndependentMetric()
        val row = db.syncOutboxDao().getAll().single()
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO next_recovery_audits VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(id(910), id(1), id(2), id(3), id(4),
            NEXT_OPERATION, row.operationId, "a".repeat(64), "b".repeat(64), "c".repeat(64), 1))
        sql.execSQL("INSERT INTO next_abandoned_requests VALUES(?,?,?,'never_sent','bad','bad','bad',NULL,NULL)", arrayOf(NEXT_OPERATION, row.operationId, id(910)))
        rejected { proof(row) }; rejected { sender(http).sendOperation(access(), row.operationId) }
        rejected { sender(http).acceptOperation(delivery) }
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertEquals(1, count("next_transmissions")); assertEquals(1, count("next_abandoned_requests"))
    }
    @Test fun classificationIsOfflineAndReadOnlyAndContentChangesInvalidateEvidence() = runBlocking<Unit> {
        val (http, server) = initialize(); val row = create()
        val never = proof(row); assertEquals(NextRecoveryRequestState.NEVER_SENT, never.state)
        refuse(http, row)
        val before = proof(row); val requests = server.requests.size; val prefs = dataStore.data.first()
        val state = db.nextSyncStateDao().rows().single(); val failure = db.nextSyncStateDao().rejections().single()
        val original = originalIntent(row); val wire = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        assertNotEquals(never.evidenceHash, before.evidenceHash)
        repeat(2) { assertEquals(before, proof(row)); assertFalse(initials().containsKey(row.entityUuid)) }
        assertEquals(requests, server.requests.size); assertTrue(prefs == dataStore.data.first()); assertEquals(state, db.nextSyncStateDao().rows().single())
        assertEquals(original, originalIntent(row)); assertEquals(failure, db.nextSyncStateDao().rejections().single())
        assertArrayEquals(wire, transmission(NEXT_OPERATION, row.operationId).wireBytes); assertEquals(row, db.syncOutboxDao().getById(row.id))
        assertEquals(0, count("next_recovery_audits")); assertEquals(0, count("next_acceptances"))
    }
}
