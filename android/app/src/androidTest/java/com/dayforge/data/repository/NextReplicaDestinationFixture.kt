package com.dayforge.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.*
import com.dayforge.data.local.*
import com.dayforge.data.model.*
import com.dayforge.domain.appearance.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountIconRuntime
import com.dayforge.domain.service.IconMaterialOutcome
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

abstract class NextReplicaDestinationFixture : NextCoreRequestFixture() {
    @get:org.junit.Rule(order = -2) val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    internal lateinit var iconDb: AccountIconDatabase
    internal lateinit var icons: AccountIconRepository
    internal lateinit var files: AccountIconFiles
    internal lateinit var store: AccountIconStore
    internal lateinit var transfers: AccountIconTransfers
    internal lateinit var themes: ThemeFileRepository
    internal lateinit var builtIns: BuiltInThemes
    internal lateinit var http: NextSyncHttp
    internal lateinit var peer: MaterialSocketServer
    internal val materialJson = Json { encodeDefaults = true }
    internal var materialReply: ((MaterialSocketServer.Input) -> MaterialSocketServer.Reply)? = null
    internal var recoveryPack: IconPack? = null
    internal var destinationTransform: (ReplicaRecoveryPreviewResponse) -> ReplicaRecoveryPreviewResponse = { it }
    internal var destinationFailure: Int? = null
    // Registry intentionally holds weak references. Keep the real fault observer alive across
    // the complete archive verification, including GC, until this per-case fixture is released.
    internal lateinit var cancellationCache: AccountIconMemory.Cache

