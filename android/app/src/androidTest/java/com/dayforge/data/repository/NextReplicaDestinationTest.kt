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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQL COMMIT/archive/DataStore/TCP and cold reopen, not a synthetic phase receipt.
 * Destination-bound still freezes ordinary consumers and does not install a production entry.
 */
@RunWith(AndroidJUnit4::class)
class NextReplicaDestinationTest : NextCoreRequestFixture() {
    @get:org.junit.Rule(order = -2) val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    private lateinit var iconDb: AccountIconDatabase
    private lateinit var icons: AccountIconRepository
    private lateinit var files: AccountIconFiles
    private lateinit var store: AccountIconStore
    private lateinit var transfers: AccountIconTransfers
    private lateinit var themes: ThemeFileRepository
    private lateinit var builtIns: BuiltInThemes
    private lateinit var http: NextSyncHttp
    private lateinit var peer: MaterialSocketServer
    private val materialJson = Json { encodeDefaults = true }
    private var materialReply: ((MaterialSocketServer.Input) -> MaterialSocketServer.Reply)? = null
    // Registry intentionally holds weak references. Keep the real fault observer alive across
    // the complete archive verification, including GC, until this per-case fixture is released.
    private lateinit var cancellationCache: AccountIconMemory.Cache

    private fun materialParticipants() {
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
    private fun canonical(kind: String, uuid: String, payload: JsonObject) = SyncV2Change(0, kind, uuid, "upsert", 1,
        JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun checkpoint() = ChallengeMetadata(1, listOf(habit.uuid, timerHabit.uuid).map {
        val head = initialChallengeRoundHead(it)
        ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
    }, emptyList())
    private suspend fun initialize(destinationAsset: String? = null) {
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
                    MaterialSocketServer.Reply(json.encodeToString(response).toByteArray())
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
    private fun workflow(): NextReplicaRecoveryWorkflow {
        val coordinator = BusinessSyncRepository(io.mockk.mockk(), io.mockk.mockk(), db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
        val inputs = NextReplicaArchiveInputReader(app, db, tokens, sessions, preferences, http, iconDb,
            icons, transfers, files, dataStore, themes, builtIns)
        return NextReplicaRecoveryWorkflow(coordinator, db, tokens, sessions, http,
            NextReplicaCandidateValidator(app, tokens, sessions, preferences),
            NextReplicaDifferenceReader(db, tokens, sessions, preferences), inputs, ReplicaPrivateArchiveStore(directory))
    }
    private suspend fun committed(): ReplicaSwitchStage {
        val original = access(); val installation = tokens.registeredInstallationId()!!
        val candidate = NextReplicaCandidateValidator(app, tokens, sessions, preferences)
            .validate(original, installation, http.recoveryPreview(original, id(99), installation))
        val flow = workflow()
        val stage = flow.prepare(NextReplicaDifferenceReader(db, tokens, sessions, preferences).capture(candidate))
        return flow.confirm(flow.verifyForConfirmation(stage))
    }
    private fun archive(stage: ReplicaSwitchStage) = File(directory,
        "replica-archives-v1/${stage.accountId}/${stage.serverInstanceId}/${stage.oldEpoch}/${stage.archive!!.archiveId}.candidate")
    private suspend fun coldReopen() {
        scope.coroutineContext[Job]!!.cancelAndJoin(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") })
        tokens = TokenManager(dataStore); preferences = PreferencesManager(dataStore); storage.reopen(); materialParticipants()
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(com.dayforge.data.api.interceptor.BaseUrlInterceptor(preferences))
            .addInterceptor(com.dayforge.data.api.interceptor.AuthInterceptor(tokens))
            .authenticator(com.dayforge.data.api.authenticator.TokenAuthenticator(tokens, preferences,
                com.dayforge.data.api.SelectedNetworkTransport())).build()
        http = NextSyncHttp(client, tokens, peer.origin)
    }
    private suspend fun assertFrozen(stage: ReplicaSwitchStage) {
        assertTrue(tokens.replicaSwitchPending()); assertEquals(stage, tokens.activeReplicaStage())
        assertNull(tokens.localCoreWriteAccess()); assertFalse(tokens.localFactAccess()!!.canAppend)
        assertFalse(tokens.canEditStructure.first()); assertNull(tokens.iconAccessChanges.first())
        assertNull(WidgetDisplayPublisher(tokens, sessions).capturePublication())
        assertEquals(stage.fence, db.nextReplicaFenceDao().state())
        assertNotNull(rejected { db.habitDao().update(habit.copy(name = "Ordinary edit")) })
        assertNotNull(rejected { tokens.saveSyncCursor(999) })
        assertNotNull(rejected { workflow().cancel(stage) })
        assertEquals(ReplicaCommitFate.COMMITTED, NextReplicaCommitStore(db, tokens, sessions).fate(stage))
    }

    private val svg get() = """<svg width="1" height="1"><rect width="1" height="1" fill="#000000"/></svg>""".toByteArray()
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(svg).joinToString("") { "%02x".format(it) }
    private val asset get() = IconAsset(id(100), "Material", "general", "template",
        IconBlob(hash, svg.size, "image/svg+xml", 1, 1), null)
    private val pack get() = IconPack("dayforge.icon-pack", 1, id(101), 1, "Material pack", listOf(asset),
        mapOf("habit.custom" to asset.assetId, "metric.custom" to asset.assetId), null)
    private fun runtime(): AccountIconRuntime {
        val imports = AccountIconImport(icons, store, transfers)
        return AccountIconRuntime(icons, store, AccountIconRenderer(icons, store), imports,
            AccountIconDocuments(imports, app.contentResolver), {})
    }
    // Independent actual durable observation; ordinary queue APIs intentionally remain frozen.
    private suspend fun destinationJobs() = iconDb.transfers().rows(id(1), id(2), id(99))
    private fun materialResponse(input: MaterialSocketServer.Input,
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

    @Test fun frozenDestinationDownloadsAuthenticatedNewScopeBytesWithoutBorrowingOldReadyOrSelecting() = runBlocking<Unit> {
        initialize(); val old = icons.capture()
        icons.reserveAsset(old, asset); icons.reservePack(old, pack)
        store.install(old, asset.assetId, hash, svg); store.select(old, 0, IconPackVersion(pack.packId, 1))
        val committed = committed(); val calls = peer.requests.size
        assertNotNull(rejected { workflow().recoverMaterials(committed, runtime(), AccountIconHttp(client, tokens, peer.origin)) })
        assertEquals(calls, peer.requests.size)
        val destination = workflow().bindDestination(committed); val archiveBytes = archive(destination).readBytes()
        val sql = ReplicaProjection.capture(db).hash; val metadata = dataStore.data.first()
        materialReply = { materialResponse(it) }
        assertNotNull(rejected { runtime().synchronize(icons.capture(), AccountIconHttp(client, tokens, peer.origin)) })
        assertEquals(calls, peer.requests.size)
        assertEquals(IconMaterialOutcome.RETRY, workflow().recoverMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
        val context = icons.capture(); assertNull(icons.installation(context, asset.assetId, hash).validationProfile)
        assertTrue(destinationJobs().all { it.kind == "download" && it.state == "pending" })
        assertNull(icons.selection(context).pack); assertNotNull(rejected { store.read(context, asset.assetId, hash) })
        assertEquals(IconMaterialOutcome.COMPLETE, workflow().recoverMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
        assertArrayEquals(svg, store.read(context, asset.assetId, hash)); store.verifyPack(context, pack)
        assertTrue(destinationJobs().all { it.state == "complete" })
        assertNull(icons.selection(context).pack); assertNotNull(rejected { store.select(context, 0, IconPackVersion(pack.packId, 1)) })
        assertNotNull(rejected { transfers.enqueueAsset(context, asset.assetId) })
        assertNotNull(rejected { icons.authorized(context) { error("Publication must remain blocked") } })
        assertEquals(metadata, dataStore.data.first()); assertEquals(sql, ReplicaProjection.capture(db).hash)
        assertArrayEquals(archiveBytes, archive(destination).readBytes()); assertFrozen(destination)
        val materialCalls = peer.requests.drop(calls).filter { it.path.startsWith("/api/v2/appearance/") }
        assertEquals(1, materialCalls.count { it.path.endsWith("/content/light") }); assertTrue(materialCalls.all { it.method == "GET" })
        coldReopen(); assertEquals(IconMaterialOutcome.COMPLETE,
            workflow().recoverMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
        assertArrayEquals(svg, store.read(icons.capture(), asset.assetId, hash)); assertFrozen(destination)
        assertEquals(1, peer.requests.count { it.path.endsWith("/content/light") })
        val verified = workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)) as ReplicaMaterialVerification.Verified
        assertEquals(AccountIconSelection(1, IconPackVersion(pack.packId, 1)), verified.proof.selection)
        assertFalse(verified.proof.activationAuthorized); assertTrue(verified.proof.unresolvedRoles.isEmpty())
        coldReopen()
        val again = workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)) as ReplicaMaterialVerification.Verified
        assertEquals(verified.proof.hash, again.proof.hash); assertEquals(verified.proof.selection, again.proof.selection)
        assertArrayEquals(archiveBytes, archive(destination).readBytes()); assertEquals(metadata, dataStore.data.first())
        assertEquals(sql, ReplicaProjection.capture(db).hash); assertFrozen(destination)
        val ready = File(directory, "account-icons-v1/${id(1)}/${id(2)}/${id(99)}/$hash")
        val damaged = svg.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }; ready.writeBytes(damaged)
        assertNotNull(rejected { workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)) })
        assertArrayEquals(damaged, ready.readBytes()); assertEquals(verified.proof.selection, icons.selection(icons.capture()))
        assertFrozen(destination)
    }

    @Test fun transientMaterialFailureRetainsNewDownloadIdentityAndColdResumeNeverReplaysOldNamespaceWork() = runBlocking<Unit> {
        initialize(); val destination = workflow().bindDestination(committed()); materialReply = { materialResponse(it) }
        assertEquals(IconMaterialOutcome.RETRY, workflow().recoverMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
        val pending = destinationJobs().single(); assertEquals("download", pending.kind)
        for (status in listOf(503, 401)) {
            materialReply = { input -> if (input.path.endsWith("/content/light"))
                MaterialSocketServer.Reply(byteArrayOf(), status = status) else materialResponse(input) }
            assertEquals(IconMaterialOutcome.RETRY, workflow().recoverMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
            assertEquals(pending.operationId, destinationJobs().single().operationId)
        }
        val retried = destinationJobs().single()
        assertEquals(pending.operationId, retried.operationId); assertEquals("pending", retried.state)
        assertNull(icons.installation(icons.capture(), asset.assetId, hash).validationProfile)
        ReplicaMaterialAccess.verified(db, tokens, sessions, ReplicaPrivateArchiveStore(directory), destination) { context ->
            assertEquals(pending.operationId, transfers.prepareNext(context)!!.job.operationId)
        }
        assertEquals("sending", destinationJobs().single().state)
        coldReopen(); materialReply = { materialResponse(it) }
        assertEquals(IconMaterialOutcome.COMPLETE, workflow().recoverMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
        val complete = destinationJobs().single(); assertEquals(pending.operationId, complete.operationId)
        assertTrue(complete.generation > retried.generation); assertEquals("complete", complete.state)
        assertArrayEquals(svg, store.read(icons.capture(), asset.assetId, hash)); assertFrozen(destination)
        assertTrue(peer.requests.filter { it.path.startsWith("/api/v2/appearance/") }.all { it.method == "GET" && it.target.contains("sync_epoch=${id(99)}") })
        val verified = workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)) as ReplicaMaterialVerification.Verified
        assertEquals(AccountIconSelection(0, null), verified.proof.selection)
        assertFalse(verified.proof.activationAuthorized)
        assertEquals(setOf("habit.custom", "metric.custom"), verified.proof.unresolvedRoles)
        assertFrozen(destination)
    }

    @Test fun recoveryLeaseCannotAuthorizeWritesMalformedScopeRefreshOrWorkAfterReauthenticationOrReturn() = runBlocking<Unit> {
        initialize(); val destination = workflow().bindDestination(committed()); materialReply = { materialResponse(it) }
        val otherStore = AccountIconStore(icons, AccountIconFiles(directory))
        val otherTransfers = AccountIconTransfers(iconDb, icons, otherStore)
        val otherImports = AccountIconImport(icons, otherStore, otherTransfers)
        val otherRuntime = AccountIconRuntime(icons, otherStore, AccountIconRenderer(icons, otherStore), otherImports,
            AccountIconDocuments(otherImports, app.contentResolver), {})
        val beforeCalls = peer.requests.size
        assertEquals("ICON_RECOVERY_STORE_MISMATCH", rejected {
            workflow().verifyMaterials(destination, otherRuntime, AccountIconHttp(client, tokens, peer.origin))
        }.message)
        assertEquals(beforeCalls, peer.requests.size)
        var captured: AccountIconContext? = null
        ReplicaMaterialAccess.verified(db, tokens, sessions, ReplicaPrivateArchiveStore(directory), destination) { context ->
            captured = context; val lease = context.recovery!!
            val route = com.dayforge.data.api.MaterialHttpRoute(peer.origin, null)
            val url = peer.origin.newBuilder().encodedPath("/api/v2/appearance/catalog")
                .addQueryParameter("server_instance_id", id(2)).addQueryParameter("sync_epoch", id(99))
                .addQueryParameter("device_id", id(4)).addQueryParameter("after", "0").addQueryParameter("limit", "4").build()
            val valid = okhttp3.Request.Builder().url(url).header("X-DayForge-Protocol", "5")
                .tag(com.dayforge.data.api.MaterialHttpRoute::class.java, route)
                .tag(AuthenticationSession::class.java, context.access.session.authentication)
                .tag(LocalIconAccess::class.java, context.access).tag(ReplicaMaterialAccess::class.java, lease).build()
            lease.authorizeRequest(tokens, valid)
            val invalid = listOf(valid.newBuilder().delete().build(), valid.newBuilder().url(url.newBuilder()
                .addQueryParameter("limit", "4").build()).build(), valid.newBuilder().url(url.newBuilder()
                .setQueryParameter("sync_epoch", id(3)).build()).build(), valid.newBuilder().url(url.newBuilder()
                .encodedPath("/api/v1/auth/refresh").build()).build(), valid.newBuilder().url(url.newBuilder()
                .setQueryParameter("after", "00").build()).build())
            val calls = peer.requests.size
            for (request in invalid) {
                assertNotNull(rejected { lease.authorizeRequest(tokens, request) })
                assertNotNull(rejected { withContext(Dispatchers.IO) { client.newCall(request).execute().close() } })
            }
            assertEquals(calls, peer.requests.size)
            assertNotNull(rejected { transfers.enqueueAsset(context, asset.assetId) })
        }
        assertNotNull(rejected { icons.library(captured!!) })
        assertNotNull(rejected { captured!!.recovery!!.authorize(tokens) })
        assertNotNull(rejected {
            ReplicaMaterialAccess.verified(db, tokens, sessions, ReplicaPrivateArchiveStore(directory), destination) { context ->
                captured = context
                tokens.saveLoginSession("synthetic-fresh", "synthetic-refresh-fresh", "member", id(1), false)
                assertNotNull(rejected { context.recovery!!.authorize(tokens) })
                assertNotNull(rejected { icons.library(context) })
                // Factory must also reject its return if authentication changed during owned work.
            }
        })
        assertNotNull(rejected { captured!!.recovery!!.authorize(tokens) })
        assertEquals(IconMaterialOutcome.RETRY, workflow().recoverMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
        assertEquals(IconMaterialOutcome.COMPLETE, workflow().recoverMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
        assertEquals("synthetic-fresh", tokens.accessToken.first()); assertFrozen(destination)
    }

    @Test fun explicitNoPackSurvivesFreshEmptyRemoteCatalogAndColdRepeatWithoutActivating() = runBlocking<Unit> {
        initialize(); val old = icons.capture()
        icons.reserveAsset(old, asset); icons.reservePack(old, pack)
        store.install(old, asset.assetId, hash, svg)
        store.select(old, 0, IconPackVersion(pack.packId, 1)); store.select(old, 1, null)
        val destination = workflow().bindDestination(committed()); val archiveBytes = archive(destination).readBytes()
        val sql = ReplicaProjection.capture(db).hash; val preferencesBefore = dataStore.data.first()
        materialReply = { materialResponse(it, emptyList()) }
        val verified = workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)) as ReplicaMaterialVerification.Verified
        assertEquals(AccountIconSelection(1, null), verified.proof.selection); assertFalse(verified.proof.activationAuthorized)
        assertEquals(setOf("habit.custom", "metric.custom"), verified.proof.unresolvedRoles)
        assertFalse(File(directory, "account-icons-v1/${id(1)}/${id(2)}/${id(99)}").exists())
        assertTrue(destinationJobs().isEmpty()); assertTrue(peer.requests.any { it.path == "/api/v2/appearance/catalog" })
        coldReopen()
        val again = workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)) as ReplicaMaterialVerification.Verified
        assertEquals(verified.proof.hash, again.proof.hash); assertEquals(verified.proof.selection, again.proof.selection)
        assertEquals(preferencesBefore, dataStore.data.first()); assertEquals(sql, ReplicaProjection.capture(db).hash)
        assertArrayEquals(archiveBytes, archive(destination).readBytes())
        assertNotNull(rejected { store.select(icons.capture(), 1, null) }); assertFrozen(destination)
    }

    @Test fun missingPinnedRevisionCannotFallBackAndFailedSelectionSqlColdResumesExactlyOnce() = runBlocking<Unit> {
        initialize(); val old = icons.capture()
        icons.reserveAsset(old, asset); icons.reservePack(old, pack); store.install(old, asset.assetId, hash, svg)
        store.select(old, 0, IconPackVersion(pack.packId, 1))
        val destination = workflow().bindDestination(committed())
        val another = pack.copy(revision = 2)
        var catalog = listOf(AppearanceCatalogEntry.Asset(1, asset), AppearanceCatalogEntry.Pack(2, another))
        materialReply = { materialResponse(it, catalog) }
        assertEquals(ReplicaMaterialVerification.Waiting(IconMaterialOutcome.RETRY),
            workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
        assertEquals("ICON_PACK_NOT_OWNED", rejected {
            workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin))
        }.message)
        assertEquals(AccountIconSelection(0, null), icons.selection(icons.capture())); assertFrozen(destination)
        ReplicaMaterialAccess.verified(db, tokens, sessions, ReplicaPrivateArchiveStore(directory), destination) { context ->
            assertEquals("ICON_RECOVERY_PACK_CHANGED", rejected {
                icons.restoreReplicaSelectionValidated(context, 0, another, emptyMap())
            }.message)
            assertEquals("ICON_RECOVERY_PACK_CHANGED", rejected {
                icons.restoreReplicaSelectionValidated(context, 0, pack.copy(name = "Altered immutable metadata"), emptyMap())
            }.message)
            assertNotNull(rejected { store.select(context, 0, IconPackVersion(another.packId, another.revision)) })
            assertNotNull(rejected { icons.withSelection(context, AccountIconSelection(0, null)) { fail("Publication") } })
        }
        catalog = catalog + AppearanceCatalogEntry.Pack(3, pack)
        iconDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER synthetic_pin_fault BEFORE INSERT ON icon_pack_selection
            BEGIN SELECT RAISE(ABORT,'synthetic_pin_fault'); END""")
        assertNotNull(rejected { workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)) })
        assertEquals(AccountIconSelection(0, null), icons.selection(icons.capture()))
        iconDb.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_pin_fault")
        coldReopen()
        val verified = workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)) as ReplicaMaterialVerification.Verified
        assertEquals(AccountIconSelection(1, IconPackVersion(pack.packId, 1)), verified.proof.selection)
        assertFalse(verified.proof.activationAuthorized)
        val again = workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)) as ReplicaMaterialVerification.Verified
        assertEquals(verified.proof.hash, again.proof.hash); assertEquals(1L, icons.selection(icons.capture()).generation)
        assertEquals(1, peer.requests.count { it.path.endsWith("/content/light") }); assertFrozen(destination)
    }

    @Test fun explicitNewAuthorityAssetMustBeAuthenticatedAndDownloadedBeforeMaterialProof() = runBlocking<Unit> {
        val requiredAsset = asset.copy(assetId = id(102), name = "Required fixed reference")
        initialize(destinationAsset = requiredAsset.assetId)
        val destination = workflow().bindDestination(committed())
        var catalog = listOf(AppearanceCatalogEntry.Asset(1, asset), AppearanceCatalogEntry.Pack(2, pack))
        materialReply = { input ->
            if (input.path == "/api/v2/appearance/assets/${requiredAsset.assetId}/content/light")
                MaterialSocketServer.Reply(svg, type = "image/svg+xml", headers = mapOf("Cache-Control" to "private, no-store",
                    "X-Content-Type-Options" to "nosniff")) else materialResponse(input, catalog)
        }
        assertEquals(ReplicaMaterialVerification.Waiting(IconMaterialOutcome.RETRY),
            workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
        assertEquals("ICON_RECOVERY_REFERENCE_MISSING", rejected {
            workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin))
        }.message)
        assertEquals(IconReference.Asset(requiredAsset.assetId), db.habitDao().getHabitByUuid(habit.uuid)!!.appearance!!.icon)
        assertEquals(AccountIconSelection(0, null), icons.selection(icons.capture())); assertFrozen(destination)
        catalog = catalog + AppearanceCatalogEntry.Asset(3, requiredAsset)
        assertEquals(ReplicaMaterialVerification.Waiting(IconMaterialOutcome.RETRY),
            workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)))
        coldReopen()
        val verified = workflow().verifyMaterials(destination, runtime(), AccountIconHttp(client, tokens, peer.origin)) as ReplicaMaterialVerification.Verified
        assertEquals(AccountIconSelection(0, null), verified.proof.selection); assertFalse(verified.proof.activationAuthorized)
        assertEquals(setOf("habit.custom", "metric.custom"), verified.proof.unresolvedRoles)
        assertArrayEquals(svg, store.read(icons.capture(), requiredAsset.assetId, hash))
        assertTrue(destinationJobs().all { it.kind == "download" && it.state == "complete" })
        assertFrozen(destination)
    }

    @Test fun destinationPublishesCompleteMetadataAtomicallyWithoutBorrowingOldMaterialOrResettingTheme() = runBlocking<Unit> {
        initialize()
        ThemeCatalogRepository(dataStore, themes, builtIns).initialize()
        DeviceThemeStore(dataStore, themes).select(0, DeviceThemeSelection(ThemeVersionRef(BuiltInTheme.OCEAN.themeId, 1),
            ThemeVersionRef(BuiltInTheme.DUSK.themeId, 1), DeviceThemeMode.SYSTEM, DeviceCardStyle.PERSONALIZED))
        val theme = DeviceThemeStore(dataStore, themes).read()
        val svg = """<svg width="1" height="1"><rect width="1" height="1" fill="#000000"/></svg>""".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(svg).joinToString("") { "%02x".format(it) }
        val blob = IconBlob(hash, svg.size, "image/svg+xml", 1, 1)
        val asset = IconAsset(id(100), "Old asset", "general", "template", blob, blob)
        val pack = IconPack("dayforge.icon-pack", 1, id(101), 1, "Old pack", listOf(asset),
            mapOf("habit.custom" to id(100), "metric.custom" to id(100)), id(100))
        val oldContext = icons.capture(); icons.reserveAsset(oldContext, asset); icons.reservePack(oldContext, pack)
        store.install(oldContext, asset.assetId, hash, svg); store.select(oldContext, 0, IconPackVersion(pack.packId, 1))
        val oldMaterial = icons.recoveryMetadata(oldContext)
        val old = access(); val committed = committed(); val before = dataStore.data.first()
        val originalBytes = archive(committed).readBytes(); val sql = ReplicaProjection.capture(db).hash; val calls = peer.requests.size
        val observations = mutableListOf<androidx.datastore.preferences.core.Preferences>()
        val collecting = launch(start = CoroutineStart.UNDISPATCHED) { dataStore.data.collect { observations.add(it) } }
        val destination = workflow().bindDestination(committed)
        val after = dataStore.data.first(); collecting.cancelAndJoin()
        val changed = (before.asMap().keys + after.asMap().keys).filter { before[it] != after[it] }.map { it.name }.toSet()
        assertEquals(setOf("sync_epoch", "sync_device_capabilities", "sync_device_primary_editor", "sync_device_capability_revision",
            "sync_cursor", "sync_bootstrapped", ReplicaSwitchStage.key(destination.recoveryId).name), changed)
        for (value in observations + after) {
            val stage = ReplicaSwitchStage.decode(value[ReplicaSwitchStage.key(destination.recoveryId)]!!)
            assertTrue(stage == committed && value[stringPreferencesKey("sync_epoch")] == old.session.syncEpoch ||
                stage == destination && value[stringPreferencesKey("sync_epoch")] == destination.newEpoch &&
                value[stringPreferencesKey("sync_device_capability_revision")] == "2" &&
                value[booleanPreferencesKey("sync_device_primary_editor")] == false)
        }
        assertEquals(ReplicaSwitchPhase.DESTINATION_BOUND, destination.phase); assertEquals(6L, destination.generation)
        assertEquals(destination.destination(old.session.authentication), tokens.replicaDestinationAccess(destination))
        assertEquals(20L, tokens.syncCursor.first()); assertTrue(tokens.isSyncBootstrapped.first()); assertFalse(tokens.isPrimaryEditor.first())
        assertNull(tokens.syncAuthenticationSnapshot(old)); assertEquals(theme, DeviceThemeStore(dataStore, themes).read())
        assertNotNull(rejected { icons.recoveryMetadata(oldContext) })
        val newMaterial = icons.recoveryMetadata(icons.capture())
        assertNull(newMaterial.selection.pack); assertTrue(newMaterial.assets.isEmpty()); assertTrue(newMaterial.installations.isEmpty())
        assertNotNull(rejected { store.select(icons.capture(), 0, IconPackVersion(pack.packId, 1)) })
        assertEquals(sql, ReplicaProjection.capture(db).hash); assertArrayEquals(originalBytes, archive(destination).readBytes())
        assertFrozen(destination)
        assertEquals(destination, workflow().bindDestination(committed)); assertEquals(destination, workflow().resume(destination))
        coldReopen(); assertEquals(destination, workflow().resume(tokens.activeReplicaStage()!!)); assertFrozen(destination)
        assertEquals(calls, peer.requests.size); assertEquals(sql, ReplicaProjection.capture(db).hash)
        // Old namespace rows/files remain retained; they were not copied/adopted into the new scope.
        assertEquals(1, oldMaterial.assets.size)
        assertTrue(File(directory, "account-icons-v1/${id(1)}/${id(2)}/${id(3)}").isDirectory)
    }

    @Test fun sameOwnerFreshAuthenticationCanBindAndColdContinueWithoutRestoringOldCredentialsOrHttp() = runBlocking<Unit> {
        initialize(); val committed = committed(); val original = dataStore.data.first(); val calls = peer.requests.size
        tokens.clearAuthenticationTokens(); assertNotNull(rejected { workflow().bindDestination(committed) })
        assertNotNull(rejected { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(90), false) })
        tokens.saveLoginSession("synthetic-new", "synthetic-refresh-new", "member", id(1), false)
        val fresh = tokens.authenticationSnapshot()!!.session
        assertNotEquals(committed.authenticationGeneration, fresh.generation)
        val destination = workflow().bindDestination(committed)
        assertEquals(fresh, tokens.replicaDestinationAccess(destination).session.authentication)
        assertEquals("synthetic-new", tokens.accessToken.first()); assertFrozen(destination)
        coldReopen(); tokens.clearAuthenticationTokens(); assertNotNull(rejected { workflow().resume(destination) })
        tokens.saveLoginSession("synthetic-cold", "synthetic-refresh-cold", "member", id(1), false)
        assertEquals(destination, workflow().resume(destination)); assertEquals("synthetic-cold", tokens.accessToken.first())
        assertEquals(calls, peer.requests.size); assertNotEquals(original, dataStore.data.first()); assertFrozen(destination)
    }

    @Test fun cancellationAfterActualDestinationTransformJoinsPersistenceAndColdForwardProof() = runBlocking<Unit> {
        initialize(); val committed = committed(); val sql = ReplicaProjection.capture(db).hash; val calls = peer.requests.size
        val armed = AtomicBoolean(true)
        val writing = async(start = CoroutineStart.LAZY) { workflow().bindDestination(committed) }
        cancellationCache = object : AccountIconMemory.Cache {
            override fun authenticationTransition(blocked: Boolean) {
                if (blocked && armed.compareAndSet(true, false)) writing.cancel()
            }
        }
        tokens.registerIconCache(cancellationCache)
        writing.start()
        val error = runCatching { writing.await() }.exceptionOrNull()
        assertTrue("Expected caller cancellation; actual=${error?.javaClass?.simpleName ?: "successful return"}",
            error is CancellationException)
        writing.cancelAndJoin(); assertFalse(armed.get())
        val destination = tokens.activeReplicaStage()!!
        assertEquals(committed.transition(ReplicaSwitchPhase.DESTINATION_BOUND), destination)
        coldReopen(); assertEquals(destination, workflow().bindDestination(committed)); assertFrozen(destination)
        assertEquals(sql, ReplicaProjection.capture(db).hash); assertEquals(calls, peer.requests.size)
    }

    @Test fun damagedActualArchiveOrSqlPreventsDestinationPublicationWithoutRollbackOrCredentialReplacement() = runBlocking<Unit> {
        initialize(); val committed = committed(); val before = dataStore.data.first()
        val file = archive(committed); val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertNotNull(rejected { workflow().bindDestination(committed) }); assertEquals(before, dataStore.data.first())
        file.writeBytes(bytes)
        val sql = db.openHelper.writableDatabase
        val ddl = sql.query("SELECT sql FROM sqlite_master WHERE name='replica_fence_metrics_update'").use { assertTrue(it.moveToFirst()); it.getString(0) }
        db.withTransaction {
            sql.execSQL("DROP TRIGGER replica_fence_metrics_update")
            sql.execSQL("UPDATE metrics SET updatedAt=updatedAt+1")
            sql.execSQL(ddl)
        }
        assertEquals("SYNC_REPLICA_COMMIT_STATE_INVALID", rejected { workflow().bindDestination(committed) }.message)
        assertEquals(before, dataStore.data.first()); assertEquals(committed, tokens.activeReplicaStage())
        assertNotNull(rejected { workflow().cancel(committed) }); assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun coldDestinationChecksCursorBootstrapPermissionsAndPointerRatherThanTrustingPhaseName() = runBlocking<Unit> {
        initialize(); val committed = committed(); val destination = workflow().bindDestination(committed)
        val original = dataStore.data.first()
        val damaged: List<suspend () -> Unit> = listOf(
            { dataStore.edit { it[stringPreferencesKey("sync_cursor")] = "21" } },
            { dataStore.edit { it[booleanPreferencesKey("sync_bootstrapped")] = false } },
            { dataStore.edit { it[booleanPreferencesKey("sync_device_primary_editor")] = true } },
            { dataStore.edit { it[stringPreferencesKey("sync_device_capability_revision")] = "3" } },
            { dataStore.edit { it[stringPreferencesKey("sync_installation_id")] = id(90) } },
            { dataStore.edit { it[ReplicaSwitchGuard.POINTER] = id(91) } }
        )
        for (damage in damaged) {
            damage(); assertNotNull(rejected { workflow().resume(destination) }); assertNull(tokens.localCoreWriteAccess())
            dataStore.updateData { original }; assertEquals(destination, tokens.activeReplicaStage())
        }
        assertEquals(destination, workflow().resume(destination)); assertFrozen(destination)
        assertNotNull(rejected { destination.transition(ReplicaSwitchPhase.ABORTED) })
        assertNotNull(rejected { destination.copy(generation = 5) })
    }
}
