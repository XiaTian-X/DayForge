package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.*
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual original producer, physical Room, real files/HTTP and direct ACK consumers. */
@RunWith(AndroidJUnit4::class)
class NextConfigReplacementRepositoryTest : NextObjectEditorFixture() {
    private val canonical = mutableMapOf<String, JsonObject>()
    private val responses = mutableMapOf<String, MaterialSocketServer.Reply>()
    private val json = Json { encodeDefaults = true }
    private var roundMetadata: ChallengeMetadata? = null
    private fun service() = NextConfigImportRepository(db, tokens, sessions, icons,
        NextObjectCreator(db, tokens, sessions, icons), app)
    private suspend fun source() = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
    private suspend fun entry(id: String, source: ValidatedConfigBundle): NextConfigImportStore.Entry {
        val context = icons.capture()
        return sessions.exclusive { db.withTransaction { NextConfigImportStore(db).read(context, id, source) } }
    }
    private fun same(expected: NextConfigImportStore.Entry, actual: NextConfigImportStore.Entry) {
        assertEquals(expected.plan.identities, actual.plan.identities); assertEquals(expected.plan.habits, actual.plan.habits)
        assertEquals(expected.plan.metrics, actual.plan.metrics); assertEquals(expected.sources, actual.sources)
        assertEquals(expected.network, actual.network); assertEquals(expected.committed, actual.committed)
    }
    private suspend fun network(id: String) = db.withTransaction { NextConfigImportStore(db).network(id) }
    private fun state(id: String) = db.openHelper.readableDatabase.query(
        "SELECT state FROM next_config_imports WHERE importId=?", arrayOf(id)).use { check(it.moveToFirst()); it.getString(0) }
    private fun response(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        val op = wireOperation(input)
        val id = op.getValue("operation_id").jsonPrimitive.content
        responses[id]?.let { return it }
        val uuid = op.getValue("entity_uuid").jsonPrimitive.content
        val type = op.getValue("entity_type").jsonPrimitive.content
        val old = canonical[uuid]
        val revision = (old?.get("revision")?.jsonPrimitive?.long ?: 0L) + 1
        val result = if (op.getValue("action") == JsonPrimitive("delete")) {
            requireNotNull(old); assertEquals(old.getValue("revision"), op.getValue("base_revision"))
            val body = JsonObject(old + mapOf("revision" to JsonPrimitive(revision),
                "updated_at" to JsonPrimitive(time), "deleted_at" to JsonPrimitive(time)))
            canonical[uuid] = body
            MaterialSocketServer.Reply(buildJsonObject { put("results", JsonArray(listOf(buildJsonObject {
                put("operation_id", id); put("entity_type", type); put("entity_uuid", uuid)
                put("status", "applied"); put("revision", revision); put("entity", body)
            }))) }.toString().toByteArray())
        } else successReply(input, revision) { body ->
            // Model the reason phases matter: no same-name old live object may remain remotely.
            if (type in setOf("plan_node", "metric")) {
                val field = if (type == "plan_node") "title" else "name"
                val name = body.getValue(field)
                assertFalse("Old live $type has the same $field", canonical.any { (other, value) ->
                    other != uuid && value[field] != null && value["deleted_at"] == JsonNull && value[field] == name
                })
            }
            canonical[uuid] = body; body
        }
        val meta = roundMetadata
        val wrapped = if (meta == null) result else {
            assertEquals("/api/v2/sync/rounds/push", input.path)
            val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
            val head = request.contexts.single().head
            var updated = meta
            if (head != null && updated.checkpoints.none { it.head.activityUuid == head.activityUuid }) {
                val initial = ChallengeRoundRecord(head, null, null, null)
                updated = updated.copy(checkpoints = updated.checkpoints + ChallengeCheckpoint(head, listOf(initial)))
            }
            roundMetadata = updated
            val ordinary = json.decodeFromString<NextSyncPushResponse>(result.bytes.toString(Charsets.UTF_8))
            MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(ordinary.results, 1,
                updated.checkpoints, updated.births)).toByteArray())
        }
        responses[id] = wrapped; return wrapped
    }
    private suspend fun drain(http: NextSyncHttp) {
        while (db.syncOutboxDao().getAll().isNotEmpty()) {
            val row = db.syncOutboxDao().getAll().first()
            assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        }
    }
    private suspend fun graph(): Pair<ValidatedConfigBundle, NextSyncHttp> {
        register(); db.clearAllData() // Testbed only; no application reset or production data.
        val source = source()
        val id = service().confirm(service().preview(source)); service().resume(id, source)
        val (http, _) = channel(::response); drain(http)
        db.nextSyncStateDao().insert(NextSyncStateEntity(id(1), id(2), id(3), id(4), 1, 0,
            "a".repeat(64), "b".repeat(64))) // Explicit local cursor fixture, not backend bootstrap.
        return source to http
    }
    private suspend fun confirm(source: ValidatedConfigBundle) = service().confirmReplacement(service().previewReplacement(source))
    private suspend fun blocked(http: NextSyncHttp, step: ConfigNetworkStep) {
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendOperation(access(), step.operationId) } as NextRequestException).reason)
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, step.operationId))
    }

    @Test fun originalDeletionIdsPersistBeforeFilesAndColdReplacementCommitsExactNewGraphWithNoStyleApply() = runBlocking<Unit> {
        val (source, http) = graph(); val old = db.habitDao().getAllHabitsOnce()
        val oldMetrics = db.metricDao().getAllMetricsOnce(); val selected = iconMetadata.selection(icons.capture())
        val id = confirm(source); val saved = entry(id, source)
        assertFalse(saved.committed); assertNotNull(saved.network); assertEquals(old, db.habitDao().getAllHabitsOnce())
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); storage.reopen()
        assertEquals(id, service().pendingImportId()); same(saved, entry(id, source))
        val receipt = service().resume(id, ValidatedConfigBundle.parse(source.exportBytes()))
        assertEquals(saved.plan.habits.size, receipt.nodes); assertEquals("local_committed", state(id))
        val current = entry(id, source); val steps = saved.network!!.steps
        assertEquals(steps.map { it.operationId }.toSet(), current.sources.keys)
        assertEquals(steps.map { it.operationId }.toSet(), db.syncOutboxDao().getAll().map { it.operationId }.toSet())
        assertEquals(saved.plan.habits.map { it.uuid }.toSet(), db.habitDao().getVisibleHabitsOnce().map { it.uuid }.toSet())
        assertEquals(old.size + saved.plan.habits.size, db.habitDao().getAllHabitsOnce().size)
        assertTrue(oldMetrics.all { db.metricDao().getMetricByUuid(it.uuid) == null })
        val plans = saved.plan.habits.associateBy { it.uuid }; val metrics = saved.plan.metrics.associateBy { it.uuid }
        for (link in db.habitMetricLinkDao().getAllLinksOnce()) {
            assertEquals(plans.getValue(link.habitUuid).uuid, db.habitDao().getHabitById(link.habitId)!!.uuid)
            assertEquals(metrics.getValue(link.metricUuid).uuid, db.metricDao().getMetricById(link.metricId)!!.uuid)
        }
        assertEquals(0, count("completions")); assertEquals(0, count("timelogs")); assertEquals(0, count("metric_logs"))
        assertEquals(selected, iconMetadata.selection(icons.capture()))
        val once = db.habitDao().getVisibleHabitsOnce().single { it.completionPolicy == "one_and_done" }
        assertFalse(onceRepository().read(once.id).completed)
        storage.reopen(); assertEquals(receipt, service().resume(id, source))
        same(current, entry(id, source)); assertEquals("CONFIG_IMPORT_ALREADY_COMMITTED",
            rejected { service().cancelPrepared(id, source) }.message)
        assertEquals("CONFIG_IMPORT_SYNC_PENDING", rejected { confirm(source) }.message)
        drain(http); assertEquals("operations_accepted", state(id))
        assertEquals(saved.plan.habits.size, db.habitDao().getAllHabitsOnce().size)
        assertEquals(selected, iconMetadata.selection(icons.capture())); assertEquals(receipt, service().resume(id, source))
    }

    @Test fun directSenderCannotJumpAnyDeletionOrCreationPhaseAndLateExactAckReplayIsIdempotent() = runBlocking<Unit> {
        val (source, http) = graph(); val id = confirm(source); service().resume(id, source)
        val steps = network(id).plan.steps
        assertEquals((0..5).toList(), steps.map { it.phase }.distinct())
        for (phase in 0..5) {
            steps.filter { it.phase > phase }.forEach { blocked(http, it) }
            for (step in steps.filter { it.phase == phase }) {
                val delivery = requireNotNull(sender(http).sendOperation(access(), step.operationId))
                assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, step.operationId))
                assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
                storage.reopen()
                assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
                assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), step.operationId))
            }
        }
        val accepted = network(id); assertEquals("operations_accepted", state(id))
        assertEquals(steps.map { it.operationId }.toSet(), accepted.accepted!!.keys)
        val another = confirm(source); assertNotEquals(id, another)
        service().resume(another, source)
        val newer = db.syncOutboxDao().getAll()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), steps.last().operationId))
        assertEquals(newer, db.syncOutboxDao().getAll()); assertEquals("local_committed", state(another))
    }

    @Test fun lostDeleteResponseRetainsExactWireAndOldDataUntilRealAckThenColdRetryContinuesSameGroup() = runBlocking<Unit> {
        val (source, _) = graph(); val id = confirm(source); service().resume(id, source)
        val steps = network(id).plan.steps; val link = steps.first()
        val lose = AtomicBoolean(true)
        val (http, server) = channel { input ->
            val result = response(input)
            if (wireOrIdentity(input) == link.operationId && lose.compareAndSet(true, false)) null else result
        }
        rejected { sender(http).sendOperation(access(), link.operationId) }
        val wire = transmission(NEXT_OPERATION, link.operationId).wireBytes.copyOf()
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, link.operationId))
        blocked(http, steps.first { it.phase == 3 }); assertEquals("local_committed", state(id))
        storage.reopen(); service().resume(id, source)
        drain(http); assertEquals("operations_accepted", state(id))
        assertArrayEquals(wire, transmission(NEXT_OPERATION, link.operationId).wireBytes)
        assertEquals(2, server.requests.count { wireOrIdentity(it) == link.operationId })
    }

    @Test fun absenceShadowAndDeadLetterDoNotStandInForTheSavedOriginalDeletionAck() = runBlocking<Unit> {
        val (source, http) = graph(); val id = confirm(source); service().resume(id, source)
        val steps = network(id).plan.steps; val first = steps.first(); val row = db.syncOutboxDao().getByOperationId(first.operationId)!!
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET deadLetteredAt=123,errorCode='TEST' WHERE id=?", arrayOf<Any>(row.id))
        blocked(http, steps.first { it.phase == 3 })
        db.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox WHERE id=?", arrayOf<Any>(row.id))
        assertNotNull(db.syncOutboxDao().getState(first.entityType, first.entityUuid))
        blocked(http, steps.first { it.phase == 3 }); assertEquals("local_committed", state(id))
        assertNull(network(id).accepted)
    }

    @Test fun changedPreviewOrNewFactAfterConfirmationNeverDeletesLaterWorkOrReallocatesIds() = runBlocking<Unit> {
        val (source, _) = graph(); val preview = service().previewReplacement(source)
        val id = service().confirmReplacement(preview); val saved = entry(id, source)
        val row = db.habitDao().getAllHabitsOnce().first { it.habitType == HabitType.CHECK_IN && it.completionPolicy == "recurring" }
        creatingHabits().logCompletion(app, row.id)
        val work = db.syncOutboxDao().getAll(); val before = db.habitDao().getAllHabitsOnce()
        assertEquals("CONFIG_REPLACEMENT_PREVIEW_CHANGED", rejected { service().resume(id, source) }.message)
        assertEquals(work, db.syncOutboxDao().getAll()); assertEquals(before, db.habitDao().getAllHabitsOnce())
        same(saved, entry(id, source)); assertEquals(1, count("completions"))
    }

    @Test fun businessAndLateOriginFaultsRollBackAllReplacementChangesAndUseTheSavedIdsOnColdRetry() = runBlocking<Unit> {
        val (source, http) = graph(); val before = db.habitDao().getAllHabitsOnce(); val oldMetrics = db.metricDao().getAllMetricsOnce()
        // Trigger definitions do not enter the value fingerprint; no business/table row is changed.
        val id = confirm(source); val saved = entry(id, source)
        for ((name, statement) in listOf("replacement_link_fault" to "BEFORE INSERT ON habit_metric_links",
                "replacement_origin_fault" to "BEFORE INSERT ON next_request_origins")) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER $name $statement BEGIN SELECT RAISE(ABORT,'replacement fault'); END")
            rejected { service().resume(id, source) }; storage.reopen()
            assertEquals(before, db.habitDao().getAllHabitsOnce()); assertEquals(oldMetrics, db.metricDao().getAllMetricsOnce())
            same(saved, entry(id, source)); assertEquals(0, count("sync_outbox")); assertEquals("prepared", state(id))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $name")
        }
        storage.reopen(); service().resume(id, source); drain(http)
        assertEquals(saved.network, entry(id, source).network); assertEquals("operations_accepted", state(id))
    }

    @Test fun materialFailureAndExplicitCancelKeepOldBusinessAndAlreadyOwnedAssetsWithoutRemoteUndo() = runBlocking<Unit> {
        val (oldSource, _) = graph(); val before = db.habitDao().getAllHabitsOnce()
        val bytes = String(ConfigFileFixture.svg).replace("#ff0000", "#0000ff").toByteArray()
        val pack = oldSource.manifest.iconPack!!
        val changed = pack.copy(assets = pack.assets.mapIndexed { i, asset -> if (i == 0) asset.copy(
            light = asset.light.copy(sha256 = ConfigFileFixture.hash(bytes), byteLength = bytes.size)) else asset })
        val source = ConfigBundleOutput.create(oldSource.manifest.copy(iconPack = changed)) {
            if (it.sha256 == ConfigFileFixture.hash(bytes)) bytes else ConfigFileFixture.content(it)
        }
        val id = confirm(source); val plan = entry(id, source).plan
        iconDatabase.openHelper.writableDatabase.execSQL("CREATE TRIGGER replacement_file_fault BEFORE INSERT ON icon_blob_ready " +
            "BEGIN SELECT RAISE(ABORT,'replacement file fault'); END")
        rejected { service().resume(id, source) }
        assertEquals(before, db.habitDao().getAllHabitsOnce()); assertEquals(0, count("sync_outbox"))
        iconDatabase.openHelper.writableDatabase.execSQL("DROP TRIGGER replacement_file_fault")
        service().cancelPrepared(id, source)
        assertEquals(plan.iconPack, iconMetadata.pack(icons.capture(), plan.iconPack!!.packId, 1))
        assertEquals(before, db.habitDao().getAllHabitsOnce()); assertNull(service().pendingImportId())
    }

    @Test fun tamperedNetworkChunksOrChangedSourceCannotBeAdoptedDuringPreparedColdRecovery() = runBlocking<Unit> {
        val (source, _) = graph(); val id = confirm(source); val before = db.habitDao().getAllHabitsOnce()
        val changed = ConfigBundleOutput.create(source.manifest.copy(nodes = source.manifest.nodes.mapIndexed { i, row ->
            if (i == 0) row.copy(name = "Different frozen input") else row }), ConfigFileFixture::content)
        assertEquals("CONFIG_IMPORT_SOURCE_CHANGED", rejected { service().resume(id, changed) }.message)
        db.openHelper.writableDatabase.execSQL("UPDATE next_config_import_payloads SET bytes=X'7B7D' " +
            "WHERE importId=? AND payloadKind='network'", arrayOf(id))
        storage.reopen(); assertEquals("CONFIG_IMPORT_JOURNAL_CHANGED", rejected { service().resume(id, source) }.message)
        assertEquals(before, db.habitDao().getAllHabitsOnce()); assertEquals(0, count("sync_outbox"))
    }

    @Test fun staleAuthorizationAndDifferentDeviceCannotConfirmRecoverOrTransmitAReplacement() = runBlocking<Unit> {
        val (source, http) = graph(); val preview = service().previewReplacement(source); val id = confirm(source)
        val saved = entry(id, source)
        tokens.saveLoginSession("synthetic-again", "synthetic-refresh", "member", id(1), false); register()
        assertEquals("CONFIG_IMPORT_SESSION_CHANGED", rejected { service().confirmReplacement(preview) }.message)
        register(permissions = setOf("sync.read"))
        assertEquals("CONFIG_IMPORT_ACCESS_DENIED", rejected { service().resume(id, source) }.message)
        register(device = id(900)); assertEquals("CONFIG_IMPORT_REPLICA_CHANGED", rejected { service().resume(id, source) }.message)
        register(); service().resume(id, source)
        register(device = id(900)); rejected { sender(http).sendOperation(access(), saved.network!!.steps.first().operationId) }
        register(); drain(http); assertEquals("operations_accepted", state(id))
    }

    @Test fun previousAckDamageAfterHttpOrInsideNewAckTransactionCannotAdvancePhases() = runBlocking<Unit> {
        val (source, http) = graph(); val id = confirm(source); service().resume(id, source)
        val steps = network(id).plan.steps; val link = steps.first()
        sender(http).sendAndAcceptOperation(access(), link.operationId)
        val next = steps.first { it.phase == 1 }; val delivery = requireNotNull(sender(http).sendOperation(access(), next.operationId))
        val sql = db.openHelper.writableDatabase
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, link.operationId))
        sql.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?", arrayOf("f".repeat(64), link.operationId))
        rejected { sender(http).acceptOperation(delivery) }
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, next.operationId)); assertEquals("local_committed", state(id))
        sql.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?", arrayOf(receipt.resultHash, link.operationId))
        sql.execSQL("CREATE TRIGGER replacement_previous_ack AFTER INSERT ON next_acceptances WHEN NEW.requestId='${next.operationId}' " +
            "BEGIN UPDATE next_acceptances SET resultHash='${"f".repeat(64)}' WHERE requestId='${link.operationId}'; END")
        rejected { sender(http).acceptOperation(delivery) }; storage.reopen()
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, link.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, next.operationId))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER replacement_previous_ack")
        sender(http).acceptOperation(delivery); drain(http); assertEquals("operations_accepted", state(id))
    }

    @Test fun metricDeleteReceiptWithMatchingHashButInvalidTombstoneCannotReleaseTheNextPhase() = runBlocking<Unit> {
        val (source, http) = graph(); val id = confirm(source); service().resume(id, source)
        val steps = network(id).plan.steps
        for (step in steps.filter { it.phase <= 1 }) sender(http).sendAndAcceptOperation(access(), step.operationId)
        val metric = steps.single { it.entityType == "metric" && it.action == "delete" }
        val original = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, metric.operationId))
        val result = json.parseToJsonElement(original.resultJson).jsonObject
        val damaged = JsonObject(result + ("entity" to JsonObject(result.getValue("entity").jsonObject +
            ("deleted_at" to JsonNull)))).toString()
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf(damaged, nextRequestHash(damaged.toByteArray(Charsets.UTF_8)), metric.operationId))
        val next = steps.first { it.phase == 2 }
        rejected { sender(http).sendOperation(access(), next.operationId) }
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, next.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, next.operationId))
        assertEquals("local_committed", state(id))
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf(original.resultJson, original.resultHash, metric.operationId))
        storage.reopen(); drain(http); assertEquals("operations_accepted", state(id))
    }

    @Test fun finalAckAndCompletionReceiptRollBackTogetherOnTerminalJournalFailureThenColdReplayFinishes() = runBlocking<Unit> {
        val (source, http) = graph(); val id = confirm(source); service().resume(id, source)
        val steps = network(id).plan.steps
        for (step in steps.dropLast(1)) sender(http).sendAndAcceptOperation(access(), step.operationId)
        val last = steps.last(); val delivery = requireNotNull(sender(http).sendOperation(access(), last.operationId))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER replacement_finish_fault BEFORE UPDATE ON next_config_imports " +
            "WHEN NEW.state='operations_accepted' BEGIN SELECT RAISE(ABORT,'completion receipt fault'); END")
        rejected { sender(http).acceptOperation(delivery) }; storage.reopen()
        assertEquals("local_committed", state(id)); assertNull(network(id).accepted)
        assertNotNull(db.syncOutboxDao().getByOperationId(last.operationId)); assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, last.operationId))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER replacement_finish_fault")
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
        assertEquals("operations_accepted", state(id)); assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
    }

    @Test fun realDeferredFinalCommitFailurePreservesOldGraphAndPreparedMappingForExactColdRetry() = runBlocking<Unit> {
        val (source, http) = graph(); val before = db.habitDao().getAllHabitsOnce(); val metrics = db.metricDao().getAllMetricsOnce()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE replacement_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE replacement_deferred(id INTEGER, FOREIGN KEY(id) REFERENCES replacement_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        val id = confirm(source); val saved = entry(id, source)
        sql.execSQL("CREATE TRIGGER replacement_final_commit AFTER UPDATE ON next_config_imports WHEN NEW.state='local_committed' " +
            "BEGIN INSERT INTO replacement_deferred VALUES(99); END")
        assertTrue(rejected { service().resume(id, source) } is android.database.sqlite.SQLiteConstraintException)
        // Close the actual fault writer BEFORE any persistence assertion, as required by Room's
        // real deferred-COMMIT fault contract. No WAL/old-writer read pretends to prove rollback.
        storage.reopen(); assertEquals(before, db.habitDao().getAllHabitsOnce()); assertEquals(metrics, db.metricDao().getAllMetricsOnce())
        same(saved, entry(id, source)); assertEquals(0, count("replacement_deferred")); assertEquals(0, count("sync_outbox"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER replacement_final_commit")
        // The empty fault tables were part of the original snapshot; preserve them on exact retry.
        storage.reopen(); service().resume(id, source); drain(http); assertEquals("operations_accepted", state(id))
    }

    @Test fun priorReceiptChangedInsideActualHttpResponseCannotReturnAUsableDelivery() = runBlocking<Unit> {
        val (source, originalHttp) = graph(); val id = confirm(source); service().resume(id, source)
        val steps = network(id).plan.steps; val first = steps.first()
        sender(originalHttp).sendAndAcceptOperation(access(), first.operationId)
        val old = db.nextRequestDao().acceptance(NEXT_OPERATION, first.operationId)!!
        val next = steps.first { it.phase == 1 }; val change = AtomicBoolean(true)
        val (http, _) = channel { input ->
            val result = response(input)
            if (wireOrIdentity(input) == next.operationId && change.compareAndSet(true, false))
                db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?",
                    arrayOf("f".repeat(64), first.operationId))
            result
        }
        rejected { sender(http).sendOperation(access(), next.operationId) }
        assertNotNull(db.syncOutboxDao().getByOperationId(next.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, next.operationId)); assertEquals("local_committed", state(id))
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?", arrayOf(old.resultHash, first.operationId))
        storage.reopen(); drain(http); assertEquals("operations_accepted", state(id))
    }

    @Test fun realRuntimeDefersNewCreatesDrainsDeletesThenPullsWithoutClaimingMaterialTransferSuccess() = runBlocking<Unit> {
        val (source, _) = graph(); val id = confirm(source); service().resume(id, source)
        val steps = network(id).plan.steps
        val (http, server) = channel { input -> when {
            input.path == "/api/v2/sync/changes" -> MaterialSocketServer.Reply(json.encodeToString(
                NextSyncPullResponse(emptyList(), 0, false, time)).toByteArray())
            else -> response(input)
        } }
        NextSyncRuntime(db, tokens, sessions, http, preferences).sync()
        assertEquals("operations_accepted", state(id)); assertEquals(0, count("sync_outbox"))
        val sent = server.requests.filter { it.path.endsWith("/push") }.mapNotNull(::wireOrIdentity)
        assertEquals(steps.map { it.operationId }.toSet(), sent.toSet())
        val phases = sent.map { id -> steps.single { it.operationId == id }.phase }
        assertEquals(phases.sorted(), phases)
        assertTrue(server.requests.any { it.path == "/api/v2/sync/changes" })
        // This is business operation acceptance only; material still has its separate real queue.
        assertTrue(iconMetadata.library(icons.capture()).packs.isNotEmpty())
        storage.reopen(); assertEquals(steps.map { it.operationId }.toSet(), network(id).accepted!!.keys)
    }

    @Test fun emptyIncomingConfigurationStillDeletesOldGraphButEmptyToEmptyCannotLeaveAnUnfinishableGroup() = runBlocking<Unit> {
        val (_, http) = graph(); val source = ConfigBundleOutput.create(ConfigFileFixture.empty()) { error("No blobs") }
        val id = confirm(source); service().resume(id, source)
        assertEquals("local_committed", state(id)); assertTrue(db.habitDao().getVisibleHabitsOnce().isEmpty())
        drain(http); assertEquals("operations_accepted", state(id)); assertEquals(0, count("habits"))
        val next = confirm(source); service().resume(next, source)
        assertEquals("operations_accepted", state(next)); assertTrue(network(next).accepted!!.isEmpty())
        assertNull(service().pendingImportId()); assertEquals(0, count("sync_outbox"))
    }

    @Test fun actualAcceptedRoundProfileCarriesChildDeleteFrontiersAndNewInitialCreationThroughEveryPhase() = runBlocking<Unit> {
        register()
        val goal = habit.copy(id = 0, uuid = id(750), name = "Old challenge goal", habitType = HabitType.GOAL,
            parentHabitId = null, completionPolicy = null, targetValue = 1,
            planMetadata = habit.planMetadata!!.copy(timezone = null))
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().insert(goal)
            db.habitDao().update(habit.copy(parentHabitId = goal.uuid)); db.habitDao().update(timerHabit.copy(parentHabitId = goal.uuid))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        roundMetadata = ChallengeMetadata(1, listOf(habit, timerHabit).map {
            val round = ChallengeRoundRecord(initialChallengeRoundHead(it.uuid), null, null, null)
            ChallengeCheckpoint(round.head, listOf(round))
        }, emptyList())
        val (http, _) = channel(::response)
        val changes = db.habitDao().getAllHabitsOnce().map { row ->
            SyncV2Change(0, "plan_node", row.uuid, "upsert", 1, JsonObject(NextStructureMapper.writePlan(row) + mapOf(
                "public_id" to JsonPrimitive(row.uuid), "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time),
                "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        } + SyncV2Change(0, "metric", metric.uuid, "upsert", 1, JsonObject(NextStructureMapper.writeMetric(metric) + mapOf(
            "public_id" to JsonPrimitive(metric.uuid), "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time),
            "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        changes.forEach { canonical[it.entityUuid] = it.payload }
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        val merge = NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
        merge.bootstrap(access(), null, RoundSyncBootstrapResponse(changes, 20, time, emptyList(), 1,
            roundMetadata!!.checkpoints, roundMetadata!!.births))
        val source = source(); val id = confirm(source); service().resume(id, source)
        val steps = network(id).plan.steps
        val goalDelete = steps.single { it.entityUuid == goal.uuid }
        val original = db.nextRequestDao().origin(NEXT_OPERATION, goalDelete.operationId)!!
        assertEquals(setOf(habit.uuid, timerHabit.uuid), roundOperationIntent(original.intentJson)!!.goalChildFrontier!!.map { it.childUuid }.toSet())
        blocked(http, goalDelete); drain(http)
        assertEquals("operations_accepted", state(id)); assertEquals(20L, merge.state(access(), true)!!.cursor)
        val plan = entry(id, source).plan
        for (row in plan.habits.filter { it.completionPolicy == "recurring" }) {
            val origin = db.nextRequestDao().origin(NEXT_OPERATION, plan.creationOperationIds.getValue(row.uuid))!!
            assertTrue(roundOperationIntent(origin.intentJson)!!.initialCreation)
        }
    }
}
