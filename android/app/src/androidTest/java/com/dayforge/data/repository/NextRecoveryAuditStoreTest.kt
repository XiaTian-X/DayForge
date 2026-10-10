package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.domain.model.ChallengeRoundRecord
import com.dayforge.domain.model.initialChallengeRoundHead
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real producers/refusals and SQLite; audits are created by the actual atomic transaction participant. */
@RunWith(AndroidJUnit4::class)
class NextRecoveryAuditStoreTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private var failing = false
    private lateinit var recoveryHttp: NextSyncHttp
    private val audit get() = NextRecoveryAuditStore(db)
    private val payloads get() = NextRecoveryPayloadStore(db)
    private val projections get() = NextRecoveryProjectionStore(db)
    private val sql get() = db.openHelper.writableDatabase
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
        val operation = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8)).operations.single()
        val results = if (failing) listOf(NextSyncOperationResult(operation.operationId, operation.entityType,
            operation.entityUuid, "rejected", errorCode = "INVALID_PAYLOAD")) else json.decodeFromString<NextSyncPushResponse>(
            successReply(input, (operation.baseRevision ?: 0) + 1) { body ->
                if (operation.entityUuid == metric.uuid) JsonObject(body + ("created_at" to JsonPrimitive(time))) else body
            }.bytes.toString(Charsets.UTF_8)).results
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(results, 1, meta.checkpoints, meta.births)).toByteArray())
    }
    private suspend fun initialize(): Pair<NextSyncHttp, MaterialSocketServer> {
        register(); val (http, server) = channel(::respond)
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        val merge = NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
        val meta = metadata()
        merge.bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), 20, time, emptyList(), 1, meta.checkpoints, meta.births))
        recoveryHttp = http
        return http to server
    }
    private suspend fun edit(name: String): SyncOutboxEntity {
        val ticket = editor.metric(metric.id)
        editingMetrics().updateMetric(requireNotNull(ticket.value).copy(name = name), ticket.authority)
        return db.syncOutboxDao().getAll().last()
    }
    private suspend fun refuse(http: NextSyncHttp, id: String): String {
        failing = true
        val delivery = requireNotNull(sender(http).sendOperation(access(), id))
        sender(http).recordRejection(access(), NEXT_OPERATION, delivery.requestId, delivery.transmissionProof,
            json.encodeToString(delivery.result.results.single()), delivery.challengeMetadata)
        return delivery.requestId
    }
    private suspend fun preview(http: NextSyncHttp, root: String) = NextSyncRuntime(db, tokens, sessions, http, preferences)
        .readRecoveryPreview(NextRecoveryKey(NEXT_OPERATION, root))
    private data class Saved(val ticket: NextRecoveryConfirmation, val manifest: NextRecoveryAuditManifest,
        val drafts: List<NextRecoveryProjectionDraft>) { val id get() = ticket.recoveryId }
    private fun confirmationStore(): NextRecoveryConfirmationStore {
        val core = sender(recoveryHttp)
        val timers = NextTimerRequestStore(db, tokens, sessions, core)
        val local = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        val facts = OneTimeAcceptedEventStore(db, tokens, sessions, local, timerRequests = timers)
        val once = NextOneTimeRequestStore(db, tokens, sessions, recoveryHttp, facts, core)
        return NextRecoveryConfirmationStore(db, NextRecoveryDependencyReader(db, tokens, sessions, core, timers, once,
            NextChallengeRestartRepository(db, tokens, sessions, recoveryHttp)))
    }
    private suspend fun prepare(preview: NextRecoveryDependencyPreview, selected: Set<NextRecoveryKey> = preview.affected.map { it.key }.toSet()) =
        NextSyncRuntime(db, tokens, sessions, recoveryHttp, preferences).prepareRecoveryConfirmation(preview, selected)
    private suspend fun confirm(ticket: NextRecoveryConfirmation) =
        NextSyncRuntime(db, tokens, sessions, recoveryHttp, preferences).confirmRecovery(ticket)
    private suspend fun terminal(preview: NextRecoveryDependencyPreview): Saved {
        val ticket = prepare(preview)
        val proof = confirm(ticket)
        return Saved(ticket, proof.manifest, ticket.drafts())
    }
    private suspend fun saved(): Pair<Saved, MaterialSocketServer> {
        val (http, server) = initialize(); val a = edit("A"); edit("B"); edit("C")
        return terminal(preview(http, refuse(http, a.operationId))) to server
    }
    private class Rollback : RuntimeException()
    private suspend fun rejectsMutation(saved: Saved, change: suspend () -> Unit) {
        var checked = false
        try { db.withTransaction { change(); rejected { audit.read(access(), saved.id) }; checked = true; throw Rollback() } }
        catch (_: Rollback) { }
        assertTrue(checked); assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
    }
    private suspend fun rewriteManifest(saved: Saved, value: NextRecoveryAuditManifest) {
        sql.execSQL("DELETE FROM next_recovery_audit_payloads WHERE recoveryId=? AND payloadKey='manifest'", arrayOf(saved.id))
        val ref = payloads.write(saved.id, "manifest", NextRecoveryAuditCodec.encode(value))
        sql.execSQL("UPDATE next_recovery_audits SET manifestHash=? WHERE recoveryId=?", arrayOf(ref.hash, saved.id))
    }

    @Test fun completeGroupColdReadKeepsOriginalSourcesWithoutAckOrCursorAdvance() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A"); val b = edit("B"); val c = edit("C")
        val root = refuse(http, a.operationId); val originalRows = db.syncOutboxDao().getAll(); val cursor = db.nextSyncStateDao().rows()
        val saved = terminal(preview(http, root)); val calls = server.requests.size
        storage.reopen()
        for (row in originalRows) db.withTransaction {
            val proof = requireNotNull(audit.source(access(), NextRecoveryKey(NEXT_OPERATION, row.operationId)))
            assertEquals(row, proof.second); assertEquals(saved.manifest, proof.first.manifest)
        }
        assertEquals(setOf(a.operationId, b.operationId, c.operationId), saved.manifest.requests.map { it.key.requestId }.toSet())
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(cursor, db.nextSyncStateDao().rows())
        assertEquals(0, count("next_acceptances")); assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertEquals(calls, server.requests.size)
    }
    @Test fun readingOldAuditAfterNewLocalEditDoesNotReapplyOldProjection() = runBlocking<Unit> {
        val (saved, server) = saved(); val calls = server.requests.size
        // Ordinary admission/causal consumer integration is a later part of D-020. Use a private
        // testbed local change here to verify the terminal reader itself never restores old rows.
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().update(requireNotNull(db.metricDao().getMetricById(metric.id)).copy(name = "Later local display"))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        storage.reopen()
        assertEquals(saved.manifest, confirm(saved.ticket).manifest)
        assertEquals(1, count("next_recovery_audits")); assertEquals(3, count("next_abandoned_requests"))
        assertEquals("Later local display", db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(calls, server.requests.size); assertEquals(0, count("next_acceptances"))
    }
    @Test fun missingMemberExtraPayloadAndWrongCountCannotValidatePartialGroups() = runBlocking<Unit> {
        val (saved, _) = saved()
        rejectsMutation(saved) { sql.execSQL("DELETE FROM next_abandoned_requests WHERE requestId=?", arrayOf(saved.manifest.requests.last().key.requestId)) }
        rejectsMutation(saved) { payloads.write(saved.id, "source:sync_operation:${id(999)}", byteArrayOf(1)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_recovery_audits SET selectedCount=2 WHERE recoveryId=?", arrayOf(saved.id)) }
    }
    @Test fun rawTypesAndChangedOriginSourceAndRejectionCannotBecomeTerminalProof() = runBlocking<Unit> {
        val (saved, _) = saved(); val root = saved.manifest.root.requestId
        rejectsMutation(saved) { sql.execSQL("UPDATE next_recovery_audits SET selectedCount=1.5 WHERE recoveryId=?", arrayOf(saved.id)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_request_origins SET intentJson='{}' WHERE requestId=?", arrayOf(root)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_rejections SET resultHash=? WHERE requestId=?", arrayOf("d".repeat(64), root)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_abandoned_requests SET sourceSnapshotHash=? WHERE requestId=?", arrayOf("e".repeat(64), root)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_recovery_audit_payloads SET bytes=X'00' WHERE recoveryId=? AND payloadKey=? AND part=0",
            arrayOf(saved.id, saved.manifest.requests.first().source.key)) }
    }
    @Test fun remainingSourceWrongScopeOrDisabledOutboxCannotMasqueradeAsCommittedRecovery() = runBlocking<Unit> {
        val (saved, _) = saved()
        rejectsMutation(saved) {
            val request = saved.manifest.requests.first()
            db.syncOutboxDao().insert(NextRecoverySourceSnapshot.decode(payloads.read(saved.id, request.source), request.sourceHash))
        }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_recovery_audits SET deviceId=? WHERE recoveryId=?", arrayOf(id(888), saved.id)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
    }
    @Test fun rehashedManifestCannotOmitKnownDependencyOrChangeSelectedSet() = runBlocking<Unit> {
        val (saved, _) = saved()
        rejectsMutation(saved) {
            val graph = saved.manifest.graph.copy(dependencies = emptyList())
            val hash = graph.preview(saved.manifest.root).graphHash
            rewriteManifest(saved, saved.manifest.copy(graph = graph))
            sql.execSQL("UPDATE next_recovery_audits SET graphHash=? WHERE recoveryId=?", arrayOf(hash, saved.id))
        }
        val omitted = saved.manifest.requests.first { it.key != saved.manifest.root }.key
        rejectsMutation(saved) { rewriteManifest(saved, saved.manifest.copy(requests = saved.manifest.requests.filter { it.key != omitted })) }
    }
    @Test fun rehashedAfterSnapshotMustStillMatchFullFrozenBaselineAndPreserveLocalIdentity() = runBlocking<Unit> {
        val (saved, _) = saved(); val descriptor = saved.manifest.projections.single(); val draft = saved.drafts.single()
        for (local in listOf(JsonObject(draft.after.local + ("name" to JsonPrimitive("Forged restore"))),
            JsonObject(draft.after.local + ("iconResId" to JsonPrimitive(555))))) rejectsMutation(saved) {
            val after = draft.after.copy(local = local, body = if (local["name"] != draft.after.local["name"])
                JsonObject(requireNotNull(draft.after.body) + ("name" to local.getValue("name"))) else draft.after.body)
            sql.execSQL("DELETE FROM next_recovery_audit_payloads WHERE recoveryId=? AND payloadKey=?", arrayOf(saved.id, descriptor.after.key))
            val reference = payloads.write(saved.id, descriptor.after.key, NextRecoveryAuditCodec.encode(after))
            rewriteManifest(saved, saved.manifest.copy(projections = listOf(descriptor.copy(after = reference))))
        }
    }
    @Test fun originalAliasAndAcceptedAncestorAreAuditedButNeverSelectedForRetirement() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val b = edit("B"); edit("C")
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), a.operationId))
        val root = refuse(http, b.operationId); assertNotEquals(b.operationId, root)
        val saved = terminal(preview(http, root))
        assertTrue(saved.manifest.requests.none { it.key.requestId in setOf(a.operationId, b.operationId) })
        assertEquals("A", db.metricDao().getMetricById(metric.id)!!.name); assertEquals(1, count("next_acceptances"))
        rejectsMutation(saved) { sql.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?", arrayOf("d".repeat(64), a.operationId)) }
        rejectsMutation(saved) {
            val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId))
            val json = Json.parseToJsonElement(receipt.resultJson).jsonObject
            val changed = JsonObject(json + ("operation_id" to JsonPrimitive(id(999)))).toString()
            sql.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
                arrayOf(changed, nextRequestHash(changed.toByteArray()), a.operationId))
            val hash = requireNotNull(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, a.operationId)))
            rewriteManifest(saved, saved.manifest.copy(rows = saved.manifest.rows.map {
                if (it.table == "next_acceptances" && it.key.requestId == a.operationId) it.copy(hash = hash) else it
            }))
        }
        rejectsMutation(saved) {
            sql.execSQL("UPDATE next_structural_supersessions SET sourceSnapshotHash=? WHERE originalId=?", arrayOf("c".repeat(64), b.operationId))
            val hash = requireNotNull(NextRequestSql.rowHash(sql, "next_structural_supersessions", "originalId=?", arrayOf(b.operationId)))
            rewriteManifest(saved, saved.manifest.copy(rows = saved.manifest.rows.map {
                if (it.table == "next_structural_supersessions" && it.key.requestId == b.operationId) it.copy(hash = hash) else it
            }))
        }
        storage.reopen(); assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        register(permissions = setOf("sync.read"), revision = 2)
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
    }
    @Test fun ordinaryPlannerIsReadOnlyAndPreservesFullLocalPreferenceIdentity() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("Changed metric"); val root = refuse(http, a.operationId)
        val queue = db.syncOutboxDao().getAll(); val calls = server.requests.size
        val before = requireNotNull(db.metricDao().getMetricById(metric.id))
        val frozen = preview(http, root)
        val draft = db.withTransaction { projections.plan(frozen) }.single()
        assertEquals(before.id, draft.before.localId); assertEquals(before.id, draft.after.localId)
        assertEquals(before.colorHex, draft.after.local.getValue("colorHex").jsonPrimitive.content)
        assertEquals(before.iconResId, draft.after.local.getValue("iconResId").jsonPrimitive.int)
        assertEquals(NextStructureMapper.writeMetric(metric), draft.after.body)
        assertEquals(queue, db.syncOutboxDao().getAll()); assertEquals(before, db.metricDao().getMetricById(metric.id)); assertEquals(calls, server.requests.size)
    }

    @Test fun rejectedRetainedLinkPlansSameLocalIdAndStillBlocksItsMetricDeletion() = runBlocking<Unit> {
        val (http, server) = initialize()
        creatingMetrics().linkHabits(metric, setOf(habit.id))
        val creation = db.syncOutboxDao().getAll().single()
        sender(http).sendAndAcceptOperation(access(), creation.operationId)
        val link = db.habitMetricLinkDao().getAllLinksForMetric(metric.id).single()
        editingMetrics().unlinkHabit(link.id, link.uuid)
        val deletion = db.syncOutboxDao().getAll().single()
        val root = refuse(http, deletion.operationId)
        val before = db.habitMetricLinkDao().getLinkByUuid(link.uuid)
        val frozen = preview(http, root)
        val draft = db.withTransaction { projections.plan(frozen) }.single()
        assertEquals(link.id, draft.before.localId); assertEquals(link.id, draft.after.localId)
        assertEquals(link.habitUuid, draft.after.local.getValue("habitUuid").jsonPrimitive.content)
        assertEquals(link.metricUuid, draft.after.local.getValue("metricUuid").jsonPrimitive.content)
        assertEquals(before, db.habitMetricLinkDao().getLinkByUuid(link.uuid))
        // Leave the rejected link intact; do not bypass that real dependency by deleting its metric.
        val metricTicket = editor.metric(metric.id)
        editingMetrics().deleteMetric(requireNotNull(metricTicket.value), metricTicket.authority)
        val metricDelete = db.syncOutboxDao().getAll().single { it.recordType == "metric" }
        val pushes = server.requests.count { it.path.endsWith("/push") }
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendOperation(access(), metricDelete.operationId) } as NextRequestException).reason)
        assertEquals(pushes, server.requests.count { it.path.endsWith("/push") })
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, metricDelete.operationId))
        assertNotNull(db.metricDao().getMetricById(metric.id))
        assertNull(db.metricDao().getVisibleMetricById(metric.id))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, deletion.operationId))
    }

    @Test fun rejectedRetainedMetricDeleteKeepsFullBaselineAndLocalIdentityForColdPlanning() = runBlocking<Unit> {
        val (http, server) = initialize(); val ticket = editor.metric(metric.id)
        editingMetrics().deleteMetric(requireNotNull(ticket.value), ticket.authority)
        val deletion = db.syncOutboxDao().getAll().single()
        val root = refuse(http, deletion.operationId); storage.reopen()
        val original = requireNotNull(db.metricDao().getMetricById(metric.id)); val calls = server.requests.size
        val frozen = preview(http, root)
        val drafts = db.withTransaction { NextRecoveryProjectionStore(db).plan(frozen) }
        val draft = drafts.single()
        assertEquals(metric.id, draft.before.localId); assertEquals(metric.id, draft.after.localId)
        assertEquals(NextStructureMapper.writeMetric(metric), draft.after.body)
        assertEquals(original.iconResId, draft.after.local.getValue("iconResId").jsonPrimitive.int)
        assertEquals(original, db.metricDao().getMetricById(metric.id)); assertEquals(calls, server.requests.size)
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, deletion.operationId))
        assertNull(db.metricDao().getVisibleMetricById(metric.id))
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
        creatingMetrics().createMetric(metric.copy(id = 0, uuid = id(881)), creationAuthority = creator.capture())
        val later = preview(http, root)
        assertEquals("RECOVERY_PROJECTION_NAME_CONFLICT",
            rejected { db.withTransaction { NextRecoveryProjectionStore(db).plan(later) } }.message)
        assertEquals(original, db.metricDao().getMetricById(metric.id))
    }
    @Test fun codecRejectsOmittedFieldsDuplicateKeysWrongTypesUnknownFieldsAndMalformedUtf8() = runBlocking<Unit> {
        val (saved, _) = saved()
        val checkpoint = db.withTransaction { audit.checkpoint(access()) }
        val text = NextRecoveryAuditCodec.encodeText(checkpoint)
        val invalid = listOf(text.replace("\"syncHash\":\"${checkpoint.syncHash}\",", ""),
            text.replace("\"accountId\":", "\"accountId\":\"${checkpoint.accountId}\",\"accountId\":"),
            text.replace("\"accountId\":\"${checkpoint.accountId}\"", "\"accountId\":1"), text.dropLast(1) + ",\"extra\":0}")
        for (value in invalid) rejected { NextRecoveryAuditCodec.decode<NextRecoveryAuditCheckpoint>(value.toByteArray(), 2_097_152) }
        rejected { NextRecoveryAuditCodec.decode<NextRecoveryAuditCheckpoint>(byteArrayOf(0xC3.toByte(), 0x28), 2_097_152) }
        assertEquals(saved.manifest, NextRecoveryAuditCodec.decode<NextRecoveryAuditManifest>(NextRecoveryAuditCodec.encode(saved.manifest), 67_108_864))
    }
    @Test fun threeStructureKindsCaptureAllRawCellsAndValidateAgainstRealDomainBaselines() = runBlocking<Unit> {
        initialize()
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val row = HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid, metricId = metric.id, metricUuid = metric.uuid,
                uuid = id(800), coefficient = 1.25, createdAt = millis, updatedAt = millis)
            db.habitMetricLinkDao().insertOrIgnore(row)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            val linkBody = buildJsonObject { put("activity_uuid", habit.uuid); put("metric_uuid", metric.uuid); put("coefficient", 1.25)
                put("show_in_activity_detail", true); put("prompt_on_complete", false); put("is_active", true) }
            for ((type, uuid, body) in listOf(Triple("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
                Triple("metric", metric.uuid, NextStructureMapper.writeMetric(metric)), Triple("activity_metric_link", row.uuid, linkBody))) {
                val snapshot = projections.capture(type, uuid)
                assertEquals(body, snapshot.body); assertNotNull(snapshot.localId)
                projections.validatePair(snapshot, snapshot)
                assertEquals(snapshot, NextRecoveryAuditCodec.decode<NextRecoveryProjectionSnapshot>(NextRecoveryAuditCodec.encode(snapshot), NEXT_RECOVERY_SOURCE_BYTES))
                rejected { projections.validatePair(snapshot, snapshot.copy(local = JsonObject(snapshot.local + ("id" to JsonPrimitive(1.5))))) }
                rejected { projections.validatePair(snapshot, snapshot.copy(localId = snapshot.localId!! + 1)) }
            }
        }
    }
    @Test fun neverAcceptedCreationPlansOnlyPureLocalRemovalWithoutInventingRemoteTombstone() = runBlocking<Unit> {
        val (http, server) = initialize()
        val fresh = metric.copy(id = 0, uuid = id(700), name = "Never accepted")
        val localId = creatingMetrics().createMetric(fresh, creationAuthority = creator.capture())
        val source = db.syncOutboxDao().getAll().single { it.entityUuid == fresh.uuid }
        val root = refuse(http, source.operationId); val frozen = preview(http, root); val calls = server.requests.size
        val draft = db.withTransaction { projections.plan(frozen) }.single()
        assertEquals(localId, draft.before.localId); assertNull(draft.after.localId); assertNull(draft.baseline.rowHash)
        assertNull(db.syncOutboxDao().getState("metric", fresh.uuid)); assertNotNull(db.metricDao().getMetricById(localId))
        assertEquals(listOf(source.operationId), db.syncOutboxDao().getAll().map { it.operationId })
        assertEquals(0, count("next_acceptances")); assertEquals(calls, server.requests.size)
        val saved = terminal(frozen)
        assertNull(db.metricDao().getMetricById(localId)); assertNull(db.syncOutboxDao().getState("metric", fresh.uuid))
        assertEquals(0, count("next_acceptances")); assertEquals(calls, server.requests.size)
        storage.reopen()
        assertEquals(source, db.withTransaction { audit.source(access(), saved.manifest.root) }!!.second)
        assertEquals(saved.manifest, confirm(saved.ticket).manifest)
    }
    @Test fun newUnselectedEditStopsOldPlanRatherThanDroppingLaterWork() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val root = refuse(http, a.operationId)
        val old = preview(http, root); val b = edit("Later edit")
        val rows = db.syncOutboxDao().getAll()
        rejected { db.withTransaction { projections.plan(old) } }
        assertEquals(rows, db.syncOutboxDao().getAll()); assertNotNull(db.syncOutboxDao().getById(b.id))
        assertEquals(0, count("next_recovery_audits"))
    }
    @Test fun coldAuditDoesNotGrantWritePermissionButLegitimatePermissionRefreshKeepsItsEvidence() = runBlocking<Unit> {
        val (saved, _) = saved()
        register(permissions = setOf("sync.read"), revision = 2)
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        assertEquals(setOf("sync.read"), access().capabilities)
        register(device = id(900), permissions = setOf("sync.read"), revision = 3)
        rejected { db.withTransaction { audit.read(access(), saved.id) } }
    }
    @Test fun cancellationAndMissingAuditCannotProducePartialTerminalResults() = runBlocking<Unit> {
        val (saved, _) = saved(); var entered = false
        val job = launch { db.withTransaction { entered = true; currentCoroutineContext().cancel(); audit.read(access(), saved.id); fail("Cancelled read returned") } }
        job.join(); assertTrue(entered); assertTrue(job.isCancelled)
        rejected { db.withTransaction { audit.read(access(), id(999)) } }
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
    }

    @Test fun selectionMustExplicitlyIncludeEveryNeverSentSuccessor() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); edit("B")
        val root = refuse(http, a.operationId); val frozen = preview(http, root)
        val rows = db.syncOutboxDao().getAll()
        rejected { prepare(frozen, setOf(frozen.root)) }
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(0, count("next_recovery_audits"))
    }

    @Test fun actualUnknownHttpResultCannotBeConfirmedAsNeverSentOrRejected() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A")
        assertNotNull(sender(http).sendOperation(access(), a.operationId))
        val rows = db.syncOutboxDao().getAll(); val calls = server.requests.size
        val unknown = preview(http, a.operationId)
        assertEquals(NextRecoveryRequestState.UNKNOWN_RESULT, unknown.affected.single().state)
        rejected { prepare(unknown) }
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(0, count("next_abandoned_requests"))
        assertEquals(0, count("next_acceptances")); assertEquals(calls, server.requests.size)
    }

    @Test fun changedGraphProjectionAndDevicePermissionsRejectTheWholeFrozenConfirmation() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val root = refuse(http, a.operationId)
        val ticket = prepare(preview(http, root)); edit("Later")
        val rows = db.syncOutboxDao().getAll(); val metricBefore = db.metricDao().getMetricById(metric.id)
        rejected { confirm(ticket) }
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(metricBefore, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("next_recovery_audits"))
        val newTicket = prepare(preview(http, root))
        register(permissions = setOf("sync.read"), revision = 2)
        assertEquals("SYNC_RECOVERY_WRITE_PERMISSION_REQUIRED", rejected { confirm(newTicket) }.message)
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(0, count("next_recovery_audits"))
        register(device = id(900), revision = 3)
        assertEquals("SYNC_CHALLENGE_CONTEXT_CHANGED", rejected { confirm(newTicket) }.message)
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(0, count("next_acceptances"))
    }

    @Test fun changedPrivateProjectionWithSameGraphHashRequiresNewPreviewInsteadOfOverwriting() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val root = refuse(http, a.operationId)
        val ticket = prepare(preview(http, root)); val rows = db.syncOutboxDao().getAll()
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE metrics SET iconResId=777 WHERE id=?", arrayOf(metric.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals("SYNC_RECOVERY_PROJECTION_CHANGED", rejected { confirm(ticket) }.message)
        assertEquals(777, db.metricDao().getMetricById(metric.id)!!.iconResId)
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(0, count("next_recovery_audits"))
    }

    @Test fun lateProtectedStateMutationRollsBackAuditQueueAndProjectionThenSameTicketCanRetry() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A"); edit("B")
        val ticket = prepare(preview(http, refuse(http, a.operationId)))
        val rows = db.syncOutboxDao().getAll(); val before = db.metricDao().getMetricById(metric.id)
        val cursor = db.nextSyncStateDao().rows(); val calls = server.requests.size
        sql.execSQL("CREATE TRIGGER recovery_cursor_fault AFTER INSERT ON next_abandoned_requests BEGIN UPDATE next_sync_state SET cursor=cursor+1; END")
        assertEquals("SYNC_RECOVERY_PROTECTED_STATE_CHANGED", rejected { confirm(ticket) }.message)
        sql.execSQL("DROP TRIGGER recovery_cursor_fault")
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(before, db.metricDao().getMetricById(metric.id))
        assertEquals(cursor, db.nextSyncStateDao().rows()); assertEquals(0, count("next_recovery_audits"))
        assertEquals(0, count("next_abandoned_requests")); assertEquals(0, count("next_recovery_audit_payloads"))
        assertEquals(0, count("next_acceptances")); assertEquals(calls, server.requests.size)
        storage.reopen(); assertEquals(ticket.recoveryId, confirm(ticket).recoveryId)
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(cursor, db.nextSyncStateDao().rows())
    }

    @Test fun actualFinalCommitFailureAndCancellationPreserveExactSourcesForColdSameTicketRetry() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); edit("B")
        val ticket = prepare(preview(http, refuse(http, a.operationId)))
        val rows = db.syncOutboxDao().getAll(); val before = db.metricDao().getMetricById(metric.id)
        sql.execSQL("CREATE TABLE recovery_commit_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE recovery_commit_guard(id INTEGER PRIMARY KEY,parent INTEGER,FOREIGN KEY(parent) REFERENCES recovery_commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        var applied = false
        val error = rejected { sessions.exclusive { db.withTransaction {
            sql.execSQL("INSERT INTO recovery_commit_guard VALUES(1,99)")
            val result = confirmationStore().confirm(access(), ticket)
            assertEquals(ticket.recoveryId, result.recoveryId); applied = true
        } } }
        assertTrue(applied); assertTrue(error is android.database.sqlite.SQLiteConstraintException)
        // A deferred COMMIT fault can leave the framework writer's native transaction open.
        // Close that actual writer before persistence assertions or another transaction; a WAL
        // reader on the old handle is not proof of rollback. This is the same cold-fault contract
        // used by the real config replacement and deletion ACK suites.
        storage.reopen()
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(before, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("next_recovery_audits")); assertEquals(0, count("next_abandoned_requests"))
        var entered = false
        val job = launch { sessions.exclusive { db.withTransaction {
            confirmationStore().confirm(access(), ticket); entered = true
            currentCoroutineContext().cancel(); currentCoroutineContext().ensureActive()
        } } }
        job.join(); assertTrue(entered); assertTrue(job.isCancelled)
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(before, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("next_recovery_audits")); assertEquals(0, count("next_recovery_audit_payloads"))
        storage.reopen(); assertEquals(ticket.recoveryId, confirm(ticket).recoveryId)
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("next_acceptances"))
    }

    @Test fun realRejectedRetainedLinkAndMetricDeletionRestoreOriginalVisibleIdentitiesTogether() = runBlocking<Unit> {
        val (http, server) = initialize()
        creatingMetrics().linkHabits(metric, setOf(habit.id))
        sender(http).sendAndAcceptOperation(access(), db.syncOutboxDao().getAll().single().operationId)
        val link = db.habitMetricLinkDao().getAllLinksForMetric(metric.id).single()
        editingMetrics().unlinkHabit(link.id, link.uuid)
        val root = refuse(http, db.syncOutboxDao().getAll().single().operationId)
        val metricTicket = editor.metric(metric.id)
        editingMetrics().deleteMetric(requireNotNull(metricTicket.value), metricTicket.authority)
        val frozen = preview(http, root); assertEquals(2, frozen.affected.size)
        val rows = db.syncOutboxDao().getAll(); val cursor = db.nextSyncStateDao().rows(); val calls = server.requests.size
        val saved = terminal(frozen)
        assertEquals(metric, db.metricDao().getVisibleMetricById(metric.id))
        assertEquals(link, db.habitMetricLinkDao().getLinkByUuid(link.uuid))
        assertEquals(listOf(link), db.habitMetricLinkDao().getVisibleLinksForHabit(habit.id))
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertEquals(1, count("next_acceptances"))
        assertEquals(cursor, db.nextSyncStateDao().rows()); assertEquals(calls, server.requests.size)
        storage.reopen(); assertEquals(saved.manifest, confirm(saved.ticket).manifest)
        for (row in rows) assertEquals(row, db.withTransaction { audit.source(access(), NextRecoveryKey(NEXT_OPERATION, row.operationId)) }!!.second)
    }

    @Test fun rejectedPlanEditWithNeverSentDeleteRestoresOriginalLocalIdAndUntouchedRoundPolicy() = runBlocking<Unit> {
        val (http, server) = initialize()
        val editTicket = editor.habit(habit.id)
        editingHabits().updateHabit(requireNotNull(editTicket.value).copy(name = "Edited count"), editAuthority = editTicket.authority)
        val edit = db.syncOutboxDao().getAll().single()
        val root = refuse(http, edit.operationId)
        val deleteTicket = editor.habit(habit.id)
        editingHabits().deleteHabit(requireNotNull(deleteTicket.value), authority = deleteTicket.authority)
        assertNull(db.habitDao().getVisibleHabitById(habit.id))
        val rows = db.syncOutboxDao().getAll()
        val rounds = Triple(db.nextChallengeDao().rounds(), db.nextChallengeDao().births(), db.nextChallengeDao().states())
        val cursor = db.nextSyncStateDao().rows(); val calls = server.requests.size
        val frozen = preview(http, root); assertEquals(2, frozen.affected.size)
        val saved = terminal(frozen)
        assertEquals(habit, db.habitDao().getVisibleHabitById(habit.id))
        assertEquals(timerHabit, db.habitDao().getHabitById(timerHabit.id))
        assertEquals(rounds, Triple(db.nextChallengeDao().rounds(), db.nextChallengeDao().births(), db.nextChallengeDao().states()))
        assertEquals(cursor, db.nextSyncStateDao().rows())
        assertEquals(0, count("next_acceptances")); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertEquals(calls, server.requests.size)
        storage.reopen(); assertEquals(saved.manifest, confirm(saved.ticket).manifest)
        for (row in rows) assertEquals(row, db.withTransaction { audit.source(access(), NextRecoveryKey(NEXT_OPERATION, row.operationId)) }!!.second)
    }

    @Test fun exactProjectionApplicationRestoresAValidNamePermutationWithoutChangingUnselectedRows() = runBlocking<Unit> {
        initialize()
        db.withTransaction {
            val originals = listOf(habit, timerHabit).map { projections.capture("plan_node", it.uuid) }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habits SET name='Temporary test name' WHERE id=?", arrayOf(habit.id))
            sql.execSQL("UPDATE habits SET name=? WHERE id=?", arrayOf<Any>(habit.name, timerHabit.id))
            sql.execSQL("UPDATE habits SET name=? WHERE id=?", arrayOf<Any>(timerHabit.name, habit.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            val queue = db.syncOutboxDao().getAll()
            val drafts = originals.map { after ->
                val state = requireNotNull(db.syncOutboxDao().getState("plan_node", after.entityUuid))
                val baseline = NextRecoveryProjectionBaseline("plan_node", after.entityUuid,
                    NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf("plan_node", after.entityUuid)),
                    state.revision, state.deleted, state.payloadJson, state.payloadHash, state.updatedAt)
                NextRecoveryProjectionDraft(projections.capture("plan_node", after.entityUuid), after, baseline)
            }
            projections.apply(drafts)
            assertEquals(habit, db.habitDao().getHabitById(habit.id)); assertEquals(timerHabit, db.habitDao().getHabitById(timerHabit.id))
            assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(queue, db.syncOutboxDao().getAll())
            NextRequestSql.requireOutboxEnabled(sql)
        }
    }
}
