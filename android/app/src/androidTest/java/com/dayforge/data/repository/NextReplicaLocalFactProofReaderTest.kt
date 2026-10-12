package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual once completion, draft store, Room receipts and TCP acceptance; no invented ACKs. */
@RunWith(AndroidJUnit4::class)
class NextReplicaLocalFactProofReaderTest : NextCoreRequestFixture() {
    private val json = Json { encodeDefaults = true }
    private lateinit var http: NextSyncHttp
    private lateinit var peer: MaterialSocketServer
    private lateinit var spare: MetricEntity
    private lateinit var links: List<HabitMetricLinkEntity>
    private var loseReply = false
    private fun metadata(): ChallengeMetadata {
        val head = initialChallengeRoundHead(timerHabit.uuid)
        return ChallengeMetadata(1, listOf(ChallengeCheckpoint(head,
            listOf(ChallengeRoundRecord(head, null, null, null)))), emptyList())
    }
    private fun canonical(type: String, uuid: String, payload: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun prompts() = CompletionMetricPromptStore(db, tokens, sessions, now = { millis + 1000 }, zone = { "Asia/Shanghai" })
    private fun intents() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun timers() = NextTimerRequestStore(db, tokens, sessions, sender(http))
    private fun facts() = OneTimeAcceptedEventStore(db, tokens, sessions, intents(), timerRequests = timers())
    private fun merger() = NextSyncMergeStore(db, tokens, sessions, facts(), timers())
    private fun onceSender() = NextOneTimeRequestStore(db, tokens, sessions, http, facts(), sender(http))
    private suspend fun initialize() {
        register()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(habitType = HabitType.CHECK_IN, targetValue = 1, schedule = HabitSchedule.Once(),
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
            db.habitDao().update(habit)
            val other = metric.copy(id = 0, uuid = id(13), name = "Second metric")
            spare = other.copy(id = db.metricDao().insert(other))
            links = listOf(metric, spare).mapIndexed { index, m ->
                val link = HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid, metricId = m.id,
                    metricUuid = m.uuid, uuid = id(15 + index), promptOnComplete = true, createdAt = millis, updatedAt = millis)
                // Only structure fixture rows pre-exist. A fresh authoritative link must
                // be created by the real bootstrap, not adopted from an unproved local row.
                link
            }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val channel = channel { input ->
            if (input.path.endsWith("/identity")) reply(input)
            else if (loseReply) null
            else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                val ordinary = json.decodeFromString<NextSyncPushResponse>(successReply(input) { body ->
                    val once = body["one_time"] as? JsonObject
                    if (once == null) body else JsonObject(body + ("one_time_state_after" to buildJsonObject {
                        put("version", once.getValue("expected_version").jsonPrimitive.int + 1)
                        put("head_event_uuid", once.getValue("event_uuid"))
                        put("completion_event_uuid", if (once["action"] == JsonPrimitive("complete")) once.getValue("event_uuid") else JsonNull)
                    }))
                }.bytes.toString(Charsets.UTF_8))
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(ordinary.results, 1,
                    meta.checkpoints, meta.births)).toByteArray())
            }
        }
        http = channel.first; peer = channel.second
        val meta = metadata()
        merger().bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(
            canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)),
            canonical("metric", spare.uuid, NextStructureMapper.writeMetric(spare))) +
            links.map { canonical("activity_metric_link", it.uuid, SyncV2Mapper.link(it)) },
            20, time, listOf(OneTimeProjection(habit.uuid, OneTimeState(0, null, null))), 1, meta.checkpoints, meta.births))
    }
    private suspend fun draft(): CompletionMetricPrompt {
        val state = intents().read(habit.uuid).queue.optimisticState
        val command = OneTimeLocalCommand(habit.uuid, PendingOneTimeIntent(id(100), OneTimeIntent(id(101),
            "complete", state.version, state.headEventUuid, null)), millis, "Asia/Shanghai")
        intents().appendRounds(producer().captureRounds(), command)
        return prompts().read(id(101))
    }
    private suspend fun saved(): CompletionMetricPrompt {
        val updated = prompts().updateDraft(draft(), mapOf(metric.uuid to CompletionMetricInput("12.25", "original note")))
        prompts().submit(updated)
        return prompts().read(updated.row.eventUuid)
    }
    private suspend fun drain() {
        while (db.syncOutboxDao().getAll().isNotEmpty()) {
            val row = db.syncOutboxDao().getAll().first()
            val op = roundOperationIntent(originalIntent(row).intentJson)!!.operation
            if (op.payload["one_time"] is JsonObject) {
                assertEquals(NextOneTimeOutcome.Accepted(NextOperationAcceptance.COMMITTED), onceSender().sendAndAccept(access(), row.operationId))
            } else assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        }
    }
    private suspend fun configuration(): ReplicaConfigProof {
        val hash = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }
        val schema = NextReplicaSchemaValidator(app, db, tokens, sessions).validate(access(), hash)
        val requests = NextReplicaRequestProofReader(db, tokens, sessions, preferences, http).read(schema)
        return NextReplicaConfigProofReader(db, tokens, sessions).read(requests)
    }
    private fun reader() = NextReplicaLocalFactProofReader(db, tokens, sessions)
    private suspend fun unchanged(block: suspend () -> Unit) {
        val image = storage.snapshotClosedDatabase(); val prefs = dataStore.data.first(); val original = access(); val calls = peer.requests.size
        block()
        assertArrayEquals(image, storage.snapshotClosedDatabase()); assertEquals(prefs, dataStore.data.first())
        assertEquals(original, access()); assertEquals(calls, peer.requests.size)
    }

    @Test fun emptyEvidenceIsReadOnlyAndDoesNotAuthorizeActivation() = runBlocking<Unit> {
        initialize(); val config = configuration()
        unchanged { val result = reader().read(config); assertTrue(result.submissions.isEmpty()); assertTrue(result.prompts.isEmpty())
            assertFalse(result.hasPendingDrafts); assertFalse(result.activationAuthorized) }
    }
    @Test fun incompletePendingTextAndOriginalIdentitiesSurviveColdReopenAndReadOnlyPermission() = runBlocking<Unit> {
        initialize(); val prompt = prompts().updateDraft(draft(), mapOf(metric.uuid to CompletionMetricInput("-", "unsubmitted")))
        register(permissions = setOf("sync.read"), revision = 2); val config = configuration()
        unchanged {
            val proof = reader().read(config); val evidence = proof.prompts.single()
            assertEquals(prompt.row, evidence.row); assertEquals(prompt.entries, evidence.entries)
            assertTrue(evidence.pending); assertTrue(proof.hasPendingDrafts); assertFalse(proof.activationAuthorized)
            assertTrue(evidence.observations.isEmpty()); assertEquals(listOf(id(100)), proof.submissions.map { it.operationId })
            assertEquals(0, count("metric_logs")); assertEquals(1, config.requests.states.size)
            storage.reopen(); assertEquals(prompt.row, reader().read(config).prompts.single().row)
        }
    }
    @Test fun realSavedPartialSelectionUsesLocalReceiptsAndDoesNotInventBlankObservationOrigins() = runBlocking<Unit> {
        initialize(); val prompt = saved(); val config = configuration()
        unchanged {
            val proof = reader().read(config); val chosen = prompt.entries.single { it.input.isNotBlank() }
            val omitted = prompt.entries.single { it.input.isBlank() }
            assertEquals(2, proof.submissions.size); assertFalse(proof.hasPendingDrafts); assertFalse(proof.activationAuthorized)
            assertEquals(mapOf(chosen.operationId to NextRecoveryRequestState.NEVER_SENT), proof.prompts.single().observations)
            assertNull(db.nextRequestDao().origin(NEXT_OPERATION, omitted.operationId))
            assertNull(db.metricLogDao().getLogByUuid(omitted.observationUuid))
            assertTrue(config.requests.hasUnsettledRequests)
            assertTrue(runCatching { (proof.prompts as MutableList<*>).clear() }.isFailure)
            assertTrue(runCatching { (proof.prompts.single().entries as MutableList<*>).clear() }.isFailure)
            assertTrue(runCatching { (proof.prompts.single().observations as MutableMap<*, *>).clear() }.isFailure)
        }
    }
    @Test fun actualAckAndRemoteMetricDeletionKeepFrozenHistoryWithoutRequiringCurrentParents() = runBlocking<Unit> {
        initialize(); val prompt = saved(); drain()
        val original = canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))
        val deletion = original.copy(sequence = 21, operation = "delete", revision = 2, payload =
            JsonObject(original.payload + mapOf("revision" to JsonPrimitive(2), "deleted_at" to JsonPrimitive(time))))
        val meta = metadata(); val state = merger().state(access(), true)!!
        merger().page(access(), state, RoundSyncPullResponse(listOf(deletion), 21, false, time, 1, meta.checkpoints, meta.births))
        assertNull(db.metricDao().getMetricByUuid(metric.uuid)); assertEquals(0, count("metric_logs"))
        val config = configuration()
        unchanged {
            val proof = reader().read(config); assertEquals(prompt.row, proof.prompts.single().row)
            assertEquals(prompt.entries, proof.prompts.single().entries)
            assertEquals(setOf(NextRecoveryRequestState.ACCEPTED), proof.prompts.single().observations.values.toSet())
            assertFalse(config.requests.hasUnsettledRequests); assertFalse(proof.hasPendingDrafts); assertFalse(proof.activationAuthorized)
            storage.reopen(); assertEquals(prompt.row, reader().read(config).prompts.single().row)
        }
    }
    @Test fun dismissedIncompleteDraftRetainsInputWithoutCreatingObservationsOrBlockingAsPending() = runBlocking<Unit> {
        initialize(); val prompt = prompts().updateDraft(draft(), mapOf(metric.uuid to CompletionMetricInput("1e", "note")))
        prompts().dismiss(prompt); drain(); val config = configuration()
        unchanged { val proof = reader().read(config); val evidence = proof.prompts.single()
            assertEquals("dismissed", evidence.row.state); assertEquals(prompt.entries, evidence.entries)
            assertFalse(proof.hasPendingDrafts); assertFalse(proof.activationAuthorized); assertTrue(evidence.observations.isEmpty())
            assertEquals(1, proof.submissions.size); assertEquals(0, count("metric_logs")) }
    }
    @Test fun actualFailedSubmitRetainsFrozenTimeAndPendingDraftWithoutPartialReceipts() = runBlocking<Unit> {
        initialize(); val prompt = prompts().updateDraft(draft(), mapOf(metric.uuid to CompletionMetricInput("3")))
        val selected = prompt.entries.single { it.metricUuid == metric.uuid }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER proof_submit_fault BEFORE INSERT ON sync_outbox " +
            "WHEN NEW.operationId='${selected.operationId}' BEGIN SELECT RAISE(ABORT,'failed submit'); END")
        assertTrue(rejected { prompts().submit(prompt) }.message.orEmpty().contains("failed submit"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER proof_submit_fault")
        val config = configuration()
        unchanged { val proof = reader().read(config); val evidence = proof.prompts.single()
            assertTrue(proof.hasPendingDrafts); assertEquals(millis + 1000, evidence.row.recordedAtMillis)
            assertEquals("Asia/Shanghai", evidence.row.timezone); assertEquals(prompt.entries, evidence.entries)
            assertEquals(1, proof.submissions.size); assertTrue(evidence.observations.isEmpty()); assertEquals(0, count("metric_logs")) }
    }
    @Test fun lostObservationResponseIsUnknownDespiteSavedLocalReceiptAndNeverTriggersNetworkOrRepair() = runBlocking<Unit> {
        initialize(); val prompt = saved()
        onceSender().sendAndAccept(access(), id(100)); loseReply = true
        val chosen = prompt.entries.single { it.input.isNotBlank() }; rejected { sender(http).sendOperation(access(), chosen.operationId) }
        val config = configuration(); val wire = transmission(NEXT_OPERATION, chosen.operationId).wireBytes.copyOf()
        unchanged { val proof = reader().read(config)
            assertEquals(mapOf(chosen.operationId to NextRecoveryRequestState.UNKNOWN_RESULT), proof.prompts.single().observations)
            assertTrue(config.requests.hasUnsettledRequests); assertFalse(proof.hasPendingDrafts); assertFalse(proof.activationAuthorized)
            assertArrayEquals(wire, transmission(NEXT_OPERATION, chosen.operationId).wireBytes) }
    }
    @Test fun orphanAndChangedMetricReceiptFieldsAreRejectedEvenWithFreshStorageProof() = runBlocking<Unit> {
        initialize(); val prompt = saved(); drain(); val chosen = prompt.entries.single { it.input.isNotBlank() }
        val original = db.completionFollowUpDao().submission(chosen.operationId)!!
        val changes = listOf("referenceUuid" to id(90), "entityUuid" to id(91), "entityType" to "activity_event",
            "payloadJson" to "{}", "operationId" to id(92))
        for ((column, value) in changes) {
            db.openHelper.writableDatabase.execSQL("UPDATE local_fact_submissions SET $column=? WHERE operationId=?", arrayOf(value, original.operationId))
            val config = configuration()
            unchanged { assertEquals("SYNC_RECOVERY_FACT_RECEIPT_INVALID", rejected { reader().read(config) }.message) }
            db.openHelper.writableDatabase.execSQL("DELETE FROM local_fact_submissions WHERE operationId IN (?,?)", arrayOf(original.operationId, id(92)))
            db.completionFollowUpDao().insertSubmission(original)
        }
    }
    @Test fun savedPromptCannotChangeSubmittedValueOrPretendItsCommittedSelectionWasBlank() = runBlocking<Unit> {
        initialize(); val prompt = saved(); drain()
        for (input in listOf("99", "")) {
            val changed = prompt.entries.map { if (it.metricUuid == metric.uuid) it.copy(input = input) else it }
            db.completionFollowUpDao().updatePrompt(prompt.row.copy(entriesJson = json.encodeToString(changed)))
            val config = configuration()
            unchanged { rejected { reader().read(config) } }
        }
    }
    @Test fun duplicateJsonFieldsCannotMasqueradeAsTheOriginalReceiptOrDraft() = runBlocking<Unit> {
        initialize(); val prompt = saved(); drain(); val chosen = prompt.entries.single { it.input.isNotBlank() }
        val receipt = db.completionFollowUpDao().submission(chosen.operationId)!!
        val duplicate = "{\"metric_uuid\":\"${metric.uuid}\"," + receipt.payloadJson.drop(1)
        db.openHelper.writableDatabase.execSQL("UPDATE local_fact_submissions SET payloadJson=? WHERE operationId=?",
            arrayOf(duplicate, chosen.operationId))
        val receiptConfig = configuration()
        unchanged { rejected { reader().read(receiptConfig) } }
        db.openHelper.writableDatabase.execSQL("UPDATE local_fact_submissions SET payloadJson=? WHERE operationId=?",
            arrayOf(receipt.payloadJson, chosen.operationId))
        val duplicateDraft = prompt.row.entriesJson.replaceFirst("[{", "[{\"decimalPlaces\":3,")
        assertNotEquals(prompt.row.entriesJson, duplicateDraft)
        db.completionFollowUpDao().updatePrompt(prompt.row.copy(entriesJson = duplicateDraft))
        val draftConfig = configuration(); unchanged { rejected { reader().read(draftConfig) } }
    }
    @Test fun neverSentPrefilledPayloadNeedsItsOwnExactReceiptAndCannotBeAdoptedByRehashingTheQueue() = runBlocking<Unit> {
        initialize(); val prompt = saved(); val entry = prompt.entries.single { it.input.isNotBlank() }
        val receipt = db.completionFollowUpDao().submission(entry.operationId)!!
        db.openHelper.writableDatabase.execSQL("DELETE FROM local_fact_submissions WHERE operationId=?", arrayOf(entry.operationId))
        unchanged { rejected { reader().read(configuration()) } }
        db.completionFollowUpDao().insertSubmission(receipt)
        val row = db.syncOutboxDao().getByOperationId(entry.operationId)!!
        val changed = row.copy(payloadJson = "{}")
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET payloadJson=? WHERE id=?", arrayOf<Any>("{}", row.id))
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET sourceHash=? WHERE kind=? AND requestId=?",
            arrayOf(NextRequestSql.sourceHash(changed), NEXT_OPERATION, entry.operationId))
        unchanged { rejected { reader().read(configuration()) } }
    }
    @Test fun pendingDraftCannotReuseCompletionOperationOrAnotherDraftObservationIdentity() = runBlocking<Unit> {
        initialize(); val prompt = draft()
        val changes = listOf(prompt.entries.mapIndexed { index, entry -> if (index == 0) entry.copy(operationId = id(100)) else entry },
            prompt.entries.map { it.copy(observationUuid = id(110)) })
        for (entries in changes) {
            db.completionFollowUpDao().updatePrompt(prompt.row.copy(entriesJson = json.encodeToString(entries)))
            val config = configuration(); unchanged { rejected { reader().read(config) } }
        }
    }
    @Test fun rawPromptIntegerOverflowAndSourceMismatchAreRejectedWithoutRepair() = runBlocking<Unit> {
        initialize(); val prompt = draft()
        // Room schema permits Long revisions. JSON Int metadata must not wrap to a valid value.
        val wrapped = prompt.row.entriesJson.replace("\"decimalPlaces\":3", "\"decimalPlaces\":4294967299")
        assertNotEquals(prompt.row.entriesJson, wrapped)
        db.completionFollowUpDao().updatePrompt(prompt.row.copy(entriesJson = wrapped))
        val config = configuration(); unchanged { rejected { reader().read(config) } }
        db.completionFollowUpDao().updatePrompt(prompt.row.copy(activityUuid = timerHabit.uuid))
        val wrongSource = configuration()
        unchanged { assertEquals("SYNC_RECOVERY_DRAFT_SOURCE_INVALID", rejected { reader().read(wrongSource) }.message) }
    }
    @Test fun originalContentAndAuthorizationChangesInvalidateLocalEvidenceProof() = runBlocking<Unit> {
        initialize(); val prompt = draft(); val config = configuration()
        prompts().updateDraft(prompt, mapOf(metric.uuid to CompletionMetricInput("2")))
        unchanged { assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { reader().read(config) }.message) }
        val current = configuration(); register(permissions = setOf("sync.read"), revision = 2)
        unchanged { assertEquals("SYNC_ACCOUNT_CHANGED", rejected { reader().read(current) }.message) }
    }
}
