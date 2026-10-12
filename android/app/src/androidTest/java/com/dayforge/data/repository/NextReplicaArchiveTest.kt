package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.appearance.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountIconRuntime
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room/DataStore/material files/private archive/TCP once ACK; no formal app or data. */
@RunWith(AndroidJUnit4::class)
class NextReplicaArchiveTest : NextCoreRequestFixture() {
    @get:org.junit.Rule(order = -2) val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    private val json = Json { encodeDefaults = true }
    private lateinit var iconDb: AccountIconDatabase
    private lateinit var icons: AccountIconRepository
    private lateinit var iconFiles: AccountIconFiles
    private lateinit var iconStore: AccountIconStore
    private lateinit var transfers: AccountIconTransfers
    private lateinit var themes: ThemeFileRepository
    private lateinit var builtIns: BuiltInThemes
    private lateinit var http: NextSyncHttp
    private lateinit var peer: MaterialSocketServer
    private var task: HabitEntity? = null
    private var remoteCount = false
    private var recoveryWire: ReplicaRecoveryPreviewResponse? = null
    private var materialPreviewTransform: (ReplicaRecoveryPreviewResponse) -> ReplicaRecoveryPreviewResponse = { it }
    private val svg = """<svg width="1" height="1"><rect width="1" height="1" fill="#000000"/></svg>""".toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun blob() = IconBlob(sha(svg), svg.size, "image/svg+xml", 1, 1)
    private fun asset(purpose: String = "general") = IconAsset(id(100), "Original icon", purpose, "template", blob(), blob())
    private fun pack() = IconPack("dayforge.icon-pack", 1, id(101), 1, "Original style", listOf(asset()),
        mapOf("habit.custom" to id(100), "metric.custom" to id(100)), id(100))
    private fun configureIcons() {
        icons = AccountIconRepository(iconDb, tokens, sessions); iconFiles = AccountIconFiles(directory)
        iconStore = AccountIconStore(icons, iconFiles); transfers = AccountIconTransfers(iconDb, icons, iconStore)
    }
    @Before fun materialSetup() {
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        iconDb = AccountIconDatabase.open(app); assertEquals(5, iconDb.openHelper.writableDatabase.version); configureIcons()
        themes = ThemeFileRepository(directory); builtIns = BuiltInThemes(app.assets)
    }
    @After fun materialCleanup() {
        if (::iconDb.isInitialized) iconDb.close()
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }
    private fun reader(files: AccountIconFiles = iconFiles, owner: TokenManager = tokens,
        coordinator: com.dayforge.domain.service.AccountSessionCoordinator = sessions,
        values: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = dataStore) =
        NextReplicaArchiveInputReader(app, db, owner, coordinator, preferences, http, iconDb, icons, transfers,
            files, values, themes, builtIns)
    private fun service(store: ReplicaPrivateArchiveStore = ReplicaPrivateArchiveStore(directory)) = NextReplicaArchiveService(reader(), store, tokens)
    private suspend fun storageHash() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }
    private suspend fun inspect(block: suspend (ReplicaArchiveInput, ReplicaArchiveFileAccess) -> Unit) =
        reader().inspect(access(), storageHash(), block)
    private fun archive(receipt: ReplicaArchiveReceipt) = File(directory,
        "replica-archives-v1/${receipt.namespace.accountId}/${receipt.namespace.serverInstanceId}/${receipt.namespace.syncEpoch}/${receipt.archiveId}.candidate")
    private fun iconRoot() = File(directory, "account-icons-v1/${id(1)}/${id(2)}/${id(3)}")
    private fun themeRoot() = File(directory, "theme-definitions-v1")
    private fun metadata() = ChallengeMetadata(1, listOf(habit.uuid, timerHabit.uuid).map { uuid ->
        val head = initialChallengeRoundHead(uuid)
        ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
    }, if (remoteCount) listOf(ChallengeBirth("activity_event", id(50), initialChallengeRoundHead(habit.uuid))) else emptyList())
    private fun canonical(kind: String, uuid: String, payload: JsonObject) = SyncV2Change(0, kind, uuid, "upsert", 1,
        JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun oldCount() = canonical("activity_event", id(50), buildJsonObject {
        put("activity_uuid", habit.uuid); put("event_type", "count_delta"); put("value", 3)
        for (key in listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "reverts_event_uuid")) put(key, JsonNull)
        put("occurred_at", time); put("received_at", time); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
        put("note", "Original unknown rule"); put("source_type", "app"); put("source_device_id", id(4))
        put("external_event_id", id(50)); put("metadata", buildJsonObject {})
    })
    private suspend fun initialize(fixed: Boolean = false, once: Boolean = false, unknownCount: Boolean = false) {
        register(); remoteCount = unknownCount
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            if (fixed) { habit = habit.copy(appearance = habit.appearance!!.copy(icon = IconReference.Asset(id(100)))); db.habitDao().update(habit) }
            if (once) {
                val row = habit.copy(id = 0, uuid = id(13), name = "Once", habitType = HabitType.CHECK_IN,
                    schedule = HabitSchedule.Once(), targetValue = 1, completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                    appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
                task = row.copy(id = db.habitDao().insert(row))
            }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val channel = channel { input ->
            if (input.path.endsWith("/identity")) reply(input).let { reply ->
                if (recoveryWire == null) reply else reply.copy(bytes = reply.bytes.toString(Charsets.UTF_8)
                    .replace(id(3), id(99)).toByteArray())
            } else if (input.path == "/api/v2/sync/rounds/recovery-preview") {
                assertEquals("GET", input.method)
                val response = recoveryMaterialReply(requireNotNull(recoveryWire), input, listOf(pack()), listOf(asset()))
                MaterialSocketServer.Reply(json.encodeToString(if (response.materialPreview == null) response
                    else materialPreviewTransform(response)).toByteArray())
            } else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
                val operation = request.operations.single()
                val results = json.decodeFromString<NextSyncPushResponse>(successReply(input, (operation.baseRevision ?: 0) + 1) { body ->
                    if (operation.payload["one_time"] is JsonObject) {
                        val intent = operation.payload.getValue("one_time").jsonObject
                        JsonObject(body + ("one_time_state_after" to buildJsonObject {
                            put("version", intent.getValue("expected_version").jsonPrimitive.int + 1)
                            put("head_event_uuid", intent.getValue("event_uuid")); put("completion_event_uuid", intent.getValue("event_uuid"))
                        }))
                    } else if (operation.entityType in setOf("plan_node", "metric")) JsonObject(body + ("created_at" to JsonPrimitive(time))) else body
                }.bytes.toString(Charsets.UTF_8)).results
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(results, 1, meta.checkpoints, meta.births)).toByteArray())
            }
        }
        http = channel.first; peer = channel.second
        val core = sender(http); val timers = NextTimerRequestStore(db, tokens, sessions, core)
        val onceStore = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        val changes = listOf(habit, timerHabit).plus(listOfNotNull(task)).map {
            canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it))
        } + canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)) + (if (unknownCount) listOf(oldCount()) else emptyList())
        val meta = metadata()
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions, onceStore, timerRequests = timers), timers)
            .bootstrap(access(), null, RoundSyncBootstrapResponse(changes, 20, time,
                task?.let { listOf(OneTimeProjection(it.uuid, OneTimeState(0, null, null))) }.orEmpty(), 1, meta.checkpoints, meta.births))
    }
    private suspend fun completeMaterial() {
        val context = icons.capture(); icons.reserveAsset(context, asset()); icons.reservePack(context, pack())
        iconStore.install(context, id(100), blob().sha256, svg); iconStore.select(context, 0, IconPackVersion(id(101), 1))
        transfers.enqueuePack(context, IconPackVersion(id(101), 1))
        while (true) {
            val attempt = transfers.prepareNext(context) ?: break
            when (attempt.job.kind) {
                IconTransferKind.DECLARE_ASSET -> transfers.confirmAsset(attempt, AssetRecord(attempt.binding, attempt.asset!!, listOf("dark", "light")))
                IconTransferKind.DECLARE_PACK -> transfers.confirmPack(attempt, PackDeclaration(attempt.binding, attempt.pack!!))
                IconTransferKind.UPLOAD -> transfers.confirmUpload(attempt, AssetTransferReceipt(attempt.binding, id(100), attempt.job.variant, blob()))
                IconTransferKind.DOWNLOAD -> error("No download queued")
            }
        }
    }
    private suspend fun initializeThemes() {
        ThemeCatalogRepository(dataStore, themes, builtIns).initialize()
        DeviceThemeStore(dataStore, themes).select(0, DeviceThemeSelection(ThemeVersionRef(BuiltInTheme.OCEAN.themeId, 1),
            ThemeVersionRef(BuiltInTheme.DUSK.themeId, 1), DeviceThemeMode.SYSTEM, DeviceCardStyle.PERSONALIZED))
    }
    private suspend fun unchanged(block: suspend () -> Unit) {
        val before = storage.snapshotClosedDatabase(); db.openHelper.writableDatabase
        val prefs = dataStore.data.first(); val scope = access(); val calls = peer.requests.size
        val iconsBefore = icons.recoveryMetadata(icons.capture())
        block()
        assertArrayEquals(before, storage.snapshotClosedDatabase()); assertEquals(prefs, dataStore.data.first())
        assertEquals(scope, access()); assertEquals(calls, peer.requests.size); assertEquals(iconsBefore, icons.recoveryMetadata(icons.capture()))
    }

    private suspend fun startRecovery() {
        val installation = tokens.getOrCreateInstallationId()
        recoveryWire = ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(99),
            RecoveryDeviceProof(id(4), installation, "android", "interactive", null, null,
                false, false, 1, listOf("sync.read", "facts.append"), time),
            RoundSyncBootstrapResponse(listOf(habit, timerHabit).map {
                canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it))
            } + canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)),
                20, time, emptyList(), 1, metadata().checkpoints, metadata().births), null)
    }
    private fun validator() = NextReplicaCandidateValidator(app, tokens, sessions, preferences)
    private fun differences() = NextReplicaDifferenceReader(db, tokens, sessions, preferences)
    private suspend fun recoveryCandidate() = validator().validate(access(), tokens.registeredInstallationId()!!,
        http.recoveryPreview(access(), id(99), tokens.registeredInstallationId()!!))
    private fun workflow(store: ReplicaPrivateArchiveStore = ReplicaPrivateArchiveStore(directory)): NextReplicaRecoveryWorkflow {
        val coordinator = BusinessSyncRepository(io.mockk.mockk(), io.mockk.mockk(), db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
        return NextReplicaRecoveryWorkflow(coordinator, db, tokens, sessions, http, validator(), differences(), reader(), store)
    }
    private fun receipt(stage: ReplicaSwitchStage): ReplicaArchiveReceipt {
        val saved = stage.archive!!
        return ReplicaArchiveReceipt(AccountIconNamespace(stage.accountId, stage.serverInstanceId, stage.oldEpoch),
            saved.archiveId, saved.inputHash, saved.byteLength, saved.contentHash,
            saved.entries.map { ReplicaArchiveEntry(it.name, it.byteLength, it.contentHash) })
    }

    private suspend fun commitTarget(candidate: ValidatedReplicaCandidate) = candidate.deviceProof.let {
        ReplicaStageTarget(candidate.projection.hash, ReplicaProjection.schemaHash(db), it.deviceId,
            it.capabilityRevision, it.capabilities.sorted(), it.isPrimaryEditor, it.structuralEditEnabled)
    }
    private suspend fun coldReopen() {
        scope.coroutineContext[Job]!!.cancelAndJoin(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(directory, "auth.preferences_pb") })
        tokens = TokenManager(dataStore); preferences = PreferencesManager(dataStore); storage.reopen(); configureIcons()
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(com.dayforge.data.api.interceptor.BaseUrlInterceptor(preferences))
            .addInterceptor(com.dayforge.data.api.interceptor.AuthInterceptor(tokens))
            .authenticator(com.dayforge.data.api.authenticator.TokenAuthenticator(tokens, preferences,
                com.dayforge.data.api.SelectedNetworkTransport())).build()
        http = NextSyncHttp(client, tokens, peer.origin)
    }

    @Test fun missingExactMaterialPackBlocksBeforeAnyFreezeOrArchive() = runBlocking<Unit> {
        initialize(); completeMaterial(); startRecovery()
        val preview = differences().capture(recoveryCandidate())
        val original = storageHash(); val preferencesBefore = dataStore.data.first()
        materialPreviewTransform = { response -> response.copy(materialPreview = response.materialPreview!!.copy(pack = null, assets = emptyList())) }
        assertEquals("ICON_RECOVERY_PACK_MISSING", rejected { workflow().prepare(preview) }.message)
        assertNull(tokens.activeReplicaStage()); assertNull(db.nextReplicaFenceDao().state())
        assertEquals(original, storageHash()); assertEquals(preferencesBefore, dataStore.data.first())
        assertFalse(File(directory, "replica-archives-v1").exists())
    }

    @Test fun freshMaterialLossInvalidatesConfirmationAndColdResumeWithoutCommitting() = runBlocking<Unit> {
        initialize(); completeMaterial(); startRecovery()
        val flow = workflow(); val stage = flow.prepare(differences().capture(recoveryCandidate()))
        val evidence = flow.verifyForConfirmation(stage)
        evidence.materials.requireAvailable(); assertFalse(evidence.materials.nativeFilesVerified)
        val before = storageHash(); val saved = archive(receipt(stage)).readBytes()
        materialPreviewTransform = { response -> response.copy(materialPreview = response.materialPreview!!.let { material ->
            material.copy(assets = material.assets.map { it.copy(readyVariants = emptyList()) })
        }) }
        assertEquals("ICON_RECOVERY_CONTENT_PENDING", rejected { flow.confirm(evidence) }.message)
        assertEquals(before, storageHash()); assertEquals(stage, tokens.activeReplicaStage())
        assertArrayEquals(saved, archive(receipt(stage)).readBytes())
        coldReopen()
        assertEquals("ICON_RECOVERY_CONTENT_PENDING", rejected { workflow().verifyForConfirmation(stage) }.message)
        assertEquals(stage, tokens.activeReplicaStage()); assertEquals(before, storageHash())
        assertArrayEquals(saved, archive(receipt(stage)).readBytes())
        assertEquals(ReplicaSwitchPhase.ABORTED, workflow().cancel(stage).phase)
    }

    @Test fun explicitConfirmationCommitsExactProjectionAndKeepsSqlAndPublicationFrozenAfterColdReopen() = runBlocking<Unit> {
        initialize(); completeMaterial(); initializeThemes(); startRecovery()
        val original = access(); val themeSelection = DeviceThemeStore(dataStore, themes).read()
        val wire = recoveryWire!!
        recoveryWire = wire.copy(snapshot = wire.snapshot.copy(changes = wire.snapshot.changes.filterNot { it.entityType == "metric" } +
            canonical("metric", id(200), NextStructureMapper.writeMetric(metric.copy(uuid = id(200), name = "New scope weight",
                targetDirection = "range", targetValue = 1.25, targetValueUpper = 6.75)))))
        val staged = workflow().prepare(differences().capture(recoveryCandidate()))
        val evidence = workflow().verifyForConfirmation(staged); val archiveBytes = archive(receipt(staged)).readBytes()
        // Semantic ordering/server_time changes cannot change the validated target or private row IDs.
        val freshWire = recoveryWire!!
        recoveryWire = freshWire.copy(snapshot = freshWire.snapshot.copy(serverTime = "2026-10-06T00:01:00Z",
            changes = freshWire.snapshot.changes.reversed()), device = freshWire.device.copy(capabilities = freshWire.device.capabilities.reversed()))
        val committed = workflow().confirm(evidence)
        assertEquals(ReplicaSwitchPhase.SQL_COMMITTED, committed.phase); assertEquals(5L, committed.generation)
        assertEquals(evidence.candidate.projection.hash, ReplicaProjection.capture(db).hash)
        assertEquals(committed.target!!.schemaHash, ReplicaProjection.schemaHash(db))
        val state = db.nextSyncStateDao().rows().single()
        assertEquals(id(99), state.syncEpoch); assertEquals(committed.candidateHash, state.bootstrapHash)
        assertEquals(state.bootstrapHash, state.batchHash); assertEquals(20L, state.cursor)
        assertNull(db.metricDao().getMetricByUuid(metric.uuid)); assertEquals("New scope weight", db.metricDao().getMetricByUuid(id(200))!!.name)
        assertEquals(1.25, db.metricDao().getMetricByUuid(id(200))!!.targetValue!!, 0.0)
        assertEquals(6.75, db.metricDao().getMetricByUuid(id(200))!!.targetValueUpper!!, 0.0)
        assertEquals(original, access()); assertEquals(id(3), tokens.syncEpoch.first())
        assertNull(tokens.localCoreWriteAccess()); assertFalse(tokens.localFactAccess()!!.canAppend)
        assertNull(WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
        assertEquals(committed.fence, db.nextReplicaFenceDao().state())
        assertNotNull(rejected { db.habitDao().update(habit.copy(name = "ordinary write after COMMIT")) })
        assertNotNull(rejected { workflow().cancel(committed) }); assertNotNull(rejected { workflow().confirm(evidence) })
        assertArrayEquals(archiveBytes, archive(receipt(committed)).readBytes())
        assertEquals(themeSelection, DeviceThemeStore(dataStore, themes).read())
        val after = ReplicaProjection.capture(db).hash; val calls = peer.requests.size
        coldReopen(); assertEquals(committed, workflow().resume(tokens.activeReplicaStage()!!))
        assertEquals(after, ReplicaProjection.capture(db).hash); assertEquals(calls, peer.requests.size)
        assertNull(tokens.localCoreWriteAccess()); assertEquals(original, access())
    }

    @Test fun sqlCommittedBeforeJournalDeliveryOnlyMovesForwardAfterActualColdProofAndSameOwnerReauthentication() = runBlocking<Unit> {
        initialize(); startRecovery()
        val archived = workflow().prepare(differences().capture(recoveryCandidate()))
        val evidence = workflow().verifyForConfirmation(archived)
        val committing = NextReplicaStageStore(db, tokens, sessions).recordCommitting(archived, commitTarget(evidence.candidate))
        NextReplicaCommitStore(db, tokens, sessions).commit(committing, evidence.candidate)
        val after = ReplicaProjection.capture(db).hash; val archiveBytes = archive(receipt(archived)).readBytes()
        assertEquals(committing, tokens.activeReplicaStage()); assertEquals(id(99), db.nextSyncStateDao().rows().single().syncEpoch)
        assertNotNull(rejected { workflow().cancel(committing) })
        coldReopen(); tokens.clearAuthenticationTokens()
        assertNotNull(rejected { workflow().resume(committing) }); assertEquals(after, ReplicaProjection.capture(db).hash)
        tokens.saveLoginSession("synthetic-relogin", "synthetic-refresh", "member", id(1), false)
        val calls = peer.requests.size; val committed = workflow().resume(committing)
        assertEquals(ReplicaSwitchPhase.SQL_COMMITTED, committed.phase); assertEquals(after, ReplicaProjection.capture(db).hash)
        assertEquals(calls, peer.requests.size); assertEquals("synthetic-relogin", tokens.accessToken.first())
        assertEquals(id(3), tokens.syncEpoch.first()); assertNull(tokens.localCoreWriteAccess())
        assertArrayEquals(archiveBytes, archive(receipt(committed)).readBytes()); assertEquals(committed.fence, db.nextReplicaFenceDao().state())
    }

    @Test fun fullNonemptySnapshotCommitsCountCompleteTimerOnceHistoryObservationAndLinkWithoutCreatingRequests() = runBlocking<Unit> {
        initialize(); startRecovery()
        val once = habit.copy(id = 0, uuid = id(13), name = "Recovered once", habitType = HabitType.CHECK_IN,
            schedule = HabitSchedule.Once(), targetValue = 1, completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
            appearance = requireNotNull(habit.appearance).copy(icon = IconReference.Role("task.custom")))
        val completedOnce = canonical("activity_event", id(52), buildJsonObject {
            put("activity_uuid", once.uuid); put("event_type", "check_in"); put("value", "1.0")
            put("occurred_at", time); put("received_at", time); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
            put("source_type", "app"); put("source_device_id", id(4)); put("note", "Original once")
            put("metadata", buildJsonObject {})
            for (key in listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "external_event_id", "reverts_event_uuid")) put(key, JsonNull)
            put("one_time", buildJsonObject {
                put("event_uuid", id(52)); put("action", "complete"); put("expected_version", 0)
                put("expected_head_event_uuid", JsonNull); put("reverts_event_uuid", JsonNull)
            })
            put("one_time_state_after", buildJsonObject {
                put("version", 1); put("head_event_uuid", id(52)); put("completion_event_uuid", id(52))
            })
        })
        val duration = canonical("activity_event", id(53), buildJsonObject {
            val end = java.time.Instant.ofEpochMilli(millis + 60_000).toString()
            put("activity_uuid", timerHabit.uuid); put("event_type", "duration_session"); put("value", JsonNull)
            put("duration_seconds", 60); put("duration_milliseconds", 60_000); put("started_at", time); put("ended_at", end)
            put("occurred_at", end); put("received_at", time); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
            put("source_type", "app"); put("source_device_id", id(4)); put("external_event_id", id(53)); put("note", "Original timer")
            put("reverts_event_uuid", JsonNull); put("metadata", buildJsonObject { put("timer_session_id", id(53)) })
            put("day_allocations", buildJsonArray { add(buildJsonObject {
                put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai"); put("duration_milliseconds", 60_000)
            }) })
        })
        val observation = canonical("metric_observation", id(54), buildJsonObject {
            put("metric_uuid", metric.uuid); put("value", "21.125"); put("unit", "kg")
            put("occurred_at", time); put("received_at", time); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
            put("note", "Original observation"); put("source_type", "app"); put("source_device_id", id(4))
            put("external_event_id", JsonNull); put("metadata", buildJsonObject {})
        })
        val link = canonical("activity_metric_link", id(55), buildJsonObject {
            put("activity_uuid", habit.uuid); put("metric_uuid", metric.uuid); put("coefficient", "0.5")
            put("show_in_activity_detail", true); put("prompt_on_complete", true); put("is_active", true)
        })
        val wire = recoveryWire!!
        recoveryWire = wire.copy(snapshot = wire.snapshot.copy(changes = wire.snapshot.changes + listOf(
            canonical("plan_node", once.uuid, NextStructureMapper.writePlan(once)), oldCount(), duration, completedOnce, observation, link),
            oneTimeCheckpoints = listOf(OneTimeProjection(once.uuid, OneTimeState(1, id(52), id(52)))),
            births = listOf(ChallengeBirth("activity_event", id(50), initialChallengeRoundHead(habit.uuid)),
                ChallengeBirth("activity_event", id(53), initialChallengeRoundHead(timerHabit.uuid)),
                ChallengeBirth("timer_session", id(53), initialChallengeRoundHead(timerHabit.uuid)))))
        val archived = workflow().prepare(differences().capture(recoveryCandidate()))
        val evidence = workflow().verifyForConfirmation(archived); val committed = workflow().confirm(evidence)
        assertEquals(ReplicaSwitchPhase.SQL_COMMITTED, committed.phase)
        assertEquals(evidence.candidate.projection.hash, ReplicaProjection.capture(db).hash)
        assertEquals(3, db.completionDao().getCompletionByUuid(id(50))!!.value)
        assertEquals(millis, db.completionDao().getCompletionByUuid(id(50))!!.actualCompletedAt)
        assertEquals(1, db.habitDao().getHabitByUuid(once.uuid)!!.oneTimeConfirmedVersion)
        assertEquals("complete", db.completionDao().getCompletionByUuid(id(52))!!.oneTimeAction)
        val timer = db.timeLogDao().getTimeLogByUuid(id(53))!!
        assertEquals(60, timer.durationSeconds); assertEquals(millis, timer.startTime); assertEquals(millis + 60_000, timer.endTime)
        assertEquals(1, count("timelog_day_allocations")); assertEquals(1, count("habit_metric_links"))
        val value = db.metricLogDao().getLogByUuid(id(54))!!
        assertEquals(21.125, value.value, 0.0); assertEquals(millis, value.date); assertEquals("Asia/Shanghai", value.recordedTimezone)
        assertEquals("Original observation", value.note); assertEquals(0, count("sync_outbox")); assertEquals(0, count("timer_command_outbox"))
        assertFalse(db.nextRequestDao().hasAny()); assertEquals(id(3), tokens.syncEpoch.first()); assertNull(tokens.localCoreWriteAccess())
    }

    @Test fun actualSqlFullRollsBackDataAndGuardDdlAndColdOldFateRequiresExplicitRetryOrCancel() = runBlocking<Unit> {
        initialize(); startRecovery()
        val wire = recoveryWire!!
        recoveryWire = wire.copy(snapshot = wire.snapshot.copy(changes = wire.snapshot.changes + (200..229).map {
            canonical("metric", id(it), NextStructureMapper.writeMetric(metric.copy(uuid = id(it), name = "New metric $it",
                description = "x".repeat(1000))))
        }))
        val archived = workflow().prepare(differences().capture(recoveryCandidate()))
        val evidence = workflow().verifyForConfirmation(archived); val schema = ReplicaProjection.schemaHash(db)
        val sql = db.openHelper.writableDatabase
        fun pragma(name: String) = sql.query("PRAGMA $name").use { check(it.moveToFirst()); it.getLong(0) }
        // Bind the fault to the primary transaction connection, not a WAL read connection.
        val maximum = db.withTransaction {
            val before = pragma("max_page_count"); val pages = pragma("page_count")
            assertEquals(pages, pragma("max_page_count=$pages")); before
        }
        try {
            val error = rejected { workflow().confirm(evidence) }
            assertTrue("Must exercise actual storage-full rollback, got $error", generateSequence(error as Throwable?) { it.cause }
                .any { it is android.database.sqlite.SQLiteFullException })
        } finally { db.withTransaction { assertEquals(maximum, pragma("max_page_count=$maximum")) } }
        val committing = tokens.activeReplicaStage()!!; assertEquals(ReplicaSwitchPhase.COMMITTING, committing.phase)
        assertEquals(ReplicaCommitFate.OLD, NextReplicaCommitStore(db, tokens, sessions).fate(committing))
        assertEquals(schema, ReplicaProjection.schemaHash(db)); assertEquals(id(3), db.nextSyncStateDao().rows().single().syncEpoch)
        assertEquals(1, count("metrics")); assertEquals(committing.fence, db.nextReplicaFenceDao().state())
        assertNotNull(rejected { db.metricDao().update(metric.copy(name = "still blocked")) })
        coldReopen(); assertEquals(committing, workflow().resume(committing))
        assertEquals(1, count("metrics")); assertEquals(id(3), tokens.syncEpoch.first()); assertNull(tokens.localCoreWriteAccess())
        // Actual SQL cancel succeeded but its DS delivery was lost. Exact cancel must still finish forward.
        NextReplicaWriteFence(db).closeBeforeCommit(committing.fence)
        coldReopen(); assertEquals(committing, tokens.activeReplicaStage()); assertNull(tokens.localCoreWriteAccess())
        val ended = workflow().cancel(committing)
        assertEquals(ReplicaSwitchPhase.ABORTED, ended.phase); assertEquals(5L, ended.generation)
        assertEquals(ended, workflow().cancel(committing)); assertEquals(committing.target, ended.target)
        assertEquals(committing.archive, ended.archive); assertNotNull(tokens.localCoreWriteAccess())
        assertEquals("Weight", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
    }

    @Test fun corruptedArchiveAndChangedSnapshotRejectExplicitConfirmationBeforeAnySqlOrCommittingPhase() = runBlocking<Unit> {
        initialize(); startRecovery(); val archived = workflow().prepare(differences().capture(recoveryCandidate()))
        val evidence = workflow().verifyForConfirmation(archived); val before = storageHash()
        val wire = recoveryWire!!; recoveryWire = wire.copy(snapshot = wire.snapshot.copy(nextCursor = 21))
        assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { workflow().confirm(evidence) }.message)
        recoveryWire = wire
        val file = archive(receipt(archived)); val bytes = file.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNotNull(rejected { workflow().confirm(evidence) })
        assertEquals(archived, tokens.activeReplicaStage()); assertEquals(before, storageHash())
        assertEquals(id(3), db.nextSyncStateDao().rows().single().syncEpoch); assertNull(tokens.localCoreWriteAccess())
    }

    @Test fun actualPostCommitSchemaOrRowDamageCannotBeMistakenForAnOldOrCommittedReplica() = runBlocking<Unit> {
        initialize(); startRecovery(); val archived = workflow().prepare(differences().capture(recoveryCandidate()))
        val evidence = workflow().verifyForConfirmation(archived)
        val committing = NextReplicaStageStore(db, tokens, sessions).recordCommitting(archived, commitTarget(evidence.candidate))
        val store = NextReplicaCommitStore(db, tokens, sessions); store.commit(committing, evidence.candidate)
        val sql = db.openHelper.writableDatabase
        val guard = sql.query("SELECT sql FROM sqlite_master WHERE name='replica_fence_metrics_update'").use { check(it.moveToFirst()); it.getString(0) }
        sql.execSQL("DROP TRIGGER replica_fence_metrics_update")
        assertEquals("SYNC_REPLICA_FENCE_CHANGED", rejected { workflow().resume(committing) }.message)
        sql.execSQL(guard); assertEquals(ReplicaCommitFate.COMMITTED, store.fate(committing))
        // Deliberate corruption only in isolated testbed: same row/count/cursor, exact DDL restored.
        sql.execSQL("DROP TRIGGER replica_fence_metrics_update")
        // Timestamp-only corruption is not a tracked business edit, so the normal outbox trigger does
        // not block this deliberate raw corruption after removing just the exact row guard.
        sql.execSQL("UPDATE metrics SET updatedAt=updatedAt+1 WHERE uuid=?", arrayOf(metric.uuid))
        sql.execSQL(guard)
        val after = ReplicaProjection.capture(db).hash
        assertNotEquals(committing.target!!.projectionHash, after)
        assertEquals("SYNC_REPLICA_COMMIT_STATE_INVALID", rejected { workflow().resume(committing) }.message)
        assertNotNull(rejected { workflow().cancel(committing) }); assertEquals(committing, tokens.activeReplicaStage())
        assertEquals(after, ReplicaProjection.capture(db).hash); assertEquals(id(99), db.nextSyncStateDao().rows().single().syncEpoch)
        assertNull(tokens.localCoreWriteAccess()); assertEquals(committing.fence, db.nextReplicaFenceDao().state())
    }

    @Test fun completeWorkflowPersistsArchiveAndColdVerifiesFreshEvidenceWithoutSwitchingScope() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial(); initializeThemes(); startRecovery()
        val original = access(); val initial = differences().capture(recoveryCandidate())
        val before = db.withTransaction { NextReplicaSourceClosure.storage(NextReplicaStorageProof(db).readInTransaction(), null) }
        val staged = workflow().prepare(initial)
        assertEquals(ReplicaSwitchPhase.ARCHIVE_VERIFIED, staged.phase); assertEquals(3L, staged.generation)
        assertEquals(before, staged.sourceHash); assertNotNull(staged.archive)
        assertTrue(archive(receipt(staged)).exists()); assertEquals(staged, tokens.activeReplicaStage())
        assertNull(tokens.localCoreWriteAccess()); assertEquals(original, access())
        assertThrows(UnsupportedOperationException::class.java) { (staged.archive!!.entries as MutableList).clear() }
        storage.reopen()
        val oldWire = recoveryWire!!
        recoveryWire = oldWire.copy(snapshot = oldWire.snapshot.copy(serverTime = "2026-10-06T00:01:00Z",
            changes = oldWire.snapshot.changes.reversed()), device = oldWire.device.copy(capabilities = oldWire.device.capabilities.reversed()))
        assertEquals(staged, workflow().resume(tokens.activeReplicaStage()!!))
        val proof = workflow().verifyForConfirmation(staged)
        assertFalse(proof.activationAuthorized); assertTrue(proof.differences.differences.isEmpty())
        assertEquals(staged.candidateHash, proof.candidate.contentHash); assertNotEquals(staged.candidateWireHash, proof.candidate.wireHash)
        assertEquals(20L, db.nextSyncStateDao().rows().single().cursor); assertEquals(original, access())
        val ended = workflow().cancel(staged)
        assertEquals(4L, ended.generation); assertEquals(staged.archive, ended.archive)
        assertTrue(archive(receipt(staged)).exists()); assertNotNull(tokens.localCoreWriteAccess()); assertEquals(original, access())
    }

    @Test fun changedSourceOrMissingMaterialBeforeFreezeCannotCreateAnActiveStage() = runBlocking<Unit> {
        initialize(fixed = true); startRecovery()
        val preview = differences().capture(recoveryCandidate()); val before = storageHash()
        assertEquals("SYNC_RECOVERY_REVIEW_REQUIRED", rejected { workflow().prepare(preview) }.message)
        assertFalse(tokens.replicaSwitchPending()); assertNull(db.nextReplicaFenceDao().state()); assertEquals(before, storageHash())
        assertFalse(File(directory, "replica-archives-v1").exists())
        completeMaterial()
        val writer = producer(); writer.writeRounds(writer.captureRounds()) { metrics().recordValue(metric.id, 5.5, "", millis) }
        val changed = storageHash()
        assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { workflow().prepare(preview) }.message)
        assertFalse(tokens.replicaSwitchPending()); assertEquals(changed, storageHash()); assertEquals(1, db.syncOutboxDao().getAll().size)
    }

    @Test fun archiveFailureLeavesFrozenStageAndResumeCreatesNewCandidateWithoutAdoptingPartialFile() = runBlocking<Unit> {
        initialize(); startRecovery(); val preview = differences().capture(recoveryCandidate())
        val failing = ReplicaPrivateArchiveStore(directory, object : ReplicaArchiveIo() {
            override fun sync(fd: FileDescriptor) { throw IOException("simulated archive fsync failure") }
        })
        assertEquals("simulated archive fsync failure", rejected { workflow(failing).prepare(preview) }.message)
        val frozen = tokens.activeReplicaStage()!!; assertEquals(ReplicaSwitchPhase.FROZEN, frozen.phase); assertNull(frozen.archive)
        assertEquals(frozen.fence, db.nextReplicaFenceDao().state()); assertNull(tokens.localCoreWriteAccess())
        val root = File(directory, "replica-archives-v1/${id(1)}/${id(2)}/${id(3)}")
        val partial = root.listFiles()!!.single(); val retained = partial.readBytes()
        val wire = recoveryWire!!; recoveryWire = wire.copy(snapshot = wire.snapshot.copy(nextCursor = 21))
        assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { workflow().resume(frozen) }.message)
        assertEquals(frozen, tokens.activeReplicaStage()); assertEquals(1, root.listFiles()!!.size)
        recoveryWire = wire
        val archived = workflow().resume(frozen)
        assertEquals(ReplicaSwitchPhase.ARCHIVE_VERIFIED, archived.phase); assertNotEquals(partial, archive(receipt(archived)))
        assertArrayEquals(retained, partial.readBytes()); assertEquals(2, root.listFiles()!!.size)
        assertFalse(workflow().verifyForConfirmation(archived).activationAuthorized)
    }

    @Test fun corruptedArchiveOrChangedDeviceProofInvalidatesConfirmationWithoutReplacingTheReceipt() = runBlocking<Unit> {
        initialize(); startRecovery(); val stage = workflow().prepare(differences().capture(recoveryCandidate()))
        val wire = recoveryWire!!; recoveryWire = wire.copy(device = wire.device.copy(capabilityRevision = 2))
        assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { workflow().verifyForConfirmation(stage) }.message)
        recoveryWire = wire
        val file = archive(receipt(stage)); val bytes = file.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNotNull(rejected { workflow().verifyForConfirmation(stage) }); assertNotNull(rejected { workflow().resume(stage) })
        assertEquals(stage, tokens.activeReplicaStage()); assertEquals(stage.fence, db.nextReplicaFenceDao().state())
        assertEquals(1, file.parentFile!!.listFiles()!!.size); assertNull(tokens.localCoreWriteAccess())
        workflow().cancel(stage); assertArrayEquals(bytes, file.readBytes()); assertEquals(id(3), tokens.syncEpoch.first())
    }

    @Test fun coldResumeAtEveryActualFreezeGapRevalidatesFullInputsAndArchiveBeforeConfirmation() = runBlocking<Unit> {
        initialize(); completeMaterial(); initializeThemes(); startRecovery()
        for (gap in 0..2) {
            val fresh = recoveryCandidate(); val prior = db.nextReplicaFenceDao().state()
            val inputStage = reader().inspect(access(), storageHash()) { input, _ ->
                tokens.replicaStageFixture(NextReplicaSourceClosure.storage(input.storage, prior)).copy(newEpoch = id(99),
                    inputHash = NextReplicaSourceClosure.input(input, prior), candidateHash = fresh.contentHash,
                    candidateWireHash = fresh.wireHash, previousFenceHash = NextReplicaSourceClosure.controlHash(prior))
            }
            var stage = NextReplicaStageStore(db, tokens, sessions).begin(inputStage)
            if (gap > 0) NextReplicaWriteFence(db).acquire(stage.fence)
            if (gap == 2) stage = NextReplicaStageStore(db, tokens, sessions).recordFrozen(stage)
            scope.coroutineContext[Job]!!.cancelAndJoin(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope,
                produceFile = { File(directory, "auth.preferences_pb") })
            tokens = TokenManager(dataStore); preferences = PreferencesManager(dataStore); storage.reopen(); configureIcons()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            client = okhttp3.OkHttpClient.Builder()
                .addInterceptor(com.dayforge.data.api.interceptor.BaseUrlInterceptor(preferences))
                .addInterceptor(com.dayforge.data.api.interceptor.AuthInterceptor(tokens))
                .authenticator(com.dayforge.data.api.authenticator.TokenAuthenticator(tokens, preferences,
                    com.dayforge.data.api.SelectedNetworkTransport())).build()
            http = NextSyncHttp(client, tokens, peer.origin)
            assertEquals(stage, tokens.activeReplicaStage()); assertNull(tokens.localCoreWriteAccess())
            val archived = workflow().resume(stage); assertEquals(ReplicaSwitchPhase.ARCHIVE_VERIFIED, archived.phase)
            assertFalse(workflow().verifyForConfirmation(archived).activationAuthorized)
            workflow().cancel(archived); assertNotNull(tokens.localCoreWriteAccess()); assertEquals(id(3), tokens.syncEpoch.first())
        }
    }

    @Test fun sameOwnerReauthenticationCannotContinueArchivedConfirmationButCanCancelWithoutRestoringCredentials() = runBlocking<Unit> {
        initialize(); startRecovery(); val archived = workflow().prepare(differences().capture(recoveryCandidate()))
        tokens.clearAuthenticationTokens()
        assertNotNull(rejected { workflow().resume(archived) })
        tokens.saveLoginSession("synthetic-relogin", "synthetic-refresh", "member", id(1), false)
        assertEquals("SYNC_REPLICA_STAGE_AUTHORITY_CHANGED", rejected { workflow().verifyForConfirmation(archived) }.message)
        workflow().cancel(archived); assertEquals("synthetic-relogin", tokens.accessToken.first())
        assertEquals(id(3), tokens.syncEpoch.first()); assertTrue(archive(receipt(archived)).exists())
    }

    @Test fun frozenParticipantsStillAllowActualCompleteOldInputAndPrivateArchiveColdReadback() = runBlocking<Unit> {
        initialize(); completeMaterial(); initializeThemes()
        val stage = NextReplicaStageStore(db, tokens, sessions).begin(tokens.replicaStageFixture(storageHash()))
        NextReplicaWriteFence(db).acquire(stage.fence)
        val frozen = NextReplicaStageStore(db, tokens, sessions).recordFrozen(stage)
        val expected = access(); val before = storageHash(); val data = dataStore.data.first(); val calls = peer.requests.size
        inspect { input, _ ->
            assertFalse(input.hasReview); assertEquals(1, input.storage.contents.getValue("tables").jsonObject
                .getValue("next_replica_fence").jsonArray.size)
            assertTrue(input.iconFiles.files.isNotEmpty()); assertTrue(input.themes.files.isNotEmpty())
        }
        val receipt = service().create(expected, before)
        service(ReplicaPrivateArchiveStore(directory)).verify(expected, receipt)
        assertEquals(before, storageHash()); assertEquals(data, dataStore.data.first()); assertEquals(calls, peer.requests.size)
        assertEquals(frozen, tokens.activeReplicaStage()); assertFalse(receipt.activationAuthorized)
        NextReplicaStageStore(db, tokens, sessions).cancel(frozen)
        assertNotNull(tokens.localCoreWriteAccess()); assertEquals(expected, access())
    }

    @Test fun completeEmptyMaterialInputKeepsAllBusinessAndWhitelistWithoutInitializingAppearance() = runBlocking<Unit> {
        initialize()
        lateinit var expired: ReplicaArchiveFileAccess
        unchanged { inspect { input, access ->
            expired = access
            assertFalse(input.activationAuthorized); assertFalse(input.hasReview); assertTrue(input.missingAssets.isEmpty())
            assertEquals(3, input.business.records.size); assertEquals(36, input.storage.contents.getValue("tables").jsonObject.keys
                .count { !it.startsWith("sqlite_") && it !in setOf("android_metadata", "room_master_table") })
            assertEquals(8, input.icons.contents.getValue("tables").jsonObject.size); assertTrue(input.iconFiles.files.isEmpty())
            assertNull(input.themes.preferences.selectionRaw); assertNull(input.themes.preferences.catalogRaw); assertTrue(input.themes.files.isEmpty())
            assertEquals(setOf("current", "authority"), input.uses.map { it.source }.toSet())
            assertFalse(input.manifest.toString().contains("synthetic-first")); assertFalse(input.manifest.toString().contains("synthetic-refresh"))
            assertThrows(UnsupportedOperationException::class.java) { (input.uses as MutableList).clear() }
        } }
        assertFalse(iconRoot().exists()); assertFalse(themeRoot().exists())
        assertEquals("SYNC_ARCHIVE_ACCESS_CLOSED", rejected { expired.theme("not-a-file") }.message)
    }

    @Test fun fullMaterialThemeAndBusinessArchiveSurvivesColdValidationWithoutPublishingAnyStage() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial(); initializeThemes()
        val before = dataStore.data.first(); val raw = storageHash(); val calls = peer.requests.size
        val receipt = service().create(access(), raw)
        assertFalse(receipt.activationAuthorized); assertEquals(receipt.byteLength, archive(receipt).length())
        assertEquals(receipt.contentHash, sha(archive(receipt).readBytes()))
        assertTrue(receipt.entries.any { it.name == "icons/${blob().sha256}" }); assertEquals(7, receipt.entries.count { it.name.startsWith("themes/") })
        assertEquals(setOf("input.json", "business.json", "icons.json"), receipt.entries.take(3).map { it.name }.toSet())
        assertEquals(before, dataStore.data.first()); assertEquals(raw, storageHash()); assertEquals(calls, peer.requests.size)
        storage.reopen(); db.openHelper.writableDatabase
        service(ReplicaPrivateArchiveStore(directory)).verify(access(), receipt)
        assertThrows(UnsupportedOperationException::class.java) { (receipt.entries as MutableList).clear() }
        assertTrue(db.nextRecoveryDao().rows().isEmpty())
    }

    @Test fun missingOwnedAssetIsExplicitAndCannotBorrowPixelsOrTurnIntoAPlaceholder() = runBlocking<Unit> {
        initialize(fixed = true)
        unchanged { inspect { input, access ->
            assertEquals(setOf(id(100)), input.missingAssets); assertTrue(input.hasReview)
            assertTrue(input.uses.any { it.source == "authority" && it.icon == IconReference.Asset(id(100)) })
            rejected { access.icon(blob().sha256) }
        } }
        assertFalse(iconRoot().exists())
    }

    @Test fun taskPurposeCannotAuthorizeAnOrdinaryHistoricalOrCurrentAssetUse() = runBlocking<Unit> {
        initialize(fixed = true)
        val context = icons.capture(); icons.reserveAsset(context, asset("task")); iconStore.install(context, id(100), blob().sha256, svg)
        unchanged { assertEquals("SYNC_ARCHIVE_ICON_PURPOSE_INVALID", rejected { inspect { _, _ -> error("Must not consume") } }.message) }
    }

    @Test fun pendingTransfersAndOriginalPartialBytesArePreservedInACandidateNotPromoted() = runBlocking<Unit> {
        initialize(fixed = true)
        val context = icons.capture(); icons.reserveAsset(context, asset()); transfers.enqueueAsset(context, id(100))
        val reservation = icons.recoveryMetadata(context).installations.single().reservation
        iconFiles.exclusive(context.namespace, true, { icons.reauthorize(context) }) { directory ->
            File(directory!!.path, ".install-${reservation.operationId}.part").writeBytes(byteArrayOf(1, 2, 3))
        }
        lateinit var receipt: ReplicaArchiveReceipt
        inspect { input, access ->
            assertTrue(input.hasReview); assertTrue(input.icons.hasUnresolvedTransfers)
            val file = input.iconFiles.files.single(); assertTrue(file.temporary); assertEquals(3, file.byteLength)
            assertArrayEquals(byteArrayOf(1, 2, 3), access.icon(file.name))
            receipt = ReplicaPrivateArchiveStore(directory).create(input, access) { assertEquals(input.icons.original, icons.capture().access) }
        }
        service().verify(access(), receipt)
        assertTrue(transfers.jobs(context).all { it.state == IconTransferState.PENDING })
        assertTrue(icons.recoveryMetadata(context).installations.all { it.validationProfile == null })
        assertEquals(3L, receipt.entries.single { it.name.startsWith("icons/") }.byteLength)
    }

    @Test fun changedCurrentRoleStillRetainsBothOldAuthorityAndOriginalRequestAssetReferences() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial()
        producer().writeRounds(producer().captureRounds()) {
            db.habitDao().update(db.habitDao().getHabitById(habit.id)!!.copy(appearance = habit.appearance!!.copy(icon = IconReference.Asset(id(102)))))
        }
        producer().writeRounds(producer().captureRounds()) {
            db.habitDao().update(db.habitDao().getHabitById(habit.id)!!.copy(appearance = habit.appearance!!.copy(icon = IconReference.Role("habit.custom"))))
        }
        unchanged { inspect { input, _ ->
            assertTrue(input.uses.any { it.source == "authority" && it.icon == IconReference.Asset(id(100)) })
            assertTrue(input.uses.any { it.source == "request" && it.icon == IconReference.Asset(id(102)) })
            assertTrue(input.uses.any { it.source == "current" && it.identity == habit.uuid && it.icon == IconReference.Role("habit.custom") })
            assertEquals(setOf(id(102)), input.missingAssets); assertTrue(input.hasReview)
        } }
    }

    @Test fun actualPreparedConfigurationKeepsAllocatedAssetDependenciesAndUnfinishedSourceState() = runBlocking<Unit> {
        initialize()
        val source = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
        val materialImports = AccountIconImport(icons, iconStore, transfers)
        val controller = AccountIconController({ AccountIconRuntime(icons, iconStore,
            AccountIconRenderer(icons, iconStore), materialImports,
            AccountIconDocuments(materialImports, app.contentResolver), {}) }, tokens)
        val imports = NextConfigImportRepository(db, tokens, sessions, controller, NextObjectCreator(db, tokens, sessions, controller), app)
        val importId = try {
            val preview = imports.previewReplacement(source); assertTrue(preview.eligible)
            imports.confirmReplacement(preview)
        } finally { controller.awaitImages(); controller.close() }
        unchanged { inspect { input, _ ->
            val journal = input.business.localFacts.configuration.journals.single()
            assertEquals(importId, journal.importId); assertTrue(journal.hasUnsettledWork); assertTrue(journal.allocatedAssets.isNotEmpty())
            assertEquals(journal.allocatedAssets, input.missingAssets); assertTrue(input.hasReview)
            assertTrue(input.uses.filter { it.source == "configuration" }.all { it.identity == importId && it.oneTime == null })
            assertNull(input.themes.preferences.selectionRaw); assertTrue(input.themes.files.isEmpty())
        } }
    }

    @Test fun actualOnceTcpAcknowledgementFeedsEntireBusinessProofAndPrivateArchiveNotJustLedger() = runBlocking<Unit> {
        initialize(once = true)
        val once = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        once.appendRounds(producer().captureRounds(), OneTimeLocalCommand(task!!.uuid,
            PendingOneTimeIntent(id(200), OneTimeIntent(id(201), "complete", 0, null, null)), millis, "Asia/Shanghai"))
        val core = sender(http); val timers = NextTimerRequestStore(db, tokens, sessions, core)
        val accepted = OneTimeAcceptedEventStore(db, tokens, sessions, once, timerRequests = timers)
        assertTrue(NextOneTimeRequestStore(db, tokens, sessions, http, accepted, core).sendAndAccept(access(), id(200)) is NextOneTimeOutcome.Accepted)
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, id(200)))
        unchanged { inspect { input, _ ->
            assertTrue(input.business.records.containsKey(ReplicaRecordKey("activity_event", id(201))))
            assertFalse(input.business.hasProjectionReview); assertFalse(input.hasReview)
            assertEquals(NextRecoveryRequestState.ACCEPTED, input.business.localFacts.configuration.requests.states[NextRecoveryKey(NEXT_OPERATION, id(200))])
        } }
        val receipt = service().create(access(), storageHash()); service().verify(access(), receipt)
        assertFalse(receipt.activationAuthorized)
        // The image comparison deliberately cold-reopens the fixture; never reuse its old DAO owner.
        assertEquals(id(201), OneTimeLocalIntentStore(db, tokens, sessions, preferences).read(task!!.uuid).queue.optimisticState.completionEventUuid)
    }

    @Test fun oldServerCountWithoutOriginalPolicyRemainsReviewEvenIfTodayTargetCouldCompleteIt() = runBlocking<Unit> {
        initialize(unknownCount = true)
        unchanged { inspect { input, _ ->
            val key = ReplicaRecordKey("activity_event", id(50))
            assertTrue(input.business.records.containsKey(key)); assertTrue(key in input.business.unconfirmed)
            assertTrue(input.hasReview); assertFalse(input.activationAuthorized)
            assertTrue(input.business.localFacts.configuration.requests.states.isEmpty())
        } }
    }

    @Test fun sourceEditDuringArchiveIOMakesWholeResultStaleWithoutRemovingThePrivateCandidate() = runBlocking<Unit> {
        initialize(); val old = access(); val original = storageHash(); var created: ReplicaArchiveReceipt? = null
        assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected {
            reader().inspect(old, original) { input, access ->
                created = ReplicaPrivateArchiveStore(directory).create(input, access) { assertEquals(old, tokens.localSyncAccess()) }
                producer().writeRounds(producer().captureRounds()) { metrics().updateMetric(metric.copy(name = "Concurrent real edit")) }
            }
        }.message)
        assertTrue(archive(requireNotNull(created)).isFile)
        assertEquals("Concurrent real edit", db.metricDao().getMetricById(metric.id)!!.name)
        assertFalse(requireNotNull(created).activationAuthorized); assertNotEquals(original, storageHash())
    }

    @Test fun originalIconSqlWhitespaceChangeAndThemePreferenceChangeCannotReuseTheInput() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial(); initializeThemes()
        assertEquals("ICON_RECOVERY_PREVIEW_CHANGED", rejected { inspect { _, _ ->
            iconDb.openHelper.writableDatabase.execSQL("UPDATE icon_assets SET metadataJson=' '||metadataJson")
        } }.message)
        val key = stringPreferencesKey("appearance_theme_selection_v1")
        assertEquals("THEME_RECOVERY_PREFERENCES_CHANGED", rejected { inspect { _, _ ->
            dataStore.edit { it[key] = " " + requireNotNull(it[key]) }
        } }.message)
    }

    @Test fun unrelatedPreferencesDoNotEnterArchiveButChangedAuthorityDeniesFurtherBorrowedPixels() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial()
        inspect { input, access ->
            dataStore.edit { it[stringPreferencesKey("unrelated")] = "unrelated-value" }
            assertArrayEquals(svg, access.icon(blob().sha256)); assertFalse(input.manifest.toString().contains("unrelated-value"))
        }
        assertEquals("SYNC_ACCOUNT_CHANGED", rejected { inspect { _, access ->
            sessions.exclusive { tokens.saveDeviceRegistration(id(5), caps, true, 2) }
            access.icon(blob().sha256)
        } }.message)
    }

    @Test fun mismatchedStoresOrCoordinatorAreRejectedBeforeAnyDataOrFileRead() = runBlocking<Unit> {
        initialize()
        for (factory in listOf<() -> NextReplicaArchiveInputReader>(
            { reader(files = AccountIconFiles(directory)) }, { reader(owner = TokenManager(dataStore)) },
            { reader(coordinator = com.dayforge.domain.service.AccountSessionCoordinator()) })) {
            assertEquals("SYNC_ARCHIVE_STORE_MISMATCH", rejected { factory() }.message)
        }
        assertFalse(File(directory, "replica-archives-v1").exists())
    }

    @Test fun borrowedAccessCannotEscapeToAnotherCoroutineOrSurviveCancellation() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial(); var borrowed: ReplicaArchiveFileAccess? = null
        inspect { _, access ->
            borrowed = access
            supervisorScope {
                val child = async { access.icon(blob().sha256) }
                assertEquals("SYNC_ARCHIVE_ACCESS_CLOSED", rejected { child.await() }.message)
            }
        }
        assertEquals("SYNC_ARCHIVE_ACCESS_CLOSED", rejected { borrowed!!.icon(blob().sha256) }.message)
        inspect { _, access -> assertArrayEquals(svg, access.icon(blob().sha256)) }
    }

    @Test fun coldReadbackRejectsSameLengthContentFramingTruncationAndTrailingBytesWithoutRepair() = runBlocking<Unit> {
        initialize(); val receipt = service().create(access(), storageHash()); val file = archive(receipt); val original = file.readBytes()
        val forged = ReplicaArchiveReceipt(receipt.namespace, "../owned-target", receipt.inputHash,
            receipt.byteLength, receipt.contentHash, receipt.entries)
        assertEquals("SYNC_ARCHIVE_RECEIPT_INVALID", rejected { service().verify(access(), forged) }.message)
        assertEquals("SYNC_ARCHIVE_RECEIPT_INVALID", rejected { ReplicaPrivateArchiveStore(directory).iconIntent(forged) {} }.message)
        val intent = ReplicaPrivateArchiveStore(directory).iconIntent(receipt) {}
        assertEquals(0L, intent.originalGeneration); assertNull(intent.version)
        assertArrayEquals(original, file.readBytes())
        val corruptions = listOf(original.copyOf().also { it[0] = 0 }, original.copyOf().also { it[it.lastIndex] = 1 },
            original.copyOf().also { it[it.lastIndex - 10] = (it[it.lastIndex - 10].toInt() xor 1).toByte() },
            original.copyOf(original.size - 1), original + byteArrayOf(0))
        for (bad in corruptions) {
            file.writeBytes(bad)
            rejected { service(ReplicaPrivateArchiveStore(directory)).verify(access(), receipt) }
            rejected { ReplicaPrivateArchiveStore(directory).iconIntent(receipt) {} }
            assertArrayEquals(bad, file.readBytes())
        }
        file.writeBytes(original); service().verify(access(), receipt)
    }

    @Test fun existingCandidateIsNeverOverwrittenAndAllWriteReadOrSyncFailuresKeepSourceIntact() = runBlocking<Unit> {
        initialize(); val raw = storageHash(); val same = ReplicaPrivateArchiveStore(directory, identity = { id(210) })
        val receipt = service(same).create(access(), raw); val bytes = archive(receipt).readBytes()
        rejected { service(same).create(access(), raw) }; assertArrayEquals(bytes, archive(receipt).readBytes())
        val failures = listOf(
            object : ReplicaArchiveIo() { override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = 0 },
            object : ReplicaArchiveIo() { override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = length + 1 },
            object : ReplicaArchiveIo() { override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = throw IOException("synthetic-read") },
            object : ReplicaArchiveIo() { override fun sync(fd: FileDescriptor) = throw IOException("synthetic-sync") },
            object : ReplicaArchiveIo() {
                private var syncs = 0
                override fun sync(fd: FileDescriptor) { if (++syncs == 2) throw IOException("synthetic-directory-sync"); super.sync(fd) }
            }
        )
        for ((index, fault) in failures.withIndex()) {
            rejected { service(ReplicaPrivateArchiveStore(directory, fault) { id(220 + index) }).create(access(), raw) }
            assertEquals(raw, storageHash()); assertTrue(File(archive(receipt).parentFile, "${id(220 + index)}.candidate").exists())
        }
        service().verify(access(), receipt)
    }

    @Test fun validShortReadsAndWritesStillVerifyAllFramesThroughEof() = runBlocking<Unit> {
        initialize(); val raw = storageHash(); var writes = 0; var reads = 0
        val short = object : ReplicaArchiveIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                writes++; return super.write(fd, bytes, offset, minOf(length, 4093))
            }
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                reads++; return super.read(fd, bytes, offset, minOf(length, 4091))
            }
        }
        val store = ReplicaPrivateArchiveStore(directory, short)
        val receipt = service(store).create(access(), raw)
        assertTrue(writes > 1); assertTrue(reads > 1)
        service(store).verify(access(), receipt); assertEquals(raw, storageHash())
        assertNull(store.iconIntent(receipt) {}.version)
        assertEquals(receipt.contentHash, sha(archive(receipt).readBytes()))
        assertFalse(receipt.activationAuthorized)
    }

    @Test fun cancellationDuringActualArchiveWriteJoinsAndReleasesAllLeasesWithoutDeletingSource() = runBlocking<Unit> {
        initialize(fixed = true); completeMaterial(); val original = storageHash(); val auth = access()
        var writes = 0; lateinit var operation: Job
        val fault = object : ReplicaArchiveIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                val result = super.write(fd, bytes, offset, length); writes++; operation.cancel(); return result
            }
        }
        supervisorScope {
            operation = launch(start = CoroutineStart.LAZY) { service(ReplicaPrivateArchiveStore(directory, fault) { id(230) }).create(auth, original) }
            operation.start(); operation.join(); assertTrue(operation.isCancelled)
        }
        assertTrue(writes > 0); assertEquals(original, storageHash())
        val normal = service().create(access(), original); service().verify(access(), normal)
        assertArrayEquals(svg, File(iconRoot(), blob().sha256).readBytes())
    }

    @Test fun anotherAccountCannotVerifyThePrivateCandidateAndLinksOrSpecialTargetsAreRefused() = runBlocking<Unit> {
        initialize(); val receipt = service().create(access(), storageHash()); val file = archive(receipt); val original = file.readBytes()
        sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-other-refresh", "member", id(9), false); register() }
        assertEquals("SYNC_ARCHIVE_SCOPE_CHANGED", rejected { service().verify(access(), receipt) }.message)
        assertArrayEquals(original, file.readBytes())
        sessions.exclusive { tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false); register() }
        assertTrue(file.delete()); val target = File(file.parentFile, "owned-target").also { it.writeBytes(original) }
        Files.createSymbolicLink(file.toPath(), target.toPath()); rejected { service().verify(access(), receipt) }
        assertArrayEquals(original, target.readBytes()); assertTrue(file.delete()); assertTrue(file.mkdir())
        rejected { service().verify(access(), receipt) }; assertTrue(file.delete()); file.writeBytes(original)
        service().verify(access(), receipt)
    }
}
