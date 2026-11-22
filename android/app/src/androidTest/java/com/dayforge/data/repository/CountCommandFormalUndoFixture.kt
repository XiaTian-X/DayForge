package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.*
import com.dayforge.domain.service.SyncManager
import io.mockk.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.CountCommandStorageInitialization
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
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Independent formal undo C fixture. Real bootstrap mapper, ordinary/durable undo, and
 * SyncManager round ACK. Only negative corruption tests mutate source evidence. */
abstract class CountCommandFormalUndoFixture : NextObjectEditorFixture() {
    internal val fault = CountObsoleteFollowUpNativeBoundary()
    init { storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, fault) } }
    internal lateinit var parent: CountCommandReceipt
    internal var heldChild: NextObjectEditor.CountAttempt? = null
    internal val json = Json { encodeDefaults = true }
    internal val history = mutableListOf<ChallengeRoundRecord>()
    internal val births = mutableListOf<ChallengeBirth>()
    internal val changes = mutableListOf<SyncV2Change>()
    internal val events = mutableMapOf<String, NextSyncOperationResult>()
    internal lateinit var http: NextSyncHttp
    internal lateinit var server: MaterialSocketServer
    internal lateinit var serverPlan: HabitEntity
    internal lateinit var now: String
    internal var revision = 1L
    internal var cursor = 20L
    internal fun store() = CountCommandStore(db, tokens, sessions)
    internal suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    internal suspend fun row(ref: CountCommandStore.Reference) = store().resolve(ref).historical
    internal fun metadata() = ChallengeMetadata(1, listOf(
        ChallengeCheckpoint(history.last().head, history.toList()),
        ChallengeCheckpoint(initialChallengeRoundHead(timerHabit.uuid),
            listOf(ChallengeRoundRecord(initialChallengeRoundHead(timerHabit.uuid), null, null, null)))), births.toList())
    internal fun plan(value: HabitEntity, version: Long) = SyncV2Change(0, "plan_node", value.uuid, "upsert", version,
        JsonObject(NextStructureMapper.writePlan(value) + mapOf("public_id" to JsonPrimitive(value.uuid),
            "revision" to JsonPrimitive(version), "created_at" to JsonPrimitive(Instant.ofEpochMilli(value.createdAt).toString()),
            "updated_at" to JsonPrimitive(now), "deleted_at" to JsonNull)), now, id(4))
    internal fun merger() = NextSyncMergeStore(db, tokens, sessions,
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)),
        NextTimerRequestStore(db, tokens, sessions, sender(http)))
    internal fun restart() = NextChallengeRestartRepository(db, tokens, sessions, http)
    internal suspend fun openRounds() {
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
                request.path == "/api/v2/devices/register" -> {
                    val body = json.decodeFromString<DeviceRegisterRequest>(request.body.toString(Charsets.UTF_8))
                    MaterialSocketServer.Reply(json.encodeToString(DeviceResponse(id(4), body.installationId,
                        "android", isPrimaryEditor = true, structuralEditEnabled = true, capabilityRevision = 1,
                        capabilities = caps.toList(), lastSeenAt = now)).toByteArray())
                }
                request.path == "/api/v2/sync/rounds/changes" -> {
                    val from = request.target.substringAfter("cursor=").substringBefore('&').toLong()
                    val meta = metadata()
                    MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(changes.filter { it.sequence > from },
                        cursor, false, now, 1, meta.checkpoints, meta.births)).toByteArray())
                }
                request.path == "/api/v2/timers/rounds/commands" -> additionalTimerReply(request)
                else -> {
                    assertEquals("/api/v2/sync/rounds/push", request.path)
                    val wire = json.decodeFromString<RoundSyncPushRequest>(request.body.toString(Charsets.UTF_8))
                    val operation = wire.operations.single()
                    if (operation.entityType == "activity_event" && operation.payload["event_type"] == JsonPrimitive("revert")) {
                        if (revertResponse == RevertResponse.LOST) return@channel null
                        if (revertResponse == RevertResponse.REJECT) {
                            val meta = metadata()
                            val refused = NextSyncOperationResult(operation.operationId, operation.entityType,
                                operation.entityUuid, "rejected", null, errorCode = "INVALID_PAYLOAD")
                            return@channel MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(
                                listOf(refused), 1, meta.checkpoints, meta.births)).toByteArray())
                        }
                    }
                    if (operation.entityType in setOf("plan_node", "activity_metric_link", "metric_observation"))
                        return@channel additionalStructuralReply(request, wire)
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
    /** A separate deletion fixture supplies only its real structural wire peer. Existing
     * undo/restart tests retain their original routes and fail on unexpected structure. */
    internal open fun additionalStructuralReply(request: MaterialSocketServer.Input,
        wire: RoundSyncPushRequest): MaterialSocketServer.Reply? =
        error("ROUND_INVALIDATION_UNEXPECTED_OPERATION:${wire.operations.single().entityType}")

    /** Opt-in test peer only; every existing undo/deletion path still rejects timer endpoints. */
    internal open fun additionalTimerReply(request: MaterialSocketServer.Input): MaterialSocketServer.Reply? =
        error("ROUND_INVALIDATION_UNEXPECTED_TIMER_ENDPOINT:${request.path}")

    internal suspend fun propose(): NextRestartReference {
        val selected = editor.habit(habit.id)
        return restart().restart(requireNotNull(selected.value), requireNotNull(selected.authority))
    }
    internal fun reopen() { storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons) }
    internal suspend fun refused(code: String, block: suspend () -> Unit) {
        val error = try { block(); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalArgumentException) { failure } catch (failure: IllegalStateException) { failure }
        assertEquals(code, error?.message)
    }
    internal suspend fun authenticatedImage(): JsonObject = db.withTransaction {
        val image = NextReplicaStorageProof(db).readInTransaction().contents
        val root = CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase)
        val control = image.getValue("tables").jsonObject.getValue("count_trust_control").jsonArray.single().jsonObject
        assertEquals(setOf("__recovery_rowid", "id", "storageId", "generation", "dirty", "commandRootHash"), control.keys)
        assertEquals(buildJsonObject { put("sqlite_type", 3); put("value", CountCommandRootAnchor.digest(root.native())) }, control.getValue("commandRootHash"))
        image
    }
    internal fun pendingRepository(): CountCommandPendingRepository {
        val reader = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
        val legacy = LegacyWidgetFactWorkflow(db, tokens, sessions,
            LegacyWidgetIdentityVerifier(db, tokens, ReplicaPresentationRepository(db, tokens, sessions)), creatingHabits(),
            com.dayforge.domain.service.StructuralEditGuard(tokens, io.mockk.mockk()))
        return CountCommandPendingRepository(db, tokens, sessions, creatingHabits(), legacy, reader, preferences)
    }
    internal suspend fun resolvedClosed(ref: CountCommandReference) {
        val consumer = pendingRepository()
        val result = consumer.resume(app, consumer.prepare(ref))
        assertTrue(result is CountCommandPendingRepository.Result.FollowUpEnded)
        assertEquals(ref, (result as CountCommandPendingRepository.Result.FollowUpEnded).reference)
    }
    internal suspend fun prefImage() = dataStore.data.first().asMap().mapKeys { it.key.name }
    internal suspend fun childPrepared(release: Boolean = true): CountCommandReference {
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
    internal suspend fun pending(owner: CountCommandStore) {
        assertTrue(owner.resolve(parent.reference.native()).historical.status.pending)
        assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING, row(parent.reference.native()).status.delivery)
    }
    internal enum class RevertResponse { ACCEPT, LOST, REJECT }
    internal var revertResponse = RevertResponse.ACCEPT
    internal lateinit var originalFact: CompletionEntity
    internal lateinit var revert: com.dayforge.data.local.entity.SyncOutboxEntity
    internal var undo: CountCommandReceipt? = null

    /** The actual stable sync entry drives real original send, response, ACK and pull. */
    internal suspend fun syncAll(expectSuccess: Boolean = true) {
        val monitor = mockk<NetworkMonitor>()
        coEvery { monitor.localNetworks() } returns emptyList()
        val legacy = mockk<IncrementalSyncRepository>(relaxed = true)
        val endpoints = EndpointResolver(monitor, preferences, tokens, json, SelectedNetworkTransport())
        val manager = SyncManager(legacy, BusinessSyncRepository(legacy, endpoints, db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences)))
        val result = manager.sync()
        if (expectSuccess) result.getOrThrow()
        coVerify(exactly = 0) { legacy.syncForAuthentication(any(), any(), any()) }
    }
    internal suspend fun add(ack: Boolean = false) {
        openRounds()
        val selected = editor.habit(habit.id)
        parent = requireNotNull(creatingHabits().logCompletionWithReceipt(app, habit.id,
            authority = requireNotNull(selected.authority), expectedHabitUuid = habit.uuid).command)
        assertTrue(parent.recorded); assertTrue(parent.requiresMetricFollowUp)
        originalFact = requireNotNull(db.completionDao().getCompletionByUuid(requireNotNull(parent.factUuid)))
        val before = row(parent.reference.native())
        if (ack) {
            syncAll(); assertEquals(0, count("sync_outbox")); assertEquals(1, count("next_acceptances"))
            val normalized = requireNotNull(db.completionDao().getCompletionByUuid(originalFact.uuid))
            assertNotEquals("Real ACK exercises the mapper's server created_at normalization", originalFact.createdAt, normalized.createdAt)
            originalFact = normalized
            val after = row(parent.reference.native())
            assertEquals(before.intent.text(), after.intent.text()); assertEquals(before.outcome!!.text(), after.outcome!!.text())
        }
        pending(store())
    }
    internal suspend fun undoOriginal(durable: Boolean, ack: Boolean = false) {
        val selected = editor.habit(habit.id)
        val beforeCommands = count("count_command_intents")
        val acceptedBefore = count("next_acceptances"); val birthsBefore = count("next_challenge_births")
        if (durable) undo = requireNotNull(creatingHabits().undoCompletionWithReceipt(app, originalFact.id,
            authority = requireNotNull(selected.authority)).command)
        else creatingHabits().undoCompletion(app, originalFact.id, authority = requireNotNull(selected.authority))
        assertEquals(beforeCommands + if (durable) 1 else 0, count("count_command_intents"))
        assertNull(db.completionDao().getCompletionByUuid(originalFact.uuid))
        revert = db.syncOutboxDao().getAll().single { it.action == "delete" && it.entityUuid == originalFact.uuid }
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, revert.operationId))
        val wire = requireNotNull(roundOperationIntent(origin.intentJson))
        assertEquals(JsonPrimitive(originalFact.uuid), wire.operation.payload["reverts_event_uuid"])
        assertEquals(JsonPrimitive(habit.uuid), wire.operation.payload["activity_uuid"])
        assertNotEquals(originalFact.uuid, wire.operation.entityUuid)
        sessions.exclusive { db.withTransaction {
            assertEquals(NextRecoveryRequestState.NEVER_SENT,
                NextOriginalRequestProofStore(db).pending(access(), NEXT_OPERATION, revert.operationId).state)
        } }
        if (durable) {
            val saved = row(requireNotNull(undo).reference.native())
            assertEquals(CountCommandRecords.Action.COUNT_UNDO, saved.intent.action)
            assertEquals(CountCommandRecords.Execution.APPLIED, saved.status.execution)
            assertNotNull(saved.outcome)
            assertEquals(CountCommandOutcome.FactKind.COUNT_REVERT, requireNotNull(saved.outcome).effects.single().factKind)
        }
        if (ack) {
            syncAll(); assertNull(db.syncOutboxDao().getByOperationId(revert.operationId))
            sessions.exclusive { db.withTransaction {
                val accepted = NextAcceptedFactProofStore(db).read(access(), revert.operationId)
                assertEquals(wire.operation, accepted.first)
            } }
            assertEquals(acceptedBefore + 1, count("next_acceptances")); assertEquals(birthsBefore + 1, count("next_challenge_births"))
        }
    }
    internal suspend fun advanceRound() {
        val next = propose(); syncAll()
        assertEquals(next.head, requireNotNull(editor.habit(habit.id).authority).rounds!!.completionRounds.head(habit.uuid))
        assertEquals(1, count("next_restart_materializations")); assertEquals(1, count("next_restart_plan_proofs"))
    }
    internal suspend fun decision(owner: CountCommandStore = store()): CountCommandStore.ObsoleteFollowUp {
        val before = image(); val preferencesBefore = prefImage()
        val proof = requireNotNull(owner.obsoleteFollowUp(parent.reference.native(), preferences))
        assertEquals(CountCommandObsoleteFollowUp.Reason.FORMAL_ORIGINAL_COUNT_REVERTED, proof.reason)
        assertTrue(proof.preferenceReady); assertEquals(parent.reference.native(), proof.reference)
        sameImage(before, image()); assertEquals(preferencesBefore, prefImage()); pending(owner)
        return proof
    }
    internal suspend fun rejectedObservation() {
        val before = image(); val prefs = prefImage()
        val result = try { store().obsoleteFollowUp(parent.reference.native(), preferences) }
            catch (cancel: CancellationException) { throw cancel } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }
        assertNull("Insufficient/damaged evidence cannot issue C", result)
        sameImage(before, image()); assertEquals(prefs, prefImage())
        db.openHelper.writableDatabase.query("SELECT execution,delivery FROM count_command_status WHERE commandId=?",
            arrayOf(parent.commandId)).use {
            assertTrue(it.moveToFirst()); assertEquals("APPLIED", it.getString(0)); assertEquals("FOLLOW_UP_PENDING", it.getString(1)); assertFalse(it.moveToNext())
        }
    }
    internal suspend fun mutation(sql: String, vararg values: Any?) = db.withTransaction {
        if (values.isEmpty()) db.openHelper.writableDatabase.execSQL(sql)
        else db.openHelper.writableDatabase.execSQL(sql, values)
    }
    internal fun sameImage(before: JsonObject, after: JsonObject) {
        if (before == after) return
        val b = before.getValue("tables").jsonObject; val a = after.getValue("tables").jsonObject
        val changed = (b.keys + a.keys).filter { b[it] != a[it] }.take(8)
        fail("Full schema/main image differs; first changed tables=$changed; schemaChanged=${before["schema"] != after["schema"]}")
    }
    internal fun statuses(image: JsonObject): Map<String, CountCommandRecords.Status> =
        image.getValue("tables").jsonObject.getValue("count_command_status").jsonArray.associate { item ->
            val text = item.jsonObject.getValue("stateJson").jsonObject.getValue("value").jsonPrimitive.content
            val bytes = text.toByteArray(Charsets.UTF_8)
            val decoded = CountCommandRecords.Status.decode(bytes, bytes.size) { }
            decoded.commandId to decoded
        }
    internal fun cell(value: Any?): JsonObject = buildJsonObject {
        put("sqlite_type", when (value) { null -> 0; is Long, is Int -> 1; else -> 3 })
        when (value) { null -> put("value", JsonNull); is Long -> put("value", value); is Int -> put("value", value); else -> put("value", value.toString()) }
    }
    /** Independent expected linked-list edits; never calls production transition/pending rules. */
    internal suspend fun onlyCChanged(before: JsonObject, child: CountCommandReference? = null) {
        val after = authenticatedImage(); val old = before.getValue("tables").jsonObject; val fresh = after.getValue("tables").jsonObject
        val expected = statuses(before).toMutableMap()
        for (id in listOfNotNull(child?.commandId, parent.commandId)) {
            val row = expected.getValue(id)
            row.previous?.let { previous -> val p = expected.getValue(previous); expected[previous] = p.copy(revision = Math.addExact(p.revision, 1), next = row.next) }
            row.next?.let { next -> val n = expected.getValue(next); expected[next] = n.copy(revision = Math.addExact(n.revision, 1), previous = row.previous) }
            expected[id] = row.copy(revision = Math.addExact(row.revision, 1), delivery = CountCommandRecords.Delivery.CLOSED,
                previous = null, next = null, execution = if (id == parent.commandId) row.execution else CountCommandRecords.Execution.INVALIDATED,
                reason = if (id == parent.commandId) row.reason else "EXPLICIT_OBSOLETE_FOLLOWUP_AFTER_COMPLETE_NO_EFFECTS")
        }
        assertEquals(expected, statuses(after))
        val statusRows = old.getValue("count_command_status").jsonArray.map { native ->
            val row = native.jsonObject; val id = row.getValue("commandId").jsonObject.getValue("value").jsonPrimitive.content
            val state = expected.getValue(id)
            JsonObject(row + mapOf("revision" to cell(state.revision), "execution" to cell(state.execution.name),
                "delivery" to cell(state.delivery.name), "previousPendingId" to cell(state.previous), "nextPendingId" to cell(state.next),
                "stateJson" to cell(CountCommandRecords.canonical(state.body()))))
        }
        assertEquals(JsonArray(statusRows), fresh.getValue("count_command_status"))
        val oldRoot = old.getValue("count_command_recovery_roots").jsonArray.single().jsonObject
        val newRoot = fresh.getValue("count_command_recovery_roots").jsonArray.single().jsonObject
        val mutableRoot = setOf("catalogHash", "catalogHeight", "pendingHeadId", "pendingTailId", "pendingCount", "revision", "mac")
        assertEquals(oldRoot.filterKeys { it !in mutableRoot }, newRoot.filterKeys { it !in mutableRoot })
        fun integer(row: JsonObject, key: String) = row.getValue(key).jsonObject.getValue("value").jsonPrimitive.long
        val removed = if (child == null) 1L else 2L
        assertEquals(integer(oldRoot, "revision") + removed, integer(newRoot, "revision"))
        assertEquals(integer(oldRoot, "pendingCount") - removed, integer(newRoot, "pendingCount"))
        val oldNodes = old.getValue("count_command_nodes").jsonArray
        val newNodes = fresh.getValue("count_command_nodes").jsonArray
        assertTrue("All original native nodes survive C", newNodes.containsAll(oldNodes))
        val oldControl = old.getValue("count_trust_control").jsonArray.single().jsonObject
        val newControl = fresh.getValue("count_trust_control").jsonArray.single().jsonObject
        val expectedControl = JsonObject(oldControl + ("commandRootHash" to newControl.getValue("commandRootHash")))
        val expectedTables = JsonObject(old + mapOf("count_command_status" to JsonArray(statusRows),
            "count_command_recovery_roots" to fresh.getValue("count_command_recovery_roots"),
            "count_command_nodes" to fresh.getValue("count_command_nodes"),
            "count_trust_control" to JsonArray(listOf(expectedControl))))
        // Intents/results, all source/ACK/round/fact/prefs-related SQL, sqlite_sequence and every schema cell remain exact.
        sameImage(JsonObject(before + ("tables" to expectedTables)), after)
    }
    internal suspend fun endAndCold(owner: CountCommandStore, proof: CountCommandStore.ObsoleteFollowUp,
        child: CountCommandReference? = null) {
        val before = authenticatedImage(); val prefs = prefImage()
        assertEquals(parent.reference.native(), owner.endObsoleteFollowUp(proof, preferences) { true }.reference)
        onlyCChanged(before, child); assertEquals(prefs, prefImage())
        assertEquals(0, count("completions")); assertEquals(0, count("metric_logs"))
        repeat(2) {
            reopen(); val stable = image()
            assertEquals(parent.reference.native(), requireNotNull(store().closedFollowUp(parent.reference.native())).reference)
            child?.let { assertEquals(it.native(), requireNotNull(store().closedFollowUp(it.native())).reference) }
            resolvedClosed(parent.reference); child?.let { resolvedClosed(it) }
            sameImage(stable, image()); assertEquals(prefs, prefImage())
        }
    }
}
