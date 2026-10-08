package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.HabitDeletionCoordinator
import com.dayforge.ui.screens.edithabit.EditHabitViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real local delete/confirmation, retained facts and original HTTP journals, without activation. */
@RunWith(AndroidJUnit4::class)
class NextPlanDeletionWorkflowTest : NextObjectEditorFixture() {
    private val canonical = mutableMapOf<String, JsonObject>()
    private val replies = mutableMapOf<String, MaterialSocketServer.Reply>()

    private fun response(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        val op = wireOperation(input)
        val operation = op.getValue("operation_id").jsonPrimitive.content
        replies[operation]?.let { return it }
        val uuid = op.getValue("entity_uuid").jsonPrimitive.content
        val type = op.getValue("entity_type").jsonPrimitive.content
        val old = canonical[uuid]
        val revision = (old?.get("revision")?.jsonPrimitive?.long ?: 0L) + 1
        val result = if (op.getValue("action") == JsonPrimitive("delete")) {
            assertNotNull("Delete requires an existing authoritative entity", old)
            assertEquals(old!!.getValue("revision"), op.getValue("base_revision"))
            val body = JsonObject(old + mapOf("revision" to JsonPrimitive(revision),
                "updated_at" to JsonPrimitive(time), "deleted_at" to JsonPrimitive(time)))
            canonical[uuid] = body
            MaterialSocketServer.Reply(buildJsonObject { put("results", JsonArray(listOf(buildJsonObject {
                put("operation_id", operation); put("entity_type", type); put("entity_uuid", uuid)
                put("status", "applied"); put("revision", revision); put("entity", body)
            }))) }.toString().toByteArray())
        } else successReply(input, revision) { body ->
            val value = if (type == "activity_event" && body["one_time"] != null) {
                val intent = body.getValue("one_time").jsonObject
                JsonObject(body + ("one_time_state_after" to buildJsonObject {
                    put("version", intent.getValue("expected_version").jsonPrimitive.int + 1)
                    put("head_event_uuid", intent.getValue("event_uuid"))
                    put("completion_event_uuid", if (intent.getValue("action") == JsonPrimitive("complete"))
                        intent.getValue("event_uuid") else JsonNull)
                }))
            } else body
            canonical[uuid] = value
            value
        }
        replies[operation] = result
        return result
    }

    private fun reopen() {
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
    }

    private suspend fun once(name: String = "Retained task", parent: String? = null, linked: Boolean = false): HabitEntity {
        val id = onceHabits(onceRepository()).createHabit(name, "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
            parentHabitId = parent, failMode = FailMode.LOOSE, appearance = ObjectAppearance(IconReference.Role("task.default"), "#123456", "theme"),
            completionPolicy = "one_and_done", creationAuthority = creator.capture(), selectedMetricIds = if (linked) setOf(metric.id) else emptySet())
        return db.habitDao().getHabitById(id)!!
    }

    private suspend fun structures(http: NextSyncHttp) {
        while (true) {
            val row = db.syncOutboxDao().getAll().firstOrNull { it.recordType in setOf("habit", "metric", "link") && it.action == "upsert" } ?: return
            assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        }
    }

    private suspend fun accept(http: NextSyncHttp, row: com.dayforge.data.local.entity.SyncOutboxEntity) {
        if (row.recordType == "one_time_completion") {
            val local = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
            val core = sender(http)
            val timers = NextTimerRequestStore(db, tokens, sessions, core)
            val facts = OneTimeAcceptedEventStore(db, tokens, sessions, local, timerRequests = timers)
            assertTrue(NextOneTimeRequestStore(db, tokens, sessions, http, facts, core)
                .sendAndAccept(access(), row.operationId) is NextOneTimeOutcome.Accepted)
        } else assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
    }

