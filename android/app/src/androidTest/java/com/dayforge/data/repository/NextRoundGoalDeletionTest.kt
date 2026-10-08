package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual typed editor -> original child sources -> full HTTP ACKs -> retained parent deletion. */
@RunWith(AndroidJUnit4::class)
class NextRoundGoalDeletionTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private lateinit var goal: HabitEntity
    private lateinit var task: HabitEntity
    private lateinit var metadata: ChallengeMetadata
    private val canonical = mutableMapOf<String, JsonObject>()
    private val replies = mutableMapOf<String, MaterialSocketServer.Reply>()
    private var dropParent = false
    private var unknownRemoteChild = false

    private fun merger(http: NextSyncHttp): NextSyncMergeStore {
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        return NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
    }

    private suspend fun initialize(withOnce: Boolean = false): Pair<NextSyncHttp, MaterialSocketServer> {
        register()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val root = habit.copy(id = 0, uuid = id(600), name = "Challenge container", habitType = HabitType.GOAL,
                targetValue = 1, completionPolicy = null, appearance = ObjectAppearance(IconReference.Role("goal.default"), "#123456", "theme"),
                planMetadata = requireNotNull(habit.planMetadata).copy(timezone = null))
            goal = root.copy(id = db.habitDao().insert(root))
            habit = habit.copy(parentHabitId = goal.uuid); db.habitDao().update(habit)
            timerHabit = timerHabit.copy(parentHabitId = goal.uuid); db.habitDao().update(timerHabit)
            if (withOnce) {
                val once = habit.copy(id = 0, uuid = id(601), name = "Independent child task", habitType = HabitType.CHECK_IN,
                    targetValue = 1, schedule = HabitSchedule.Once(), completionPolicy = "one_and_done",
                    appearance = ObjectAppearance(IconReference.Role("task.default"), "#123456", "theme"), oneTimeConfirmedVersion = 0)
                task = once.copy(id = db.habitDao().insert(once))
            }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        metadata = ChallengeMetadata(1, listOf(habit, timerHabit).map { activity ->
            val initial = ChallengeRoundRecord(initialChallengeRoundHead(activity.uuid), null, null, null)
            ChallengeCheckpoint(initial.head, listOf(initial))
        }, emptyList())
        val (http, server) = channel(::response)
        val plans = listOf(goal, habit, timerHabit) + if (withOnce) listOf(task) else emptyList()
        val changes = plans.map { canonicalChange("plan_node", it.uuid, NextStructureMapper.writePlan(it)) } +
            canonicalChange("metric", metric.uuid, NextStructureMapper.writeMetric(metric))
        changes.forEach { canonical[it.entityUuid] = it.payload }
        merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(changes, 20, time,
            if (withOnce) listOf(OneTimeProjection(task.uuid, OneTimeState(0, null, null))) else emptyList(),
            1, metadata.checkpoints, metadata.births))
        return http to server
    }

    private fun canonicalChange(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)

    private fun response(input: MaterialSocketServer.Input): MaterialSocketServer.Reply? {
        if (input.path.endsWith("/identity")) return reply(input)
        assertEquals("/api/v2/sync/rounds/push", input.path)
        val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
        val operation = request.operations.single()
        assertEquals(emptyList<ChallengeRoundHead>(), request.contexts.single().affectedHeads)
        assertFalse(input.body.toString(Charsets.UTF_8).contains("goal_child_frontier"))
        val cached = replies[operation.operationId]
        if (cached != null) return if (dropParent && operation.entityUuid == goal.uuid) null else cached
        val result = if (operation.entityType == "activity_event") {
            metadata = metadata.copy(births = metadata.births + ChallengeBirth("activity_event", operation.entityUuid,
                requireNotNull(request.contexts.single().head)))
            json.decodeFromString<NextSyncPushResponse>(successReply(input).bytes.toString(Charsets.UTF_8)).results.single()
        } else if (unknownRemoteChild && operation.entityUuid == goal.uuid && operation.action == "delete")
            NextSyncOperationResult(operation.operationId, "plan_node", goal.uuid, "conflict", errorCode = "CHALLENGE_STATE_CONFLICT")
        else {
            val old = requireNotNull(canonical[operation.entityUuid])
            val revision = old.getValue("revision").jsonPrimitive.long + 1
            assertEquals(revision - 1, operation.baseRevision)
            val body = if (operation.action == "delete") JsonObject(old + mapOf("revision" to JsonPrimitive(revision),
                "updated_at" to JsonPrimitive(time), "deleted_at" to JsonPrimitive(time)))
            else JsonObject(operation.payload + mapOf("public_id" to JsonPrimitive(operation.entityUuid), "revision" to JsonPrimitive(revision),
                "created_at" to old.getValue("created_at"), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull))
            canonical[operation.entityUuid] = body
            NextSyncOperationResult(operation.operationId, operation.entityType, operation.entityUuid, "applied", revision = revision, entity = body)
        }
        val response = MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1,
            metadata.checkpoints, metadata.births)).toByteArray())
        replies[operation.operationId] = response
        return if (dropParent && operation.entityUuid == goal.uuid) null else response
    }

    private suspend fun stage(policy: String): com.dayforge.data.local.entity.SyncOutboxEntity {
        val ticket = requireNotNull(editor.habit(goal.id).authority)
        assertNotNull(ticket.rounds)
        editor.deleteHabit(db.habitDao().getHabitById(goal.id)!!, policy, ticket)
        return db.syncOutboxDao().getAll().single { it.entityUuid == goal.uuid && it.action == "delete" }
    }

    private suspend fun children(http: NextSyncHttp) {
        while (true) {
            val row = db.syncOutboxDao().getAll().firstOrNull { it.recordType == "habit" && it.entityUuid != goal.uuid } ?: return
            assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        }
    }

    private suspend fun blocked(http: NextSyncHttp, parent: com.dayforge.data.local.entity.SyncOutboxEntity) {
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendOperation(access(), parent.operationId) } as NextRequestException).reason)
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, parent.operationId))
    }

    @Test fun typedDetachWaitsForEveryRealChildAckAndColdReplayPreservesOriginalWholeEnvelope() = runBlocking<Unit> {
        val (http, server) = initialize()
        val parent = stage("detach_children")
        val original = originalIntent(parent)
        val source = roundOperationIntent(original.intentJson)!!
        assertEquals(setOf(habit.uuid, timerHabit.uuid), source.goalChildFrontier!!.map { it.childUuid }.toSet())
        assertTrue(db.habitDao().getChildrenByParentUuidOnce(goal.uuid).isEmpty())
        blocked(http, parent)
        val first = db.syncOutboxDao().getAll().first { it.entityUuid == habit.uuid }
        sender(http).sendAndAcceptOperation(access(), first.operationId)
        blocked(http, parent)
        storage.reopen(); children(http)
        dropParent = true
        rejected { sender(http).sendOperation(access(), parent.operationId) }
        val wire = transmission(NEXT_OPERATION, parent.operationId).wireBytes.copyOf()
        assertNotNull(db.habitDao().getHabitByUuid(goal.uuid))
        storage.reopen(); dropParent = false
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), parent.operationId))
        assertNull(db.habitDao().getHabitByUuid(goal.uuid))
        assertNotNull(db.habitDao().getHabitByUuid(habit.uuid)); assertNotNull(db.habitDao().getHabitByUuid(timerHabit.uuid))
        assertEquals(original, originalIntent(parent)); assertArrayEquals(wire, transmission(NEXT_OPERATION, parent.operationId).wireBytes)
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), parent.operationId))
        assertEquals(2, server.requests.count { it.path.endsWith("/push") && wireOperation(it)["entity_uuid"] == JsonPrimitive(goal.uuid) })
        assertEquals(20L, merger(http).state(access(), true)!!.cursor)
    }

    @Test fun cascadeIncludesIndependentOnceChildWithoutInventingBirthAndRemovesOnlyAfterEachAck() = runBlocking<Unit> {
        val (http, _) = initialize(withOnce = true)
        val parent = stage("cascade_children")
        val source = roundOperationIntent(originalIntent(parent).intentJson)!!
        assertEquals(3, source.goalChildFrontier!!.size)
        val onceDelete = db.syncOutboxDao().getAll().single { it.entityUuid == task.uuid }
        assertNull(roundOperationIntent(originalIntent(onceDelete).intentJson)!!.context.head)
        blocked(http, parent)
        children(http)
        assertNotNull(db.habitDao().getHabitByUuid(goal.uuid))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), parent.operationId))
        assertTrue(listOf(goal.uuid, habit.uuid, timerHabit.uuid, task.uuid).all { db.habitDao().getHabitByUuid(it) == null })
        assertEquals(0, count("next_challenge_births")); assertEquals(2, metadata.checkpoints.size)
        assertNotNull(db.metricDao().getMetricByUuid(metric.uuid))
    }

    @Test fun cascadePreservesPendingActualCountAndDayRuleUntilItsOriginalFullAck() = runBlocking<Unit> {
        val (http, _) = initialize()
        editor.mutateHabit(db.habitDao().getHabitById(habit.id)!!) { current ->
            val fact = CompletionEntity(habitId = current.id, habitUuid = current.uuid, uuid = id(620), value = 3,
                date = millis, actualCompletedAt = millis, recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06")
            NextCountDayStore(db).capture(current, fact); db.completionDao().insert(fact)
        }
        val fact = db.syncOutboxDao().getAll().single()
        val originalFact = originalIntent(fact)
        val parent = stage("cascade_children")
        val child = db.syncOutboxDao().getAll().single { it.entityUuid == habit.uuid && it.action == "delete" }
        blocked(http, child); blocked(http, parent)
        assertNotNull(db.completionDao().getCompletionByUuid(id(620)))
        assertEquals(10, db.countDayDao().get(habit.id, "2026-10-06")!!.targetValue)
        sender(http).sendAndAcceptOperation(access(), fact.operationId)
        assertEquals(originalFact, originalIntent(fact)); assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, fact.operationId))
        children(http)
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), parent.operationId))
        assertEquals(initialChallengeRoundHead(habit.uuid), merger(http).challengeMetadata(access())!!.requireBirth(
            "activity_event", id(620), habit.uuid).head)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }

    @Test fun missingReceiptCannotBeReplacedByQueueAbsenceOrPulledDetachedShadow() = runBlocking<Unit> {
        val (http, _) = initialize()
        val parent = stage("detach_children"); children(http)
        val frontier = roundOperationIntent(originalIntent(parent).intentJson)!!.goalChildFrontier!!.first()
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_acceptances WHERE requestId=?", arrayOf(frontier.operationId))
        assertTrue(db.habitDao().getChildrenByParentUuidOnce(goal.uuid).isEmpty())
        assertEquals(0, db.syncOutboxDao().getAll().count { it.entityUuid == frontier.childUuid })
        blocked(http, parent); assertNotNull(db.habitDao().getHabitByUuid(goal.uuid))
    }

    @Test fun originalChildProofOrReceiptDamageCannotFreezeOrConsumeParent() = runBlocking<Unit> {
        val (http, server) = initialize()
        val parent = stage("detach_children"); children(http)
        val item = roundOperationIntent(originalIntent(parent).intentJson)!!.goalChildFrontier!!.first()
        val receipt = db.nextRequestDao().acceptance(NEXT_OPERATION, item.operationId)!!
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?", arrayOf("0".repeat(64), item.operationId))
        rejected { sender(http).sendOperation(access(), parent.operationId) }
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?", arrayOf(receipt.resultHash, item.operationId))
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE requestId=?", arrayOf(id(9), item.operationId))
        rejected { sender(http).sendOperation(access(), parent.operationId) }
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, parent.operationId)); assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, parent.operationId))
        assertTrue(server.requests.none { it.path.endsWith("/push") && wireOperation(it)["entity_uuid"] == JsonPrimitive(goal.uuid) })
    }

    @Test fun neverSentChildDetachReplacementUsesActualAckButDoesNotRewriteParentFrontier() = runBlocking<Unit> {
        val (http, _) = initialize()
        editor.mutateHabit(db.habitDao().getHabitById(habit.id)!!) { db.habitDao().update(it.copy(description = "Earlier offline edit")) }
        val earlier = db.syncOutboxDao().getAll().single()
        val parent = stage("detach_children")
        val original = originalIntent(parent)
        blocked(http, parent)
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), earlier.operationId))
        children(http)
        val item = roundOperationIntent(original.intentJson)!!.goalChildFrontier!!.single { it.childUuid == habit.uuid }
        assertNotNull(db.nextStructuralCausalDao().supersession(item.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), parent.operationId))
        assertEquals(original, originalIntent(parent))
        assertEquals("Earlier offline edit", db.habitDao().getHabitByUuid(habit.uuid)!!.description)
    }

    @Test fun unknownRemoteChildConflictsWithoutAdoptingItRewritingSourceOrRemovingParent() = runBlocking<Unit> {
        val (http, _) = initialize()
        val parent = stage("detach_children"); val original = originalIntent(parent); children(http)
        unknownRemoteChild = true
        val delivery = sender(http).sendOperation(access(), parent.operationId)!!
        assertEquals("CHALLENGE_STATE_CONFLICT", delivery.result.results.single().errorCode)
        rejected { sender(http).acceptOperation(delivery) }
        assertEquals(original, originalIntent(parent)); assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, parent.operationId))
        assertEquals(parent, db.syncOutboxDao().getById(parent.id)); assertNotNull(db.habitDao().getHabitByUuid(goal.uuid))
        assertTrue(db.habitDao().getChildrenByParentUuidOnce(goal.uuid).isEmpty())
    }

    @Test fun lateChildReceiptFaultRollsBackParentTombstonePhysicalRemovalAndReceipt() = runBlocking<Unit> {
        val (http, _) = initialize()
        val parent = stage("detach_children"); children(http)
        val item = roundOperationIntent(originalIntent(parent).intentJson)!!.goalChildFrontier!!.first()
        val receipt = db.nextRequestDao().acceptance(NEXT_OPERATION, item.operationId)!!
        val delivery = sender(http).sendOperation(access(), parent.operationId)!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER child_receipt_fault AFTER INSERT ON next_acceptances WHEN NEW.requestId='${parent.operationId}' BEGIN UPDATE next_acceptances SET resultHash='bad' WHERE requestId='${item.operationId}'; END")
        rejected { sender(http).acceptOperation(delivery) }
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, item.operationId))
        assertNotNull(db.habitDao().getHabitByUuid(goal.uuid)); assertFalse(db.syncOutboxDao().getState("plan_node", goal.uuid)!!.deleted)
        assertUnaccepted(parent)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER child_receipt_fault")
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
    }

    @Test fun permanentParentRejectionReauditsChildAckAndLateFaultRollsBackWithoutConsumingAnything() = runBlocking<Unit> {
        val (http, _) = initialize()
        val parent = stage("detach_children"); children(http)
        val original = originalIntent(parent)
        val item = roundOperationIntent(original.intentJson)!!.goalChildFrontier!!.first()
        val receipt = db.nextRequestDao().acceptance(NEXT_OPERATION, item.operationId)!!
        unknownRemoteChild = true
        val delivery = sender(http).sendOperation(access(), parent.operationId)!!
        val result = delivery.result.results.single()
        val encoded = com.dayforge.data.api.encodeSyncRequest(NextSyncOperationResult.serializer(), result).toString(Charsets.UTF_8)
        suspend fun record() = sender(http).recordRejection(access(), NEXT_OPERATION, delivery.requestId,
            delivery.transmissionProof, encoded, delivery.challengeMetadata)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER rejected_child_receipt_fault AFTER INSERT ON next_rejections BEGIN UPDATE next_acceptances SET resultHash='bad' WHERE requestId='${item.operationId}'; END")
        rejected { record() }
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, item.operationId))
        assertEquals(0, count("next_rejections")); assertEquals(original, originalIntent(parent)); assertUnaccepted(parent)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER rejected_child_receipt_fault")
        storage.reopen(); record()
        assertEquals(1, count("next_rejections")); assertEquals(parent, db.syncOutboxDao().getById(parent.id))
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, item.operationId))
        assertNotNull(db.habitDao().getHabitByUuid(goal.uuid)); assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, parent.operationId))
    }

    @Test fun lateChildOriginFaultRollsBackOriginalBatchAndPreservesEveryVisibleObject() = runBlocking<Unit> {
        initialize()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER child_origin_fault AFTER INSERT ON next_request_origins WHEN NEW.intentJson LIKE '%\"child_policy\"%' BEGIN UPDATE next_request_origins SET accountId='${id(9)}' WHERE requestId != NEW.requestId; END")
        rejected { stage("detach_children") }
        assertEquals(goal.name, db.habitDao().getHabitByUuid(goal.uuid)!!.name)
        assertEquals(goal.uuid, db.habitDao().getHabitByUuid(habit.uuid)!!.parentHabitId)
        assertEquals(goal.uuid, db.habitDao().getHabitByUuid(timerHabit.uuid)!!.parentHabitId)
        assertEquals(0, count("next_request_origins")); assertEquals(0, count("sync_outbox"))
    }

    @Test fun staleDisplayedChildHeadAndChangedDeviceRejectBeforeDeletionCallbackCommits() = runBlocking<Unit> {
        val (http, _) = initialize()
        val ticket = editor.habit(goal.id).authority!!
        val old = metadata.checkpoints.first { it.head.activityUuid == habit.uuid }
        val next = ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, id(610), 1), id(4), id(611),
            ChallengeRestartIntent(habit.uuid, id(610), old.head.roundUuid, 0, 1))
        val changed = metadata.copy(checkpoints = metadata.checkpoints.map { if (it == old) ChallengeCheckpoint(next.head, old.records + next) else it })
        db.withTransaction { NextChallengeStore(db).acknowledgeInTransaction(access(), changed) }
        rejected { editor.deleteHabit(goal, "detach_children", ticket) }
        assertEquals(goal.uuid, db.habitDao().getHabitByUuid(habit.uuid)!!.parentHabitId)
        assertEquals(0, count("sync_outbox"))
        register(device = id(9))
        rejected { editor.deleteHabit(goal, "cascade_children", ticket) }
        assertEquals(goal.name, db.habitDao().getHabitByUuid(goal.uuid)!!.name)
        rejected { merger(http).state(access(), true) } // A changed device cannot claim the old cursor.
    }

    @Test fun strictPrivateFrontierRejectsWrongTypesDuplicatesAndCrossEntityUse() = runBlocking<Unit> {
        initialize(); val parent = stage("detach_children")
        val source = roundOperationIntent(originalIntent(parent).intentJson)!!
        val body = Json.encodeToJsonElement(source).jsonObject
        val frontier = body.getValue("goal_child_frontier").jsonArray
        val invalid = listOf<JsonElement>(JsonPrimitive("[]"),
            JsonArray(listOf(frontier.first(), frontier.first())))
        for (value in invalid) rejected {
            roundOperationIntent(JsonObject(body + ("goal_child_frontier" to value)).toString())
        }
        rejected { source.copy(operation = source.operation.copy(entityType = "metric")) }
        rejected { source.copy(goalChildFrontier = source.goalChildFrontier!!.map { it.copy(originHash = "0") }) }
    }

    @Test fun staleGoalConfirmationCannotCascadeOrDetachNewIndependentChild() = runBlocking<Unit> {
        initialize()
        val ticket = editor.habit(goal.id).authority!!
        assertEquals(listOf(habit.uuid, timerHabit.uuid).sorted(), ticket.goalChildUuids)
        val newChild = db.habitDao().getHabitById(habit.id)!!.copy(id = 0, uuid = id(630), name = "New unseen task",
            habitType = HabitType.CHECK_IN, targetValue = 1, schedule = HabitSchedule.Once(), completionPolicy = "one_and_done",
            appearance = ObjectAppearance(IconReference.Role("task.default"), "#123456", "theme"), oneTimeConfirmedVersion = 0)
        producer().writeRounds(producer().captureRounds()) { db.habitDao().insert(newChild) }
        val originalQueue = db.syncOutboxDao().getAll().single()
        val original = originalIntent(originalQueue)
        for (policy in listOf("cascade_children", "detach_children")) {
            val failure = rejected { editor.deleteHabit(db.habitDao().getHabitById(goal.id)!!, policy, ticket) }
            assertEquals("OBJECT_DELETE_CHILDREN_CHANGED_RELOAD_REQUIRED", failure.message)
        }
        assertEquals(goal.name, db.habitDao().getHabitByUuid(goal.uuid)!!.name)
        assertEquals(3, db.habitDao().getChildrenByParentUuidOnce(goal.uuid).size)
        assertEquals(listOf(originalQueue), db.syncOutboxDao().getAll()); assertEquals(original, originalIntent(originalQueue))
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("next_acceptances"))
    }

    @Test fun typedMetricAndHabitEditTicketsUseAcceptedProfileWithoutDowngradingOldPlainTickets() = runBlocking<Unit> {
        register()
        val stalePlain = editor.metric(metric.id).authority!!
        assertNull(stalePlain.rounds)
        val (http, _) = initialize()
        rejected { editor.editMetric(db.metricDao().getMetricById(metric.id)!!.copy(name = "Old callback"), stalePlain) {
            db.metricDao().update(it)
        } }
        assertEquals(0, count("sync_outbox"))
        val current = db.metricDao().getMetricById(metric.id)!!
        val ticket = editor.metric(metric.id).authority!!
        assertNotNull(ticket.rounds)
        editor.editMetric(current.copy(name = "Round metric"), ticket) { db.metricDao().update(it) }
        val row = db.syncOutboxDao().getAll().single()
        assertNotNull(roundOperationIntent(originalIntent(row).intentJson))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals("Round metric", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertEquals(20L, merger(http).state(access(), true)!!.cursor)
    }
}
