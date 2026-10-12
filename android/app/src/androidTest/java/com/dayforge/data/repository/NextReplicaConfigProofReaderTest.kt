package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.*
import com.dayforge.data.export.ConfigImportIdentities
import com.dayforge.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real configuration service, original requests, TCP ACKs and physical cold reopen. */
@RunWith(AndroidJUnit4::class)
class NextReplicaConfigProofReaderTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private lateinit var http: NextSyncHttp
    private lateinit var peer: MaterialSocketServer
    private var metadata = ChallengeMetadata(1, emptyList(), emptyList())
    private val remote = mutableMapOf<String, JsonObject>()
    private val replies = mutableMapOf<String, MaterialSocketServer.Reply>()
    private suspend fun <T> timed(name: String, block: suspend () -> T): T {
        val started = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("ConfigProofPhase", "Starting $name")
        return try { block() }
        finally { android.util.Log.i("ConfigProofPhase", "$name finished in ${android.os.SystemClock.elapsedRealtime() - started}ms") }
    }
    private fun service() = NextConfigImportRepository(db, tokens, sessions, icons,
        NextObjectCreator(db, tokens, sessions, icons), app)
    private suspend fun initialize() {
        register(); db.clearAllData() // Only the owned testbed fixture; never a production reset.
        val channel = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
                val op = request.operations.single()
                replies[op.operationId] ?: run {
                    val old = remote[op.entityUuid]
                    val revision = (old?.get("revision")?.jsonPrimitive?.long ?: 0L) + 1
                    val ordinary = if (op.action == "delete") {
                        assertEquals(old!!.getValue("revision").jsonPrimitive.long, op.baseRevision)
                        val body = JsonObject(old + mapOf("revision" to JsonPrimitive(revision),
                            "updated_at" to JsonPrimitive(time), "deleted_at" to JsonPrimitive(time)))
                        NextSyncPushResponse(listOf(NextSyncOperationResult(op.operationId, op.entityType, op.entityUuid,
                            "applied", revision, entity = body)))
                    } else json.decodeFromString<NextSyncPushResponse>(successReply(input, revision).bytes.toString(Charsets.UTF_8))
                    remote[op.entityUuid] = requireNotNull(ordinary.results.single().entity)
                    request.contexts.single().head?.let { head ->
                        if (metadata.checkpoints.none { it.head.activityUuid == head.activityUuid }) metadata = metadata.copy(
                            checkpoints = metadata.checkpoints + ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null))))
                    }
                    MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(ordinary.results, 1,
                        metadata.checkpoints, metadata.births)).toByteArray()).also { replies[op.operationId] = it }
                }
            }
        }
        http = channel.first; peer = channel.second
        val core = sender(http); val timers = NextTimerRequestStore(db, tokens, sessions, core)
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
            .bootstrap(access(), null, RoundSyncBootstrapResponse(emptyList(), 20, time, emptyList(), 1, emptyList(), emptyList()))
    }
    private suspend fun source(): ValidatedConfigBundle {
        val fixture = ConfigFileFixture.manifest()
        // Goal + task + metric + link cover ALL six replacement phases. Repeating every
        // tracking-mode variant here only repeats existing full configuration-service coverage.
        val nodes = fixture.nodes.filter { it.key in setOf("goal", "task") }.map { node -> node.copy(
            appearance = node.appearance.copy(icon = IconReference.Role(if (node.kind == "goal") "goal.default" else "task.default"))) }
        assertEquals(setOf("goal", "task"), nodes.map { it.key }.toSet())
        val rolesOnly = fixture.copy(nodes = nodes,
            metrics = fixture.metrics.map { it.copy(appearance = it.appearance.copy(icon = IconReference.Role("metric.default"))) },
            iconPack = null, themes = emptyList(), unresolvedRoles = buildSet {
                nodes.forEach { add((it.appearance.icon as IconReference.Role).role) }
                if (fixture.metrics.isNotEmpty()) add("metric.default")
            }.toList())
        return ConfigBundleOutput.create(rolesOnly, ConfigFileFixture::content)
    }
    private suspend fun prepare(source: ValidatedConfigBundle): String = service().confirm(service().preview(source))
    private suspend fun prepare(): String = prepare(source())
    private suspend fun commit(): String {
        val source = source(); val id = timed("prepare empty") { prepare(source) }
        timed("commit empty") { service().resume(id, source) }; return id
    }
    private suspend fun replace(): String {
        val source = source(); val preview = timed("preview replacement") { service().previewReplacement(source) }
        assertTrue(preview.eligible)
        val id = timed("confirm replacement") { service().confirmReplacement(preview) }
        timed("commit replacement") { service().resume(id, source) }
        val phases = db.withTransaction { NextConfigImportStore(db).network(id).plan.steps.map { it.phase }.distinct() }
        assertEquals((0..5).toList(), phases)
        return id
    }
    private suspend fun drain() {
        val expected = db.syncOutboxDao().getAll().map { it.operationId }.toMutableSet()
        while (db.syncOutboxDao().getAll().isNotEmpty()) {
            val row = db.syncOutboxDao().getAll().first()
            assertTrue("An accepted original must leave the queue", expected.remove(row.operationId))
            timed("send ${row.recordType}/${row.action}, remaining ${expected.size}") {
                assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
            }
        }
        assertTrue(expected.isEmpty())
    }
    private suspend fun requests(): ReplicaRequestProof {
        val hash = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }
        val schema = timed("schema proof") { NextReplicaSchemaValidator(app, db, tokens, sessions).validate(access(), hash) }
        return timed("request proof") { NextReplicaRequestProofReader(db, tokens, sessions, preferences, http).read(schema) }
    }
    private fun reader() = NextReplicaConfigProofReader(db, tokens, sessions)
    private suspend fun unchanged(block: suspend () -> Unit) {
        val image = storage.snapshotClosedDatabase(); val prefs = dataStore.data.first(); val original = access(); val calls = peer.requests.size
        block()
        assertArrayEquals(image, storage.snapshotClosedDatabase()); assertEquals(prefs, dataStore.data.first())
        assertEquals(original, access()); assertEquals(calls, peer.requests.size)
    }
    private fun state(id: String) = db.openHelper.readableDatabase.query("SELECT state FROM next_config_imports WHERE importId=?", arrayOf(id))
        .use { assertTrue(it.moveToFirst()); it.getString(0) }

    @Test fun emptyJournalIsReadOnlyAndNeverAuthorizesActivation() = runBlocking<Unit> {
        initialize(); val proof = requests()
        unchanged { val result = reader().read(proof); assertTrue(result.journals.isEmpty())
            assertFalse(result.hasUnsettledWork); assertFalse(result.activationAuthorized) }
    }
    @Test fun actualEmptyReplacementRetainsPreparedAndAtomicTerminalWithoutInventingOperations() = runBlocking<Unit> {
        initialize()
        val empty = ConfigFileFixture.manifest().copy(nodes = emptyList(), metrics = emptyList(), links = emptyList(),
            iconPack = null, themes = emptyList(), unresolvedRoles = emptyList())
        val source = ConfigBundleOutput.create(empty, ConfigFileFixture::content)
        val preview = service().previewReplacement(source); assertTrue(preview.eligible)
        val id = service().confirmReplacement(preview); val prepared = requests()
        unchanged {
            val proof = reader().read(prepared); val entry = proof.journals.single()
            assertEquals(id, entry.importId); assertEquals("prepared", entry.state)
            assertTrue(entry.requests.isEmpty()); assertTrue(entry.reservedOperationIds.isEmpty())
            assertTrue(proof.hasUnsettledWork); assertFalse(proof.activationAuthorized)
        }
        service().resume(id, source); assertEquals("operations_accepted", state(id))
        assertEquals(0, count("next_request_origins")); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val accepted = requests()
        unchanged {
            val proof = reader().read(accepted)
            assertEquals("operations_accepted", proof.journals.single().state)
            assertTrue(proof.journals.single().requests.isEmpty())
            assertFalse(proof.hasUnsettledWork); assertFalse(proof.activationAuthorized)
            storage.reopen(); assertEquals(id, reader().read(accepted).journals.single().importId)
        }
    }
    @Test fun preparedMappingIsBlockedAndReservedIdsAreNotInventedOriginalRequests() = runBlocking<Unit> {
        initialize(); val id = prepare(); val proof = requests(); assertTrue(proof.states.isEmpty())
        unchanged {
            val result = reader().read(proof); val journal = result.journals.single()
            assertEquals(id, journal.importId); assertEquals("prepared", journal.state)
            assertTrue(journal.reservedOperationIds.isNotEmpty()); assertTrue(journal.requests.isEmpty())
            assertTrue(result.hasUnsettledWork); assertFalse(result.activationAuthorized)
            storage.reopen(); assertEquals(journal.reservedOperationIds, reader().read(proof).journals.single().reservedOperationIds)
        }
    }
    @Test fun localCommitIsNotRemoteAcceptanceAndRetainsAllOriginalCreationBindings() = runBlocking<Unit> {
        initialize(); val id = commit(); val proof = requests()
        unchanged {
            val result = reader().read(proof); val journal = result.journals.single()
            assertEquals(id, journal.importId); assertEquals("local_committed", journal.state)
            assertEquals(proof.states, journal.requests); assertTrue(journal.reservedOperationIds.isEmpty())
            assertTrue(journal.requests.values.all { it == NextRecoveryRequestState.NEVER_SENT })
            assertTrue(result.hasUnsettledWork); assertFalse(result.activationAuthorized)
        }
    }
    @Test fun realAcceptedEmptyImportCanBeReadWithReadOnlyPermissionsAndColdReopen() = runBlocking<Unit> {
        initialize(); val id = commit(); drain(); register(permissions = setOf("sync.read"), revision = 2)
        val proof = requests()
        unchanged {
            val result = reader().read(proof); assertEquals("local_committed", result.journals.single().state)
            assertEquals(id, result.journals.single().importId); assertEquals(proof.states, result.journals.single().requests)
            assertFalse(result.hasUnsettledWork); assertFalse(result.activationAuthorized)
            assertTrue(runCatching { (result.journals as MutableList<*>).clear() }.isFailure)
            storage.reopen(); assertEquals(proof.states, reader().read(proof).journals.single().requests)
        }
    }
    @Test fun allHistoricalJournalsAndReplacementTerminalAreAuditedTogether() = runBlocking<Unit> {
        initialize(); val first = commit(); drain(); val second = replace(); drain()
        assertEquals("operations_accepted", state(second))
        val proof = requests()
        unchanged {
            val result = timed("config journal proof") { reader().read(proof) }
            assertEquals(setOf(first, second), result.journals.map { it.importId }.toSet())
            assertEquals(proof.states.keys, result.journals.flatMap { it.requests.keys }.toSet())
            assertFalse(result.hasUnsettledWork); assertFalse(result.activationAuthorized)
            storage.reopen(); assertEquals(result.journals.map { it.importId }, reader().read(proof).journals.map { it.importId })
        }
    }
    @Test fun partialRealReplacementAckCannotPretendThatLocalCommitIsTerminal() = runBlocking<Unit> {
        initialize(); commit(); drain(); val second = replace()
        sender(http).sendAndAcceptOperation(access(), db.syncOutboxDao().getAll().first().operationId)
        val proof = requests()
        unchanged {
            val result = reader().read(proof); val entry = result.journals.single { it.importId == second }
            assertEquals("local_committed", entry.state)
            assertTrue(entry.requests.values.any { it == NextRecoveryRequestState.ACCEPTED })
            assertTrue(entry.requests.values.any { it == NextRecoveryRequestState.NEVER_SENT })
            assertTrue(result.hasUnsettledWork); assertFalse(result.activationAuthorized)
        }
    }
    @Test fun completedHistoryWithDamagedChunksIsRefusedEvenWithNoActiveImportOrQueue() = runBlocking<Unit> {
        initialize(); val id = commit(); drain()
        db.openHelper.writableDatabase.execSQL("UPDATE next_config_import_payloads SET bytes=X'00' WHERE importId=? AND payloadKind='identities' AND part=0", arrayOf(id))
        val proof = requests()
        unchanged { rejected { reader().read(proof) } }
    }
    @Test fun completedJournalCannotClaimAReceiptThatOmitsOriginalSourcesEvenAfterRehash() = runBlocking<Unit> {
        initialize(); val id = commit(); drain()
        val sql = db.openHelper.writableDatabase
        val receipt = sql.query("SELECT bytes FROM next_config_import_payloads WHERE importId=? AND payloadKind='receipt'", arrayOf(id))
            .use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getBlob(0).also { assertFalse(cursor.moveToNext()) } }
        val original = json.decodeFromString<Map<String, ConfigImportedSource>>(receipt.toString(Charsets.UTF_8))
        val changed = json.encodeToString(original - original.keys.first()).toByteArray()
        sql.execSQL("UPDATE next_config_import_payloads SET bytes=? WHERE importId=? AND payloadKind='receipt' AND part=0", arrayOf(changed, id))
        sql.execSQL("UPDATE next_config_imports SET receiptHash=? WHERE importId=?", arrayOf(nextRequestHash(changed), id))
        val proof = requests()
        unchanged { rejected { reader().read(proof) } }
    }
    @Test fun changedJournalOrNewAuthorizationInvalidatesCapturedRequestProof() = runBlocking<Unit> {
        initialize(); val proof = requests(); prepare()
        unchanged { assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { reader().read(proof) }.message) }
        val fresh = requests(); register(revision = 2)
        unchanged { assertEquals("SYNC_ACCOUNT_CHANGED", rejected { reader().read(fresh) }.message) }
    }
    @Test fun rehashedCompletedNetworkCannotChangePhaseBoundToActualOriginalKind() = runBlocking<Unit> {
        initialize(); commit(); drain(); val id = replace(); drain(); val sql = db.openHelper.writableDatabase
        fun bytes(kind: String): ByteArray = sql.query("SELECT bytes FROM next_config_import_payloads WHERE importId=? AND payloadKind=?",
            arrayOf(id, kind)).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getBlob(0).also { assertFalse(cursor.moveToNext()) } }
        val plan = ConfigNetworkPlan.decode(bytes("network"))
        val goal = plan.steps.single { it.action == "upsert" && it.entityType == "plan_node" && it.phase == 3 }
        val changed = plan.copy(steps = plan.steps.map { if (it == goal) it.copy(phase = 4) else it }.sortedBy { it.phase }).encode()
        val receipt = json.decodeFromString<ConfigNetworkReceipt>(bytes("receipt").toString(Charsets.UTF_8))
        val newReceipt = json.encodeToString(receipt.copy(networkHash = nextRequestHash(changed))).toByteArray()
        sql.execSQL("UPDATE next_config_import_payloads SET bytes=? WHERE importId=? AND payloadKind='network' AND part=0", arrayOf(changed, id))
        sql.execSQL("UPDATE next_config_import_payloads SET bytes=? WHERE importId=? AND payloadKind='receipt' AND part=0", arrayOf(newReceipt, id))
        sql.execSQL("UPDATE next_config_imports SET receiptHash=? WHERE importId=?", arrayOf(nextRequestHash(newReceipt), id))
        val proof = requests()
        unchanged { assertEquals("CONFIG_IMPORT_NETWORK_PLAN_CHANGED", timed("config journal rejection") {
            rejected { reader().read(proof) }
        }.message) }
    }
    @Test fun historicalSourceCannotBeClaimedAgainByAnotherRehashedJournal() = runBlocking<Unit> {
        initialize(); val duplicate = id(80); val id = commit(); drain(); val sql = db.openHelper.writableDatabase
        val bytes = sql.query("SELECT bytes FROM next_config_import_payloads WHERE importId=? AND payloadKind='identities'", arrayOf(id))
            .use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getBlob(0).also { assertFalse(cursor.moveToNext()) } }
        val identities = ConfigImportIdentities.decode(bytes)
        val newBytes = identities.copy(ids = identities.ids + ("import" to duplicate)).encode().toByteArray()
        sql.execSQL("INSERT INTO next_config_imports SELECT ?,accountId,serverInstanceId,syncEpoch,deviceId,archiveHash,?,mode,state,receiptHash " +
            "FROM next_config_imports WHERE importId=?", arrayOf(duplicate, nextRequestHash(newBytes), id))
        sql.execSQL("INSERT INTO next_config_import_payloads SELECT ?,payloadKind,part,bytes FROM next_config_import_payloads WHERE importId=?",
            arrayOf(duplicate, id))
        sql.execSQL("UPDATE next_config_import_payloads SET bytes=? WHERE importId=? AND payloadKind='identities' AND part=0", arrayOf(newBytes, duplicate))
        val proof = requests()
        unchanged { assertEquals("CONFIG_IMPORT_DUPLICATE_SOURCE", rejected { reader().read(proof) }.message) }
    }
    @Test fun preparedMaterialAndThemeMappingRetainsOriginalAllocationWithoutInstallingOrApplying() = runBlocking<Unit> {
        initialize(); val source = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
        val context = icons.capture(); val catalog = iconMetadata.library(context); val id = prepare(source)
        val plan = sessions.exclusive { db.withTransaction { NextConfigImportStore(db).read(context, id, source).plan } }
        val proof = requests()
        unchanged {
            val result = reader().read(proof); val entry = result.journals.single()
            assertEquals(plan.iconPack!!.assets.map { it.assetId }.toSet(), entry.allocatedAssets)
            assertTrue(entry.allocatedAssets.isNotEmpty()); assertEquals(plan.creationOperationIds.values.toSet(), entry.reservedOperationIds)
            assertTrue(entry.requests.isEmpty()); assertTrue(result.hasUnsettledWork); assertFalse(result.activationAuthorized)
            assertEquals(catalog, iconMetadata.library(context)); assertEquals(0, count("next_request_origins"))
        }
    }
}
