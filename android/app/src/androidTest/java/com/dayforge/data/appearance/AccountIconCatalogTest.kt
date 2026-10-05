package com.dayforge.data.appearance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.authenticator.TokenAuthenticator
import com.dayforge.data.api.interceptor.AuthInterceptor
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconCatalogTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var database: AccountIconDatabase
    private lateinit var metadata: AccountIconRepository
    private lateinit var store: AccountIconStore
    private lateinit var queue: AccountIconTransfers
    private lateinit var catalog: AccountIconRemoteCatalog
    private lateinit var processor: AccountIconCatalogProcessor
    private lateinit var client: OkHttpClient
    private val servers = mutableListOf<MaterialSocketServer>()
    private val tables = listOf("icon_assets", "icon_packs", "icon_blob_reservations", "icon_blob_ready", "icon_pack_selection",
        "icon_transfers", "icon_catalog_entries", "icon_catalog_state")
    private val bytes = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private fun id(n: Int) = "a9300000-0000-4000-8000-${n.toString(16).padStart(12,'0')}"
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun asset(n: Int = 10) = IconAsset(id(n), "asset $n", "general", "template",
        IconBlob(hash,bytes.size,"image/svg+xml",1,1), IconBlob(hash,bytes.size,"image/svg+xml",1,1))
    private fun pack(n: Int = 20, assets: List<IconAsset> = listOf(asset())) = IconPack("dayforge.icon-pack",1,id(n),1,"pack $n",
        assets,mapOf("habit.general" to assets.first().assetId),null)
    private val binding get() = AssetSyncContext(id(2),id(3),id(4))
    private fun page(entries: List<AppearanceCatalogEntry>, through: Long = entries.lastOrNull()?.sequence ?: 0L,
        next: Long = entries.lastOrNull()?.sequence ?: through, more: Boolean = next < through) =
        AppearanceCatalogPage(binding,entries,next,through,more)
    private fun first(through: Long = 2) = page(listOf(AppearanceCatalogEntry.Asset(1,asset()), AppearanceCatalogEntry.Pack(2,pack())), through)
    private fun rawPage(text: String) = MaterialSocketServer.Reply(text.toByteArray())
    private val wireJson = Json { encodeDefaults = true }
    private fun encoded(page: AppearanceCatalogPage) = wireJson.encodeToString(page)
    private fun identity(version: Int = 5) = """{"server_instance_id":"${id(2)}","sync_epoch":"${id(3)}","protocol_version":$version,"capabilities":["sync.read"],"server_time":"2026-10-05T00:00:00Z"}"""
    private suspend fun login(owner: String = id(1), server: String = id(2), epoch: String = id(3), device: String = id(4),
        editing: Boolean = false, revision: Int = 1) {
        tokens.saveLoginSession("synthetic-access","synthetic-refresh","member",owner,false)
        tokens.saveServerIdentity(server,epoch)
        tokens.saveDeviceRegistration(device,if (editing) setOf("sync.read","structure.write") else setOf("sync.read"),true,revision)
    }
    private fun configure(limits: AccountIconLimits = AccountIconLimits(), jobs: Int = AccountIconTransfers.MAX_JOBS) {
        metadata = AccountIconRepository(database,tokens,AccountSessionCoordinator(),limits)
        store = AccountIconStore(metadata,AccountIconFiles(directory))
        queue = AccountIconTransfers(database,metadata,store,maximumJobs=jobs)
        catalog = AccountIconRemoteCatalog(database,metadata,queue)
        processor = AccountIconCatalogProcessor(metadata,catalog,Mutex())
    }
    private fun reopen() { database.close(); database = AccountIconDatabase.open(app); configure() }
    private fun sql(text: String) = database.openHelper.writableDatabase.execSQL(text)
    private fun rows(table: String) = database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { c -> buildList {
        while (c.moveToNext()) add(List(c.columnCount) {
            if (c.isNull(it)) "null" else "${c.getType(it)}:" +
                if (c.getType(it) == android.database.Cursor.FIELD_TYPE_BLOB)
                    c.getBlob(it).joinToString("") { byte -> "%02x".format(byte) }
                else c.getString(it)
        })
    } }
    private fun durable() = tables.associateWith(::rows)
    private fun empty() = assertTrue(durable().values.all { it.isEmpty() })
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { if (error is CancellationException) throw error; return error }
        throw AssertionError("Must reject")
    }
    private suspend fun http(reply: (MaterialSocketServer.Input, java.net.Socket) -> MaterialSocketServer.Reply?): Pair<AccountIconHttp,MaterialSocketServer> {
        val server = MaterialSocketServer { input, socket ->
            assertEquals("GET",input.method); assertEquals("5",input.headers["x-dayforge-protocol"])
            assertEquals("identity",input.headers["accept-encoding"]); assertTrue(input.body.isEmpty())
            if (input.path.endsWith("/identity")) assertNull(input.headers["authorization"])
            else {
                assertEquals("Bearer synthetic-access",input.headers["authorization"])
                assertTrue(input.target.contains("server_instance_id=${id(2)}") && input.target.contains("sync_epoch=${id(3)}") &&
                    input.target.contains("device_id=${id(4)}"))
            }
            reply(input,socket)
        }
        servers.add(server); preferences.setServerUrl(server.origin.toString())
        return AccountIconHttp(client,tokens,server.origin) to server
    }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed"); assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        directory = Files.createTempDirectory(app.filesDir.toPath(),"icon-catalog-").toFile()
        scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope=scope,produceFile={ File(directory,"auth.preferences_pb") })
        tokens=TokenManager(data); preferences=PreferencesManager(data)
        database=AccountIconDatabase.open(app); configure(); login()
        client=OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(preferences)).addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens,preferences,SelectedNetworkTransport())).build()
    }
    @After fun cleanup() = runBlocking<Unit> {
        var failure: Throwable? = null
        suspend fun finish(block: suspend () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure == null) failure=error else failure!!.addSuppressed(error)
        } }
        servers.forEach { finish { it.close() } }
        finish { if (::client.isInitialized) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }
        finish { if (::database.isInitialized) database.close() }
        finish { if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin() }
        finish { if (::directory.isInitialized) assertTrue(directory.deleteRecursively()) }
        finish { assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists()) }
        failure?.let { throw it }
    }

    @Test fun readonlyPagesCommitDeduplicatedReservationsAllDownloadsAndCheckpointTogetherAndColdReplayIsExact() = runBlocking<Unit> {
        val context=metadata.capture(); val original=catalog.next(context)
        assertEquals(IconCatalogCheckpoint(0,0,0),original.checkpoint)
        val values=listOf(AppearanceCatalogEntry.Asset(1,asset()),AppearanceCatalogEntry.Asset(2,asset(11)))
        assertEquals(IconCatalogCheckpoint(1,2,3),catalog.accept(original,page(values,3)))
        assertEquals(1,metadata.reservations(context).size); assertEquals(4,queue.jobs(context).size)
        assertTrue(queue.jobs(context).all { it.kind==IconTransferKind.DOWNLOAD && it.state==IconTransferState.PENDING })
        assertTrue(rows("icon_blob_ready").isEmpty()); assertTrue(rows("icon_pack_selection").isEmpty())
        val before=durable(); reopen(); assertEquals(before,durable())
        val next=catalog.next(context); assertEquals(3L,next.through)
        val last=page(listOf(AppearanceCatalogEntry.Pack(3,pack(20,listOf(asset(),asset(11))))),3)
        assertEquals(IconCatalogCheckpoint(2,3,3),catalog.accept(next,last))
        val committed=durable(); assertEquals(IconCatalogCheckpoint(2,3,3),catalog.accept(next,last)); assertEquals(committed,durable())
        assertEquals(before["icon_transfers"],rows("icon_transfers")); assertEquals(before["icon_blob_reservations"],rows("icon_blob_reservations"))
        assertEquals(1,metadata.library(context).packs.size); assertTrue(metadata.library(context).readyVersions.isEmpty())
        assertNull(catalog.next(context).through); assertTrue(queue.jobs(context).all { it.failure==null })
    }

    @Test fun actualDomainCatalogEntryThenReadonlyTransferPublishesRealBytesWithoutDeclarationOrSelection() = runBlocking<Unit> {
        val context=metadata.capture()
        val raw="""{"context":{"server_instance_id":"${id(2)}","sync_epoch":"${id(3)}","device_id":"${id(4)}"},"entries":[{"kind":"asset","sequence":1,"asset":{"asset_id":"${id(10)}","name":"asset 10","purpose":"general","color_mode":"template","light":{"sha256":"$hash","byte_length":${bytes.size},"media_type":"image/svg+xml","width":1,"height":1},"dark":null}}],"next_cursor":1,"through_sequence":1,"has_more":false}"""
        val (http,server)=http { input,_ -> when {
            input.path.endsWith("/identity") -> rawPage(identity())
            input.path.endsWith("/catalog") -> { assertTrue(input.target.endsWith("&after=0&limit=4")); rawPage(raw) }
            else -> MaterialSocketServer.Reply(bytes,type="image/svg+xml",headers=mapOf("Cache-Control" to "private, no-store","X-Content-Type-Options" to "nosniff"))
        } }
        val imports=AccountIconImport(metadata,store,queue)
        val runtime=AccountIconRuntime(metadata,store,AccountIconRenderer(metadata,store),imports,AccountIconDocuments(imports,app.contentResolver),database::close)
        val icons=AccountIconController({runtime},tokens); val service=AccountIconTransferService(icons,http)
        try {
            assertEquals(IconCatalogBatch(true,1,1),service.refreshCatalog())
            assertEquals(IconCatalogCheckpoint(1,1,1),runtime.catalog.next(context).checkpoint)
            assertTrue(rows("icon_blob_ready").isEmpty()); assertEquals(1,queue.jobs(context).size)
            assertEquals(IconTransferBatch(true,1,0,false,true),service.transfer())
            assertArrayEquals(bytes,store.read(context,id(10),hash)); assertNull(metadata.selection(context).pack)
            assertEquals(IconTransferKind.DOWNLOAD,queue.jobs(context).single().kind)
            assertEquals(4,server.requests.size); assertTrue(server.requests.all { it.method=="GET" })
        } finally { icons.close() }
    }

    @Test fun fourPageHttpBudgetFreezesThroughAndColdResumesBeforeDiscoveringLaterAppend() = runBlocking<Unit> {
        val context=metadata.capture()
        var maximum=6L
        val (http,server)=http { input,_ ->
            if (input.path.endsWith("/identity")) rawPage(identity()) else {
                val url=("http://localhost"+input.target).toHttpUrl()
                assertEquals("4",url.queryParameter("limit"))
                val after=url.queryParameter("after")!!.toLong(); val through=url.queryParameter("through")?.toLong() ?: maximum
                rawPage(encoded(page(listOf(AppearanceCatalogEntry.Asset(after+1,asset((after+10).toInt()))),through)))
            }
        }
        assertEquals(IconCatalogBatch(true,4,4,limited=true),processor.run(context,http))
        assertEquals(IconCatalogCheckpoint(4,4,6),catalog.next(context).checkpoint)
        val before=rows("icon_transfers"); maximum=7; reopen()
        assertEquals(before,rows("icon_transfers")); assertEquals(IconCatalogBatch(true,2,2),processor.run(context,http))
        assertEquals(IconCatalogCheckpoint(6,6,6),catalog.next(context).checkpoint)
        assertEquals(IconCatalogBatch(true,1,1),processor.run(context,http)); assertEquals(IconCatalogCheckpoint(7,7,7),catalog.next(context).checkpoint)
        val requests=server.requests.filter { it.path.endsWith("/catalog") }
        assertEquals(7,requests.size); assertFalse(requests.first().target.contains("&through="))
        assertTrue(requests[4].target.contains("&after=4&limit=4&through=6")); assertFalse(requests.last().target.contains("&through="))
        assertEquals(14,queue.jobs(context).size); assertTrue(rows("icon_blob_ready").isEmpty())
    }

    @Test fun malformedSparseQuotedDuplicateOrWrongBoundCatalogDoesNotAdvanceOrPublishAnyData() = runBlocking<Unit> {
        val context=metadata.capture(); val original=encoded(page(listOf(AppearanceCatalogEntry.Asset(1,asset()))))
        var response=original
        val (http,_)=http { input,_ -> rawPage(if (input.path.endsWith("/identity")) identity() else response) }
        for (text in listOf(original.replace("\"sequence\":1","\"sequence\":2").replace("\"next_cursor\":1","\"next_cursor\":2").replace("\"through_sequence\":1","\"through_sequence\":2"),
            original.replace("\"sequence\":1","\"sequence\":\"1\""), original.replace("\"has_more\":false","\"has_more\":false,\"has_more\":false"),
            original.replace(id(4),id(90)),original.replace(id(2),id(91)),original.replace("\"next_cursor\":1","\"next_cursor\":2").replace("\"through_sequence\":1","\"through_sequence\":2"))) {
            response=text; assertEquals(IconCatalogBatch(true,0,0,stopped=true),processor.run(context,http)); empty()
        }
        response=original; assertEquals(IconCatalogBatch(true,1,1),processor.run(context,http))
    }

    @Test fun v4AndFutureAdmissionNeverRequestsCatalogAndAnUnchangedEmptyTailDoesNotConsumeGenerations() = runBlocking<Unit> {
        val context=metadata.capture(); var version=4
        val (http,server)=http { input,_ -> rawPage(if (input.path.endsWith("/identity")) identity(version) else encoded(page(emptyList()))) }
        for (value in listOf(4,6)) { version=value; assertEquals(IconCatalogBatch(false,0,0),processor.run(context,http)); empty() }
        assertEquals(2,server.requests.size); version=5
        assertEquals(IconCatalogBatch(true,1,0),processor.run(context,http)); val saved=durable()
        assertEquals(IconCatalogCheckpoint(1,0,0),catalog.next(context).checkpoint)
        assertEquals(IconCatalogBatch(true,1,0),processor.run(context,http)); assertEquals(saved,durable())
    }

    @Test fun catalogOrderDuplicateImmutableConflictFrozenThroughAndOldCasFailuresPreserveAllData() = runBlocking<Unit> {
        login(editing=true); val local=metadata.capture(); metadata.reserveAsset(local,asset())
        login(); val context=metadata.capture(); val start=catalog.next(context); val saved=durable()
        rejected { catalog.accept(start,page(listOf(AppearanceCatalogEntry.Pack(1,pack())))) }; assertEquals(saved,durable())
        catalog.accept(start,page(listOf(AppearanceCatalogEntry.Asset(1,asset())),3)); val attempt=catalog.next(context); val before=durable()
        rejected { catalog.accept(attempt,page(listOf(AppearanceCatalogEntry.Asset(2,asset())),3)) }
        rejected { catalog.accept(attempt,page(listOf(AppearanceCatalogEntry.Asset(2,asset().copy(name="changed"))),3)) }
        rejected { catalog.accept(attempt,page(listOf(AppearanceCatalogEntry.Pack(2,pack())),4)) }
        rejected { catalog.accept(attempt,page(listOf(AppearanceCatalogEntry.Asset(3,asset(12))),3)) }
        assertEquals(before,durable())
        catalog.accept(attempt,page(listOf(AppearanceCatalogEntry.Pack(2,pack())),3)); val changed=durable()
        rejected { catalog.accept(start,page(listOf(AppearanceCatalogEntry.Asset(1,asset())),3)) }; assertEquals(changed,durable())
    }

    @Test fun abortIgnoreAtEveryStageRollsBackMetadataBlobsDownloadsJournalAndCheckpoint() = runBlocking<Unit> {
        val context=metadata.capture(); val attempt=catalog.next(context)
        for (table in listOf("icon_assets","icon_blob_reservations","icon_packs","icon_transfers","icon_catalog_entries","icon_catalog_state")) {
            for (signal in listOf("ABORT,'injected'","IGNORE")) {
                sql("CREATE TRIGGER injected BEFORE INSERT ON $table BEGIN SELECT RAISE($signal); END")
                try { rejected { catalog.accept(attempt,first()) }; empty() } finally { sql("DROP TRIGGER injected") }
            }
        }
        assertEquals(IconCatalogCheckpoint(1,2,2),catalog.accept(attempt,first())); assertEquals(2,queue.jobs(context).size)
    }

    @Test fun finalCheckpointTriggerCannotForgeReadyChoiceRewriteQueueOrHistoryAndQuotaRollbackIsAtomic() = runBlocking<Unit> {
        val context=metadata.capture(); val attempt=catalog.next(context)
        val mutations=listOf("UPDATE icon_transfers SET generation=generation+1",
            "UPDATE icon_catalog_entries SET metadataHash='${"0".repeat(64)}'",
            "UPDATE icon_catalog_state SET generation=generation+1",
            "INSERT INTO icon_pack_selection VALUES('${id(1)}','${id(2)}','${id(3)}',1,'${id(20)}',1)",
            "INSERT INTO icon_blob_ready SELECT accountId,serverInstanceId,syncEpoch,sha256,operationId,'svg-v1' FROM icon_blob_reservations",
            "UPDATE icon_assets SET metadataJson=REPLACE(metadataJson,'asset 10','rewritten')")
        for (mutation in mutations) {
            sql("CREATE TRIGGER injected AFTER INSERT ON icon_catalog_state BEGIN $mutation; END")
            try { rejected { catalog.accept(attempt,first()) }; empty() } finally { sql("DROP TRIGGER injected") }
        }
        for ((limits,jobs) in listOf(AccountIconLimits(assets=0) to 100, AccountIconLimits(bytes=0) to 100,
            AccountIconLimits(metadataBytes=0) to 100,AccountIconLimits() to 0)) {
            configure(limits,jobs); rejected { catalog.accept(catalog.next(context),first()) }; empty()
        }
        configure(); catalog.accept(catalog.next(context),first()); val saved=durable()
        configure(AccountIconLimits(0,0,0),0)
        assertEquals(IconCatalogCheckpoint(1,2,2),catalog.accept(attempt,first())); assertEquals(saved,durable())
    }

    @Test fun missingHistoryIntentBadSqlTypeOrReceiptNeverBecomesAnEmptyCheckpointOrSilentRepair() = runBlocking<Unit> {
        val context=metadata.capture(); catalog.accept(catalog.next(context),first()); val baseline=durable()
        val bad=listOf("DELETE FROM icon_catalog_state", "DELETE FROM icon_catalog_entries WHERE sequence=1",
            "DELETE FROM icon_transfers WHERE variant='dark'", "UPDATE icon_catalog_state SET generation=1.5",
            "UPDATE icon_catalog_state SET through=9223372036854775808", "UPDATE icon_catalog_state SET cursor=0,through=1",
            "UPDATE icon_catalog_entries SET revision=4294967296", "UPDATE icon_catalog_entries SET sequence=CAST(sequence AS BLOB)",
            "UPDATE icon_catalog_entries SET metadataHash=CAST(metadataHash AS BLOB)", "UPDATE icon_catalog_entries SET metadataHash='${"0".repeat(64)}'")
        // Each independent corrupted native file is restored only by the exact test transaction rollback.
        for (mutation in bad) {
            try { database.withTransaction {
                sql(mutation); val damaged=durable()
                rejected { catalog.next(context) }; assertEquals(damaged,durable())
                throw TestRollback()
            } } catch (_: TestRollback) { /* Roll back exactly this deliberate test corruption. */ }
            assertEquals(baseline,durable())
        }
    }

    private class TestRollback : RuntimeException()

    @Test fun persistedMissingIntentIsRejectedAfterColdReopenAndCannotBeRepairedByAnotherPageOrReplay() = runBlocking<Unit> {
        val context=metadata.capture(); val attempt=catalog.next(context)
        val first=page(listOf(AppearanceCatalogEntry.Asset(1,asset())),2)
        catalog.accept(attempt,first); val next=catalog.next(context)
        sql("DELETE FROM icon_transfers WHERE variant='dark'"); val damaged=durable(); reopen()
        rejected { catalog.next(context) }
        rejected { catalog.accept(next,page(listOf(AppearanceCatalogEntry.Pack(2,pack())))) }
        rejected { catalog.accept(attempt,first) }; assertEquals(damaged,durable())
        assertEquals(1,rows("icon_transfers").size); assertTrue(rows("icon_packs").isEmpty())
    }

    @Test fun mixedOriginalTransferStatesAndProofsSurviveLaterCatalogPageAndExactReplay() = runBlocking<Unit> {
        val context=metadata.capture(); catalog.accept(catalog.next(context),page(listOf(AppearanceCatalogEntry.Asset(1,asset()),AppearanceCatalogEntry.Asset(2,asset(11))),3))
        val first=requireNotNull(queue.prepareNext(context)); store.installDownloaded(context,first.job.targetId,hash,bytes); queue.confirmDownload(first)
        queue.block(requireNotNull(queue.prepareNext(context)),IconTransferFailure.REMOTE_NOT_FOUND)
        queue.prepareNext(context); val before=rows("icon_transfers"); val ready=rows("icon_blob_ready")
        val attempt=catalog.next(context); val last=page(listOf(AppearanceCatalogEntry.Pack(3,pack(20,listOf(asset(),asset(11))))))
        catalog.accept(attempt,last); catalog.accept(attempt,last)
        assertEquals(before,rows("icon_transfers")); assertEquals(ready,rows("icon_blob_ready"))
        assertEquals(setOf(IconTransferState.PENDING,IconTransferState.SENDING,IconTransferState.BLOCKED,IconTransferState.COMPLETE),queue.jobs(context).map { it.state }.toSet())
        reopen(); assertEquals(before,rows("icon_transfers")); assertEquals(IconCatalogCheckpoint(2,3,3),catalog.next(context).checkpoint)
    }

    @Test fun accountReplicaDevicePermissionOrReloginChangeRejectsCapturedPageAndKeepsDurableNamespace() = runBlocking<Unit> {
        val original=metadata.capture(); catalog.accept(catalog.next(original),page(listOf(AppearanceCatalogEntry.Asset(1,asset())),2))
        val variants=listOf<suspend () -> Unit>({ login(owner=id(90)) },{ login(server=id(91)) },{ login(epoch=id(92)) },
            { login(device=id(93)) },{ login(revision=2) },{ login() })
        for (change in variants) {
            login(); val context=metadata.capture(); val attempt=catalog.next(context); val saved=durable()
            change(); rejected { catalog.accept(attempt,page(listOf(AppearanceCatalogEntry.Pack(2,pack())))) }; assertEquals(saved,durable())
            val now=catalog.next(metadata.capture()).checkpoint
            assertTrue(now==IconCatalogCheckpoint(0,0,0) || now==IconCatalogCheckpoint(1,1,2))
        }
        login(); assertEquals(IconCatalogCheckpoint(2,2,2),catalog.accept(catalog.next(metadata.capture()),page(listOf(AppearanceCatalogEntry.Pack(2,pack())))))
    }

    @Test fun twoRealRoomConnectionsExactConcurrentDeliveryCannotAllocateSecondIdentityAndReplayCanRetry() = runBlocking<Unit> {
        val context=metadata.capture(); val a=catalog.next(context)
        val second=AccountIconDatabase.open(app)
        try {
            val repo=AccountIconRepository(second,tokens,AccountSessionCoordinator()); val files=AccountIconStore(repo,AccountIconFiles(directory))
            val other=AccountIconRemoteCatalog(second,repo,AccountIconTransfers(second,repo,files)); val b=other.next(context)
            val results=awaitAll(async(Dispatchers.IO) { runCatching { catalog.accept(a,first()) } },
                async(Dispatchers.IO) { runCatching { other.accept(b,first()) } })
            assertTrue(results.any { it.isSuccess }); assertEquals(IconCatalogCheckpoint(1,2,2),catalog.next(context).checkpoint)
            val saved=durable(); assertEquals(IconCatalogCheckpoint(1,2,2),other.accept(b,first())); assertEquals(saved,durable())
        } finally { second.close() }
    }

    @Test fun cancellationDuringFinalRealRoomInsertJoinsWorkerAndEntirePageRollsBackThroughReopen() = runBlocking<Unit> {
        val context=metadata.capture(); val attempt=catalog.next(context); database.close()
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        database=Room.databaseBuilder(app,AccountIconDatabase::class.java,AccountIconDatabase.NAME)
            .addMigrations(AccountIconDatabase.MIGRATION_1_2,AccountIconDatabase.MIGRATION_2_3,AccountIconDatabase.MIGRATION_3_4,AccountIconDatabase.MIGRATION_4_5)
            .setQueryCallback({ sql,_ -> if (sql.startsWith("INSERT",ignoreCase=true) && sql.contains("icon_catalog_state")) {
                entered.countDown(); check(release.await(5,TimeUnit.SECONDS))
            } },Executor { it.run() }).build()
        configure(); val work=async(Dispatchers.IO) { catalog.accept(attempt,first()) }
        try { assertTrue(entered.await(5,TimeUnit.SECONDS)); work.cancel() }
        finally { release.countDown(); withTimeout(5000) { work.join() } }
        assertTrue(work.isCancelled); empty(); reopen(); empty()
        assertEquals(IconCatalogCheckpoint(1,2,2),catalog.accept(catalog.next(context),first()))
    }

    @Test fun fourMaximumPackMetadataResponsesFitExistingJsonLimitOnActualHttpWithoutInstallingOrGuessingOwnership() = runBlocking<Unit> {
        val context=metadata.capture(); val name="😀".repeat(80)
        val light=IconBlob("a".repeat(64),524288,"image/svg+xml",1024,1024); val dark=light.copy(sha256="b".repeat(64))
        val assets=(100..227).map { IconAsset(id(it),name,"general","template",light,dark) }
        val roles=(0..255).associate { "habit.${"a".repeat(40)}${it.toString().padStart(6,'0')}" to assets[it%128].assetId }
        val values=(0..3).map { AppearanceCatalogEntry.Pack(129L+it,
            IconPack("dayforge.icon-pack",1,id(300+it),Int.MAX_VALUE,name,assets,roles,assets.first().assetId)) }
        val response=encoded(page(values,132)); assertTrue(response.toByteArray().size<1_048_576)
        val (http,_)=http { input,_ -> rawPage(if (input.path.endsWith("/identity")) identity() else response) }
        val actual=http.session(context) { it.catalog(IconCatalogAttempt(context,IconCatalogCheckpoint(1,128,128))) }
        assertEquals(page(values,132),actual); empty()
    }

    @Test fun actualSecondPagePeerFailureKeepsFirstCommitAndColdRetryResumesItsFrozenBoundary() = runBlocking<Unit> {
        val context=metadata.capture(); var failing=true
        val (http,server)=http { input,_ -> when {
            input.path.endsWith("/identity") -> rawPage(identity())
            input.target.contains("&after=0&") -> rawPage(encoded(page(listOf(AppearanceCatalogEntry.Asset(1,asset())),2)))
            failing -> null
            else -> { assertTrue(input.target.contains("&after=1&limit=4&through=2")); rawPage(encoded(page(listOf(AppearanceCatalogEntry.Pack(2,pack()))))) }
        } }
        assertEquals(IconCatalogBatch(true,1,1,stopped=true),processor.run(context,http))
        assertEquals(IconCatalogCheckpoint(1,1,2),catalog.next(context).checkpoint)
        val jobs=rows("icon_transfers"); assertEquals(3,server.requests.size); assertTrue(rows("icon_packs").isEmpty())
        reopen(); failing=false
        assertEquals(IconCatalogBatch(true,1,1),processor.run(context,http)); assertEquals(jobs,rows("icon_transfers"))
        assertEquals(IconCatalogCheckpoint(2,2,2),catalog.next(context).checkpoint); assertEquals(5,server.requests.size)
    }

    @Test fun actualCatalogReplyAfterAccountReplacementCannotPublishAnyMetadataOrCheckpoint() = runBlocking<Unit> {
        val context=metadata.capture()
        val (http,server)=http { input,_ -> if (input.path.endsWith("/identity")) rawPage(identity()) else {
            runBlocking { login(owner=id(90)) }; rawPage(encoded(first()))
        } }
        assertEquals("ICON_SESSION_CHANGED",rejected { processor.run(context,http) }.message)
        empty(); assertEquals(IconCatalogCheckpoint(0,0,0),catalog.next(metadata.capture()).checkpoint)
        assertEquals(2,server.requests.size)
    }

    @Test fun cancellingActualCatalogCallClosesItsSocketAndJoinsBeforeDatabaseTeardownWithoutAdvancing() = runBlocking<Unit> {
        val context=metadata.capture(); val entered=CountDownLatch(1); val closed=CountDownLatch(1)
        val (http,_)=http { input,socket -> if (input.path.endsWith("/identity")) rawPage(identity()) else {
            entered.countDown(); assertEquals(-1,socket.getInputStream().read()); closed.countDown(); null
        } }
        val work=async(Dispatchers.IO) { processor.run(context,http) }
        try { assertTrue(entered.await(5,TimeUnit.SECONDS)); work.cancel() }
        finally { withTimeout(5000) { work.join() } }
        assertTrue(work.isCancelled); assertTrue(closed.await(5,TimeUnit.SECONDS)); empty()
        reopen(); assertEquals(IconCatalogCheckpoint(0,0,0),catalog.next(context).checkpoint)
    }

    @Test fun productionRuntimeSerializesCatalogAndTransferOwnersAcrossActualNetworkWait() = runBlocking<Unit> {
        val context=metadata.capture(); catalog.accept(catalog.next(context),page(listOf(AppearanceCatalogEntry.Asset(1,asset()))))
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val (http,server)=http { input,_ -> when {
            input.path.endsWith("/identity") -> rawPage(identity())
            input.path.endsWith("/catalog") -> {
                entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS))
                rawPage(encoded(page(emptyList(),1)))
            }
            else -> MaterialSocketServer.Reply(bytes,type="image/svg+xml",headers=mapOf("Cache-Control" to "private, no-store","X-Content-Type-Options" to "nosniff"))
        } }
        val imports=AccountIconImport(metadata,store,queue)
        val icons=AccountIconController({ AccountIconRuntime(metadata,store,AccountIconRenderer(metadata,store),imports,
            AccountIconDocuments(imports,app.contentResolver),database::close) },tokens)
        val service=AccountIconTransferService(icons,http)
        val reading=async(Dispatchers.IO) { service.refreshCatalog() }
        var sending: Deferred<IconTransferBatch>?=null
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS)); val started=CompletableDeferred<Unit>()
            sending=async(Dispatchers.IO) { started.complete(Unit); service.transfer() }
            started.await(); delay(100)
            assertFalse(sending.isCompleted); assertEquals(2,server.requests.size)
            assertTrue(queue.jobs(context).all { it.state==IconTransferState.PENDING && it.generation==0L })
        } finally {
            release.countDown()
            try {
                assertEquals(IconCatalogBatch(true,1,0),withTimeout(10000) { reading.await() })
                sending?.let { assertEquals(IconTransferBatch(true,2,0,false,true),withTimeout(10000) { it.await() }) }
                assertTrue(rows("icon_pack_selection").isEmpty())
            } finally { icons.close() }
        }
        assertEquals(5,server.requests.size)
    }

    @Test fun exhaustedGenerationRetainsAllDataAndOnlyUnchangedEmptyTailMayReplayWithoutWriting() = runBlocking<Unit> {
        val context=metadata.capture(); catalog.accept(catalog.next(context),first())
        sql("UPDATE icon_catalog_state SET generation=9223372036854775807")
        val attempt=catalog.next(context); val saved=durable()
        assertEquals("ICON_CATALOG_EXHAUSTED",rejected { catalog.accept(attempt,page(listOf(AppearanceCatalogEntry.Asset(3,asset(11))))) }.message)
        assertEquals(saved,durable())
        assertEquals(IconCatalogCheckpoint(Long.MAX_VALUE,2,2),catalog.accept(attempt,page(emptyList(),2)))
        assertEquals(saved,durable())
    }

    @Test fun catalogAndProcessorRejectDifferentCanonicalMetadataOwnersBeforeWork() = runBlocking<Unit> {
        val other=AccountIconRepository(database,tokens,AccountSessionCoordinator())
        val files=AccountIconStore(other,AccountIconFiles(directory)); val otherQueue=AccountIconTransfers(database,other,files)
        assertThrows(IllegalArgumentException::class.java) { AccountIconRemoteCatalog(database,metadata,otherQueue) }
        val remote=AccountIconRemoteCatalog(database,other,otherQueue)
        assertThrows(IllegalArgumentException::class.java) { AccountIconCatalogProcessor(metadata,remote,Mutex()) }
        empty()
    }
}