    private suspend fun blocked(http: NextSyncHttp, row: com.dayforge.data.local.entity.SyncOutboxEntity) {
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendOperation(access(), row.operationId) } as NextRequestException).reason)
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, row.operationId))
    }

    @Test fun completedOnceDeletionRetainsOriginalFactReleasesNameAndWaitsForItsExactAckAcrossColdRestart() = runBlocking<Unit> {
        register()
        val task = once()
        val repo = onceHabits(onceRepository())
        val completion = repo.logCompletion(app, task.id)
        val factsBefore = db.completionDao().getByHabitOnce(task.id)
        val captured = repo.getHabitForEditing(task.id).authority
        repo.deleteHabit(task, app, authority = captured)
        val original = db.syncOutboxDao().getAll().single { it.recordType == "habit" && it.action == "delete" }
        repo.deleteHabit(task, app, authority = captured)
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "habit" && it.action == "delete" })
        assertEquals(factsBefore, db.completionDao().getByHabitOnce(task.id))
        assertNull(repo.getHabitById(task.id)); assertNull(repo.getHabit(task.id).first())
        assertTrue(repo.getOneTimeStatus(task.id).completed); assertFalse(repo.getOneTimeStatus(task.id).canChange)
        rejected { repo.undoCompletion(app, completion) }
        val fresh = once(task.name)
        assertNotEquals(task.uuid, fresh.uuid)
        assertFalse(repo.getOneTimeStatus(fresh.id).completed)
        val (http, _) = channel(::response)
        blocked(http, original)
        val origin = originalIntent(original)
        reopen()
        assertEquals(origin, originalIntent(original)); assertEquals(factsBefore, db.completionDao().getByHabitOnce(task.id))
        assertTrue(creatingHabits().allHabits.first().none { it.uuid == task.uuid })
        structures(http)
        blocked(http, original)
        accept(http, db.syncOutboxDao().getAll().single { it.recordType == "one_time_completion" })
        assertEquals(factsBefore, db.completionDao().getByHabitOnce(task.id))
        accept(http, original)
        assertNull(db.habitDao().getHabitByUuid(task.uuid)); assertNotNull(db.habitDao().getHabitByUuid(fresh.uuid))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertTrue(db.nextRequestDao().origin(NEXT_OPERATION, original.operationId) == origin)
        assertEquals(1, db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM next_structural_supersessions WHERE originalId=?",
            arrayOf<Any>(original.operationId)).use { it.moveToFirst(); it.getInt(0) })
        reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), original.operationId))
        assertNull(db.habitDao().getHabitByUuid(task.uuid))
    }

    @Test fun actualGoalConfirmationCascadesOnlyAfterChildFactsAndLinksAreAccepted() = runBlocking<Unit> {
        register()
        val repo = onceHabits(onceRepository())
        val goal = HabitDraft(id = id(280), name = "Container", habitType = HabitType.GOAL,
            appearance = ObjectAppearance(IconReference.Role("goal.default"), "#123456", "theme"))
        val count = HabitDraft(id = id(281), name = "Child count", habitType = HabitType.COUNTING, targetValue = 10,
            completionPolicy = "recurring", appearance = habit.appearance)
        repo.createGoal(goal, listOf(count), creationAuthority = creator.capture())
        val task = once(parent = goal.id)
        val counted = db.habitDao().getHabitByUuid(count.id)!!
        repo.logCompletion(app, counted.id, 3); repo.logCompletion(app, task.id)
        creatingMetrics().linkHabits(metric, setOf(counted.id))
        val root = db.habitDao().getHabitByUuid(goal.id)!!
        val coordinator = HabitDeletionCoordinator(app, repo)
        coordinator.requestDeletion(root)
        assertEquals(2, coordinator.pendingDeletion.value!!.childCount)
        coordinator.deleteWithChildren()
        assertNull(coordinator.pendingDeletion.value)
        assertTrue(repo.allHabits.first().none { it.uuid in setOf(goal.id, count.id, task.uuid) })
        assertTrue(db.completionDao().getByHabitOnce(task.id).isNotEmpty())
        assertTrue(db.completionDao().getByHabitOnce(counted.id).isNotEmpty())
        val rootDelete = db.syncOutboxDao().getAll().single { it.entityUuid == root.uuid && it.action == "delete" }
        assertEquals(JsonPrimitive("cascade_children"), NextPlanDeletionStore.payload(rootDelete)!!["child_policy"])
        assertFalse(db.syncOutboxDao().getAll().any { it.recordType == "completion" && it.action == "delete" })
        val (http, _) = channel(::response)
        structures(http)
        blocked(http, rootDelete)
        for (row in db.syncOutboxDao().getAll().filter { it.recordType in setOf("completion", "one_time_completion") }) accept(http, row)
        for (row in db.syncOutboxDao().getAll().filter { it.recordType == "link" && it.action == "delete" }) accept(http, row)
        for (row in db.syncOutboxDao().getAll().filter { it.recordType == "habit" && it.action == "delete" && it.entityUuid != root.uuid }) accept(http, row)
        accept(http, rootDelete)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertTrue(listOf(root.uuid, counted.uuid, task.uuid).all { db.habitDao().getHabitByUuid(it) == null })
        assertNotNull(db.metricDao().getMetricById(metric.id)); assertNotNull(db.habitDao().getHabitById(habit.id))
    }

    @Test fun keepingChildrenLeavesThemVisibleAndBlocksParentBeforeOriginalAttachAndDetachFinish() = runBlocking<Unit> {
        register()
        val repo = creatingHabits()
        val goal = HabitDraft(id = id(290), name = "Detach container", habitType = HabitType.GOAL,
            appearance = ObjectAppearance(IconReference.Role("goal.default"), "#123456", "theme"))
        val child = HabitDraft(id = id(291), name = "Preserved child", habitType = HabitType.COUNTING,
            completionPolicy = "recurring", appearance = habit.appearance)
        repo.createGoal(goal, listOf(child), creationAuthority = creator.capture())
        val root = db.habitDao().getHabitByUuid(goal.id)!!
        val rootCreate = db.syncOutboxDao().getAll().first { it.entityUuid == root.uuid }
        val item = db.habitDao().getHabitByUuid(child.id)!!
        repo.logCompletion(app, item.id, 1)
        repo.deleteHabitOrphanChildren(root, app, repo.getHabitForEditing(root.id).authority)
        assertNull(db.habitDao().getHabitById(item.id)!!.parentHabitId)
        assertTrue(repo.allHabits.first().any { it.id == item.id })
        val deletion = db.syncOutboxDao().getAll().single { it.entityUuid == root.uuid && it.action == "delete" }
        assertEquals(JsonPrimitive("detach_children"), NextPlanDeletionStore.payload(deletion)!!["child_policy"])
        val (http, _) = channel(::response)
        accept(http, rootCreate)
        blocked(http, deletion)
        accept(http, db.syncOutboxDao().getAll().first { it.entityUuid == item.uuid && it.action == "upsert" })
        // Old attachment is accepted, but the pending detach still belongs before the root delete.
        blocked(http, deletion)
        val detach = db.syncOutboxDao().getAll().single { it.entityUuid == item.uuid && it.action == "upsert" }
        // D-017: a later configuration cannot jump the first count's original ACK, even when
        // keeping children. Preserve that barrier rather than weakening it to fit old test order.
        blocked(http, detach)
        val firstCount = db.syncOutboxDao().getAll().single { it.recordType == "completion" }
        accept(http, firstCount)
        structures(http)
        accept(http, deletion)
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, firstCount.operationId))
        assertTrue(db.syncOutboxDao().getAll().none { it.recordType == "completion" })
        reopen()
        assertNull(db.habitDao().getHabitByUuid(root.uuid)); assertNull(db.habitDao().getHabitById(item.id)!!.parentHabitId)
        assertEquals(1, db.completionDao().getByHabitOnce(item.id).size)
    }

    @Test fun lateOriginFailureRollsBackHiddenNamesQueuesAndHierarchyAndActiveTimerCannotBeDeleted() = runBlocking<Unit> {
        val task = once()
        val before = db.syncOutboxDao().getAll()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_delete_origin BEFORE INSERT ON next_request_origins
            WHEN (SELECT action FROM sync_outbox WHERE id=NEW.queueId)='delete'
            BEGIN SELECT RAISE(ABORT,'delete origin unavailable'); END""")
        rejected { creatingHabits().deleteHabit(task, app) }
        assertEquals(task, db.habitDao().getHabitById(task.id)); assertEquals(before, db.syncOutboxDao().getAll())
        reopen()
        assertEquals(task, db.habitDao().getVisibleHabitById(task.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete_origin")
        val running = start()
        val withTimer = db.syncOutboxDao().getAll()
        rejected { creatingHabits().deleteHabit(timerHabit, app) }
        assertEquals(timerHabit, db.habitDao().getVisibleHabitById(timerHabit.id))
        assertEquals(running, db.timeLogDao().getPendingTimerCommands(Int.MAX_VALUE).single())
        assertEquals(withTimer, db.syncOutboxDao().getAll())
        creatingHabits().deleteHabit(task, app)
        assertNull(db.habitDao().getVisibleHabitById(task.id))
    }

    @Test fun pendingPromptKeepsOriginalNameAndDraftAndBlocksLinkAndParentWithoutDiscardingInput() = runBlocking<Unit> {
        register()
        val task = once(linked = true)
        val once = onceRepository(); val repo = onceHabits(once)
        repo.logCompletion(app, task.id)
        val prompt = once.saveDraft(once.prompt(task.id)!!, mapOf(metric.id to ("12.250" to "keep")))
        repo.deleteHabit(task, app)
        val stillPending = once.prompt(task.id)!!
        assertEquals(task.name, stillPending.habitName); assertEquals(prompt.snapshot.row, stillPending.snapshot.row)
        val (http, _) = channel(::response)
        structures(http)
        accept(http, db.syncOutboxDao().getAll().single { it.recordType == "one_time_completion" })
        val deletes = db.syncOutboxDao().getAll().filter { it.action == "delete" }
        assertEquals(setOf("habit", "link"), deletes.map { it.recordType }.toSet())
        for (row in deletes) blocked(http, row)
        once.submit(stillPending)
        assertEquals(12.25, db.metricLogDao().getLogsByMetric(metric.id).first().single().value, 0.0)
        accept(http, db.syncOutboxDao().getAll().single { it.recordType == "metric_log" })
        accept(http, deletes.single { it.recordType == "link" })
        accept(http, deletes.single { it.recordType == "habit" })
        assertNull(db.habitDao().getHabitById(task.id)); assertEquals(1, db.metricLogDao().countAll())
        assertEquals("saved", db.completionFollowUpDao().prompt(prompt.eventUuid)!!.state)
    }

    @Test fun lostDeleteResponseAndLateAckFailureRetainHiddenRowsAndReplayIdenticalJournalAfterColdRestart() = runBlocking<Unit> {
        register()
        val task = once()
        val lose = AtomicBoolean(false)
        val (http, server) = channel { input ->
            val value = response(input)
            if (input.path.endsWith("/push") && wireOperation(input)["action"] == JsonPrimitive("delete") && lose.compareAndSet(true, false)) null else value
        }
        structures(http)
        creatingHabits().deleteHabit(task, app)
        val deletion = db.syncOutboxDao().getAll().single()
        lose.set(true)
        rejected { sender(http).sendAndAcceptOperation(access(), deletion.operationId) }
        val frozen = transmission(NEXT_OPERATION, deletion.operationId).wireBytes.copyOf()
        assertNotNull(db.habitDao().getHabitById(task.id)); assertNull(db.habitDao().getVisibleHabitById(task.id))
        reopen()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_delete_ack BEFORE INSERT ON next_acceptances
            WHEN NEW.requestId='${deletion.operationId}' BEGIN SELECT RAISE(ABORT,'delete ack unavailable'); END""")
        rejected { sender(http).sendAndAcceptOperation(access(), deletion.operationId) }
        assertNotNull(db.habitDao().getHabitById(task.id)); assertNull(db.habitDao().getVisibleHabitById(task.id))
        reopen()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete_ack")
        accept(http, deletion)
        assertNull(db.habitDao().getHabitById(task.id))
        val requests = server.requests.filter { it.path.endsWith("/push") && wireOperation(it)["action"] == JsonPrimitive("delete") }
        assertTrue(requests.size >= 2); requests.forEach { assertArrayEquals(frozen, it.body) }
    }

    @Test fun realEditorDoesNotNavigateOnStaleAccountDeletionAndKnownReadOnlyDeletionIsAtomic() = runBlocking<Unit> {
        val task = once()
        val model = withContext(Dispatchers.Main) { own(EditHabitViewModel(creatingHabits(), db.habitDao(), db.timeLogDao(),
            db.completionDao(), db.metricDao(), db.habitMetricLinkDao(), preferences, app)).also { it.loadHabit(task.id) } }
        withTimeout(5000) { model.uiState.first { !it.isLoading && it.editAuthority != null } }
        tokens.saveLoginSession("replacement", "replacement-refresh", "member", id(1), false)
        val navigated = AtomicBoolean(false)
        withContext(Dispatchers.Main) { model.deleteHabit { navigated.set(true) } }
        withTimeout(5000) { model.uiState.first { it.errorMessage != null } }
        assertFalse(navigated.get()); assertEquals(task, db.habitDao().getVisibleHabitById(task.id))
        register(permissions = setOf("sync.read", "facts.append"))
        val before = db.syncOutboxDao().getAll()
        rejected { creatingHabits().deleteHabit(task, app) }
        assertEquals(task, db.habitDao().getVisibleHabitById(task.id)); assertEquals(before, db.syncOutboxDao().getAll())
    }
}
