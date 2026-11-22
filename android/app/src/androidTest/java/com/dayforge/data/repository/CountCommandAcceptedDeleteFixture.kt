package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.data.model.HabitType
import java.time.ZoneId
import java.util.UUID
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Actual editor/delete -> original send/ACK -> physical cold reopen. The peer owns no DB;
 * only rejection tests mutate retained evidence after the real successful transaction. */
abstract class CountCommandAcceptedDeleteFixture : CountCommandFormalUndoFixture() {
    internal enum class DeleteResponse { ACCEPT, LOST, REJECT }
    internal var deleteResponse = DeleteResponse.ACCEPT
    internal lateinit var deletion: SyncOutboxEntity
    private var timerScenario = false
    private val timerPolicies = mutableMapOf<String, TimerStartPolicy>()
    private val timerStarts = mutableMapOf<String, TimerCommandRequest>()
    private val timerReplies = mutableMapOf<String, TimerCommandResult>()
    private val timerWire = mutableMapOf<String, ByteArray>()
    override fun additionalStructuralReply(request: MaterialSocketServer.Input,
        wire: RoundSyncPushRequest): MaterialSocketServer.Reply? {
        val operation = wire.operations.single()
        if (operation.entityType == "plan_node" && operation.action == "upsert") {
            check(timerScenario) { "ACCEPTED_DELETE_UNEXPECTED_PLAN_EDIT" }
            assertEquals(habit.uuid, operation.entityUuid)
            val result = events[operation.operationId]?.copy(status = "already_applied") ?: run {
                assertEquals(revision, operation.baseRevision)
                assertEquals(JsonPrimitive("duration"), operation.payload.getValue("activity").jsonObject["tracking_mode"])
                val changed = Instant.now().toString()
                val canonical = JsonObject(operation.payload + mapOf("public_id" to JsonPrimitive(habit.uuid),
                    "revision" to JsonPrimitive(++revision),
                    "created_at" to JsonPrimitive(Instant.ofEpochMilli(habit.createdAt).toString()),
                    "updated_at" to JsonPrimitive(changed), "deleted_at" to JsonNull))
                serverPlan = NextStructureMapper.readPlan(canonical, habit.uuid, revision).copy(id = habit.id)
                changes += SyncV2Change(++cursor, "plan_node", habit.uuid, "upsert", revision, canonical, changed, id(4))
                NextSyncOperationResult(operation.operationId, "plan_node", habit.uuid, "applied", revision,
                    entity = canonical).also { events[operation.operationId] = it }
            }
            val meta = metadata()
            return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1,
                meta.checkpoints, meta.births)).toByteArray())
        }
        if (operation.entityType == "metric_observation") {
            assertEquals("upsert", operation.action); assertNull(operation.baseRevision)
            assertEquals(JsonPrimitive(metric.uuid), operation.payload["metric_uuid"])
            assertEquals(JsonPrimitive(metric.unit), operation.payload["unit"])
            val result = events[operation.operationId]?.copy(status = "already_applied") ?: run {
                val received = Instant.now().toString()
                val body = JsonObject(operation.payload + mapOf("public_id" to JsonPrimitive(operation.entityUuid),
                    "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(received), "updated_at" to JsonPrimitive(received),
                    "deleted_at" to JsonNull, "received_at" to JsonPrimitive(received),
                    "note" to (operation.payload["note"] ?: JsonPrimitive("")), "source_type" to JsonPrimitive("app"),
                    "source_device_id" to JsonPrimitive(id(4)), "external_event_id" to JsonNull, "metadata" to buildJsonObject {}))
                NextSyncOperationResult(operation.operationId, operation.entityType, operation.entityUuid, "applied", 1, entity = body)
                    .also { events[operation.operationId] = it }
            }
            val meta = metadata()
            return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1,
                meta.checkpoints, meta.births)).toByteArray())
        }
        assertEquals("delete", operation.action); assertTrue(operation.payload.isEmpty())
        val baseRevision = if (operation.entityType == "plan_node") revision else 1L
        assertEquals(baseRevision, operation.baseRevision)
        val acceptedRevision = Math.addExact(baseRevision, 1L)
        val meta = metadata()
        if (operation.entityType == "plan_node") {
            assertEquals(habit.uuid, operation.entityUuid)
            if (deleteResponse == DeleteResponse.LOST) return null
            if (deleteResponse == DeleteResponse.REJECT) return MaterialSocketServer.Reply(json.encodeToString(
                RoundSyncPushResponse(listOf(NextSyncOperationResult(operation.operationId, operation.entityType,
                    operation.entityUuid, "rejected", null, errorCode = "INVALID_PAYLOAD")), 1, meta.checkpoints, meta.births)).toByteArray())
        }
        val result = events[operation.operationId]?.copy(status = "already_applied") ?: run {
            val body = when (operation.entityType) {
                "plan_node" -> NextStructureMapper.writePlan(serverPlan)
                "activity_metric_link" -> {
                    assertEquals(id(889), operation.entityUuid)
                    SyncV2Mapper.link(HabitMetricLinkEntity(habitId = 0, habitUuid = habit.uuid, metricId = 0,
                        metricUuid = metric.uuid, promptOnComplete = true, uuid = id(889),
                        createdAt = habit.createdAt, updatedAt = habit.updatedAt))
                }
                else -> error("ACCEPTED_DELETE_UNEXPECTED_TYPE")
            }
            val changed = Instant.now().toString()
            val payload = JsonObject(body + mapOf("public_id" to JsonPrimitive(operation.entityUuid), "revision" to JsonPrimitive(acceptedRevision),
                "created_at" to JsonPrimitive(Instant.ofEpochMilli(habit.createdAt).toString()),
                "updated_at" to JsonPrimitive(changed), "deleted_at" to JsonPrimitive(changed)))
            NextSyncOperationResult(operation.operationId, operation.entityType, operation.entityUuid,
                "applied", acceptedRevision, entity = payload).also {
                events[operation.operationId] = it
                changes += SyncV2Change(++cursor, operation.entityType, operation.entityUuid, "delete", acceptedRevision, payload, changed, id(4))
            }
        }
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1,
            meta.checkpoints, meta.births)).toByteArray())
    }
    override fun additionalTimerReply(request: MaterialSocketServer.Input): MaterialSocketServer.Reply? {
        check(timerScenario) { "ACCEPTED_DELETE_TIMER_ENDPOINT_NOT_ENABLED" }
        val body = json.decodeFromString<RoundTimerCommandBatchRequest>(request.body.toString(Charsets.UTF_8))
        assertEquals(id(4), body.deviceId)
        val command = body.commands.single()
        val head = requireNotNull(body.contexts.single().head)
        assertEquals(habit.uuid, head.activityUuid); assertTrue(history.any { it.head == head })
        val result = timerReplies[command.commandId]?.let { previous ->
            assertArrayEquals(timerWire.getValue(command.commandId), request.body)
            previous.copy(status = "already_applied")
        } ?: run {
            when (command.commandType) {
                "start" -> {
                    assertEquals(1, command.sequence); assertEquals(0, command.expectedControlGeneration)
                    assertEquals(habit.uuid, command.activityUuid); assertNotNull(command.timezone)
                    assertNull(timerStarts.put(command.sessionId, command))
                    timerPolicies[command.sessionId] = requireNotNull(command.startPolicy).validate()
                    assertTrue(births.none { it.entityType == "timer_session" && it.entityUuid == command.sessionId })
                    births += ChallengeBirth("timer_session", command.sessionId, head)
                }
                "cancel" -> {
                    assertEquals(2, command.sequence); assertEquals(1, command.expectedControlGeneration)
                    assertNull(command.startPolicy); assertNotNull(timerStarts[command.sessionId])
                    assertEquals(1, timerReplies.values.count { it.sessionId == command.sessionId })
                }
                else -> error("ACCEPTED_DELETE_UNCOVERED_TIMER_TRANSITION")
            }
            val original = timerStarts.getValue(command.sessionId); val policy = timerPolicies.getValue(command.sessionId)
            val state = if (command.commandType == "start") "running" else "cancelled"
            val elapsed = if (command.commandType == "start") 0L else {
                val previous = requireNotNull(timerReplies.values.single { it.sessionId == command.sessionId }.session)
                assertEquals("running", previous.state)
                command.activeElapsedMs ?: Math.addExact(previous.activeElapsedMs,
                    java.time.Duration.between(Instant.parse(previous.stateChangedAt), Instant.parse(command.occurredAt)).toMillis())
            }
            val session = TimerSessionResponse(command.sessionId, habit.uuid, state, id(4), 1,
                command.sequence, command.sequence + 1, original.occurredAt, command.occurredAt,
                endedAt = command.occurredAt.takeIf { state == "cancelled" }, timezone = requireNotNull(original.timezone),
                isCountdown = policy.isCountdown, targetSeconds = policy.targetSeconds, maxDurationSeconds = policy.maxDurationSeconds,
                activeElapsedMs = elapsed)
            TimerCommandResult(command.commandId, command.sessionId, "applied", session = session).also {
                timerReplies[command.commandId] = it; timerWire[command.commandId] = request.body.copyOf()
            }
        }
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundTimerCommandBatchResponse(listOf(result),
            Instant.now().toString(), 1, meta.checkpoints, meta.births)).toByteArray())
    }

    /** Only NEW timer-frontier scenarios choose this legal initial COUNT bootstrap unit.
     * No persisted fact/source exists yet; default COUNT scenarios retain their original null unit. */
    internal fun prepareTimerCountBootstrap() {
        check(!timerScenario)
        assertEquals(HabitType.COUNTING, habit.habitType)
        habit = habit.copy(planMetadata = requireNotNull(habit.planMetadata).copy(targetUnit = "second"))
        timerScenario = true
    }

    /** Uses the same original typed writer/DAO state transitions as TimerService. Origins,
     * materialized journals and ACKs are never inserted by the test. Cancel has no duration fact. */
    internal suspend fun editToTimerAndQueueCancel(): TimerCommandEntity {
        check(timerScenario)
        val selected = editor.habit(habit.id); val before = requireNotNull(selected.value)
        assertEquals(HabitType.COUNTING, before.habitType)
        assertEquals("second", requireNotNull(before.planMetadata).targetUnit)
        editingHabits().updateHabit(before.copy(habitType = HabitType.TIMER, targetValue = 60), editAuthority = selected.authority)
        syncAll()
        habit = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        assertEquals(HabitType.TIMER, habit.habitType); assertEquals(2L, revision)
        assertEquals(2, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
        assertNotNull(db.completionDao().getCompletionByUuid(originalFact.uuid))
        val writer = NextTimerWriter(db, tokens, sessions)
        val at = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
        val session = UUID.randomUUID().toString(); val startId = UUID.randomUUID().toString()
        val startTicket = requireNotNull(writer.capture(habit.id))
        writer.write(habit.id, startTicket) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = habit.id, uuid = session, startTime = at,
                endTime = null, durationSeconds = 0, date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(),
                timerNextCommandSequence = 2, timerControlGeneration = 1, timerLastCommandAt = at,
                timerTimezone = zone.id, timerActiveElapsedMillis = 0L,
                timerElapsedRealtimeAnchor = android.os.SystemClock.elapsedRealtime()),
                TimerCommandEntity(commandId = startId, sessionUuid = session, sequence = 1, commandType = "start",
                    occurredAt = at, expectedControlGeneration = 0, activityUuid = habit.uuid, timezone = zone.id),
                TimerSegmentEntity(sessionUuid = session, sequence = 1, startedAt = at))
        }
        val running = requireNotNull(db.timeLogDao().getActiveTimeLogForHabit(habit.id))
        assertEquals(session, running.uuid); assertEquals(1, db.timeLogDao().getTimerSegments(session).size)
        val cancel = TimerCommandEntity(sessionUuid = session, sequence = running.timerNextCommandSequence,
            commandType = "cancel", occurredAt = maxOf(System.currentTimeMillis(), requireNotNull(running.timerLastCommandAt) + 1),
            expectedControlGeneration = running.timerControlGeneration)
        writer.write(habit.id, requireNotNull(writer.capture(habit.id))) { db.timeLogDao().deleteTimerAndQueue(running, cancel) }
        val queued = db.timeLogDao().getPendingTimerCommands().filter { it.sessionUuid == session }
        assertEquals(listOf("start", "cancel"), queued.map { it.commandType }); assertEquals(listOf(1, 2), queued.map { it.sequence })
        assertNull(db.timeLogDao().getActiveTimeLogForHabit(habit.id)); assertTrue(db.timeLogDao().getTimerSegments(session).isEmpty())
        for (command in queued) {
            assertNotNull(db.nextRequestDao().origin(NEXT_TIMER, command.commandId))
            assertNull(db.nextRequestDao().transmission(NEXT_TIMER, command.commandId))
            assertNull(db.nextRequestDao().acceptance(NEXT_TIMER, command.commandId))
        }
        return queued.single { it.commandId == cancel.commandId }
    }

    internal suspend fun queueTimerPlanFrontier(): String {
        val selected = editor.habit(habit.id); val original = requireNotNull(selected.value)
        assertEquals(HabitType.TIMER, original.habitType)
        editingHabits().updateHabit(original.copy(name = "Pending actual timer plan frontier"), editAuthority = selected.authority)
        val queued = db.syncOutboxDao().getAll().single { it.entityUuid == habit.uuid && it.action == "upsert" }
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, queued.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, queued.operationId))
        return queued.operationId
    }

    /** COUNT has already been ACKed. The real restart proposal, send/ACK and contiguous
     * Plan pull must all complete before deletion; metadata-only advancement is insufficient. */
    internal suspend fun acceptRestartBeforeDelete(expectedAcceptedRequests: Int = 2,
        expectedRevision: Long = 2, expectedTerminal: TimerCommandEntity? = null,
        expectedPlanFrontier: String? = null): NextRestartReference {
        val reference = propose()
        assertNotNull(db.syncOutboxDao().getByOperationId(reference.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, reference.operationId))
        val proposal = requireNotNull(restartProposal(requireNotNull(db.nextRequestDao()
            .origin(NEXT_OPERATION, reference.operationId)).intentJson))
        if (expectedTerminal == null) assertTrue(proposal.terminals.isEmpty()) else {
            val terminal = proposal.terminals.single()
            assertEquals(expectedTerminal.commandId, terminal.commandId)
            assertEquals(expectedTerminal.sessionUuid, terminal.sessionId)
            assertEquals(expectedTerminal.sequence, terminal.sequence)
            assertNull(db.nextRequestDao().acceptance(NEXT_TIMER, terminal.commandId))
            assertEquals(2, count("timer_command_outbox")) // Propose before actual timer ACKs.
        }
        assertEquals(expectedPlanFrontier, proposal.planFrontier?.operationId)
        syncAll()
        assertEquals(0, count("sync_outbox"))
        assertEquals(expectedAcceptedRequests, count("next_acceptances"))
        assertNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, reference.operationId))
        assertNotNull(db.nextRestartDao().materialization(reference.operationId))
        assertNotNull(db.nextRestartDao().planProof(reference.operationId))
        sessions.exclusive { db.withTransaction {
            val current = access()
            assertEquals(reference, NextRestartStore(db).original(current, reference.operationId).reference)
            val accepted = NextRestartBindingStore(db).ready(current, reference)
            assertEquals(expectedRevision, accepted.first.getValue("revision").jsonPrimitive.long)
        } }
        assertEquals(reference.head, requireNotNull(editor.habit(habit.id).authority).rounds!!.completionRounds.head(habit.uuid))
        assertEquals(expectedRevision, revision)
        if (expectedTerminal != null) {
            assertEquals(0, count("timer_command_outbox"))
            assertEquals(2, timerWire.size)
            assertEquals(setOf("running", "cancelled"), timerReplies.values.map { requireNotNull(it.session).state }.toSet())
            assertNotNull(db.nextRequestDao().acceptance(NEXT_TIMER, expectedTerminal.commandId))
        }
        return reference
    }

    internal suspend fun assertDeleteHasNoPendingRestartFrontier(reference: NextRestartReference,
        expectedAcceptedRequests: Int = 4) {
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, deletion.operationId))
        val source = requireNotNull(roundOperationIntent(origin.intentJson))
        assertEquals(reference.head, source.context.head)
        assertNull(source.restartFrontier)
        assertNull(source.restartPlanProofHash)
        assertNull(db.syncOutboxDao().getByOperationId(reference.operationId))
        assertEquals(0, count("sync_outbox"))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, reference.operationId))
        assertNotNull(db.nextRestartDao().planProof(reference.operationId))
        assertEquals(expectedAcceptedRequests, count("next_acceptances"))
    }

    internal suspend fun deleteOriginal(ack: Boolean = true, pendingFollowUp: Boolean = true) {
        val selected = editor.habit(habit.id)
        creatingHabits().deleteHabit(requireNotNull(selected.value), authority = requireNotNull(selected.authority))
        deletion = db.syncOutboxDao().getAll().single { it.recordType == "habit" && it.action == "delete" && it.entityUuid == habit.uuid }
        assertNotNull(db.habitDao().getHabitById(habit.id)); assertNull(db.habitDao().getVisibleHabitById(habit.id))
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, deletion.operationId))
        if (pendingFollowUp) rejectedObservation() // A durable request is still not an accepted deletion.
        else assertNull(store().obsoleteFollowUp(parent.reference.native(), preferences))
        if (ack) {
            syncAll()
            assertNull(db.habitDao().getHabitById(habit.id)); assertNull(db.habitDao().getHabitByUuid(habit.uuid))
            assertEquals(0, count("completions")); assertEquals(0, count("count_days")); assertEquals(0, count("sync_outbox"))
            assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, deletion.operationId))
            assertNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, deletion.operationId))
            assertTrue(requireNotNull(db.syncOutboxDao().getState("plan_node", habit.uuid)).deleted)
            assertNotNull(db.syncOutboxDao().getState("activity_event", originalFact.uuid))
            if (!timerScenario) {
                assertEquals(events.values.count { it.entityType == "activity_event" }, count("next_challenge_births"))
            } else {
                assertEquals(events.values.count { it.entityType == "activity_event" }, births.count { it.entityType == "activity_event" })
                assertEquals(1, births.count { it.entityType == "timer_session" })
                assertEquals(births.size, count("next_challenge_births"))
            }
        }
    }
    internal suspend fun deleteDecision(owner: CountCommandStore = store()): CountCommandStore.ObsoleteFollowUp {
        val before = image(); val prefs = prefImage()
        val proof = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences))
        assertEquals(CountCommandObsoleteFollowUp.Reason.FORMAL_ACTIVITY_DELETE_ACCEPTED, proof.reason)
        assertTrue(proof.preferenceReady); sameImage(before, image()); assertEquals(prefs, prefImage()); pending(owner)
        return proof
    }
    internal suspend fun bareDelete() = db.withTransaction {
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        db.habitDao().delete(requireNotNull(db.habitDao().getHabitById(habit.id)))
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
    }
}
