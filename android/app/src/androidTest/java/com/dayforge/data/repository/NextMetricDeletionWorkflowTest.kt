package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual repositories/Room/HTTP, retained IDs and ACK barriers; no formal protocol activation. */
@RunWith(AndroidJUnit4::class)
class NextMetricDeletionWorkflowTest : NextObjectEditorFixture() {
    private val canonical = mutableMapOf<String, JsonObject>()
    private val replies = mutableMapOf<String, MaterialSocketServer.Reply>()

    private fun response(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        val op = wireOperation(input)
        val uuid = op.getValue("entity_uuid").jsonPrimitive.content
        val operation = op.getValue("operation_id").jsonPrimitive.content
        replies[operation]?.let { return it }
        val old = canonical[uuid]
        val revision = (old?.getValue("revision")?.jsonPrimitive?.long ?: 0L) + 1
        val value = if (op["action"] == JsonPrimitive("delete")) {
            assertNotNull(old); assertEquals(old!!.getValue("revision"), op.getValue("base_revision"))
            val body = JsonObject(old + mapOf("revision" to JsonPrimitive(revision),
                "updated_at" to JsonPrimitive("2026-10-07T00:00:00Z"), "deleted_at" to JsonPrimitive("2026-10-07T00:00:00Z")))
            canonical[uuid] = body
            MaterialSocketServer.Reply(buildJsonObject { put("results", JsonArray(listOf(buildJsonObject {
                put("operation_id", operation); put("entity_type", op.getValue("entity_type")); put("entity_uuid", uuid)
                put("status", "applied"); put("revision", revision); put("entity", body)
            }))) }.toString().toByteArray())
        } else successReply(input, revision) { body -> canonical[uuid] = body; body }
        replies[operation] = value
        return value
    }

    private suspend fun accept(http: NextSyncHttp, row: SyncOutboxEntity) {
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
    }

    private suspend fun publish(http: NextSyncHttp) {
        val row = editMetric("Weight", "Published baseline")
        accept(http, row)
        metric = db.metricDao().getMetricById(metric.id)!!
    }