    internal fun materialParticipants() {
        icons = AccountIconRepository(iconDb, tokens, sessions); files = AccountIconFiles(directory)
        store = AccountIconStore(icons, files); transfers = AccountIconTransfers(iconDb, icons, store)
    }
    @Before fun appearanceSetup() {
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        iconDb = AccountIconDatabase.open(app)
        assertEquals(5, iconDb.openHelper.writableDatabase.version)
        materialParticipants()
        themes = ThemeFileRepository(directory); builtIns = BuiltInThemes(app.assets)
    }
    @After fun appearanceCleanup() {
        if (::iconDb.isInitialized) iconDb.close()
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }
    internal fun canonical(kind: String, uuid: String, payload: JsonObject) = SyncV2Change(0, kind, uuid, "upsert", 1,
        JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    internal fun checkpoint() = ChallengeMetadata(1, listOf(habit.uuid, timerHabit.uuid).map {
        val head = initialChallengeRoundHead(it)
        ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
    }, emptyList())
    internal suspend fun initialize(destinationAsset: String? = null) {
        register(); val installation = tokens.getOrCreateInstallationId()
        val meta = checkpoint()
        val snapshot = RoundSyncBootstrapResponse(listOf(habit, timerHabit).map {
            canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it))
        } + canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)),
            20, time, emptyList(), 1, meta.checkpoints, meta.births)
        val destinationSnapshot = if (destinationAsset == null) snapshot else snapshot.copy(changes = snapshot.changes.map { change ->
            if (change.entityUuid != habit.uuid) change else change.copy(payload = JsonObject(change.payload +
                ("appearance" to Json.encodeToJsonElement(habit.appearance!!.copy(icon = IconReference.Asset(destinationAsset))))))
        })
        val response = ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(99),
            RecoveryDeviceProof(id(4), installation, "android", "interactive", null, null,
                false, false, 2, listOf("sync.read", "facts.append"), time), destinationSnapshot, null)
        val json = Json { encodeDefaults = true }
        val channel = channel { input ->
            when (input.path) {
                "/api/v2/system/identity" -> reply(input).let { it.copy(bytes = it.bytes.toString(Charsets.UTF_8).replace(id(3), id(99)).toByteArray()) }
                "/api/v2/sync/rounds/recovery-preview" -> {
                    assertEquals("GET", input.method)
                    destinationFailure?.let { return@channel MaterialSocketServer.Reply(byteArrayOf(), status = it) }
                    val selected = recoveryPack ?: pack
                    val available = selected.assets + listOfNotNull(destinationAsset?.let {
                        asset.copy(assetId = it, name = "Required fixed reference")
                    })
                    MaterialSocketServer.Reply(json.encodeToString(recoveryMaterialReply(destinationTransform(response), input,
                        listOf(selected), available)).toByteArray(), headers = mapOf("Cache-Control" to "private, max-age=3600"))
                }
                else -> if (input.path.startsWith("/api/v2/appearance/")) requireNotNull(materialReply)(input)
                    else error("Recovery may not send ordinary work: ${input.path}")
            }
        }
        http = channel.first; peer = channel.second
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        val once = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions, once, timerRequests = timers), timers)
            .bootstrap(access(), null, snapshot)
    }
    internal fun workflow(): NextReplicaRecoveryWorkflow {
        val coordinator = BusinessSyncRepository(io.mockk.mockk(), io.mockk.mockk(), db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
        val inputs = NextReplicaArchiveInputReader(app, db, tokens, sessions, preferences, http, iconDb,
            icons, transfers, files, dataStore, themes, builtIns)
        return NextReplicaRecoveryWorkflow(coordinator, db, tokens, sessions, http,
            NextReplicaCandidateValidator(app, tokens, sessions, preferences),
            NextReplicaDifferenceReader(db, tokens, sessions, preferences), inputs, ReplicaPrivateArchiveStore(directory))
    }
    internal suspend fun committed(): ReplicaSwitchStage {
        val original = access(); val installation = tokens.registeredInstallationId()!!
        val candidate = NextReplicaCandidateValidator(app, tokens, sessions, preferences)
            .validate(original, installation, http.recoveryPreview(original, id(99), installation))
        val flow = workflow()
        val stage = flow.prepare(NextReplicaDifferenceReader(db, tokens, sessions, preferences).capture(candidate))
        return flow.confirm(flow.verifyForConfirmation(stage))
    }
    internal fun archive(stage: ReplicaSwitchStage) = File(directory,
        "replica-archives-v1/${stage.accountId}/${stage.serverInstanceId}/${stage.oldEpoch}/${stage.archive!!.archiveId}.candidate")
    internal suspend fun coldReopen() {
        scope.coroutineContext[Job]!!.cancelAndJoin(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") })
        tokens = TokenManager(dataStore); preferences = PreferencesManager(dataStore); storage.reopen(); materialParticipants()
        reconnectHttp()
    }
    internal fun reconnectHttp() {
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(com.dayforge.data.api.interceptor.BaseUrlInterceptor(preferences))
            .addInterceptor(com.dayforge.data.api.interceptor.AuthInterceptor(tokens))
            .authenticator(com.dayforge.data.api.authenticator.TokenAuthenticator(tokens, preferences,
                com.dayforge.data.api.SelectedNetworkTransport())).build()
        http = NextSyncHttp(client, tokens, peer.origin)
    }
    internal suspend fun assertFrozen(stage: ReplicaSwitchStage) {
        assertTrue(tokens.replicaSwitchPending()); assertEquals(stage, tokens.activeReplicaStage())
        assertNull(tokens.localCoreWriteAccess()); assertFalse(tokens.localFactAccess()!!.canAppend)
        assertFalse(tokens.canEditStructure.first()); assertNull(tokens.iconAccessChanges.first())
        assertNull(WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions)).capturePublication())
        assertEquals(stage.fence, db.nextReplicaFenceDao().state())
        assertNotNull(rejected { db.habitDao().update(habit.copy(name = "Ordinary edit")) })
        assertNotNull(rejected { tokens.saveSyncCursor(999) })
        assertNotNull(rejected { workflow().cancel(stage) })
        assertEquals(ReplicaCommitFate.COMMITTED, NextReplicaCommitStore(db, tokens, sessions).fate(stage))
    }

    internal fun changedDestination(title: String, cursor: Long) {
        destinationTransform = { response -> response.copy(snapshot = response.snapshot.copy(nextCursor = cursor,
            changes = response.snapshot.changes.map { change -> if (change.entityUuid != habit.uuid) change else
                change.copy(revision = 2, payload = JsonObject(change.payload + mapOf("title" to JsonPrimitive(title),
                    "revision" to JsonPrimitive(2)))) })) }
    }

    internal suspend fun pendingCatchup(stage: ReplicaSwitchStage): Pair<ReplicaSwitchStage, ValidatedReplicaCandidate> {
        val fresh = ReplicaDestinationReadAccess.verified(db, tokens, sessions, ReplicaPrivateArchiveStore(directory), stage) { read ->
            NextReplicaCandidateValidator(app, tokens, sessions, preferences).validateDestination(read, read.read(http))
        }
        val device = fresh.deviceProof
        val snapshot = ReplicaStageSnapshot(fresh.contentHash, fresh.wireHash, fresh.cursor,
            ReplicaStageTarget(fresh.projection.hash, ReplicaProjection.schemaHash(db), device.deviceId,
                device.capabilityRevision, device.capabilities.sorted(), device.isPrimaryEditor, device.structuralEditEnabled))
        val next = NextReplicaStageStore(db, tokens, sessions).recordCatchup(stage,
            ReplicaStageCatchup(stage.publishedSnapshot, snapshot, fresh.original.session.authentication.generation))
        return next to fresh
    }

    internal val svg get() = """<svg width="1" height="1"><rect width="1" height="1" fill="#000000"/></svg>""".toByteArray()
    internal val hash get() = MessageDigest.getInstance("SHA-256").digest(svg).joinToString("") { "%02x".format(it) }
    internal val asset get() = IconAsset(id(100), "Material", "general", "template",
        IconBlob(hash, svg.size, "image/svg+xml", 1, 1), null)
    internal val pack get() = IconPack("dayforge.icon-pack", 1, id(101), 1, "Material pack", listOf(asset),
        mapOf("habit.custom" to asset.assetId, "metric.custom" to asset.assetId), null)
    internal fun runtime(): AccountIconRuntime {
        val imports = AccountIconImport(icons, store, transfers)
        return AccountIconRuntime(icons, store, AccountIconRenderer(icons, store), imports,
            AccountIconDocuments(imports, app.contentResolver), {})
    }
    // Independent actual durable observation; ordinary queue APIs intentionally remain frozen.
    internal suspend fun destinationJobs() = iconDb.transfers().rows(id(1), id(2), id(99))
    internal fun materialResponse(input: MaterialSocketServer.Input,
        catalog: List<AppearanceCatalogEntry> = listOf(AppearanceCatalogEntry.Asset(1, asset), AppearanceCatalogEntry.Pack(2, pack))): MaterialSocketServer.Reply {
        assertEquals("GET", input.method); assertTrue(input.body.isEmpty())
        assertEquals("5", input.headers["x-dayforge-protocol"])
        assertEquals("Bearer ${runBlocking { tokens.accessToken.first() }}", input.headers["authorization"])
        val url = peer.origin.resolve(input.target)!!
        assertEquals(id(2), url.queryParameter("server_instance_id"))
        assertEquals(id(99), url.queryParameter("sync_epoch")); assertEquals(id(4), url.queryParameter("device_id"))
        return when (input.path) {
            "/api/v2/appearance/catalog" -> {
                val after = url.queryParameter("after")!!.toLong()
                val entries = catalog.filter { it.sequence > after }
                val through = catalog.lastOrNull()?.sequence ?: 0
                MaterialSocketServer.Reply(materialJson.encodeToString(AppearanceCatalogPage(
                    AssetSyncContext(id(2), id(99), id(4)), entries, through, through, false)).toByteArray())
            }
            "/api/v2/appearance/assets/${asset.assetId}/content/light" -> MaterialSocketServer.Reply(svg,
                type = "image/svg+xml", headers = mapOf("Cache-Control" to "private, no-store", "X-Content-Type-Options" to "nosniff"))
            else -> error("Unexpected material write/path: ${input.path}")
        }
    }

}
