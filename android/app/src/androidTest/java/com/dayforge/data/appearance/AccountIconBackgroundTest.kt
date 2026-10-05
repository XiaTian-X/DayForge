package com.dayforge.data.appearance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.authenticator.TokenAuthenticator
import com.dayforge.data.api.interceptor.AuthInterceptor
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.sync.AccountIconWorker
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconBackgroundTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var database: AccountIconDatabase
    private lateinit var metadata: AccountIconRepository
    private lateinit var store: AccountIconStore
    private lateinit var queue: AccountIconTransfers
    private lateinit var imports: AccountIconImport
    private lateinit var icons: AccountIconController
    private lateinit var client: OkHttpClient
    private val servers = mutableListOf<MaterialSocketServer>()
    private val wire = Json { encodeDefaults = true }
    private val bytes = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private fun id(n: Int) = "a9500000-0000-4000-8000-${n.toString(16).padStart(12,'0')}"
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun asset(n: Int = 10) = IconAsset(id(n),"asset $n","general","template",IconBlob(hash,bytes.size,"image/svg+xml",1,1),null)
    private fun identity(version: Int = 5) = """{"server_instance_id":"${id(2)}","sync_epoch":"${id(3)}","protocol_version":$version,"capabilities":["sync.read","structure.write"],"server_time":"2026-10-05T00:00:00Z"}"""
    private fun json(text: String, status: Int = 200) = MaterialSocketServer.Reply(text.toByteArray(),status)
    private fun page(entries: List<AppearanceCatalogEntry>, through: Long = entries.lastOrNull()?.sequence ?: 0L) =
        wire.encodeToString(AppearanceCatalogPage(AssetSyncContext(id(2),id(3),id(4)),entries,
            entries.lastOrNull()?.sequence ?: through,through,(entries.lastOrNull()?.sequence ?: through)<through))
    private suspend fun login(owner: String = id(1), editing: Boolean = false, server: String = id(2), epoch: String = id(3),
        device: String = id(4), revision: Int = 1) {
        tokens.saveLoginSession("synthetic-access","synthetic-refresh","member",owner,false)
        tokens.saveServerIdentity(server,epoch)
        tokens.saveDeviceRegistration(device,if (editing) setOf("sync.read","structure.write") else setOf("sync.read"),true,revision)
    }
    private fun configure(io: IconFileIo = IconFileIo(), deadline: Long = 240_000) {
        metadata=AccountIconRepository(database,tokens,AccountSessionCoordinator())
        store=AccountIconStore(metadata,AccountIconFiles(directory,io)); queue=AccountIconTransfers(database,metadata,store)
        imports=AccountIconImport(metadata,store,queue)
        icons=AccountIconController({ AccountIconRuntime(metadata,store,AccountIconRenderer(metadata,store),imports,
            AccountIconDocuments(imports,app.contentResolver),database::close,deadline) },tokens)
    }
    private fun reopen() { icons.close(); database.close(); database=AccountIconDatabase.open(app); configure() }
    private fun rows(table: String) = database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { c -> buildList {
        while (c.moveToNext()) add(List(c.columnCount) { i -> if (c.isNull(i)) "null" else "${c.getType(i)}:" +
            if (c.getType(i)==android.database.Cursor.FIELD_TYPE_BLOB) c.getBlob(i).joinToString("") { "%02x".format(it) } else c.getString(i) })
    } }
    private fun durable() = listOf("icon_assets","icon_packs","icon_blob_reservations","icon_blob_ready","icon_pack_selection",
        "icon_transfers","icon_catalog_entries","icon_catalog_state").associateWith(::rows)
    private fun files(context: AccountIconContext) = File(directory,"account-icons-v1/${context.namespace.accountId}/${context.namespace.serverInstanceId}/${context.namespace.syncEpoch}")
    private suspend fun known(n: Int = 10): AccountIconContext {
        login(editing=true); val context=metadata.capture(); metadata.reserveAsset(context,asset(n)); login()
        val reader=metadata.capture(); queue.enqueueDownload(reader,id(n),"light"); return reader
    }
    private suspend fun http(reply: (MaterialSocketServer.Input,java.net.Socket) -> MaterialSocketServer.Reply?): Pair<AccountIconTransferService,MaterialSocketServer> {
        val server=MaterialSocketServer { input,socket ->
            assertEquals("5",input.headers["x-dayforge-protocol"]); assertEquals("identity",input.headers["accept-encoding"])
            if (input.path.endsWith("/identity")) assertNull(input.headers["authorization"])
            else assertEquals("Bearer synthetic-access",input.headers["authorization"])
            reply(input,socket)
        }
        servers.add(server); preferences.setServerUrl(server.origin.toString())
        return AccountIconTransferService(icons,AccountIconHttp(client,tokens,server.origin)) to server
    }
    private fun normal(input: MaterialSocketServer.Input): MaterialSocketServer.Reply = when {
        input.path.endsWith("/identity") -> json(identity())
        input.path.endsWith("/catalog") -> json(page(emptyList()))
        else -> { assertEquals("GET",input.method); MaterialSocketServer.Reply(bytes,type="image/svg+xml",
            headers=mapOf("Cache-Control" to "private, no-store","X-Content-Type-Options" to "nosniff")) }
    }
    private suspend fun work(service: AccountIconTransferService) = AccountIconWorker(app,
        mockk<WorkerParameters>(relaxed=true),service::synchronize).doWork()
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName=="com.dayforge.testbed"); assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        directory=Files.createTempDirectory(app.filesDir.toPath(),"icon-background-").toFile()
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val data=PreferenceDataStoreFactory.create(scope=scope,produceFile={ File(directory,"auth.preferences_pb") })
        tokens=TokenManager(data); preferences=PreferencesManager(data); database=AccountIconDatabase.open(app); configure(); login()
        client=OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(preferences)).addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens,preferences,SelectedNetworkTransport())).build()
    }
    @After fun cleanup() = runBlocking<Unit> {
        var failure: Throwable?=null
        suspend fun finish(block: suspend () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure==null) failure=error else failure!!.addSuppressed(error)
        } }
        servers.forEach { finish { it.close() } }
        finish { if (::client.isInitialized) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }
        finish { if (::icons.isInitialized) icons.close() }
        finish { if (::database.isInitialized) database.close() }
        finish { if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin() }
        finish { if (::directory.isInitialized) assertTrue(directory.deleteRecursively()) }
        finish { assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists()) }
        failure?.let { throw it }
    }

    @Test fun inactiveAuthorizationInitializesNeitherRuntimeNorHttpOrDatabase() = runBlocking<Unit> {
        tokens.clearTokens()
        val unused=AccountIconController({ throw AssertionError("Must not initialize") },tokens)
        val server=MaterialSocketServer { _,_ -> throw AssertionError("Must not send") }; servers.add(server)
        val service=AccountIconTransferService(unused,AccountIconHttp(client,tokens,server.origin))
        assertEquals(IconMaterialOutcome.INACTIVE,service.synchronize()); assertTrue(server.requests.isEmpty())
        assertTrue(durable().values.all { it.isEmpty() })
    }

    @Test fun readonlyColdCatalogThenDownloadsUseOneSessionAndRetainIntentIdentityWithoutDeclarations() = runBlocking<Unit> {
        val (service,server)=http { input,_ -> if (input.path.endsWith("/catalog")) {
            json(if (input.target.contains("&after=0&")) page(listOf(AppearanceCatalogEntry.Asset(1,asset()))) else page(emptyList(),1))
        } else normal(input) }
        assertEquals(Result.retry(),work(service)); val context=metadata.capture(); val jobs=queue.jobs(context)
        assertEquals(1,jobs.size); assertEquals(IconTransferState.PENDING,jobs.single().state); assertTrue(rows("icon_blob_ready").isEmpty())
        reopen(); val next=AccountIconTransferService(icons,AccountIconHttp(client,tokens,server.origin))
        assertEquals(Result.success(workDataOf(AccountIconWorker.STATUS to "COMPLETE")),work(next)); assertEquals(jobs.single().operationId,queue.jobs(context).single().operationId)
        assertEquals(IconTransferState.COMPLETE,queue.jobs(context).single().state); assertArrayEquals(bytes,store.read(context,id(10),hash))
        assertEquals(5,server.requests.size); assertTrue(server.requests.all { it.method=="GET" }); assertTrue(rows("icon_pack_selection").isEmpty())
    }

    @Test fun v4AndFutureDoNotRecoverSendingOrCleanOwnedTemporaryButExactV5ColdRunDoes() = runBlocking<Unit> {
        val context=known(); store.installDownloaded(context,id(10),hash,bytes); val attempt=requireNotNull(queue.prepareNext(context))
        val operation=metadata.reservations(context).single().operationId
        val part=File(files(context),".install-$operation.part").apply { writeBytes(byteArrayOf(1)) }
        val foreign=File(files(context),".install-${id(99)}.part").apply { writeBytes(byteArrayOf(2)) }
        val saved=durable(); var version=4
        val (service,server)=http { input,_ -> if (input.path.endsWith("/identity")) json(identity(version)) else normal(input) }
        for (v in listOf(4,6)) { version=v; assertEquals(IconMaterialOutcome.UNSUPPORTED,service.synchronize()); assertEquals(saved,durable()); assertTrue(part.exists()) }
        assertEquals(2,server.requests.size); version=5
        assertEquals(IconMaterialOutcome.COMPLETE,service.synchronize()); assertFalse(part.exists()); assertArrayEquals(byteArrayOf(2),foreign.readBytes())
        val job=queue.jobs(context).single(); assertEquals(attempt.job.operationId,job.operationId); assertEquals(4L,job.generation)
        assertEquals(IconTransferState.COMPLETE,job.state); assertArrayEquals(bytes,store.read(context,id(10),hash))
    }

    @Test fun peerFailureReleasesTheOriginalIntentAndColdRetryCompletesWithoutSecondIdentity() = runBlocking<Unit> {
        val context=known(); val original=queue.jobs(context).single(); var fail=true
        val (service,server)=http { input,_ -> if (fail && input.path.endsWith("/content/light")) null else normal(input) }
        assertEquals(IconMaterialOutcome.RETRY,service.synchronize()); assertEquals(2L,queue.jobs(context).single().generation)
        assertTrue(rows("icon_blob_ready").isEmpty()); reopen(); fail=false
        val next=AccountIconTransferService(icons,AccountIconHttp(client,tokens,server.origin))
        assertEquals(IconMaterialOutcome.COMPLETE,next.synchronize()); val job=queue.jobs(context).single()
        assertEquals(original.operationId,job.operationId); assertEquals(4L,job.generation); assertEquals(IconTransferState.COMPLETE,job.state)
    }

    @Test fun contentWaitingTransientAndFiniteDownloadFailuresKeepPreciseQueueStates() = runBlocking<Unit> {
        val context=known(); var status=409; var code="ASSET_CONTENT_PENDING"
        val (service,_) = http { input,_ -> if (input.path.endsWith("/content/light")) json("""{"detail":{"code":"$code"}}""",status) else normal(input) }
        val identity=queue.jobs(context).single().operationId
        assertEquals(IconMaterialOutcome.RETRY,service.synchronize()); assertEquals(IconTransferState.PENDING,queue.jobs(context).single().state)
        status=503; code="UNAVAILABLE"; assertEquals(IconMaterialOutcome.RETRY,service.synchronize())
        status=404; code="ASSET_NOT_FOUND"; assertEquals(IconMaterialOutcome.ATTENTION,service.synchronize())
        val blocked=queue.jobs(context).single(); assertEquals(identity,blocked.operationId); assertEquals(IconTransferFailure.REMOTE_NOT_FOUND,blocked.failure)
        val saved=rows("icon_transfers"); assertEquals(IconMaterialOutcome.ATTENTION,service.synchronize()); assertEquals(saved,rows("icon_transfers"))
    }

    @Test fun catalogFiniteInvalidAndTransientRepliesNeverAdvanceAndHaveDifferentRetryOutcomes() = runBlocking<Unit> {
        var reply=json("{}",403)
        val (service,_) = http { input,_ -> if (input.path.endsWith("/catalog")) reply else normal(input) }
        for (status in listOf(403,404,413,422)) {
            reply=json("""{"detail":{"code":"DENIED"}}""",status); assertEquals(IconMaterialOutcome.ATTENTION,service.synchronize()); assertTrue(durable().values.all { it.isEmpty() })
        }
        for (status in listOf(408,425,429,503)) {
            reply=json("""{"detail":{"code":"TEMPORARY"}}""",status); assertEquals(IconMaterialOutcome.RETRY,service.synchronize()); assertTrue(durable().values.all { it.isEmpty() })
        }
        reply=json(page(listOf(AppearanceCatalogEntry.Asset(2,asset())))); assertEquals(IconMaterialOutcome.ATTENTION,service.synchronize())
        assertTrue(durable().values.all { it.isEmpty() })
    }

    @Test fun corruptReadyBytesKeepFinalAndOwnedTemporaryAndNeverClaimAJob() = runBlocking<Unit> {
        val context=known(); store.installDownloaded(context,id(10),hash,bytes)
        val operation=metadata.reservations(context).single().operationId
        val part=File(files(context),".install-$operation.part").apply { writeBytes(byteArrayOf(3)) }
        val final=File(files(context),hash).apply { writeBytes(byteArrayOf(4)) }; val saved=durable()
        val (service,server)=http { input,_ -> normal(input) }
        assertEquals(IconMaterialOutcome.ATTENTION,service.synchronize()); assertEquals(saved,durable())
        assertEquals(1,server.requests.size); assertArrayEquals(byteArrayOf(3),part.readBytes()); assertArrayEquals(byteArrayOf(4),final.readBytes())
    }

    @Test fun corruptQueueOrCatalogIsAuditedBeforeAnyOwnedTemporaryCleanupOrClaim() = runBlocking<Unit> {
        val context=known(); store.installDownloaded(context,id(10),hash,bytes)
        val operation=metadata.reservations(context).single().operationId
        val part=File(files(context),".install-$operation.part").apply { writeBytes(byteArrayOf(5)) }
        database.openHelper.writableDatabase.execSQL("UPDATE icon_transfers SET generation=1.5")
        val saved=durable(); val (service,server)=http { input,_ -> normal(input) }
        assertEquals(IconMaterialOutcome.ATTENTION,service.synchronize()); assertEquals(saved,durable()); assertEquals(1,server.requests.size)
        assertArrayEquals(byteArrayOf(5),part.readBytes())
    }

    @Test fun cancellationClosesActualPeerAndJoinsBeforeColdSendingRecovery() = runBlocking<Unit> {
        val context=known(); val entered=CountDownLatch(1); val closed=CountDownLatch(1); var blocking=true
        val (service,server)=http { input,socket -> if (blocking && input.path.endsWith("/content/light")) {
            entered.countDown(); assertEquals(-1,socket.getInputStream().read()); closed.countDown(); null
        } else normal(input) }
        val job=async(Dispatchers.IO) { work(service) }
        try { assertTrue(entered.await(5,TimeUnit.SECONDS)); job.cancel() } finally { withTimeout(5000) { job.join() } }
        assertTrue(job.isCancelled); assertTrue(closed.await(5,TimeUnit.SECONDS)); val sending=queue.jobs(context).single()
        assertEquals(IconTransferState.SENDING,sending.state); assertTrue(rows("icon_blob_ready").isEmpty())
        reopen(); blocking=false; val next=AccountIconTransferService(icons,AccountIconHttp(client,tokens,server.origin))
        assertEquals(IconMaterialOutcome.COMPLETE,next.synchronize()); assertEquals(sending.operationId,queue.jobs(context).single().operationId)
        assertEquals(4L,queue.jobs(context).single().generation)
    }

    @Test fun onlyOwnedDeadlineBecomesRetryAfterClosingActualIoAndKeepsSendingForNextRun() = runBlocking<Unit> {
        configure(deadline=2000); val context=known(); val closed=CountDownLatch(1)
        val (service,_) = http { input,socket -> if (input.path.endsWith("/content/light")) {
            assertEquals(-1,socket.getInputStream().read()); closed.countDown(); null
        } else normal(input) }
        assertEquals(Result.retry(),withTimeout(6000) { work(service) })
        assertTrue(closed.await(5,TimeUnit.SECONDS)); assertEquals(IconTransferState.SENDING,queue.jobs(context).single().state)
        assertTrue(rows("icon_blob_ready").isEmpty())
    }

    @Test fun thirtyTwoIntentBudgetColdContinuesOriginalPendingIdsInsteadOfPretendingQueueIsComplete() = runBlocking<Unit> {
        login(editing=true); val writer=metadata.capture()
        for (n in 10..43) metadata.reserveAsset(writer,asset(n))
        login(); val context=metadata.capture()
        for (n in 10..43) queue.enqueueDownload(context,id(n),"light")
        val original=queue.jobs(context).map { it.operationId }.toSet()
        val (service,server)=http { input,_ -> normal(input) }
        assertEquals(IconMaterialOutcome.RETRY,service.synchronize())
        assertEquals(32,queue.jobs(context).count { it.state==IconTransferState.COMPLETE })
        assertEquals(2,queue.jobs(context).count { it.state==IconTransferState.PENDING }); assertEquals(34,server.requests.size)
        reopen(); val next=AccountIconTransferService(icons,AccountIconHttp(client,tokens,server.origin))
        assertEquals(IconMaterialOutcome.COMPLETE,next.synchronize()); assertEquals(original,queue.jobs(context).map { it.operationId }.toSet())
        assertTrue(queue.jobs(context).all { it.state==IconTransferState.COMPLETE }); assertEquals(38,server.requests.size)
    }

    @Test fun fourCatalogPageBudgetAndNewDownloadsContinueAcrossColdWholeRoundsWithFrozenThrough() = runBlocking<Unit> {
        val (service,server)=http { input,_ -> if (input.path.endsWith("/catalog")) {
            val after=Regex("[?&]after=(\\d+)").find(input.target)!!.groupValues[1].toLong()
            if (after in 1..5) assertTrue(input.target.contains("through=6"))
            json(if (after<6) page(listOf(AppearanceCatalogEntry.Asset(after+1,asset((10+after).toInt()))),6) else page(emptyList(),6))
        } else normal(input) }
        assertEquals(IconMaterialOutcome.RETRY,service.synchronize()); val context=metadata.capture()
        assertEquals(IconCatalogCheckpoint(4,4,6),metadata.remoteCatalog(queue).next(context).checkpoint)
        val ids=queue.jobs(context).map { it.operationId }.toSet(); assertEquals(4,ids.size); reopen()
        val next=AccountIconTransferService(icons,AccountIconHttp(client,tokens,server.origin))
        assertEquals(IconMaterialOutcome.RETRY,next.synchronize()); assertEquals(4,queue.jobs(context).count { it.state==IconTransferState.COMPLETE })
        assertTrue(queue.jobs(context).map { it.operationId }.containsAll(ids)); assertEquals(IconCatalogCheckpoint(6,6,6),metadata.remoteCatalog(queue).next(context).checkpoint)
        assertEquals(IconMaterialOutcome.COMPLETE,next.synchronize()); assertTrue(queue.jobs(context).all { it.state==IconTransferState.COMPLETE })
        assertEquals(3,server.requests.count { it.path.endsWith("/identity") })
    }

    @Test fun lateReplacementAccountCatalogCannotBecomeSuccessOrPublishNewOwnerDataAndEarlierCommitIsRetained() = runBlocking<Unit> {
        val context=known()
        val (service,server)=http { input,_ -> if (input.path.endsWith("/catalog")) {
            runBlocking { login(owner=id(90)) }; json(page(listOf(AppearanceCatalogEntry.Asset(1,asset(11)))))
        } else normal(input) }
        assertEquals(IconMaterialOutcome.ATTENTION,service.synchronize()); assertEquals(3,server.requests.size)
        assertEquals(1,rows("icon_assets").size); assertEquals(1,rows("icon_blob_ready").size); assertTrue(rows("icon_catalog_entries").isEmpty())
        assertTrue(rows("icon_transfers").single().contains("3:complete")); val current=metadata.capture()
        assertTrue(metadata.reservations(current).isEmpty()); assertTrue(queue.jobs(current).isEmpty())
        login(); assertArrayEquals(bytes,store.read(metadata.capture(),id(10),hash)); assertEquals(id(1),context.namespace.accountId)
    }

    private fun archive(): ByteArray {
        val blob="""{"sha256":"$hash","byte_length":${bytes.size},"media_type":"image/svg+xml","width":1,"height":1}"""
        val manifest="""{"format":"dayforge.icon-pack","format_version":1,"pack_id":"${id(20)}","revision":1,"name":"pack",
            "assets":[{"asset_id":"${id(10)}","name":"asset 10","purpose":"general","color_mode":"template","light":$blob,"dark":null}],
            "roles":{"habit.general":"${id(10)}"},"placeholder_asset_id":null}"""
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> for ((name,value) in listOf("manifest.json" to manifest.toByteArray(),"blobs/$hash" to bytes)) {
            zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
        } }
        return out.toByteArray()
    }

    @Test fun actualImportFilePhaseOwnsRuntimeBeforeBackgroundCanClaimOrSendAndThenWakeupIsDelivered() = runBlocking<Unit> {
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val once=AtomicBoolean(true)
        configure(object: IconFileIo() { override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
            if (once.compareAndSet(true,false)) { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
            return super.write(fd,bytes,offset,length)
        } })
        login(editing=true); val context=metadata.capture(); val preview=imports.preview { ByteArrayInputStream(archive()) }
        val wake=async(start=CoroutineStart.UNDISPATCHED) { icons.workRequests.first() }
        val (service,server)=http { input,_ -> when {
            input.path.endsWith("/identity") -> json(identity())
            input.path.endsWith("/catalog") -> json(page(listOf(AppearanceCatalogEntry.Asset(1,asset()),AppearanceCatalogEntry.Pack(2,preview.manifest))))
            input.path.endsWith("/content/light") -> {
                assertEquals("PUT",input.method); assertArrayEquals(bytes,input.body)
                json(wire.encodeToString(AssetTransferReceipt(AssetSyncContext(id(2),id(3),id(4)),id(10),"light",asset().light)))
            }
            input.path.contains("/packs/") -> json(wire.encodeToString(PackDeclaration(AssetSyncContext(id(2),id(3),id(4)),preview.manifest)))
            else -> json(wire.encodeToString(AssetRecord(AssetSyncContext(id(2),id(3),id(4)),asset(),emptyList())))
        } }
        val installing=async(Dispatchers.IO) { icons.install(preview) }; var working: Deferred<IconMaterialOutcome>?=null
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS)); assertEquals(3,queue.jobs(context).size)
            val started=CompletableDeferred<Unit>(); working=async(Dispatchers.IO) { started.complete(Unit); service.synchronize() }
            started.await(); delay(100); assertFalse(working.isCompleted); assertTrue(server.requests.isEmpty())
            assertTrue(queue.jobs(context).all { it.state==IconTransferState.PENDING }); assertFalse(wake.isCompleted)
        } finally {
            release.countDown()
            try { withTimeout(10000) { installing.await(); working?.let { assertEquals(IconMaterialOutcome.RETRY,it.await()) }; wake.await() } }
            finally { installing.cancelAndJoin(); working?.cancelAndJoin(); wake.cancelAndJoin() }
        }
        assertEquals(5,server.requests.size); assertEquals(3,queue.jobs(context).count { it.state==IconTransferState.COMPLETE })
        assertTrue(queue.jobs(context).none { it.failure==IconTransferFailure.LOCAL_CONTENT }); assertArrayEquals(bytes,store.read(context,id(10),hash))
    }

    @Test fun failedFilePhaseStillWakesBackgroundAndPreservesDurableIdentityForExplicitImportRetry() = runBlocking<Unit> {
        login(editing=true); val context=metadata.capture(); val preview=imports.preview { ByteArrayInputStream(archive()) }
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER injected BEFORE INSERT ON icon_blob_ready BEGIN SELECT RAISE(ABORT,'injected'); END")
        val wake=async(start=CoroutineStart.UNDISPATCHED) { icons.workRequests.first() }
        try {
            var failure: Exception?=null
            try { icons.install(preview) } catch (error: Exception) { if (error is CancellationException) throw error; failure=error }
            assertNotNull(failure); withTimeout(5000) { wake.await() }; assertTrue(rows("icon_blob_ready").isEmpty())
        } finally { wake.cancelAndJoin(); database.openHelper.writableDatabase.execSQL("DROP TRIGGER injected") }
        val before=rows("icon_transfers"); assertEquals(3,before.size); assertTrue(File(files(context),hash).exists())
        icons.install(preview); assertEquals(before,rows("icon_transfers")); assertArrayEquals(bytes,store.read(context,id(10),hash))
    }

    @Test fun actualIdentityFailureIsFiniteOrTransientWithoutRecoveringTheExistingSendingIntent() = runBlocking<Unit> {
        val context=known(); queue.prepareNext(context); val before=durable(); var response=json(identity().replace(id(2),id(88)))
        val (service,server)=http { _,_ -> response }
        assertEquals(IconMaterialOutcome.ATTENTION,service.synchronize()); assertEquals(before,durable())
        response=json("not json"); assertEquals(IconMaterialOutcome.ATTENTION,service.synchronize()); assertEquals(before,durable())
        response=json("""{"detail":{"code":"UNAVAILABLE"}}""",503); assertEquals(IconMaterialOutcome.RETRY,service.synchronize()); assertEquals(before,durable())
        assertEquals(3,server.requests.size)
    }

    @Test fun actualAuthenticationFlowIgnoresCredentialRefreshAndUnrelatedPreferencesButPublishesLogout() = runBlocking<Unit> {
        val original=requireNotNull(tokens.localIconAccess())
        val seen=CopyOnWriteArrayList<com.dayforge.data.local.LocalIconAccess?>()
        val first=CompletableDeferred<Unit>()
        val observing=launch(Dispatchers.IO) { tokens.iconAccessChanges.take(2).collect { seen.add(it); first.complete(Unit) } }
        try {
            withTimeout(5000) { first.await() }; assertEquals(listOf(original),seen.toList())
            val authentication=requireNotNull(tokens.authenticationSnapshot())
            assertTrue(tokens.saveRefreshedTokens(authentication,"fresh-access","fresh-refresh","member",id(1),false))
            preferences.setServerUrl("http://127.0.0.1:9123/")
            assertEquals(original,tokens.localIconAccess()); delay(100); assertEquals(listOf(original),seen.toList())
            tokens.clearTokens(); withTimeout(5000) { observing.join() }
            assertEquals(listOf(original,null),seen.toList()); assertNull(tokens.localIconAccess())
            assertTrue(durable().values.all { it.isEmpty() })
        } finally { observing.cancelAndJoin() }
    }

    @Test fun blockedDownloadDoesNotPreventIndependentPendingWorkAndCannotBecomeUnlimitedRetry() = runBlocking<Unit> {
        login(editing=true); val writer=metadata.capture(); metadata.reserveAsset(writer,asset(10)); metadata.reserveAsset(writer,asset(11))
        login(); val context=metadata.capture(); queue.enqueueDownload(context,id(10),"light"); queue.enqueueDownload(context,id(11),"light")
        val original=queue.jobs(context).associateBy { it.targetId }
        val rejected=original.values.minBy { it.operationId }.targetId
        val accepted=original.keys.single { it != rejected }
        val (service,server)=http { input,_ -> if (input.path.contains("/$rejected/content/light"))
            json("""{"detail":{"code":"ASSET_NOT_FOUND"}}""",404) else normal(input) }
        // The existing transfer batch stops on a finite rejection. Remaining eligible work must
        // request another bounded round, not become success or be suppressed by the blocked row.
        assertEquals(Result.retry(),work(service))
        assertEquals(IconTransferState.BLOCKED,queue.jobs(context).single { it.targetId==rejected }.state)
        assertEquals(IconTransferState.PENDING,queue.jobs(context).single { it.targetId==accepted }.state)
        assertEquals(Result.failure(workDataOf(AccountIconWorker.STATUS to "ATTENTION")),work(service))
        val jobs=queue.jobs(context).associateBy { it.targetId }
        assertEquals(IconTransferState.BLOCKED,jobs.getValue(rejected).state); assertEquals(IconTransferState.COMPLETE,jobs.getValue(accepted).state)
        assertEquals(original.mapValues { it.value.operationId },jobs.mapValues { it.value.operationId })
        assertArrayEquals(bytes,store.read(context,accepted,hash)); val before=durable()
        assertEquals(IconMaterialOutcome.ATTENTION,service.synchronize()); assertEquals(before,durable())
        assertEquals(8,server.requests.size); assertEquals(2,server.requests.count { it.path.endsWith("/content/light") })
    }
}
