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
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real producers/refusals and SQLite. The explicit fixture writer is NOT a confirmation API. */
@RunWith(AndroidJUnit4::class)
class NextRecoveryAuditStoreTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private var failing = false
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
    private data class Saved(val id: String, val manifest: NextRecoveryAuditManifest, val drafts: List<NextRecoveryProjectionDraft>)

    /** Explicit testbed terminal fixture, uses actual original rows and bound HTTP refusals. */
    private suspend fun terminal(preview: NextRecoveryDependencyPreview): Saved = db.withTransaction {
        assertTrue(preview.canConfirm)
        val id = UUID.randomUUID().toString(); val checkpoint = audit.checkpoint(access())
        val drafts = projections.plan(preview); val rows = audit.graphRows(preview.archive)
        sql.execSQL("INSERT INTO next_recovery_audits VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(id,
            checkpoint.accountId, checkpoint.serverInstanceId, checkpoint.syncEpoch, checkpoint.deviceId,
            preview.root.kind, preview.root.requestId, preview.graphHash, "a".repeat(64), "b".repeat(64), preview.affected.size))
        val checkpointRef = payloads.write(id, "checkpoint", NextRecoveryAuditCodec.encode(checkpoint))
        val requests = preview.affected.map { node ->
            val origin = requireNotNull(db.nextRequestDao().origin(node.key.kind, node.key.requestId))
            val source = requireNotNull(db.syncOutboxDao().getById(origin.queueId))
            val ref = payloads.write(id, "source:${node.key.kind}:${node.key.requestId}", NextRecoverySourceSnapshot.encode(source, origin.sourceHash))
            fun row(table: String) = rows.singleOrNull { it.table == table && it.key == node.key }?.hash
            NextRecoveryAuditRequest(node.key, node.state, ref, origin.sourceHash, requireNotNull(row("next_request_origins")),
                row("next_transmissions"), row("next_rejections"))
        }
        val projectionRefs = drafts.map { draft ->
            val key = "${draft.before.entityType}:${draft.before.entityUuid}"
            NextRecoveryAuditProjection(draft.before.entityType, draft.before.entityUuid,
                payloads.write(id, "projection_before:$key", NextRecoveryAuditCodec.encode(draft.before)),
                payloads.write(id, "projection_after:$key", NextRecoveryAuditCodec.encode(draft.after)),
                payloads.write(id, "projection_base:$key", NextRecoveryAuditCodec.encode(draft.baseline)))
        }
        val manifest = NextRecoveryAuditManifest(1, preview.root, preview.archive, checkpointRef, requests, rows, projectionRefs)
        val manifestRef = payloads.write(id, "manifest", NextRecoveryAuditCodec.encode(manifest))
        sql.execSQL("UPDATE next_recovery_audits SET checkpointHash=?,manifestHash=? WHERE recoveryId=?", arrayOf(checkpointRef.hash, manifestRef.hash, id))
        for (request in requests) {
            sql.execSQL("INSERT INTO next_abandoned_requests VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(request.key.kind, request.key.requestId, id,
                request.state.name, request.sourceHash, request.source.hash, request.originHash, request.transmissionHash, request.rejectionHash))
            sql.execSQL("DELETE FROM sync_outbox WHERE operationId=?", arrayOf(request.key.requestId))
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        for (draft in drafts) {
            assertEquals("metric", draft.after.entityType); assertNotNull(draft.after.localId)
            val cells = draft.after.local.entries.toList()
            sql.execSQL("UPDATE metrics SET ${cells.joinToString(",") { "`${it.key}`=?" }} WHERE id=?",
                (cells.map { (_, value) -> when {
                    value == JsonNull -> null
                    value.jsonPrimitive.isString -> value.jsonPrimitive.content
                    value.jsonPrimitive.longOrNull != null -> value.jsonPrimitive.long
                    else -> value.jsonPrimitive.double
                } } + draft.after.localId).toTypedArray<Any?>())
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        assertEquals(manifest, audit.read(access(), id).manifest)
        Saved(id, manifest, drafts)
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
}