    private suspend fun blocked(http: NextSyncHttp, row: SyncOutboxEntity) {
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendOperation(access(), row.operationId) } as NextRequestException).reason)
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, row.operationId))
    }

    private fun reopen() {
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
    }

    @Test fun stagedDeleteKeepsOriginalRowsFactsAndIdsButHidesEveryPublicSelectionAndRejectsStaleWrites() = runBlocking<Unit> {
        val repo = creatingMetrics()
        repo.recordValue(metric.id, 12.25, "retained")
        repo.linkHabits(metric, setOf(habit.id))
        val links = db.habitMetricLinkDao().getAllLinksForMetric(metric.id)
        val logs = db.metricLogDao().getAllLogsForMetric(metric.id)
        val ticket = repo.getMetricForEditing(metric.id)
        repo.deleteMetric(metric, ticket.authority)
        val queue = db.syncOutboxDao().getAll()
        repo.deleteMetric(metric, ticket.authority)
        assertEquals(queue, db.syncOutboxDao().getAll())
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(logs, db.metricLogDao().getAllLogsForMetric(metric.id))
        assertEquals(links, db.habitMetricLinkDao().getAllLinksForMetric(metric.id))
        assertNull(db.metricDao().getVisibleMetricById(metric.id)); assertNull(db.metricDao().observeMetricById(metric.id).first())
        assertNull(repo.getMetricForEditing(metric.id).value); assertNull(db.metricDao().getMetricByName(metric.name))
        assertTrue(repo.observeActiveMetrics().first().isEmpty()); assertTrue(db.metricDao().getAllMetrics().first().isEmpty())
        assertTrue(db.metricDao().getVisibleMetricsOnce().isEmpty())
        assertTrue(db.habitMetricLinkDao().getVisibleLinksForHabit(habit.id).isEmpty())
        assertTrue(db.habitMetricLinkDao().getLinksByHabit(habit.id).first().isEmpty())
        assertTrue(db.habitMetricLinkDao().getLinksByMetric(metric.id).first().isEmpty())
        assertTrue(db.habitMetricLinkDao().getAllActiveLinks().isEmpty())
        assertTrue(db.habitMetricLinkDao().observeLinkedMetricSnapshots().first().isEmpty())
        assertTrue(withContext(Dispatchers.IO) { db.habitMetricLinkDao().getLinksByHabitSync(habit.id) }.isEmpty())
        rejected { repo.updateMetric(metric.copy(description = "stale"), ticket.authority) }
        rejected { repo.recordValue(metric.id, 1.0, "stale", authority = ticket.authority) }
        rejected { repo.updateAggregationType(metric.id, "sum") }
        rejected { repo.linkHabits(metric, setOf(timerHabit.id), ticket.authority) }
        rejected { producer().write(local()) { db.metricDao().update(metric.copy(unit = "g")) } }
        assertEquals(queue, db.syncOutboxDao().getAll()); assertEquals(metric, db.metricDao().getMetricById(metric.id))
        assertFalse(queue.any { it.recordType == "metric_log" && it.action == "delete" })
        // A visible name is released, but identity is never reused and old pending work survives.
        val freshId = repo.createMetric(metric.copy(id = 0, uuid = id(880)), creationAuthority = creator.capture())
        assertNotEquals(metric.id, freshId); assertEquals(id(880), db.metricDao().getMetricByName(metric.name)!!.uuid)
        reopen(); assertEquals(logs, db.metricLogDao().getAllLogsForMetric(metric.id))
        assertNull(db.metricDao().getVisibleMetricById(metric.id)); assertEquals(links, db.habitMetricLinkDao().getAllLinksForMetric(metric.id))
    }

    @Test fun pendingMetricEditAndObservationAndLinkHaveRealResultsBeforeDeleteAndItsBaseIsRebased() = runBlocking<Unit> {
        register(); val (http, server) = channel(::response); publish(http)
        val repo = creatingMetrics()
        val ticket = repo.getMetricForEditing(metric.id)
        repo.updateMetric(metric.copy(description = "pending"), ticket.authority)
        metric = db.metricDao().getMetricById(metric.id)!!
        repo.recordValue(metric.id, 3.0, "pending"); repo.linkHabits(metric, setOf(habit.id))
        val before = db.syncOutboxDao().getAll()
        val logs = db.metricLogDao().getAllLogsForMetric(metric.id)
        repo.deleteMetric(metric, repo.getMetricForEditing(metric.id).authority)
        val deletes = db.syncOutboxDao().getAll().filter { it.action == "delete" }
        val root = deletes.single { it.recordType == "metric" }
        val original = originalIntent(root)
        for (row in deletes) blocked(http, row)
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        for (row in before) accept(http, row)
        val acceptedLogs = db.metricLogDao().getAllLogsForMetric(metric.id)
        assertEquals(logs.map { it.copy(createdAt = 0, updatedAt = 0) }, acceptedLogs.map { it.copy(createdAt = 0, updatedAt = 0) })
        blocked(http, root)
        accept(http, deletes.single { it.recordType == "link" })
        assertEquals(acceptedLogs, db.metricLogDao().getAllLogsForMetric(metric.id))
        accept(http, root)
        val replacement = db.nextStructuralCausalDao().supersession(root.operationId)!!
        assertNotEquals(root.operationId, replacement.replacementId)
        assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, root.operationId))
        assertEquals(JsonPrimitive(2), wireOperation(server.requests.last()).getValue("base_revision"))
        assertNull(db.metricDao().getMetricById(metric.id)); assertEquals(0, db.metricLogDao().countAll())
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertNotNull(db.habitDao().getHabitById(habit.id))
        reopen(); assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), root.operationId))
    }

    @Test fun standaloneUnlinkRemainsHiddenAndRetainedUntilExactAckWithoutDeletingMetricHistory() = runBlocking<Unit> {
        register(); val (http, _) = channel(::response)
        creatingMetrics().linkHabits(metric, setOf(habit.id))
        val create = db.syncOutboxDao().getAll().single()
        val link = db.habitMetricLinkDao().getAllLinksForMetric(metric.id).single()
        creatingMetrics().unlinkHabit(link.id, link.uuid)
        val deletion = db.syncOutboxDao().getAll().single { it.action == "delete" }
        assertEquals(link, db.habitMetricLinkDao().getLinkByUuid(link.uuid))
        assertTrue(db.habitMetricLinkDao().getVisibleLinksForHabit(habit.id).isEmpty())
        val queue = db.syncOutboxDao().getAll()
        rejected { creatingMetrics().linkHabits(metric, setOf(habit.id)) }
        rejected { producer().write(local()) { db.habitMetricLinkDao().update(link.copy(promptOnComplete = !link.promptOnComplete)) } }
        assertEquals(queue, db.syncOutboxDao().getAll()); blocked(http, deletion)
        accept(http, create); accept(http, deletion)
        assertNull(db.habitMetricLinkDao().getLinkByUuid(link.uuid)); assertEquals(metric, db.metricDao().getMetricById(metric.id))
        reopen(); assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), deletion.operationId))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        creatingMetrics().linkHabits(metric, setOf(habit.id))
        val fresh = db.habitMetricLinkDao().getVisibleLinksForHabit(habit.id).single()
        assertNotEquals(link.uuid, fresh.uuid); assertNotEquals(link.id, fresh.id)
        assertTrue(db.syncOutboxDao().getState("activity_metric_link", link.uuid)!!.deleted)
        accept(http, db.syncOutboxDao().getAll().single())
        assertEquals(fresh.uuid, db.habitMetricLinkDao().getVisibleLinksForHabit(habit.id).single().uuid)
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertEquals(metric, db.metricDao().getMetricById(metric.id))
    }

    @Test fun planAndMetricDeletionReuseOneRetainedLinkIdentityAndBothWaitItsRealAck() = runBlocking<Unit> {
        register(); val (http, _) = channel(::response); publish(http)
        producer().write(local()) { habits().updateHabit(habit.copy(description = "Published plan")) }
        accept(http, db.syncOutboxDao().getAll().single())
        habit = db.habitDao().getHabitById(habit.id)!!
        creatingMetrics().linkHabits(metric, setOf(habit.id))
        accept(http, db.syncOutboxDao().getAll().single())
        creatingHabits().deleteHabit(habit, app)
        val linkDelete = db.syncOutboxDao().getAll().single { it.recordType == "link" }
        creatingMetrics().deleteMetric(metric)
        val queue = db.syncOutboxDao().getAll()
        assertEquals(linkDelete, queue.single { it.recordType == "link" })
        for (row in queue.filter { it.recordType != "link" }) blocked(http, row)
        accept(http, linkDelete)
        for (row in queue.filter { it.recordType != "link" }) accept(http, row)
        assertNull(db.metricDao().getMetricById(metric.id)); assertNull(db.habitDao().getHabitById(habit.id))
        assertNotNull(db.habitDao().getHabitById(timerHabit.id)); assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }

    @Test fun existingPromptKeepsItsInputButExplicitMetricDeleteRefusesSubmissionAndWaitsDismissal() = runBlocking<Unit> {
        val repo = onceHabits(onceRepository())
        val taskId = repo.createHabit("Draft task", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
            failMode = FailMode.LOOSE, appearance = ObjectAppearance(IconReference.Role("task.default"), "#123456", "theme"),
            completionPolicy = "one_and_done", selectedMetricIds = setOf(metric.id), creationAuthority = creator.capture())
        repo.logCompletion(app, taskId)
        val once = onceRepository()
        val prompt = once.saveDraft(once.prompt(taskId)!!, mapOf(metric.id to ("12.250" to "keep input")))
        val fresh = repo.createHabit("No hidden prompt", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
            failMode = FailMode.LOOSE, appearance = ObjectAppearance(IconReference.Role("task.default"), "#123456", "theme"),
            completionPolicy = "one_and_done", selectedMetricIds = setOf(metric.id), creationAuthority = creator.capture())
        assertEquals(metric.uuid, db.habitMetricLinkDao().getAllLinksForHabit(fresh).single().metricUuid)
        creatingMetrics().deleteMetric(metric)
        val queue = db.syncOutboxDao().getAll()
        assertEquals(CompletionMetricPromptException.Reason.TARGET_MISSING,
            (rejected { once.submit(prompt) } as CompletionMetricPromptException).reason)
        assertEquals(prompt.snapshot.row, db.completionFollowUpDao().prompt(prompt.eventUuid))
        assertEquals(queue, db.syncOutboxDao().getAll()); assertEquals(0, db.metricLogDao().countAll())
        once.dismiss(prompt); assertEquals("dismissed", db.completionFollowUpDao().prompt(prompt.eventUuid)!!.state)
        // A later completed task must not generate a prompt for the retained, hidden metric.
        repo.logCompletion(app, fresh); assertNull(once.prompt(fresh))
        assertEquals(1, db.habitMetricLinkDao().getAllLinksForHabit(fresh).size)
        assertTrue(db.habitMetricLinkDao().getVisibleLinksForHabit(fresh).isEmpty())
    }

    @Test fun lateDeleteOriginFailureAndCancellationRollbackRowsQueuesAndMarkersAndReadOnlyTicketFails() = runBlocking<Unit> {
        creatingMetrics().linkHabits(metric, setOf(habit.id))
        val queue = db.syncOutboxDao().getAll(); val links = db.habitMetricLinkDao().getAllLinksForMetric(metric.id)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_metric_delete_origin BEFORE INSERT ON next_request_origins
            WHEN (SELECT recordType FROM sync_outbox WHERE id=NEW.queueId)='metric' AND
            (SELECT action FROM sync_outbox WHERE id=NEW.queueId)='delete'
            BEGIN SELECT RAISE(ABORT,'metric delete origin unavailable'); END""")
        rejected { creatingMetrics().deleteMetric(metric) }
        reopen(); assertEquals(metric, db.metricDao().getVisibleMetricById(metric.id))
        assertEquals(links, db.habitMetricLinkDao().getVisibleLinksForHabit(habit.id)); assertEquals(queue, db.syncOutboxDao().getAll())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_metric_delete_origin")
        coroutineScope { launch {
            producer().write(local()) {
                NextMetricDeletionStore(db).stageMetric(metric)
                cancel(); currentCoroutineContext().ensureActive()
            }
        }.join() }
        assertEquals(queue, db.syncOutboxDao().getAll()); assertEquals(metric, db.metricDao().getVisibleMetricById(metric.id))
        register(permissions = setOf("sync.read"))
        rejected { creatingMetrics().deleteMetric(metric) }
        assertEquals(queue, db.syncOutboxDao().getAll()); assertEquals(metric, db.metricDao().getVisibleMetricById(metric.id))
    }

    @Test fun lostResponseAndRealDeferredAckCommitFailurePreserveRowAndSourceThenColdExactReplayRemovesIt() = runBlocking<Unit> {
        register(); val lose = AtomicBoolean(false)
        val (http, server) = channel { input ->
            val value = response(input)
            if (!input.path.endsWith("/identity") && wireOperation(input)["action"] == JsonPrimitive("delete") &&
                lose.compareAndSet(true, false)) null else value
        }
        publish(http); creatingMetrics().deleteMetric(metric)
        val deletion = db.syncOutboxDao().getAll().single(); val original = originalIntent(deletion)
        lose.set(true); rejected { sender(http).sendAndAcceptOperation(access(), deletion.operationId) }
        val frozen = transmission(NEXT_OPERATION, deletion.operationId).wireBytes.copyOf()
        reopen(); val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE metric_ack_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE metric_ack_deferred(id INTEGER, FOREIGN KEY(id) REFERENCES metric_ack_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER metric_ack_commit AFTER INSERT ON next_acceptances WHEN NEW.requestId='${deletion.operationId}' " +
            "BEGIN INSERT INTO metric_ack_deferred VALUES(99); END")
        assertTrue(rejected { sender(http).sendAndAcceptOperation(access(), deletion.operationId) } is android.database.sqlite.SQLiteConstraintException)
        reopen(); assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertNull(db.metricDao().getVisibleMetricById(metric.id))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, deletion.operationId)); assertEquals(deletion, db.syncOutboxDao().getById(deletion.id))
        assertEquals(0, count("metric_ack_deferred")); assertEquals(original, originalIntent(deletion))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER metric_ack_commit")
        accept(http, deletion); assertNull(db.metricDao().getMetricById(metric.id)); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val requests = server.requests.filter { !it.path.endsWith("/identity") && wireOperation(it)["action"] == JsonPrimitive("delete") }
        assertTrue(requests.size >= 2); requests.forEach { assertArrayEquals(frozen, it.body) }
    }

    @Test fun damagedDeleteOriginCannotReopenMetricOrLinkWrites() = runBlocking<Unit> {
        val repo = creatingMetrics()
        repo.linkHabits(metric, setOf(habit.id))
        val link = db.habitMetricLinkDao().getAllLinksForMetric(metric.id).single()
        val ticket = repo.getMetricForEditing(metric.id)
        repo.deleteMetric(metric, ticket.authority)
        val queue = db.syncOutboxDao().getAll()
        for (row in queue.filter { it.action == "delete" }) db.openHelper.writableDatabase.execSQL(
            "UPDATE next_request_origins SET protocol=4 WHERE requestId=?", arrayOf(row.operationId))
        // A broken origin no longer satisfies the display join; it cannot confer write authority.
        assertFalse(db.metricDao().hasPendingNextDeletion(metric.uuid))
        assertFalse(db.habitMetricLinkDao().hasPendingNextDeletion(link.uuid))
        rejected { NextMetricDeletionStore(db).requireWritable(metric.uuid) }
        rejected { NextMetricDeletionStore(db).requireLinkWritable(link) }
        rejected { repo.recordValue(metric.id, 2.0, "corrupt origin", authority = ticket.authority) }
        assertEquals(queue, db.syncOutboxDao().getAll()); assertEquals(0, db.metricLogDao().countAll())
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(link, db.habitMetricLinkDao().getLinkByUuid(link.uuid))
        reopen(); rejected { NextMetricDeletionStore(db).requireWritable(metric.uuid) }
        assertEquals(queue, db.syncOutboxDao().getAll())
    }

    @Test fun internalMarkerRejectsDuplicateOrCoercedLocalIdentityAndNeverLeaksToWire() {
        val row = SyncOutboxEntity(operationId = id(889), recordType = "metric", entityUuid = id(11), wireEntityUuid = id(11), action = "delete",
            referenceUuid = "next-metric-delete:{\"local_id\":1}")
        assertEquals(buildJsonObject {}, NextMetricDeletionStore.payload(row))
        for (body in listOf("{\"local_id\":1,\"local_id\":2}", "{\"local_id\":\"1\"}", "{\"local_id\":0}",
            "{\"local_id\":1.5}", "{\"local_id\":1,\"extra\":true}")) {
            assertTrue(body, runCatching { NextMetricDeletionStore.payload(row.copy(referenceUuid = "next-metric-delete:$body")) }.isFailure)
        }
    }
}
