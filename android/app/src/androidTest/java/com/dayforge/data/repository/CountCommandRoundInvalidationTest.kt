package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real formal bootstrap, original local restart origin/materialization/ACK and contiguous
 * accepted Plan log. No head seeding, original-input rewrite, or exception-to-invalid adapter. */
@RunWith(AndroidJUnit4::class)
class CountCommandRoundInvalidationTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private val history = mutableListOf<ChallengeRoundRecord>()
    private val births = mutableListOf<ChallengeBirth>()
    private val changes = mutableListOf<SyncV2Change>()
    private val events = mutableMapOf<String, NextSyncOperationResult>()
    private lateinit var http: NextSyncHttp
    private lateinit var server: MaterialSocketServer
    private lateinit var serverPlan: HabitEntity
    private lateinit var now: String
    private var revision = 1L
    private var cursor = 20L
    private fun store() = CountCommandStore(db, tokens, sessions)
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun row(ref: CountCommandStore.Reference) = store().resolve(ref).historical
    private data class Pending(val input: CountCommandRecords.Intent, val ref: CountCommandStore.Reference)
    private fun metadata() = ChallengeMetadata(1, listOf(
        ChallengeCheckpoint(history.last().head, history.toList()),
        ChallengeCheckpoint(initialChallengeRoundHead(timerHabit.uuid),
            listOf(ChallengeRoundRecord(initialChallengeRoundHead(timerHabit.uuid), null, null, null)))), births.toList())
    private fun plan(value: HabitEntity, version: Long) = SyncV2Change(0, "plan_node", value.uuid, "upsert", version,
        JsonObject(NextStructureMapper.writePlan(value) + mapOf("public_id" to JsonPrimitive(value.uuid),
            "revision" to JsonPrimitive(version), "created_at" to JsonPrimitive(Instant.ofEpochMilli(value.createdAt).toString()),
            "updated_at" to JsonPrimitive(now), "deleted_at" to JsonNull)), now, id(4))
    private fun merger() = NextSyncMergeStore(db, tokens, sessions,
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)),
        NextTimerRequestStore(db, tokens, sessions, sender(http)))
    private fun restart() = NextChallengeRestartRepository(db, tokens, sessions, http)
    private suspend fun openRounds() {
        tokens.getOrCreateInstallationId(); register()
        now = Instant.now().toString()
        val zone = ZoneId.systemDefault(); val born = LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant()
        habit = habit.copy(targetCycles = 3, isActive = true, createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(),
            planMetadata = requireNotNull(habit.planMetadata).copy(creationTimestamp = born.toString(), timezone = zone.id))
        serverPlan = habit
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        history += ChallengeRoundRecord(initialChallengeRoundHead(habit.uuid), null, null, null)
        val configured = channel { request ->
            when {
                request.path.endsWith("/identity") -> reply(request, 5)
                request.path == "/api/v2/sync/rounds/changes" -> {
                    val from = request.target.substringAfter("cursor=").substringBefore('&').toLong()
                    val meta = metadata()
                    MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(changes.filter { it.sequence > from },
                        cursor, false, now, 1, meta.checkpoints, meta.births)).toByteArray())
                }
                else -> {
                    assertEquals("/api/v2/sync/rounds/push", request.path)
                    val wire = json.decodeFromString<RoundSyncPushRequest>(request.body.toString(Charsets.UTF_8))
                    val operation = wire.operations.single()
                    val result = when (operation.entityType) {
                        "activity_event" -> {
                            val head = requireNotNull(wire.contexts.single().head)
                            assertTrue(history.any { it.head == head })
                            events[operation.operationId]?.copy(status = "already_applied") ?: run {
                                births += ChallengeBirth("activity_event", operation.entityUuid, head)
                                val received = Instant.now().toString()
                                val response = successReply(request) { value -> JsonObject(value + mapOf(
                                    "created_at" to JsonPrimitive(received), "updated_at" to JsonPrimitive(received), "received_at" to JsonPrimitive(received))) }
                                json.decodeFromString<NextSyncPushResponse>(response.bytes.toString(Charsets.UTF_8)).results.single().also {
                                    events[operation.operationId] = it
                                    changes += SyncV2Change(++cursor, "activity_event", operation.entityUuid, "upsert", 1,
                                        requireNotNull(it.entity), received, id(4))
                                }
                            }
                        }
                        "challenge_round" -> {
                            assertNull(wire.contexts.single().head)
                            val intent = json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload)
                            val existing = history.singleOrNull { it.restartOperationUuid == operation.operationId }
                            val accepted = existing ?: ChallengeRoundRecord(
                                ChallengeRoundHead(habit.uuid, intent.roundUuid, intent.expectedGeneration + 1), id(4),
                                operation.operationId, intent).also {
                                assertEquals(history.last().head.roundUuid, intent.expectedRoundUuid)
                                assertEquals(revision, intent.expectedPlanRevision)
                                history += it; revision++
                                serverPlan = serverPlan.copy(isActive = true)
                                changes += SyncV2Change(++cursor, "challenge_round", it.head.roundUuid, "upsert", 1,
                                    json.encodeToJsonElement(it).jsonObject, now, id(4))
                                changes += plan(serverPlan, revision).copy(sequence = ++cursor)
                            }
                            NextSyncOperationResult(operation.operationId, operation.entityType, operation.entityUuid,
                                if (existing == null) "applied" else "already_applied", 1, entity = json.encodeToJsonElement(accepted).jsonObject)
                        }
                        else -> error("ROUND_INVALIDATION_UNEXPECTED_OPERATION:${operation.entityType}")
                    }
                    val meta = metadata()
                    MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1,
                        meta.checkpoints, meta.births)).toByteArray())
                }
            }
        }
        http = configured.first; server = configured.second
        val metricChange = SyncV2Change(0, "metric", metric.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writeMetric(metric) + mapOf("public_id" to JsonPrimitive(metric.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(Instant.ofEpochMilli(metric.createdAt).toString()),
                "updated_at" to JsonPrimitive(now), "deleted_at" to JsonNull)), now)
        val meta = metadata()
        merger().bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(plan(habit, 1), plan(timerHabit, 1), metricChange),
            cursor, now, emptyList(), 1, meta.checkpoints, meta.births))
        habit = requireNotNull(db.habitDao().getHabitByUuid(habit.uuid))
        assertEquals(1, db.nextSyncStateDao().rows().single().challengeContract)
        assertEquals(initialChallengeRoundHead(habit.uuid), requireNotNull(editor.habit(habit.id).authority).rounds?.completionRounds?.head(habit.uuid))
        assertEquals(0, count("sync_outbox"))
    }
    private suspend fun prepare(target: CompletionEntity? = null): Pending {
        val selected = editor.habit(habit.id); val current = requireNotNull(selected.value); val authority = requireNotNull(selected.authority)
        val input = if (target == null) CountCommandInputs.add(db, UUID.randomUUID().toString(), current, authority,
            Instant.now(), ZoneId.systemDefault(), 1)
        else CountCommandInputs.undo(db, UUID.randomUUID().toString(), current, authority, target)
        val owner = store(); val registered = owner.register(input); owner.releaseUnadmitted(requireNotNull(registered.ticket))
        return Pending(input, registered.reference)
    }
    private suspend fun acceptedCount(): CompletionEntity {
        val selected = editor.habit(habit.id)
        val id = creatingHabits().logCompletion(app, habit.id, authority = requireNotNull(selected.authority), expectedHabitUuid = habit.uuid)
        assertTrue(id > 0)
        val queued = db.syncOutboxDao().getAll().single()
        val original = requireNotNull(roundOperationIntent(originalIntent(queued).intentJson))
        assertEquals(initialChallengeRoundHead(habit.uuid), original.context.head)
        val request = sender(http); val delivery = requireNotNull(request.sendOperation(access(), queued.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, request.acceptOperation(delivery))
        assertEquals(NextOperationAcceptance.REPLAYED, request.acceptOperation(delivery))
        sessions.exclusive { db.withTransaction { request.requireAcceptedCountInTransaction(access(), queued.operationId) } }
        assertEquals(0, count("sync_outbox")); assertEquals(1, count("next_challenge_births"))
        return requireNotNull(db.completionDao().getCompletionById(id))
    }
    private suspend fun pull() {
        val merge = merger(); val current = requireNotNull(merge.state(access(), true))
        val response = http.session(access()) { it.roundPull(current.cursor) }
        merge.page(access(), current, requireNotNull(response))
    }
    private suspend fun propose(): NextRestartReference {
        val selected = editor.habit(habit.id)
        return restart().restart(requireNotNull(selected.value), requireNotNull(selected.authority))
    }
    private suspend fun acceptRestart(ref: NextRestartReference, oldPending: Pending? = null) {
        assertEquals(NextRestartOutcome.COMMITTED, restart().sendAndAccept(access(), ref.operationId))
        assertEquals(NextRestartOutcome.REPLAYED, restart().sendAndAccept(access(), ref.operationId))
        oldPending?.let {
            assertEquals(0, count("next_restart_plan_proofs"))
            val beforePlanLog = image()
            refused("CAUSAL_PREDECESSOR_PENDING") { store().prepareGenericInvalidation(it.ref) }
            assertTrue(beforePlanLog == image()); requireOriginal(it)
            assertEquals(CountCommandRecords.Execution.PREPARED, row(it.ref).status.execution)
        }
        pull()
        sessions.exclusive { db.withTransaction {
            val original = NextRestartStore(db).original(access(), ref.operationId)
            assertEquals(ref, original.reference)
            assertEquals(initialChallengeRoundHead(habit.uuid), original.value.expectedHead)
            val ready = NextRestartBindingStore(db).ready(access(), ref)
            assertEquals(2L, ready.first.getValue("revision").jsonPrimitive.long)
            val rounds = requireNotNull(producer().captureDisplayedRoundsInTransaction())
            assertTrue(rounds.pendingRestarts.isEmpty())
            assertEquals(ref.head, rounds.completionRounds.head(habit.uuid))
            assertTrue(rounds.completionRounds.contains(habit.uuid, initialChallengeRoundHead(habit.uuid)))
        } }
        assertEquals(0, count("sync_outbox")); assertEquals(1, count("next_restart_materializations"))
        assertEquals(1, count("next_restart_plan_proofs"))
    }
    private fun reopen() { storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons) }
    private suspend fun refused(code: String, block: suspend () -> Unit) {
        val error = try { block(); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalArgumentException) { failure } catch (failure: IllegalStateException) { failure }
        assertEquals(code, error?.message)
    }
    private suspend fun authenticatedImage(): JsonObject = db.withTransaction {
        val image = NextReplicaStorageProof(db).readInTransaction().contents
        val root = CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase)
        val control = image.getValue("tables").jsonObject.getValue("count_trust_control").jsonArray.single().jsonObject
        assertEquals(setOf("__recovery_rowid", "id", "storageId", "generation", "dirty", "commandRootHash"), control.keys)
        assertEquals(buildJsonObject { put("sqlite_type", 3); put("value", CountCommandRootAnchor.digest(root.native())) }, control.getValue("commandRootHash"))
        image
    }
    private suspend fun onlyCommandChanged(before: JsonObject) {
        val after = authenticatedImage(); val oldTables = before.getValue("tables").jsonObject; val newTables = after.getValue("tables").jsonObject
        val control = JsonObject(oldTables.getValue("count_trust_control").jsonArray.single().jsonObject +
            ("commandRootHash" to newTables.getValue("count_trust_control").jsonArray.single().jsonObject.getValue("commandRootHash")))
        val excluded = CountCommandV20Schema.tables + "count_command_results"
        val expected = JsonObject(oldTables.filterKeys { it !in excluded } + ("count_trust_control" to JsonArray(listOf(control))))
        val actual = JsonObject(newTables.filterKeys { it !in excluded })
        assertTrue("No original fact/source/plan/control cell may change during invalidation/T2",
            JsonObject(before + ("tables" to expected)) == JsonObject(after + ("tables" to actual)))
    }
    private suspend fun requireOriginal(p: Pending) {
        val actual = row(p.ref)
        assertEquals(p.input.text(), actual.intent.text()); assertNull(actual.outcome)
        assertEquals(p.ref.inputHash, actual.intent.inputHash)
    }

    @Test(timeout = 150_000) fun actualAcceptedRestartMakesOldAddSelectionInvalidWithoutReassigningItsTimeOrRound() = runBlocking<Unit> {
        openRounds(); val pending = prepare(); val oldFact = acceptedCount(); pull()
        val owner = store(); val unchanged = image()
        assertNull(owner.prepareGenericInvalidation(pending.ref)); assertTrue(unchanged == image())
        val restart = propose(); val projection = image()
        assertNull(owner.prepareGenericInvalidation(pending.ref)); assertTrue(projection == image())
        acceptRestart(restart, pending)
        val before = authenticatedImage()
        refused("COMMAND_ORIGINAL_SELECTION_CHANGED") {
            creatingHabits().resumeCountCommandAfterCompleteProof(app, CountCommandReference.from(pending.ref))
        }
        assertTrue(before == image())
        val plan = requireNotNull(owner.prepareGenericInvalidation(pending.ref)); assertTrue(before == image())
        val result = owner.finishGenericInvalidation(plan)
        assertEquals(CountCommandInvalidationDomain.Reason.ORIGINAL_COUNT_ROUND_CHANGED, result.reason)
        assertEquals(CountCommandRecords.Execution.INVALIDATED, row(pending.ref).status.execution)
        assertEquals(CountCommandRecords.Delivery.RESULT_PENDING, row(pending.ref).status.delivery)
        requireOriginal(pending); onlyCommandChanged(before)
        owner.deliverInvalidatedGeneric(result) { true }; onlyCommandChanged(before)
        assertEquals(oldFact, db.completionDao().getCompletionById(oldFact.id)); assertEquals(1, count("completions"))
        assertEquals(0, count("count_command_results")); assertEquals(0, count("sync_outbox"))
        val reserved = CountCommandRecords.uuid(pending.input.body().getValue("input").jsonObject, "reserved_fact_uuid")
        assertNull(db.completionDao().getCompletionByUuid(reserved))
        reopen(); val fresh = store(); val terminal = requireNotNull(fresh.invalidatedGeneric(pending.ref)); assertFalse(terminal.pending)
        val stable = image(); fresh.deliverInvalidatedGeneric(terminal) { true }; assertTrue(stable == image()); requireOriginal(pending)
        assertTrue(server.requests.any { it.path == "/api/v2/sync/rounds/changes" })
        assertFalse(server.requests.any { it.path == "/api/v2/sync/push" })
    }

    @Test(timeout = 150_000) fun sameHeadAckAndContiguousMetadataProgressKeepTheOriginalPreparedCountExecutable() = runBlocking<Unit> {
        openRounds(); val pending = prepare(); val independent = acceptedCount(); pull()
        val before = image(); val owner = store()
        assertNull(owner.prepareGenericInvalidation(pending.ref)); assertTrue(before == image())
        val committed = creatingHabits().resumeCountCommandAfterCompleteProof(app, CountCommandReference.from(pending.ref)) as CountCommandRecovery.HistoricalCommitted
        assertEquals(pending.ref, committed.receipt.committed.reference); assertTrue(committed.receipt.recorded)
        assertEquals(pending.input.text(), row(pending.ref).intent.text())
        assertEquals(CountCommandRecords.Execution.APPLIED, row(pending.ref).status.execution)
        assertEquals(2, count("completions")); assertEquals(independent, db.completionDao().getCompletionById(independent.id))
        val input = pending.input.body().getValue("input").jsonObject
        val actual = requireNotNull(db.completionDao().getCompletionByUuid(CountCommandRecords.uuid(input, "reserved_fact_uuid")))
        assertEquals(Instant.parse(CountCommandRecords.text(input, "occurred_at")).toEpochMilli(), actual.actualCompletedAt)
        assertEquals(CountCommandRecords.text(input, "timezone"), actual.recordedTimezone)
        assertEquals(CountCommandRecords.text(input, "local_date"), actual.recordedLocalDate)
        assertEquals(initialChallengeRoundHead(habit.uuid), requireNotNull(editor.habit(habit.id).authority).rounds?.completionRounds?.head(habit.uuid))
        assertEquals(1, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun acceptedRoundInvalidatesOnlyPresentOldUndoTargetAndDoesNotGuessAMissingTargetFate() = runBlocking<Unit> {
        openRounds(); val target = acceptedCount(); pull(); val first = prepare(target); val missingLater = prepare(target)
        acceptRestart(propose()); val owner = store(); val before = authenticatedImage()
        val invalidated = owner.finishGenericInvalidation(requireNotNull(owner.prepareGenericInvalidation(first.ref)))
        assertEquals(CountCommandInvalidationDomain.Reason.ORIGINAL_COUNT_ROUND_CHANGED, invalidated.reason)
        assertEquals(target, db.completionDao().getCompletionById(target.id)); requireOriginal(first); onlyCommandChanged(before)
        owner.deliverInvalidatedGeneric(invalidated) { true }
        creatingHabits().undoCompletion(app, target.id, authority = requireNotNull(editor.habit(habit.id).authority))
        assertNull(db.completionDao().getCompletionById(target.id))
        val removed = image(); assertNull(owner.prepareGenericInvalidation(missingLater.ref)); assertTrue(removed == image())
        refused("COMMAND_ORIGINAL_SELECTION_CHANGED") {
            creatingHabits().resumeCountCommandAfterCompleteProof(app, CountCommandReference.from(missingLater.ref))
        }
        assertTrue(removed == image()); requireOriginal(missingLater)
        assertEquals(CountCommandRecords.Execution.PREPARED, row(missingLater.ref).status.execution)
        assertEquals(0, count("completions")); assertEquals(1, count("sync_outbox"))
    }

    @Test(timeout = 150_000) fun sameRoundTargetRemovedBeforeMutationKeepsOriginalLegalNoEffect() = runBlocking<Unit> {
        openRounds(); val target = acceptedCount(); pull(); val pending = prepare(target)
        creatingHabits().undoCompletion(app, target.id, authority = requireNotNull(editor.habit(habit.id).authority))
        assertNull(db.completionDao().getCompletionById(target.id))
        val removed = image(); assertNull(store().prepareGenericInvalidation(pending.ref)); assertTrue(removed == image())
        val noEffect = creatingHabits().resumeCountCommandAfterCompleteProof(app, CountCommandReference.from(pending.ref)) as CountCommandRecovery.HistoricalCommitted
        assertFalse(noEffect.receipt.recorded)
        assertEquals(CountCommandRecords.Execution.NO_EFFECT, row(pending.ref).status.execution)
        assertEquals(pending.input.text(), row(pending.ref).intent.text())
        assertEquals(0, count("completions")); assertEquals(1, count("sync_outbox"))
    }

    @Test(timeout = 150_000) fun changedAcceptedMetadataExpiresIssuedProofAndBrokenChainIsNeverInvalidated() = runBlocking<Unit> {
        openRounds(); val pending = prepare(); acceptedCount(); pull(); acceptRestart(propose())
        val owner = store(); val old = requireNotNull(owner.prepareGenericInvalidation(pending.ref))
        // A real same-head page still advances the validated marker/control generation.
        pull(); val progressed = image()
        refused("COMMAND_INVALIDATION_REVALIDATION_REQUIRED") { owner.finishGenericInvalidation(old) }
        assertTrue(progressed == image()); assertEquals(CountCommandRecords.Execution.PREPARED, row(pending.ref).status.execution)
        val fresh = requireNotNull(owner.prepareGenericInvalidation(pending.ref)); owner.releaseGenericInvalidation(fresh)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE next_challenge_rounds SET recordHash=? WHERE roundUuid=?", arrayOf("0".repeat(64), history.last().head.roundUuid))
            sql.query("SELECT changes()").use { assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)) }
        }
        val damaged = image()
        val error = try { owner.prepareGenericInvalidation(pending.ref); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalArgumentException) { failure } catch (failure: IllegalStateException) { failure }
        assertNotNull(error); assertTrue(damaged == image()); requireOriginal(pending)
        assertEquals(CountCommandRecords.Execution.PREPARED, row(pending.ref).status.execution)
    }

    @Test(timeout = 150_000) fun acceptedRestartOriginKeepsStrictProposalCodecAndExactOriginOperationBinding() = runBlocking<Unit> {
        openRounds(); val pending = prepare(); acceptedCount(); pull(); val restart = propose(); acceptRestart(restart)
        val original = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, restart.operationId))
        val body = Json.parseToJsonElement(original.intentJson).jsonObject
        val owner = store(); val positive = requireNotNull(owner.prepareGenericInvalidation(pending.ref))
        owner.releaseGenericInvalidation(positive)
        suspend fun replace(value: JsonObject) = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
                arrayOf(value.toString(), NEXT_OPERATION, restart.operationId))
            sql.query("SELECT changes()").use { assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)) }
        }
        replace(JsonObject(body + ("operationId" to JsonPrimitive(UUID.randomUUID().toString()))))
        val wrongBinding = image()
        refused("COMMAND_NO_EFFECTS_RESTART_ORIGIN_CHANGED") { owner.prepareGenericInvalidation(pending.ref) }
        assertTrue(wrongBinding == image()); requireOriginal(pending)
        replace(JsonObject(body + ("restart_proposal" to JsonPrimitive(2))))
        val malformed = image()
        val error = try { owner.prepareGenericInvalidation(pending.ref); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalArgumentException) { failure } catch (failure: IllegalStateException) { failure }
        assertNotNull(error); assertTrue(malformed == image()); requireOriginal(pending)
        assertEquals(CountCommandRecords.Execution.PREPARED, row(pending.ref).status.execution)
        replace(body)
        val accepted = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, restart.operationId))
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?",
                arrayOf("0".repeat(64), NEXT_OPERATION, restart.operationId))
            sql.query("SELECT changes()").use { assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)) }
        }
        val brokenReceipt = image()
        val receiptError = try { owner.prepareGenericInvalidation(pending.ref); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalArgumentException) { failure } catch (failure: IllegalStateException) { failure }
        assertNotNull(receiptError); assertTrue(brokenReceipt == image()); requireOriginal(pending)
        assertEquals(CountCommandRecords.Execution.PREPARED, row(pending.ref).status.execution)
        db.withTransaction { db.openHelper.writableDatabase.execSQL(
            "UPDATE next_acceptances SET resultHash=? WHERE kind=? AND requestId=?",
            arrayOf(accepted.resultHash, NEXT_OPERATION, restart.operationId)) }
        val valid = requireNotNull(owner.prepareGenericInvalidation(pending.ref))
        val result = owner.finishGenericInvalidation(valid)
        assertEquals(CountCommandInvalidationDomain.Reason.ORIGINAL_COUNT_ROUND_CHANGED, result.reason)
        requireOriginal(pending); assertEquals(1, count("completions")); assertEquals(0, count("count_command_results"))
    }
}
