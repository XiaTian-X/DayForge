package com.dayforge.data.repository

import android.os.Process
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.authenticator.TokenAuthenticator
import com.dayforge.data.api.dto.*
import com.dayforge.data.api.interceptor.AuthInterceptor
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.appearance.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.appearance.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*

/** Two fixed recovery tails. No JUnit setup/cleanup is inherited: later phases must retain
 * both original Room files, auth DataStore, archives, materials and the same Keystore alias.
 * Test seed/markers are expected inputs only; every business stage is reread from real stores.
 */
internal class ReplicaCompletionHostScenario(private val protocol: ReplicaCompletionProcessProtocol) {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val phase get() = protocol.phase
    private val directory get() = protocol.directory
    private val sessions = AccountSessionCoordinator()
    private val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    private val json = Json { encodeDefaults = true }
    private val alias get() = "dayforge.test.replica.final.${protocol.run}.${protocol.case.wire}"
    private val authFile get() = File(directory, "auth.preferences_pb")
    private val seedFile get() = File(directory, "seed.json")
    private val baselineArchive get() = File(directory, "archive-before.candidate")
    private lateinit var db: HabitDatabase
    private lateinit var iconDb: AccountIconDatabase
    private lateinit var authScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cipher: AndroidKeystoreTokenCipher
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var client: OkHttpClient
    private lateinit var http: NextSyncHttp
    private lateinit var peer: ReplicaCompletionProcessPeer
    private lateinit var icons: AccountIconRepository
    private lateinit var files: AccountIconFiles
    private lateinit var store: AccountIconStore
    private lateinit var transfers: AccountIconTransfers
    private lateinit var themes: ThemeFileRepository
    private lateinit var builtIns: BuiltInThemes
    private lateinit var seed: JsonObject
    private lateinit var installation: String
    private var closeAttempted = false
    private var resourcesClosed = false
    @Volatile private var expectedAccess = "synthetic-first"
    @Volatile private var forwardDestination = false
    private val time = "2026-10-06T00:00:00Z"
    private val millis = Instant.parse(time).toEpochMilli()
    private val caps = setOf("sync.read", "structure.write", "facts.append", "timer.control")
    private fun id(n: Int) = "aa310000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val habit = HabitEntity(uuid = id(10), name = "Recurring count", habitType = HabitType.COUNTING,
        iconResId = 0, colorHex = "#000000", schedule = HabitSchedule.Daily, targetValue = 10,
        failMode = FailMode.LOOSE, createdAt = millis, updatedAt = millis, completionPolicy = "recurring",
        appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
        planMetadata = PlanStructureMetadata(time, 0, null, null, "Asia/Shanghai", null, null, null))
    private val timerHabit = habit.copy(uuid = id(12), name = "Complete timer", habitType = HabitType.TIMER,
        targetValue = 1, planMetadata = requireNotNull(habit.planMetadata).copy(targetUnit = "second"))
    private val metric = MetricEntity(uuid = id(11), name = "Weight", unit = "kg", decimalPlaces = 3,
        iconResId = 0, colorHex = "#000000", createdAt = millis, updatedAt = millis,
        appearance = ObjectAppearance(IconReference.Role("metric.custom"), "#123456", "object"))
    private val svg get() = """<svg width="1" height="1"><rect width="1" height="1" fill="#000000"/></svg>""".toByteArray()
    private val hash get() = ReplicaCompletionProcessProtocol.sha256(svg)
    private val asset get() = IconAsset(id(100), "Material", "general", "template",
        IconBlob(hash, svg.size, "image/svg+xml", 1, 1), null)
    private val pack get() = IconPack("dayforge.icon-pack", 1, id(101), 1, "Material pack", listOf(asset),
        mapOf("habit.custom" to asset.assetId, "metric.custom" to asset.assetId), null)
    private fun canonical(kind: String, uuid: String, payload: JsonObject) = SyncV2Change(0, kind, uuid, "upsert", 1,
        JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun metadata() = ChallengeMetadata(1, listOf(habit.uuid, timerHabit.uuid).map {
        val head = initialChallengeRoundHead(it)
        ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
    }, emptyList())
    private fun bootstrap(): RoundSyncBootstrapResponse {
        val meta = metadata()
        return RoundSyncBootstrapResponse(listOf(habit, timerHabit).map {
            canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it))
        } + canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)),
            20, time, emptyList(), 1, meta.checkpoints, meta.births)
    }
    private fun descriptorHash() = ReplicaCompletionProcessProtocol.sha256((
        json.encodeToString(bootstrap()) + json.encodeToString(pack) + json.encodeToString(asset) + hash).toByteArray())

    fun runScenarioPhase() {
        widgetRefresh.apply(object : org.junit.runners.model.Statement() {
            override fun evaluate() = runBlocking {
                withTimeout(protocol.remainingMillis()) { runPhase() }
            }
        }, org.junit.runner.Description.createSuiteDescription("ReplicaCompletionHostScenario")).evaluate()
    }

    private suspend fun runPhase() {
        var primary: Throwable? = null
        try {
            open()
            when (phase) {
                ReplicaCompletionProcessProtocol.Phase.PREPARE_BOUND -> prepareBound()
                ReplicaCompletionProcessProtocol.Phase.ACTUAL_FAULT -> actualFault()
                ReplicaCompletionProcessProtocol.Phase.FRESH_COMPLETE -> freshComplete()
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            if (!closeAttempted) {
                // Failure cleanup does not grant a successor. Unknown closure never emits readiness.
                try { withContext(NonCancellable) { closeResources() } }
                catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
            }
        }
    }

    private suspend fun open() {
        check(app.packageName == "com.dayforge.testbed")
        check(DatabaseRuntimeHosts.snapshot().all { it.lifecycleEnded }) { "REPLICA_PROCESS_ACTIVE_HOST" }
        val preparing = phase == ReplicaCompletionProcessProtocol.Phase.PREPARE_BOUND
        if (preparing) {
            check(!seedFile.exists() && !authFile.exists() && !baselineArchive.exists())
            app.deleteDatabase("habit_database"); app.deleteDatabase(AccountIconDatabase.NAME)
            check(!app.getDatabasePath("habit_database").exists() && !app.getDatabasePath(AccountIconDatabase.NAME).exists())
            check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        } else {
            val prior = protocol.priorCheckpoint(if (phase == ReplicaCompletionProcessProtocol.Phase.ACTUAL_FAULT)
                ReplicaCompletionProcessProtocol.Phase.PREPARE_BOUND else ReplicaCompletionProcessProtocol.Phase.ACTUAL_FAULT)
            seed = readJson(seedFile)
            check(seed["run"] == JsonPrimitive(protocol.run) && seed["case"] == JsonPrimitive(protocol.case.wire) &&
                seed["nonce"] == JsonPrimitive(protocol.nonce) && seed["cipher_alias"] == JsonPrimitive(alias) &&
                seed["fixture_descriptor_sha256"] == JsonPrimitive(descriptorHash()))
            check(prior["seed_sha256"] == JsonPrimitive(sha(seedFile)) && prior["archive_sha256"] == JsonPrimitive(sha(baselineArchive)))
            for (file in listOf(authFile, app.getDatabasePath("habit_database"), app.getDatabasePath(AccountIconDatabase.NAME)))
                check(file.isFile && file.length() > 0) { "REPLICA_PROCESS_ORIGINAL_STORAGE_MISSING" }
            check(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)) { "REPLICA_PROCESS_ORIGINAL_KEY_MISSING" }
        }
        authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = authScope, produceFile = { authFile })
        cipher = AndroidKeystoreTokenCipher(alias)
        tokens = TokenManager(dataStore, cipher); preferences = PreferencesManager(dataStore, tokens = tokens)
        if (preparing) tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false)
        val authentication = requireNotNull(tokens.authenticationSnapshot())
        assertEquals(id(1), authentication.session.userId)
        assertTrue(authentication.accessToken == "synthetic-first" && authentication.refreshToken == "synthetic-refresh")
        HabitDatabaseProvider.clearInstanceForTesting()
        db = HabitDatabaseProvider.build(app).also(HabitDatabaseProvider::setInstanceForTesting)
        check(db.openHelper.writableDatabase.isOpen)
        iconDb = AccountIconDatabase.open(app)
        assertEquals(5, iconDb.openHelper.writableDatabase.version)
        materialParticipants()
        themes = ThemeFileRepository(directory); builtIns = BuiltInThemes(app.assets)
        if (preparing) {
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                db.habitDao().insert(habit); db.habitDao().insert(timerHabit); db.metricDao().insert(metric)
                sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
            tokens.saveServerIdentity(id(2), id(3))
            tokens.saveDeviceRegistration(id(4), caps, true, 1)
            installation = tokens.getOrCreateInstallationId()
        } else {
            installation = seed.getValue("installation_id").jsonPrimitive.content
            assertEquals(installation, tokens.registeredInstallationId())
            assertNull(tokens.localCoreWriteAccess())
            assertFalse(requireNotNull(tokens.localFactAccess()).canAppend)
        }
        val port = if (preparing) 0 else seed.getValue("port").jsonPrimitive.int.also { require(it in 1..65535) }
        peer = ReplicaCompletionProcessPeer(port, ::respond)
        if (preparing) {
            preferences.setServerUrl(peer.origin.toString())
            seed = buildJsonObject {
                put("format", 1); put("run", protocol.run); put("case", protocol.case.wire); put("nonce", protocol.nonce)
                put("cipher_alias", alias); put("fixture_descriptor_sha256", descriptorHash())
                put("account_id", id(1)); put("server_instance_id", id(2)); put("old_epoch", id(3)); put("new_epoch", id(99))
                put("device_id", id(4)); put("installation_id", installation); put("port", peer.boundPort)
                put("origin", peer.origin.toString()); put("time", time); put("svg_sha256", hash)
                put("main_database", "habit_database"); put("icon_database", AccountIconDatabase.NAME)
                put("auth_file", "auth.preferences_pb")
            }
            writeOnce(seedFile, seed.toString().toByteArray())
        } else {
            assertEquals(seed.getValue("origin").jsonPrimitive.content, peer.origin.toString())
            assertEquals(peer.origin.toString(), preferences.serverUrl.first())
            assertNull(preferences.remoteServerUrl.first())
        }
        connectHttp()
    }

    private fun materialParticipants() {
        icons = AccountIconRepository(iconDb, tokens, sessions); files = AccountIconFiles(directory)
        store = AccountIconStore(icons, files); transfers = AccountIconTransfers(iconDb, icons, store)
    }
    private fun connectHttp() {
        client = OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(preferences)).addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, preferences, SelectedNetworkTransport())).build()
        http = NextSyncHttp(client, tokens, peer.origin)
    }
    private suspend fun stopHttp() {
        assertEquals(0, client.dispatcher.runningCallsCount()); assertEquals(0, client.dispatcher.queuedCallsCount())
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        withContext(Dispatchers.IO) { check(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    private fun workflow(): NextReplicaRecoveryWorkflow {
        val coordinator = BusinessSyncRepository(io.mockk.mockk(), io.mockk.mockk(), db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
        val inputs = NextReplicaArchiveInputReader(app, db, tokens, sessions, preferences, http, iconDb,
            icons, transfers, files, dataStore, themes, builtIns)
        return NextReplicaRecoveryWorkflow(coordinator, db, tokens, sessions, http,
            NextReplicaCandidateValidator(app, tokens, sessions, preferences),
            NextReplicaDifferenceReader(db, tokens, sessions, preferences), inputs, ReplicaPrivateArchiveStore(directory))
    }
    private fun runtime(): AccountIconRuntime {
        val imports = AccountIconImport(icons, store, transfers)
        return AccountIconRuntime(icons, store, AccountIconRenderer(icons, store), imports,
            AccountIconDocuments(imports, app.contentResolver), {})
    }
    private fun transport() = AccountIconHttp(client, tokens, peer.origin)
    private fun archive(stage: ReplicaSwitchStage) = File(directory,
        "replica-archives-v1/${stage.accountId}/${stage.serverInstanceId}/${stage.oldEpoch}/${requireNotNull(stage.archive).archiveId}.candidate")
    private fun nativeMaterial() = File(directory, "account-icons-v1/${id(1)}/${id(2)}/${id(99)}/$hash")

    private suspend fun prepareBound(): Nothing {
        val original = requireNotNull(tokens.localSyncAccess())
        val timers = NextTimerRequestStore(db, tokens, sessions, NextCoreRequestStore(db, tokens, sessions, http))
        val once = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions, once, timerRequests = timers), timers)
            .bootstrap(original, null, bootstrap())
        val candidate = NextReplicaCandidateValidator(app, tokens, sessions, preferences)
            .validate(original, installation, http.recoveryPreview(original, id(99), installation))
        val flow = workflow()
        val difference = NextReplicaDifferenceReader(db, tokens, sessions, preferences).capture(candidate)
        val prepared = flow.prepare(difference)
        val evidence = flow.verifyForConfirmation(prepared)
        val committed = flow.confirm(evidence)
        val bound = flow.bindDestination(committed)
        assertEquals(ReplicaSwitchPhase.DESTINATION_BOUND, bound.phase)
        assertEquals(6L, bound.generation)
        assertEquals(bound, tokens.activeReplicaStage())
        assertEquals(ReplicaCommitFate.COMMITTED, NextReplicaCommitStore(db, tokens, sessions).fate(bound))
        assertEquals(bound.fence, db.nextReplicaFenceDao().state())
        assertNull(tokens.localCoreWriteAccess()); assertFalse(requireNotNull(tokens.localFactAccess()).canAppend)
        assertNull(WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
        val raw = requireNotNull(dataStore.data.first()[ReplicaSwitchStage.key(bound.recoveryId)])
        assertEquals(bound, ReplicaSwitchStage.decode(raw))
        writeOnce(File(directory, "bound-stage.json"), raw.toByteArray())
        writeOnce(baselineArchive, archive(bound).readBytes())
        val evidenceBase = stageEvidence(bound)
        closeResources()
        protocol.checkpoint(JsonObject(evidenceBase + closedEvidence()))
    }

    private suspend fun actualFault(): Nothing {
        val previous = protocol.priorCheckpoint(ReplicaCompletionProcessProtocol.Phase.PREPARE_BOUND)
        val initial = requireNotNull(tokens.activeReplicaStage())
        assertEquals(ReplicaSwitchPhase.DESTINATION_BOUND, initial.phase)
        assertEquals(previous.getValue("recovery_id"), JsonPrimitive(initial.recoveryId))
        assertEquals(previous.getValue("stage_sha256"), JsonPrimitive(stageHash(initial)))
        assertEquals(readStoredStage("bound-stage.json"), initial)
        assertArrayEquals(baselineArchive.readBytes(), archive(initial).readBytes())
        assertEquals(initial, workflow().resume(initial))
        val hits = AtomicInteger(0)
        val injection = IOException("synthetic actual phased final DS failure")
        val corrupted = svg.copyOf().also { it[0] = '!'.code.toByte() }
        if (protocol.case == ReplicaCompletionProcessProtocol.Case.FILE_REPAIR) {
            assertEquals(IconMaterialOutcome.RETRY, workflow().recoverMaterials(initial, runtime(), transport()))
            val pending = iconDb.transfers().rows(id(1), id(2), id(99)).single()
            assertEquals("download", pending.kind); assertEquals("pending", pending.state)
            assertEquals(IconMaterialOutcome.COMPLETE, workflow().recoverMaterials(initial, runtime(), transport()))
            val complete = iconDb.transfers().rows(id(1), id(2), id(99)).single()
            assertEquals(pending.operationId, complete.operationId); assertEquals("complete", complete.state)
            assertArrayEquals(svg, nativeMaterial().readBytes())
            writeOnce(File(directory, "material-operation.txt"), pending.operationId.toByteArray())
        }
        wrapWrites { after ->
            val observed = after[ReplicaSwitchStage.key(initial.recoveryId)]?.let { ReplicaSwitchStage.decode(it) }
            val phaseToFault = if (protocol.case == ReplicaCompletionProcessProtocol.Case.FILE_REPAIR)
                ReplicaSwitchPhase.RELEASING else ReplicaSwitchPhase.COMPLETED
            if (observed?.phase == phaseToFault && hits.compareAndSet(0, 1)) {
                if (protocol.case == ReplicaCompletionProcessProtocol.Case.FILE_REPAIR) nativeMaterial().writeBytes(corrupted)
                else throw injection
            }
        }
        val failure = rejected { workflow().finishRecovery(initial, runtime(), transport()) }
        assertEquals(1, hits.get())
        if (protocol.case == ReplicaCompletionProcessProtocol.Case.FILE_REPAIR) {
            assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it is ImageInputException && it.code == "IMAGE_HASH" })
            assertArrayEquals(corrupted, nativeMaterial().readBytes())
        } else assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it === injection })
        val releasing = requireNotNull(tokens.activeReplicaStage())
        assertBlocked(releasing)
        assertEquals(initial.recoveryId, releasing.recoveryId)
        val raw = requireNotNull(dataStore.data.first()[ReplicaSwitchStage.key(releasing.recoveryId)])
        assertEquals(releasing, ReplicaSwitchStage.decode(raw))
        writeOnce(File(directory, "fault-stage.json"), raw.toByteArray())
        val evidenceBase = JsonObject(stageEvidence(releasing) + buildJsonObject {
            put("prepare_pid", previous.getValue("pid")); put("prepare_checkpoint_sha256", protocol.checkpointHash(ReplicaCompletionProcessProtocol.Phase.PREPARE_BOUND))
            put("fault_code", protocol.case.fault); put("injection_hits", hits.get()); put("exact_fault_cause_confirmed", true)
            put("release_fate", "CLOSED")
            if (protocol.case == ReplicaCompletionProcessProtocol.Case.FILE_REPAIR) put("damaged_material_sha256", sha(nativeMaterial()))
        })
        closeResources()
        protocol.checkpoint(JsonObject(evidenceBase + closedEvidence()))
    }

    private suspend fun freshComplete() {
        val prepared = protocol.priorCheckpoint(ReplicaCompletionProcessProtocol.Phase.PREPARE_BOUND)
        val fault = protocol.priorCheckpoint(ReplicaCompletionProcessProtocol.Phase.ACTUAL_FAULT)
        val initial = readStoredStage("bound-stage.json") // Expected original identity only, never operation authority.
        assertEquals(prepared.getValue("stage_sha256"), JsonPrimitive(stageHash(initial)))
        assertEquals(prepared.getValue("recovery_id"), JsonPrimitive(initial.recoveryId))
        val releasing = requireNotNull(tokens.activeReplicaStage())
        assertEquals(readStoredStage("fault-stage.json"), releasing)
        assertEquals(fault.getValue("stage_sha256"), JsonPrimitive(stageHash(releasing)))
        assertEquals(fault.getValue("recovery_id"), JsonPrimitive(releasing.recoveryId))
        assertEquals(initial.recoveryId, releasing.recoveryId)
        assertArrayEquals(baselineArchive.readBytes(), archive(releasing).readBytes())
        assertBlocked(releasing)
        if (protocol.case == ReplicaCompletionProcessProtocol.Case.FILE_REPAIR) {
            val corrupted = svg.copyOf().also { it[0] = '!'.code.toByte() }
            assertArrayEquals(corrupted, nativeMaterial().readBytes())
            assertEquals(fault.getValue("damaged_material_sha256"), JsonPrimitive(sha(nativeMaterial())))
            val job = iconDb.transfers().rows(id(1), id(2), id(99)).single()
            assertEquals(File(directory, "material-operation.txt").readText(), job.operationId)
            assertEquals("complete", job.state)
            nativeMaterial().writeBytes(svg) // Only the known bytes damaged by this scenario.
            assertArrayEquals(svg, nativeMaterial().readBytes())
        } else {
            tokens.saveLoginSession("synthetic-final-fresh", "synthetic-final-refresh", "member", id(1), false)
            val fresh = requireNotNull(tokens.authenticationSnapshot())
            assertTrue(fresh.accessToken == "synthetic-final-fresh" && fresh.refreshToken == "synthetic-final-refresh")
            assertNotEquals(requireNotNull(releasing.release).authenticationGeneration, fresh.session.generation)
            expectedAccess = "synthetic-final-fresh"
            forwardDestination = true
        }
        val resumed = workflow().resume(releasing)
        assertEquals(releasing, resumed)
        val outcome = workflow().finishRecovery(resumed, runtime(), transport())
        assertTrue("Actual fresh completion required", outcome is ReplicaCompletionOutcome.Completed)
        val completed = (outcome as ReplicaCompletionOutcome.Completed).stage
        if (protocol.case == ReplicaCompletionProcessProtocol.Case.FILE_REPAIR) {
            assertEquals(8L, completed.generation); assertEquals(initial.target, completed.target)
            assertArrayEquals(svg, nativeMaterial().readBytes())
        } else {
            assertEquals(12L, completed.generation)
            assertNotEquals(requireNotNull(releasing.release).authenticationGeneration, requireNotNull(completed.completion).authenticationGeneration)
            assertEquals(ReplicaSwitchStage.encodeRelease(releasing), dataStore.data.first()[ReplicaSwitchStage.releaseKey(releasing)])
            assertEquals("Forward after closed SQL", requireNotNull(db.habitDao().getHabitByUuid(habit.uuid)).name)
            assertEquals(21L, tokens.syncCursor.first())
        }
        assertCompleted(initial, completed)
        val evidenceBase = JsonObject(stageEvidence(completed) + buildJsonObject {
            put("result", "same_replica_completed_and_usable")
            put("prepare_pid", prepared.getValue("pid")); put("fault_pid", fault.getValue("pid"))
            put("prepare_checkpoint_sha256", protocol.checkpointHash(ReplicaCompletionProcessProtocol.Phase.PREPARE_BOUND))
            put("fault_checkpoint_sha256", protocol.checkpointHash(ReplicaCompletionProcessProtocol.Phase.ACTUAL_FAULT))
            put("assertions", JsonObject(ReplicaCompletionProcessProtocol.ASSERTIONS.associateWith { JsonPrimitive(true) }))
        })
        closeResources()
        val allRequests = ReplicaCompletionProcessProtocol.Phase.entries.flatMap { selected ->
            val records = readJson(File(directory, "${selected.wire}/peer-requests.json")).getValue("requests").jsonArray
            records.map(JsonElement::jsonObject)
        }
        assertTrue(allRequests.all { it["method"] == JsonPrimitive("GET") && it["response_flushed"] == JsonPrimitive(true) })
        val contentPath = "/api/v2/appearance/assets/${asset.assetId}/content/light"
        assertEquals(if (protocol.case == ReplicaCompletionProcessProtocol.Case.FILE_REPAIR) 1 else 0,
            allRequests.count { it["path"] == JsonPrimitive(contentPath) })
        val freshRequests = peer.evidence().map(JsonElement::jsonObject)
        assertTrue(freshRequests.any { it["path"] == JsonPrimitive("/api/v2/sync/rounds/recovery-preview") })
        // Material preflight belongs to the original-source prepare/confirmation phase.
        // A closed destination lease deliberately requests destinationPreview(false, null).
        val preparationRequests = readJson(File(directory, "prepare_bound/peer-requests.json"))
            .getValue("requests").jsonArray.map(JsonElement::jsonObject)
        assertTrue(preparationRequests.any { request ->
            request["path"] == JsonPrimitive("/api/v2/sync/rounds/recovery-preview") &&
                requireNotNull(peer.origin.resolve(request.getValue("target").jsonPrimitive.content))
                    .queryParameterValues("include_materials") == listOf("1")
        })
        assertTrue(freshRequests.filter { it["path"] == JsonPrimitive("/api/v2/sync/rounds/recovery-preview") }.all {
            requireNotNull(peer.origin.resolve(it.getValue("target").jsonPrimitive.content))
                .queryParameterValues("include_materials").isEmpty()
        })
        assertEquals(0, freshRequests.count { it["path"] == JsonPrimitive(contentPath) })
        protocol.terminal(JsonObject(evidenceBase + closedEvidence()))
    }

    private suspend fun wrapWrites(effect: suspend (Preferences) -> Unit) {
        stopHttp()
        val actual = dataStore
        dataStore = object : DataStore<Preferences> by actual {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                actual.updateData { before -> transform(before).also { effect(it) } }
        }
        tokens = TokenManager(dataStore, cipher); preferences = PreferencesManager(dataStore, tokens = tokens)
        materialParticipants(); connectHttp()
    }
    private suspend fun assertBlocked(stage: ReplicaSwitchStage) {
        assertEquals(ReplicaSwitchPhase.RELEASING, stage.phase)
        assertEquals(stage, tokens.activeReplicaStage()); assertTrue(tokens.replicaSwitchPending())
        assertEquals(ReplicaReleaseFate.CLOSED, NextReplicaCommitStore(db, tokens, sessions).releaseFate(stage))
        assertNull(tokens.localCoreWriteAccess()); assertFalse(requireNotNull(tokens.localFactAccess()).canAppend)
        assertNull(WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
        assertFalse(db.inTransaction()); assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).hasUnknownGraphBoundary())
    }
    private suspend fun assertCompleted(initial: ReplicaSwitchStage, completed: ReplicaSwitchStage) {
        assertEquals(initial.recoveryId, completed.recoveryId); assertEquals(initial.accountId, completed.accountId)
        assertEquals(initial.serverInstanceId, completed.serverInstanceId); assertEquals(initial.oldEpoch, completed.oldEpoch)
        assertEquals(initial.newEpoch, completed.newEpoch); assertEquals(initial.archive, completed.archive)
        assertEquals(ReplicaSwitchPhase.COMPLETED, completed.phase)
        assertFalse(tokens.replicaSwitchPending()); assertNull(tokens.activeReplicaStage())
        assertEquals(completed, tokens.replicaStage(completed.recoveryId))
        assertEquals(completed.fence.copy(mode = NextReplicaFenceEntity.CLOSED), db.nextReplicaFenceDao().state())
        assertEquals(completed.publishedSnapshot.target.projectionHash, ReplicaProjection.capture(db).hash)
        assertFalse(db.inTransaction()); assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).hasUnknownGraphBoundary())
        assertArrayEquals(baselineArchive.readBytes(), archive(completed).readBytes())
        val durable = dataStore.data.first()
        assertEquals(ReplicaSwitchStage.completionWitness(requireNotNull(durable[ReplicaSwitchStage.key(completed.recoveryId)])),
            durable[ReplicaSwitchStage.completionKey(completed.recoveryId)])
        assertEquals(requireNotNull(completed.release).materialHash, requireNotNull(completed.completion).materialHash)
        assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
        assertTrue(requireNotNull(tokens.localFactAccess()).canAppend); assertNotNull(tokens.localCoreWriteAccess())
        assertNotNull(tokens.iconAccessChanges.first())
        assertNotNull(WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
        assertFalse(tokens.canEditStructure.first())
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertFalse(db.nextRequestDao().hasAny())
    }

    private fun respond(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        assertEquals("GET", input.method); assertTrue(input.body.isEmpty())
        if (input.path == "/api/v2/system/identity") {
            assertNull(input.headers["authorization"])
            return MaterialSocketServer.Reply(buildJsonObject {
                put("server_instance_id", id(2)); put("sync_epoch", id(99)); put("protocol_version", 5)
                put("capabilities", JsonArray(listOf(JsonPrimitive("sync.read")))); put("server_time", time)
            }.toString().toByteArray())
        }
        assertEquals("Bearer $expectedAccess", input.headers["authorization"])
        assertEquals("5", input.headers["x-dayforge-protocol"])
        val url = requireNotNull(peer.origin.resolve(input.target))
        if (input.path == "/api/v2/sync/rounds/recovery-preview") {
            assertEquals(id(2), input.headers["x-dayforge-server-instance"])
            assertEquals(id(99), input.headers["x-dayforge-sync-epoch"])
            assertEquals(id(4), url.queryParameter("device_id")); assertEquals("1", url.queryParameter("challenge_contract"))
            assertEquals(installation, url.queryParameter("installation_id"))
            val original = bootstrap()
            val selected = if (!forwardDestination) original else original.copy(nextCursor = 21,
                changes = original.changes.map { change -> if (change.entityUuid != habit.uuid) change else change.copy(revision = 2,
                    payload = JsonObject(change.payload + mapOf("title" to JsonPrimitive("Forward after closed SQL"), "revision" to JsonPrimitive(2)))) })
            val response = ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(99),
                RecoveryDeviceProof(id(4), installation, "android", "interactive", null, null,
                    false, false, 2, listOf("sync.read", "facts.append"), time), selected, null)
            return MaterialSocketServer.Reply(json.encodeToString(recoveryMaterialReply(response, input, listOf(pack), listOf(asset))).toByteArray(),
                headers = mapOf("Cache-Control" to "private, max-age=3600"))
        }
        assertEquals(id(2), url.queryParameter("server_instance_id")); assertEquals(id(99), url.queryParameter("sync_epoch"))
        assertEquals(id(4), url.queryParameter("device_id"))
        return when (input.path) {
            "/api/v2/appearance/catalog" -> {
                val catalog = if (protocol.case == ReplicaCompletionProcessProtocol.Case.FILE_REPAIR)
                    listOf(AppearanceCatalogEntry.Asset(1, asset), AppearanceCatalogEntry.Pack(2, pack)) else emptyList()
                val after = requireNotNull(url.queryParameter("after")).toLong()
                val through = catalog.lastOrNull()?.sequence ?: 0
                MaterialSocketServer.Reply(json.encodeToString(AppearanceCatalogPage(AssetSyncContext(id(2), id(99), id(4)),
                    catalog.filter { it.sequence > after }, through, through, false)).toByteArray())
            }
            "/api/v2/appearance/assets/${asset.assetId}/content/light" -> {
                check(protocol.case == ReplicaCompletionProcessProtocol.Case.FILE_REPAIR)
                MaterialSocketServer.Reply(svg, type = "image/svg+xml", headers = mapOf("Cache-Control" to "private, no-store", "X-Content-Type-Options" to "nosniff"))
            }
            else -> error("REPLICA_PROCESS_UNEXPECTED_HTTP_ROUTE")
        }
    }

    private suspend fun closeResources() {
        check(!closeAttempted); closeAttempted = true
        var failure: Throwable? = null
        suspend fun finish(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error) }
        }
        finish { if (::client.isInitialized) stopHttp() }
        finish { if (::peer.isInitialized) peer.close() }
        finish { if (::authScope.isInitialized) withTimeout(5_000) { requireNotNull(authScope.coroutineContext[Job]).cancelAndJoin() } }
        finish { if (::iconDb.isInitialized) { iconDb.close(); assertFalse(iconDb.isOpen) } }
        finish {
            if (::db.isInitialized) {
                val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
                check(!runtime.hasUnknownGraphBoundary()) { "REPLICA_PROCESS_NATIVE_END_UNKNOWN" }
                val proof = withTimeout(5_000) {
                    var sealed: CountTrustOpenHelperFactory.GraphDrain.Ready? = null
                    while (sealed == null) {
                        check(!runtime.hasUnknownGraphBoundary())
                        when (val value = runtime.captureGraphDrain(db)) {
                            is CountTrustOpenHelperFactory.GraphDrain.Ready ->
                                if (runtime.trySealGraphClose(db, value.witness, knownOnly = true)) sealed = value
                            CountTrustOpenHelperFactory.GraphDrain.Busy -> Unit
                            CountTrustOpenHelperFactory.GraphDrain.Unknown -> error("REPLICA_PROCESS_NATIVE_END_UNKNOWN")
                        }
                        if (sealed == null) delay(10)
                    }
                    requireNotNull(sealed)
                }
                db.close(); runtime.requireGraphClosed(db, proof.witness); assertFalse(db.isOpen)
                HabitDatabaseProvider.clearInstanceForTesting()
            }
        }
        failure?.let { throw it }
        check(::db.isInitialized && ::iconDb.isInitialized && ::peer.isInitialized && peer.closed)
        val records = peer.evidence()
        writeOnce(File(protocol.phaseDirectory, "peer-requests.json"), buildJsonObject {
            put("run", protocol.run); put("case", protocol.case.wire); put("nonce", protocol.nonce)
            put("phase", phase.wire); put("pid", Process.myPid()); put("origin", peer.origin.toString()); put("requests", records)
        }.toString().toByteArray())
        resourcesClosed = true
        protocol.remainingMillis()
    }
    private fun closedEvidence(): JsonObject {
        check(resourcesClosed && !db.isOpen && !iconDb.isOpen && peer.closed)
        check(requireNotNull(authScope.coroutineContext[Job]).isCompleted)
        return buildJsonObject {
            put("resources_closed", true); put("no_unknown_graph_boundary", true)
            put("peer_requests_sha256", sha(File(protocol.phaseDirectory, "peer-requests.json")))
            put("main_database", "habit_database"); put("icon_database", AccountIconDatabase.NAME); put("auth_file", "auth.preferences_pb")
        }
    }
    private fun stageEvidence(stage: ReplicaSwitchStage): JsonObject {
        assertArrayEquals(baselineArchive.readBytes(), archive(stage).readBytes())
        return buildJsonObject {
            put("seed_sha256", sha(seedFile)); put("archive_sha256", sha(baselineArchive)); put("recovery_id", stage.recoveryId)
            put("stage_sha256", stageHash(stage)); put("stage_phase", stage.phase.name); put("stage_generation", stage.generation)
        }
    }
    private fun stageHash(stage: ReplicaSwitchStage) = ReplicaCompletionProcessProtocol.sha256(ReplicaSwitchStage.encode(stage).toByteArray())
    private suspend fun readStoredStage(name: String) = ReplicaSwitchStage.decode(File(directory, name).readText())
    private fun readJson(file: File): JsonObject {
        check(file.isFile && file.length() in 1..1_048_576)
        return CountCommandRecords.parse(file.readBytes(), 1_048_576, {})
    }
    private fun sha(file: File) = ReplicaCompletionProcessProtocol.sha256(file.readBytes())
    private fun writeOnce(file: File, bytes: ByteArray) {
        check(!file.exists()); val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (failure: Exception) { if (failure is CancellationException) throw failure; return failure }
        throw AssertionError("The original recovery fault must reject")
    }
}
