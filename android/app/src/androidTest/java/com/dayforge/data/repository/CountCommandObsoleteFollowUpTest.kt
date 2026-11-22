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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.edit
import com.dayforge.data.local.HabitDevicePreferences
import com.dayforge.domain.model.StableHabitBinding
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real formal bootstrap, original local restart origin/materialization/ACK and contiguous
 * accepted Plan log. No head seeding, original-input rewrite, or exception-to-invalid adapter. */
@RunWith(AndroidJUnit4::class)
class CountCommandObsoleteFollowUpTest : NextObjectEditorFixture() {
    private val fault = CountObsoleteFollowUpNativeBoundary()
    init { storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, fault) } }
    private lateinit var parent: CountCommandReceipt
    private var heldChild: NextObjectEditor.CountAttempt? = null
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
        val link = HabitMetricLinkEntity(habitId = 0, habitUuid = habit.uuid, metricId = 0, metricUuid = metric.uuid,
            promptOnComplete = true, uuid = id(889), createdAt = habit.createdAt, updatedAt = habit.updatedAt)
        val linkChange = SyncV2Change(0, "activity_metric_link", link.uuid, "upsert", 1,
            JsonObject(SyncV2Mapper.link(link) + mapOf("public_id" to JsonPrimitive(link.uuid), "revision" to JsonPrimitive(1),
                "created_at" to JsonPrimitive(Instant.ofEpochMilli(link.createdAt).toString()), "updated_at" to JsonPrimitive(now), "deleted_at" to JsonNull)), now)
        merger().bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(plan(habit, 1), plan(timerHabit, 1), metricChange, linkChange),
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
        val saved = creatingHabits().logCompletionWithReceipt(app, habit.id, authority = requireNotNull(selected.authority), expectedHabitUuid = habit.uuid)
        parent = requireNotNull(saved.command)
        assertTrue(parent.recorded); assertTrue(parent.requiresMetricFollowUp)
        val fact = requireNotNull(db.completionDao().getCompletionByUuid(requireNotNull(parent.factUuid)))
        val id = fact.id
        assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING, row(parent.reference.native()).status.delivery)
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

    private fun pendingRepository(): CountCommandPendingRepository {
        val reader = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
        val legacy = LegacyWidgetFactWorkflow(db, tokens, sessions,
            LegacyWidgetIdentityVerifier(db, tokens, ReplicaPresentationRepository(db, tokens, sessions)), creatingHabits(),
            com.dayforge.domain.service.StructuralEditGuard(tokens, io.mockk.mockk()))
        return CountCommandPendingRepository(db, tokens, sessions, creatingHabits(), legacy, reader, preferences)
    }
    private suspend fun resolvedClosed(ref: CountCommandReference) {
        val consumer = pendingRepository()
        val result = consumer.resume(app, consumer.prepare(ref))
        assertTrue(result is CountCommandPendingRepository.Result.FollowUpEnded)
        assertEquals(ref, (result as CountCommandPendingRepository.Result.FollowUpEnded).reference)
    }
    private suspend fun prefImage() = dataStore.data.first().asMap().mapKeys { it.key.name }
    private suspend fun ready(): CountCommandStore {
        openRounds(); acceptedCount(); pull(); acceptRestart(propose())
        return store()
    }
    private suspend fun childPrepared(release: Boolean = true): CountCommandReference {
        val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        val source = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions)).read(current)
        assertEquals(parent.factUuid, source.claim.completionUuid)
        val selected = editor.metric(metric.id)
        val attempt = editor.beginMetricChild(parent, requireNotNull(selected.authority), current, source.claim,
            listOf(MetricValueDraft(metric.id, 2.5, "original pending input")), listOf(requireNotNull(selected.value)),
            db.habitMetricLinkDao().getAllLinksForHabit(habit.id), System.currentTimeMillis(), ZoneId.systemDefault().id)
        if (release) editor.releaseUnexecuted(attempt) else heldChild = attempt
        return CountCommandReference(attempt.input.commandId, attempt.input.inputHash, attempt.input.namespace.hash, attempt.input.namespace.dataset)
    }
    private suspend fun pending(owner: CountCommandStore) {
        assertTrue(owner.resolve(parent.reference.native()).historical.status.pending)
        assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING, row(parent.reference.native()).status.delivery)
    }
    @Test(timeout = 150_000) fun acceptedSuccessorNeedsExplicitDecisionAndPreservesOriginalCountAcrossColdResolution() = runBlocking<Unit> {
        openRounds(); acceptedCount(); pull(); val owner = store()
        val sameHead = image(); assertNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)); assertTrue(sameHead == image())
        val restart = propose(); val localProjection = image()
        assertNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)); assertTrue(localProjection == image())
        acceptRestart(restart)
        val old = row(parent.reference.native()); val before = authenticatedImage(); val prefs = prefImage()
        val proof = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences))
        assertTrue(proof.preferenceReady); assertTrue("Complete native main image must remain unchanged", before == image()); pending(owner)
        assertEquals(CountCommandObsoleteFollowUp.Reason.FORMAL_ACCEPTED_ROUND_SUCCESSOR, proof.reason)
        val closed = owner.endObsoleteFollowUp(proof, preferences) { true }
        assertEquals(parent.reference.native(), closed.reference); onlyCommandChanged(before); assertEquals(prefs, prefImage())
        val terminal = row(parent.reference.native())
        assertEquals(CountCommandRecords.Delivery.CLOSED, terminal.status.delivery)
        assertEquals(old.intent.text(), terminal.intent.text()); assertEquals(requireNotNull(old.outcome).text(), requireNotNull(terminal.outcome).text())
        assertEquals(1, count("completions")); assertEquals(0, count("metric_logs")); assertEquals(1, count("count_command_results"))
        val stable = image(); refused("COMMAND_FOLLOWUP_REVALIDATION_REQUIRED") { owner.endObsoleteFollowUp(proof, preferences) { true } }
        assertTrue("Complete native main image must remain unchanged", stable == image())
        reopen(); val fresh = store(); val afterReopen = image()
        assertEquals(parent.reference.native(), requireNotNull(fresh.closedFollowUp(parent.reference.native())).reference)
        assertNull(fresh.obsoleteFollowUp(parent.reference.native(), preferences)); assertTrue("Complete native main image must remain unchanged", afterReopen == image())
        assertTrue(fresh.discover(32).commands.isEmpty())
    }
    @Test(timeout = 150_000) fun preparedWholeMetricChildClosesAtomicallyWithBothOriginalIdsAndNoMetricOutcome() = runBlocking<Unit> {
        openRounds(); acceptedCount(); pull(); val child = childPrepared(release = false); val original = row(child.native()).intent.text()
        acceptRestart(propose()); val owner = store(); val before = authenticatedImage(); val prefs = prefImage()
        assertNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)); assertTrue("Complete native main image must remain unchanged", before == image())
        editor.releaseUnexecuted(requireNotNull(heldChild)); heldChild = null
        val wrongParent = parent.reference.native().copy(inputHash = "0".repeat(64).takeUnless { it == parent.reference.inputHash } ?: "1".repeat(64))
        val wrongChild = child.native().copy(inputHash = "0".repeat(64).takeUnless { it == child.inputHash } ?: "1".repeat(64))
        refused("COMMAND_FOLLOWUP_REFERENCE_CHANGED") { owner.obsoleteFollowUp(wrongParent, preferences) }
        refused("COMMAND_FOLLOWUP_REFERENCE_CHANGED") { owner.closedFollowUp(wrongParent) }
        refused("COMMAND_FOLLOWUP_REFERENCE_CHANGED") { owner.obsoleteFollowUp(wrongChild, preferences) }
        assertTrue("Complete native main image must remain unchanged", before == image())
        val parentOriginal = row(parent.reference.native()).intent.text(); val outcome = requireNotNull(row(parent.reference.native()).outcome).text()
        owner.endObsoleteFollowUp(requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)), preferences) { true }
        onlyCommandChanged(before); assertEquals(prefs, prefImage())
        val childRow = row(child.native()); val parentRow = row(parent.reference.native())
        assertEquals(CountCommandRecords.Execution.INVALIDATED, childRow.status.execution)
        assertEquals(CountCommandRecords.Delivery.CLOSED, childRow.status.delivery); assertNull(childRow.outcome)
        assertEquals(CountCommandTransitionRules.OBSOLETE_FOLLOWUP_CHILD_REASON, childRow.status.reason)
        assertEquals(child.commandId, parentRow.status.child); assertEquals(parent.commandId, childRow.status.parent)
        assertEquals(original, childRow.intent.text()); assertEquals(parentOriginal, parentRow.intent.text()); assertEquals(outcome, requireNotNull(parentRow.outcome).text())
        assertEquals(0, count("metric_logs")); assertEquals(1, count("count_command_results")); assertEquals(1, count("completions"))
        reopen(); val fresh = store(); val stable = image()
        assertEquals(child.native(), requireNotNull(fresh.closedFollowUp(child.native())).reference)
        assertEquals(parent.reference.native(), requireNotNull(fresh.closedFollowUp(parent.reference.native())).reference)
        refused("COMMAND_FOLLOWUP_REFERENCE_CHANGED") { fresh.closedFollowUp(wrongParent) }
        refused("COMMAND_FOLLOWUP_REFERENCE_CHANGED") { fresh.closedFollowUp(wrongChild) }
        assertTrue("Complete native main image must remain unchanged", stable == image())
        assertEquals(parent.commandId, requireNotNull(fresh.linkedChild(parent.reference.native())).historical.status.parent)
        resolvedClosed(parent.reference); resolvedClosed(child)
        assertTrue(fresh.discover(32).commands.isEmpty()); assertTrue("Complete native main image must remain unchanged", stable == image())
    }
    @Test(timeout = 150_000) fun existingAndNewPreferenceHintsNeverGetClearedOrTerminalized() = runBlocking<Unit> {
        val owner = ready(); val session = requireNotNull(tokens.localCoreWriteAccess()).session
        val key = HabitDevicePreferences.key(StableHabitBinding.capture(session, habit.uuid))
        val old = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences))
        for (value in listOf(HabitDevicePreferences(StableHabitBinding.capture(session, habit.uuid), pending = true, pendingEpoch = session.syncEpoch).encode(), "broken original hint")) {
            dataStore.edit { it[key] = value }
            val before = image(); val prefs = prefImage()
            val display = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)); assertFalse(display.preferenceReady)
            refused("COMMAND_FOLLOWUP_PREFERENCE_UNRESOLVED") { owner.endObsoleteFollowUp(old, preferences) { true } }
            refused("COMMAND_FOLLOWUP_PREFERENCE_UNRESOLVED") { owner.endObsoleteFollowUp(display, preferences) { true } }
            assertTrue("Complete native main image must remain unchanged", before == image()); assertEquals(prefs, prefImage()); pending(owner)
        }
        dataStore.edit { it.remove(key) }
        owner.endObsoleteFollowUp(requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)), preferences) { true }
    }
    @Test(timeout = 150_000) fun changedBusinessAndAuthorityCannotBeRefreshedIntoAnOldExplicitDecision() = runBlocking<Unit> {
        val owner = ready(); val old = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences))
        db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id)) }
        val before = image(); refused("COMMAND_FOLLOWUP_REVALIDATION_REQUIRED") { owner.endObsoleteFollowUp(old, preferences) { true } }
        assertTrue("Complete native main image must remain unchanged", before == image())
        val fresh = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)); register(revision = 2)
        val sameBusiness = image(); refused("COMMAND_FOLLOWUP_AUTHORITY_CHANGED") { owner.endObsoleteFollowUp(fresh, preferences) { true } }
        assertTrue("Complete native main image must remain unchanged", sameBusiness == image()); pending(owner)
        val beforePermissionChange = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences))
        register(permissions = caps - "facts.append", revision = 3)
        refused("COMMAND_FOLLOWUP_AUTHORITY_CHANGED") { owner.endObsoleteFollowUp(beforePermissionChange, preferences) { true } }
        assertTrue("Complete native main image must remain unchanged", sameBusiness == image()); pending(owner); register(revision = 3)
        owner.endObsoleteFollowUp(requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)), preferences) { true }
    }
    @Test(timeout = 150_000) fun inactiveConsumerAndCancelledActualRootWriteRetainCompletePendingGroup() = runBlocking<Unit> {
        val owner = ready(); val proof = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)); val before = image()
        refused("COMMAND_RECOVERY_CONSUMER_CLOSED") { owner.endObsoleteFollowUp(proof, preferences) { false } }
        assertTrue("Complete native main image must remain unchanged", before == image())
        var returned = false
        supervisorScope {
            val operation = launch(start = CoroutineStart.LAZY) {
                withContext(fault.cohort.asContextElement(true)) { owner.endObsoleteFollowUp(proof, preferences) { true } }
                returned = true
                fail("cancelled C may not return success")
            }
            fault.cancelAfterRoot(operation); operation.start(); operation.join(); assertTrue(operation.isCancelled)
        }
        assertFalse("Cancelled C may not return success even if a supervised child fails", returned)
        assertEquals(1, fault.cancelled.get()); assertEquals(1, fault.rootWrites.get())
        assertTrue("Complete native main image must remain unchanged", before == image()); pending(owner)
        owner.endObsoleteFollowUp(requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)), preferences) { true }
    }
    @Test(timeout = 150_000) fun lostActualClosingEndResolvesBothOriginalIdsFromTheNewOwnerWithoutAnotherWrite() = runBlocking<Unit> {
        openRounds(); acceptedCount(); pull(); val child = childPrepared(); acceptRestart(propose())
        val owner = store(); val proof = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences))
        val before = authenticatedImage(); val prefs = prefImage(); val oldOwner = com.dayforge.data.local.CountTrustRuntime.forDatabase(db)
        fault.loseAfterRootEnd(); var returned = false
        val failure = try { withContext(fault.cohort.asContextElement(true)) {
            owner.endObsoleteFollowUp(proof, preferences) { true }; returned = true
        }; null } catch (cancel: CancellationException) { throw cancel } catch (error: IllegalStateException) { error }
        fault.requireLostEnd(requireNotNull(failure)); assertFalse(returned)
        assertTrue(com.dayforge.data.local.CountTrustRetirementReason.UNKNOWN_END in requireNotNull(oldOwner).retirementReasons)
        reopen(); assertNotSame(oldOwner, com.dayforge.data.local.CountTrustRuntime.forDatabase(db))
        val fresh = store(); val after = image()
        assertEquals(parent.reference.native(), requireNotNull(fresh.closedFollowUp(parent.reference.native())).reference)
        assertEquals(child.native(), requireNotNull(fresh.closedFollowUp(child.native())).reference)
        resolvedClosed(parent.reference); resolvedClosed(child)
        assertTrue("Complete native main image must remain unchanged", after == image()); onlyCommandChanged(before); assertEquals(prefs, prefImage())
        assertEquals(0, count("metric_logs")); assertEquals(1, count("completions")); assertEquals(1, count("count_command_results"))
    }
    @Test(timeout = 150_000) fun reservedPartialChildEffectCannotBeMistakenForAnUnsubmittedGroup() = runBlocking<Unit> {
        openRounds(); acceptedCount(); pull(); val child = childPrepared(); acceptRestart(propose())
        val old = row(child.native()); val item = old.intent.body().getValue("input").jsonObject.getValue("metrics").jsonArray.single().jsonObject
        val reserved = CountCommandRecords.uuid(item, "reserved_fact_uuid")
        // Fixed native partial-effect corruption, not a replacement business producer.
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val zone = ZoneId.systemDefault(); val at = Instant.now(); val date = at.atZone(zone).toLocalDate()
            db.metricLogDao().insert(com.dayforge.data.local.entity.MetricLogEntity(metricId = metric.id,
                date = at.toEpochMilli(), value = 2.5, unit = metric.unit, note = "partial child",
                uuid = reserved, createdAt = at.toEpochMilli(), updatedAt = at.toEpochMilli(),
                recordedTimezone = zone.id, recordedLocalDate = date.toString(), timeMetadataSource = "device"))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val before = image(); val prefs = prefImage(); val owner = store()
        refused("COMMAND_EFFECT_OR_SOURCE_ALREADY_PRESENT") { owner.obsoleteFollowUp(parent.reference.native(), preferences) }
        assertTrue("Complete native main image must remain unchanged", before == image()); assertEquals(prefs, prefImage()); pending(owner)
        assertEquals(CountCommandRecords.Execution.PREPARED, row(child.native()).status.execution)
        assertEquals(old.intent.text(), row(child.native()).intent.text()); assertNull(row(child.native()).outcome)
        assertEquals(1, count("metric_logs"))
    }
    @Test(timeout = 150_000) fun appliedChildRemainsHistoricalBAndCannotAcquireAnExplicitEndObservation() = runBlocking<Unit> {
        openRounds(); acceptedCount(); pull()
        val source = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
            .read(requireNotNull(db.habitDao().getVisibleHabitById(habit.id)))
        val target = editor.metric(metric.id)
        val child = requireNotNull(creatingMetrics().recordValuesWithReceipt(listOf(MetricValueDraft(metric.id, 3.0, "saved")),
            System.currentTimeMillis(), authority = requireNotNull(target.authority), expectedMetrics = listOf(requireNotNull(target.value)),
            promptHabit = source.habit, promptFact = source.claim, promptCompletionSource = true, parentCommand = parent).command)
        val owner = store(); val before = image()
        assertNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)); assertTrue("Complete native main image must remain unchanged", before == image())
        val history = requireNotNull(owner.completedMetricGroup(child.reference.native(), preferences)); assertEquals(1, history.count)
        assertTrue(history.preferenceReady); assertTrue("Complete native main image must remain unchanged", before == image())
        assertEquals(CountCommandRecords.Delivery.METRICS_COMMITTED, row(parent.reference.native()).status.delivery)
        assertEquals(CountCommandRecords.Execution.APPLIED, row(child.reference.native()).status.execution)
    }
    @Test(timeout = 150_000) fun acceptedRestartWithoutItsOriginalPlanLogCannotIssueAnEndObservation() = runBlocking<Unit> {
        openRounds(); acceptedCount(); pull(); val proposal = propose()
        assertEquals(NextRestartOutcome.COMMITTED, restart().sendAndAccept(access(), proposal.operationId))
        assertEquals(0, count("next_restart_plan_proofs")); val before = image(); val owner = store()
        refused("CAUSAL_PREDECESSOR_PENDING") { owner.obsoleteFollowUp(parent.reference.native(), preferences) }
        assertTrue("Complete native main image must remain unchanged", before == image()); pending(owner)
        pull(); assertEquals(1, count("next_restart_plan_proofs"))
        assertNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)); pending(owner)
    }

    @Test(timeout = 150_000) fun legacyHabitOnlyPromptRemainsPendingAfterItsOriginalFactWasLegallyUndone() = runBlocking<Unit> {
        tokens.getOrCreateInstallationId(); register()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        parent = requireNotNull(creatingHabits().logCompletionWithReceipt(app, habit.id,
            countIdentity = CountActionReader(db, tokens, sessions).legacy(habit)).command)
        val fact = requireNotNull(db.completionDao().getCompletionByUuid(requireNotNull(parent.factUuid)))
        creatingHabits().undoCompletion(app, fact.id, countIdentity = CountActionReader(db, tokens, sessions)
            .legacy(requireNotNull(db.habitDao().getVisibleHabitById(habit.id))))
        assertNull(db.completionDao().getCompletionByUuid(fact.uuid)); assertNotNull(db.habitDao().getVisibleHabitById(habit.id))
        val owner = store(); val before = image(); val original = row(parent.reference.native())
        assertNull(owner.obsoleteFollowUp(parent.reference.native(), preferences)); assertTrue("Complete native main image must remain unchanged", before == image())
        pending(owner); assertEquals(original.intent.text(), row(parent.reference.native()).intent.text())
        assertEquals(requireNotNull(original.outcome).text(), requireNotNull(row(parent.reference.native()).outcome).text())
    }

}
