package com.dayforge.data.repository

import android.net.ConnectivityManager
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.model.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Actual private legacy producer, original v4 folding/ACK and same-ID recovery. Scripted
 * loopback server is a transport boundary, not NAS/server idempotency or process-death proof.
 * All ordinary helper/DAO/producer operations remain real; no suspend production spy.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandLegacyLifecycleTest : NextObjectEditorFixture() {
    private val json = Json { ignoreUnknownKeys = true }
    private val networkMonitors = arrayListOf<NetworkMonitor>()
    private val pushes = arrayListOf<JsonObject>()
    private val legacyCaps get() = caps + "facts.write"
    private var serverIdentity = ""
    private var epochIdentity = ""
    private var deviceIdentity = ""
    @After fun closeLegacyNetworkMonitors() { networkMonitors.forEach { it.close() } }

    private suspend fun legacy(countdown: Boolean = false, opaque: Boolean = false) {
        if (opaque) {
            db.clearAllData(); reopen()
            tokens.saveLoginSession("synthetic-opaque-access", "synthetic-opaque-refresh", "member", "account-a", false)
        }
        serverIdentity = if (opaque) "server-a" else id(2)
        epochIdentity = if (opaque) "epoch-a" else id(3)
        deviceIdentity = if (opaque) "device-a" else id(4)
        tokens.prepareSyncAccount(if (opaque) "account-a" else id(1)); tokens.saveServerIdentity(serverIdentity, epochIdentity)
        tokens.getOrCreateInstallationId()
        tokens.saveDeviceRegistration(deviceIdentity, legacyCaps, true, 1); tokens.saveSyncCursor(0)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null, isCountdown = countdown, targetValue = if (countdown) 1 else 10)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            if (opaque) {
                habit = habit.copy(id = db.habitDao().insert(habit.copy(id = 0)))
                timerHabit = timerHabit.copy(id = db.habitDao().insert(timerHabit.copy(id = 0)))
                metric = metric.copy(id = db.metricDao().insert(metric.copy(id = 0)))
            } else {
                db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(0, count("sync_outbox")); assertFalse(db.habitDao().hasProtocolNextState())
        assertFalse(db.metricDao().hasProtocolNextState())
    }
    private suspend fun identity() = CountActionReader(db, tokens, sessions).legacy(requireNotNull(db.habitDao().getHabitById(habit.id)))
    private fun commands() = CountCommandStore(db, tokens, sessions)
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
    }

    private suspend fun sender(): IncrementalSyncRepository {
        val server = MaterialSocketServer { request, _ ->
            val body = request.body.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)?.let { Json.parseToJsonElement(it).jsonObject }
            val response = when {
                request.path.endsWith("/system/identity") -> buildJsonObject {
                    put("server_instance_id", serverIdentity); put("sync_epoch", epochIdentity); put("protocol_version", 4)
                    put("capabilities", buildJsonArray { add("sync_v2"); add("device_capabilities") }); put("server_time", time)
                }
                request.path.endsWith("/devices/register") -> buildJsonObject {
                    put("device_id", deviceIdentity); put("installation_id", requireNotNull(body).getValue("installation_id")); put("platform", "android")
                    put("capabilities", JsonArray(legacyCaps.sorted().map(::JsonPrimitive))); put("is_primary_editor", true); put("capability_revision", 1)
                }
                request.path.endsWith("/sync/push") -> {
                    val sent = requireNotNull(body); pushes += sent
                    buildJsonObject { put("results", JsonArray(sent.getValue("operations").jsonArray.map { value ->
                        val operation = value.jsonObject
                        buildJsonObject {
                            put("operation_id", operation.getValue("operation_id")); put("entity_type", operation.getValue("entity_type"))
                            put("entity_uuid", operation.getValue("entity_uuid")); put("status", "applied"); put("revision", 1)
                            put("entity", operation.getValue("payload"))
                        }
                    })) }
                }
                request.path.substringBefore('?').endsWith("/sync/changes") -> buildJsonObject {
                    put("changes", JsonArray(emptyList())); put("next_cursor", 0); put("has_more", false); put("server_time", time)
                }
                else -> error("UNEXPECTED_LEGACY_COMMAND_REQUEST")
            }
            MaterialSocketServer.Reply(response.toString().toByteArray())
        }
        servers += server
        preferences.setServerUrl(server.origin.toString())
        val monitor = NetworkMonitor(app.getSystemService(ConnectivityManager::class.java)); networkMonitors += monitor
        val retrofit = Retrofit.Builder().baseUrl(server.origin).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        val api = retrofit.create(SyncV2Api::class.java)
        val merger = SyncV2Merger(db, db.habitDao(), db.completionDao(), db.timeLogDao(), db.metricDao(), db.metricLogDao(),
            db.habitMetricLinkDao(), db.syncOutboxDao(), db.syncConflictDao())
        return IncrementalSyncRepository(api, EndpointResolver(monitor, preferences, tokens, json, SelectedNetworkTransport()),
            retrofit.create(AuthApi::class.java), tokens, preferences, db.habitDao(), db.completionDao(), db.timeLogDao(), db.metricDao(),
            db.metricLogDao(), db.habitMetricLinkDao(), db.syncOutboxDao(), db.syncConflictDao(), TimerSyncRepository(api, db.timeLogDao()),
            merger, json, sessions)
    }

    @Test(timeout = 150_000) fun actualUnsentAddDeleteFoldLeavesBothHistoricalCommandsRecoverable() = runBlocking<Unit> {
        legacy(); val repository = creatingHabits()
        val added = repository.logCompletionWithReceipt(app, habit.id, countIdentity = identity())
        val add = requireNotNull(added.command); assertTrue(add.recorded)
        val removed = repository.undoCompletionWithReceipt(app, added.value, countIdentity = identity())
        val undo = requireNotNull(removed.command); assertTrue(undo.recorded)
        assertEquals(2, count("sync_outbox")); assertEquals(0, count("completions")); assertEquals(0, count("next_request_origins"))
        val historical = listOf(add, undo).map { commands().resolve(it.reference.native()).historical }
        assertEquals(CountCommandOutcome.FactKind.LOCAL_COUNT_DELETE, requireNotNull(historical[1].outcome).effects.single().factKind)
        assertEquals(add.factUuid, undo.factUuid)
        sender().sync()
        assertTrue("Original v4 unsent add/delete fold does not send a fake revert", pushes.isEmpty())
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("completions"))
        reopen(); val before = image()
        for ((index, receipt) in listOf(add, undo).withIndex()) {
            val restored = creatingHabits().resumeCountCommand(app, receipt.reference) as CountCommandRecovery.HistoricalCommitted
            assertEquals(receipt.reference, restored.receipt.reference)
            assertEquals(historical[index].outcome?.text(), commands().resolve(receipt.reference.native()).historical.outcome?.text())
        }
        assertEquals(before, image()); assertEquals(2, count("count_command_results"))
        assertEquals(0, count("next_request_origins")); assertEquals(0, count("count_days"))
        assertEquals(0, creatingHabits().getTodayCompletionCount(habit.id))
    }

    @Test(timeout = 150_000) fun ackThenOriginalWireRevertNeverRewritesTheLocalDeleteOutcome() = runBlocking<Unit> {
        legacy(); val repository = creatingHabits(); val sync = sender()
        val added = repository.logCompletionWithReceipt(app, habit.id, countIdentity = identity())
        val add = requireNotNull(added.command)
        sync.sync(); assertEquals(1, pushes.size); assertEquals(0, count("sync_outbox"))
        val undo = requireNotNull(repository.undoCompletionWithReceipt(app, added.value, countIdentity = identity()).command)
        val old = requireNotNull(commands().resolve(undo.reference.native()).historical.outcome)
        assertEquals(CountCommandOutcome.FactKind.LOCAL_COUNT_DELETE, old.effects.single().factKind)
        assertEquals(add.factUuid, old.primaryFactUuid)
        sync.sync(); assertEquals(2, pushes.size); assertEquals(0, count("sync_outbox"))
        val wire = pushes.last().getValue("operations").jsonArray.single().jsonObject
        assertEquals("revert", wire.getValue("payload").jsonObject.getValue("event_type").jsonPrimitive.content)
        assertEquals(add.factUuid, wire.getValue("payload").jsonObject.getValue("reverts_event_uuid").jsonPrimitive.content)
        assertNotEquals(add.factUuid, wire.getValue("entity_uuid").jsonPrimitive.content)
        reopen(); val before = image()
        assertEquals(old.text(), commands().resolve(undo.reference.native()).historical.outcome?.text())
        assertEquals(undo.reference, (creatingHabits().resumeCountCommand(app, undo.reference) as CountCommandRecovery.HistoricalCommitted).receipt.reference)
        assertEquals(add.reference, (creatingHabits().resumeCountCommand(app, add.reference) as CountCommandRecovery.HistoricalCommitted).receipt.reference)
        assertEquals(before, image()); assertEquals(0, count("completions")); assertEquals(0, count("next_request_origins"))
    }

    @Test(timeout = 150_000) fun actualT0DiskReopenResumesTheSameLegacyFactExactlyOnce() = runBlocking<Unit> {
        legacy()
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        val command = editor.beginLegacyCountAdd(habit, identity(), now, zone, 1)
        val input = command.input; val reference = CountCommandReference(input.commandId, input.inputHash, input.namespace.hash, input.namespace.dataset)
        editor.releaseLegacyCount(command)
        assertEquals(0, count("completions")); assertEquals(1, count("count_command_intents")); assertEquals(0, count("count_command_results"))
        reopen()
        val completed = creatingHabits().resumeCountCommand(app, reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(reference, completed.receipt.reference)
        val fact = db.completionDao().getAllCompletionsOnce().single()
        assertEquals(CountCommandRecords.uuid(input.body().getValue("input").jsonObject, "reserved_fact_uuid"), fact.uuid)
        assertEquals(now.toEpochMilli(), fact.actualCompletedAt); assertEquals(zone.id, fact.recordedTimezone)
        val before = image()
        assertEquals(reference, (creatingHabits().resumeCountCommand(app, reference) as CountCommandRecovery.HistoricalCommitted).receipt.reference)
        assertEquals(before, image()); assertEquals(1, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
    }

    @Test(timeout = 150_000) fun originalCountdownNoEffectGetsAnAtomicHistoricalOutcomeWithoutBusinessDml() = runBlocking<Unit> {
        legacy(countdown = true)
        val repository = creatingHabits()
        repository.logCompletion(app, habit.id, countIdentity = identity()) // Original participant, no T0.
        val current = identity()
        val business = db.withTransaction { com.dayforge.data.local.CountTrustSchema.BUSINESS_TABLES.sorted().associateWith { table ->
            CountTrustRows(db.openHelper.writableDatabase).read(table).map { it.value }
        } }
        val result = repository.logCompletionWithReceipt(app, habit.id, countIdentity = current)
        assertEquals(0L, result.value); val receipt = requireNotNull(result.command); assertFalse(receipt.recorded)
        val located = commands().resolve(receipt.reference.native())
        assertEquals(CountCommandOutcome.Kind.NO_EFFECT, located.historical.outcome?.kind)
        assertEquals(CountCommandRecords.NoEffectReason.COUNTDOWN_COMPLETE, located.historical.outcome?.reason)
        assertEquals(business, db.withTransaction { com.dayforge.data.local.CountTrustSchema.BUSINESS_TABLES.sorted().associateWith { table ->
            CountTrustRows(db.openHelper.writableDatabase).read(table).map { it.value }
        } })
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("count_command_results"))
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
    }
    @Test(timeout = 150_000) fun opaqueV4SyncRemainsLegalButOriginalCountCoreRejectionWritesNoCommand() = runBlocking<Unit> {
        legacy(opaque = true)
        assertNull(tokens.localCoreWriteAccess()) // Existing COUNT gate, not a new Namespace restriction.
        val repository = creatingHabits()
        val fact = repository.logCompletion(app, habit.id) // Original internal participant has its old contract.
        assertTrue(fact > 0); assertEquals(0, count("count_command_intents")); assertEquals(1, count("sync_outbox"))
        sender().sync()
        assertEquals(1, pushes.size); assertEquals(0, count("sync_outbox")); assertEquals(1, count("completions"))
        assertNull(tokens.localCoreWriteAccess()); assertNotNull(tokens.authenticationSnapshot())
        val before = image()
        val failure = rejected { repository.logCompletionWithReceipt(app, habit.id) }
        assertNotNull(failure)
        assertEquals(before, image()); assertEquals(0, count("count_command_intents")); assertEquals(0, count("count_command_results"))
        assertEquals(1, count("completions"))
    }

    @Test(timeout = 150_000) fun actualServiceNoTargetReceiptNeverDeletesALaterCountAfterReopen() = runBlocking<Unit> {
        legacy()
        val repository = creatingHabits()
        val service = com.dayforge.domain.service.CheckInService(repository, db.completionDao(), db.timeLogDao(),
            com.dayforge.domain.service.CountActionGate(tokens))
        val result = service.decrementCount(app, habit.id, identity()) as CheckInResult.Success
        assertFalse(result.recorded)
        val receipt = requireNotNull(result.command)
        val old = commands().resolve(receipt.reference.native()).historical
        assertEquals(CountCommandRecords.Capture.VERIFIED_NO_EFFECT, old.intent.capture)
        assertEquals(CountCommandRecords.NoEffectReason.NO_CURRENT_COUNT, old.outcome?.reason)
        val input = old.intent.body().getValue("input").jsonObject
        for (name in listOf("reserved_fact_uuid", "reserved_revert_uuid", "occurred_at", "timezone", "target_native"))
            assertFalse("No nonexistent fact/clock/undo target may be invented", name in input)
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
        val added = repository.logCompletionWithReceipt(app, habit.id, countIdentity = identity())
        assertTrue(added.value > 0); assertNotEquals(receipt.commandId, requireNotNull(added.command).commandId)
        reopen(); val before = image()
        val again = creatingHabits().resumeCountCommand(app, receipt.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(receipt.reference, again.receipt.reference); assertFalse(again.receipt.recorded)
        assertEquals(before, image()); assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox"))
        assertEquals(1, creatingHabits().getTodayCompletionCount(habit.id))
    }

    @Test(timeout = 150_000) fun capturedNoEffectCannotEnterBusinessAndTheSameIdCanCompleteAfterRollback() = runBlocking<Unit> {
        legacy()
        val selected = identity(); val repository = creatingHabits()
        val quantity = repository.captureLegacyCountQuantity(habit)
        val decision = LegacyCountNoTarget.from(quantity, null)
        val input = CountCommandLegacyInput.noCurrent(db, requireNotNull(tokens.localCoreWriteAccess()), habit, selected, decision)
        val store = commands(); val saved = store.register(input); val ticket = requireNotNull(saved.ticket)
        val before = image(); var entered = false
        val failure = rejected {
            store.producerTransaction<Unit>(ticket, requireNotNull(tokens.localCoreWriteAccess())) { participant ->
                entered = true
                participant.beginBusiness()
                error("Captured no-effect must not enter a domain callback")
            }
        }
        assertTrue(entered); assertEquals("COMMAND_NO_EFFECT_CANNOT_ENTER_BUSINESS", failure.message)
        assertEquals(before, image()); assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
        val reference = CountCommandReference.from(saved.reference)
        val recovered = repository.resumeCountCommand(app, reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(reference, recovered.receipt.reference); assertFalse(recovered.receipt.recorded)
        assertEquals(1, count("count_command_results")); assertEquals(0, count("completions"))
    }

    @Test(timeout = 150_000) fun originalStaleTypedAuthorityRejectsBothEntrancesBeforeAnyT0() = runBlocking<Unit> {
        val stale = requireNotNull(editor.habit(habit.id).authority)
        legacy(); val repository = creatingHabits()
        val beforeAdd = image()
        assertEquals("OBJECT_WRITE_CHANGED_RELOAD_REQUIRED", rejected { repository.logCompletion(app, habit.id, authority = stale) }.message)
        assertEquals(beforeAdd, image())
        assertEquals("OBJECT_WRITE_CHANGED_RELOAD_REQUIRED", rejected { repository.logCompletionWithReceipt(app, habit.id, authority = stale) }.message)
        assertEquals(beforeAdd, image()); assertEquals(0, count("count_command_intents"))
        val fact = repository.logCompletion(app, habit.id)
        val beforeUndo = image()
        assertEquals("OBJECT_WRITE_CHANGED_RELOAD_REQUIRED", rejected { repository.undoCompletion(app, fact, authority = stale) }.message)
        assertEquals(beforeUndo, image())
        assertEquals("OBJECT_WRITE_CHANGED_RELOAD_REQUIRED", rejected { repository.undoCompletionWithReceipt(app, fact, authority = stale) }.message)
        assertEquals(beforeUndo, image()); assertEquals(0, count("count_command_intents"))
        // Original computed-authority rule: a real legacy countIdentity makes the effective
        // countAuthority null; it must not be rejected merely for also carrying stale input.
        val accepted = repository.logCompletionWithReceipt(app, habit.id, authority = stale, countIdentity = identity())
        assertTrue(accepted.value > 0); assertTrue(requireNotNull(accepted.command).recorded)
    }

    @Test(timeout = 150_000) fun originalDeadLetterRetryRecoversOnlyTheHistoricalCommitBeforeAndAfterAck() = runBlocking<Unit> {
        legacy(); val repository = creatingHabits(); val sync = sender()
        val result = repository.logCompletionWithReceipt(app, habit.id, countIdentity = identity())
        val receipt = requireNotNull(result.command)
        val oldSource = db.syncOutboxDao().getAll().single()
        val original = requireNotNull(commands().resolve(receipt.reference.native()).historical.outcome)
        db.syncOutboxDao().markDeadLetter(oldSource.id, "INVALID_PAYLOAD", "fixed v4 rejection", millis)
        sync.retryDeadLetter(oldSource.id) // Real original actor: same rowid, newly allocated operationId.
        val replacement = db.syncOutboxDao().getAll().single()
        assertEquals(oldSource.id, replacement.id); assertNotEquals(oldSource.operationId, replacement.operationId)
        val before = image()
        val history = repository.resumeCountCommand(app, receipt.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(receipt.reference, history.receipt.reference); assertEquals(before, image())
        val stillOld = requireNotNull(commands().resolve(receipt.reference.native()).historical.outcome)
        assertEquals(original.text(), stillOld.text()); assertEquals(oldSource.operationId, stillOld.primaryRequestId)
        assertFalse(stillOld.evidence.records.filter { it.role == CountCommandEvidence.Role.ORIGINAL_SOURCE }
            .any { it.text("operationId") == replacement.operationId })
        assertEquals(1, count("completions"))
        sync.sync()
        assertEquals(replacement.operationId, pushes.single().getValue("operations").jsonArray.single().jsonObject.getValue("operation_id").jsonPrimitive.content)
        assertEquals(0, count("sync_outbox")); assertEquals(1, count("completions"))
        assertEquals(original.text(), commands().resolve(receipt.reference.native()).historical.outcome?.text())
    }

    @Test(timeout = 150_000) fun historicalReceiptDoesNotAuthorizeDamagedCurrentWireOrCreateASecondFact() = runBlocking<Unit> {
        legacy(); val repository = creatingHabits(); val sync = sender()
        val receipt = requireNotNull(repository.logCompletionWithReceipt(app, habit.id, countIdentity = identity()).command)
        val source = db.syncOutboxDao().getAll().single()
        db.syncOutboxDao().markPrepared(source.id, source.wireEntityUuid, source.action, "{", null, null, millis)
        val damaged = image()
        val history = repository.resumeCountCommand(app, receipt.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(receipt.reference, history.receipt.reference); assertEquals(damaged, image())
        rejected { sync.sync() } // Original toPrepared parser is still the actual sending gate.
        assertTrue(pushes.isEmpty()); assertEquals(damaged, image()); assertEquals(1, count("completions"))
        assertEquals(1, count("sync_outbox")); assertEquals(1, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun changedImmutableOutcomeNamespaceOrRootMacNeverReturnsHistoricalCommitted() = runBlocking<Unit> {
        legacy()
        val receipt = requireNotNull(creatingHabits().logCompletionWithReceipt(app, habit.id, countIdentity = identity()).command)
        val store = commands(); val before = image()
        val last = receipt.namespaceHash.last(); val other = receipt.namespaceHash.dropLast(1) + if (last == '0') '1' else '0'
        rejected { store.resolve(receipt.reference.copy(namespaceHash = other).native()) }
        assertEquals(before, image())
        val outcome = requireNotNull(store.resolve(receipt.reference.native()).historical.outcome).text()
        db.withTransaction { requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            db.openHelper.writableDatabase.execSQL("UPDATE count_command_results SET resultJson=resultJson||' ' WHERE commandId=?", arrayOf(receipt.commandId))
        } }
        val changedOutcome = image()
        rejected { creatingHabits().resumeCountCommand(app, receipt.reference) }
        assertEquals(changedOutcome, image()); assertEquals(1, count("completions"))
        db.withTransaction { requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE count_command_results SET resultJson=? WHERE commandId=?", arrayOf(outcome, receipt.commandId))
            sql.execSQL("UPDATE count_command_recovery_roots SET mac=zeroblob(32) WHERE id=1")
        } }
        val changedMac = image()
        rejected { creatingHabits().resumeCountCommand(app, receipt.reference) }
        assertEquals(changedMac, image()); assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox"))
    }

}
