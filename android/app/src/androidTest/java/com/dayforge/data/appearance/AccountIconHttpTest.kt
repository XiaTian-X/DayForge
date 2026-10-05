package com.dayforge.data.appearance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.authenticator.TokenAuthenticator
import com.dayforge.data.api.interceptor.AuthInterceptor
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconHttpTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var database: AccountIconDatabase
    private lateinit var metadata: AccountIconRepository
    private lateinit var store: AccountIconStore
    private lateinit var queue: AccountIconTransfers
    private lateinit var processor: AccountIconTransferProcessor
    private lateinit var client: OkHttpClient
    private val servers = mutableListOf<MaterialSocketServer>()
    private val bytes = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private fun id(n: Int) = "a8700000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val blob get() = IconBlob(hash, bytes.size, "image/svg+xml", 1, 1)
    private val asset get() = IconAsset(id(10), "test", "general", "template", blob, blob)
    private val binding get() = """{"server_instance_id":"${id(2)}","sync_epoch":"${id(3)}","device_id":"${id(4)}"}"""
    private val blobJson get() = """{"sha256":"$hash","byte_length":${bytes.size},"media_type":"image/svg+xml","width":1,"height":1}"""
    private val assetJson get() = """{"asset_id":"${id(10)}","name":"test","purpose":"general","color_mode":"template","light":$blobJson,"dark":$blobJson}"""
    private val record get() = """{"context":$binding,"asset":$assetJson,"ready_variants":[]}"""
    private fun identity(version: Int = 5) = """{"server_instance_id":"${id(2)}","sync_epoch":"${id(3)}","protocol_version":$version,"capabilities":["sync.read","structure.write"],"server_time":"2026-10-05T00:00:00Z"}"""
    private fun json(text: String, status: Int = 200) = MaterialSocketServer.Reply(text.toByteArray(), status)
    private suspend fun login(owner: String = id(1), editing: Boolean = true, revision: Int = 1) {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        tokens.saveServerIdentity(id(2), id(3))
        tokens.saveDeviceRegistration(id(4), if (editing) setOf("sync.read", "structure.write") else setOf("sync.read"), true, revision)
    }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        directory = Files.createTempDirectory(app.filesDir.toPath(), "material-http-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") })
        tokens = TokenManager(data); preferences = PreferencesManager(data)
        database = AccountIconDatabase.open(app)
        metadata = AccountIconRepository(database, tokens, AccountSessionCoordinator())
        store = AccountIconStore(metadata, AccountIconFiles(directory)); queue = AccountIconTransfers(database, metadata, store)
        processor = AccountIconTransferProcessor(metadata, store, queue)
        client = OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(preferences)).addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, preferences, SelectedNetworkTransport())).build()
        login(); metadata.reserveAsset(metadata.capture(), asset)
    }
    @After fun cleanup() = runBlocking<Unit> {
        // Each test joins its actual request before these resources are closed.
        var failure: Throwable? = null
        suspend fun finish(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        servers.forEach { finish { it.close() } }
        finish { if (::client.isInitialized) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }
        finish { if (::database.isInitialized) database.close() }
        finish { if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin() }
        finish { if (::directory.isInitialized) assertTrue(directory.deleteRecursively()) }
        finish { assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists()) }
        failure?.let { throw it }
    }
    private suspend fun http(respond: (MaterialSocketServer.Input, java.net.Socket) -> MaterialSocketServer.Reply?):
        Pair<AccountIconHttp, MaterialSocketServer> {
        val server = MaterialSocketServer(respond); servers.add(server)
        preferences.setServerUrl(server.origin.toString())
        return AccountIconHttp(client, tokens, server.origin) to server
    }
    private fun normal(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        assertEquals("5", input.headers["x-dayforge-protocol"]); assertEquals("identity", input.headers["accept-encoding"])
        if (input.path == "/api/v2/system/identity") { assertNull(input.headers["authorization"]); return json(identity()) }
        assertEquals("Bearer synthetic-access", input.headers["authorization"])
        if (input.path.endsWith("/content/light") || input.path.endsWith("/content/dark")) {
            assertTrue(input.target.contains("server_instance_id=${id(2)}") && input.target.contains("sync_epoch=${id(3)}") && input.target.contains("device_id=${id(4)}"))
            if (input.method == "GET") return MaterialSocketServer.Reply(bytes, type = "image/svg+xml",
                headers = mapOf("Cache-Control" to "private, no-store", "X-Content-Type-Options" to "nosniff"))
            assertEquals("PUT", input.method); assertArrayEquals(bytes, input.body)
            assertEquals("image/svg+xml", input.headers["content-type"])
            return json("""{"context":$binding,"asset_id":"${id(10)}","variant":"${input.path.substringAfterLast('/')}","blob":$blobJson}""")
        }
        assertEquals("PUT", input.method)
        assertEquals("application/json; charset=utf-8", input.headers["content-type"])
        val actual = kotlinx.serialization.json.Json.parseToJsonElement(input.body.toString(Charsets.UTF_8))
        val expected = kotlinx.serialization.json.Json.parseToJsonElement("""{"context":$binding,"asset":$assetJson}""")
        assertEquals(expected, actual)
        return json(record)
    }
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { if (error is CancellationException) throw error; return error }
        throw AssertionError("Must reject")
    }

    @Test fun v4AdmissionDoesNotClaimOrRecoverQueueAndNeverSendsPersonalCredentials() = runBlocking<Unit> {
        val context = metadata.capture(); val jobs = queue.enqueueAsset(context, id(10))
        val attempt = requireNotNull(queue.prepareNext(context)); val before = queue.jobs(context)
        val (http, server) = http { _, _ -> json(identity(4)) }
        assertEquals(IconTransferBatch(false, 0, 0, false, false), processor.run(context, http))
        assertEquals(before, queue.jobs(context)); assertEquals(3, jobs.size)
        assertEquals(attempt.job, queue.jobs(context).single { it.operationId == attempt.job.operationId })
        assertEquals(1, server.requests.size); assertNull(server.requests.single().headers["authorization"])
    }

    @Test fun actualHttpDeclarationsBothVariantsAndPackConfirmStableQueueAndColdReplay() = runBlocking<Unit> {
        val context = metadata.capture(); val pack = IconPack("dayforge.icon-pack", 1, id(20), 1, "pack", listOf(asset), mapOf("habit.general" to id(10)), null)
        metadata.reservePack(context, pack); store.install(context, id(10), hash, bytes)
        val jobs = queue.enqueuePack(context, IconPackVersion(id(20), 1)); assertEquals(4, jobs.size)
        val packJson = """{"format":"dayforge.icon-pack","format_version":1,"pack_id":"${id(20)}","revision":1,"name":"pack","assets":[$assetJson],"roles":{"habit.general":"${id(10)}"},"placeholder_asset_id":null}"""
        val (http, server) = http { input, _ -> if (input.path.contains("/packs/")) {
            assertEquals("PUT", input.method); assertEquals("Bearer synthetic-access", input.headers["authorization"])
            assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"context":$binding,"pack":$packJson}"""),
                kotlinx.serialization.json.Json.parseToJsonElement(input.body.toString(Charsets.UTF_8)))
            json("""{"context":$binding,"pack":$packJson}""")
        } else normal(input) }
        assertEquals(IconTransferBatch(true, 4, 0, false, false), processor.run(context, http))
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.COMPLETE }); assertEquals(5, server.requests.size)
        val done = queue.jobs(context); database.close(); database = AccountIconDatabase.open(app)
        metadata = AccountIconRepository(database, tokens, AccountSessionCoordinator())
        store = AccountIconStore(metadata, AccountIconFiles(directory)); queue = AccountIconTransfers(database, metadata, store)
        processor = AccountIconTransferProcessor(metadata, store, queue)
        assertEquals(done, queue.jobs(context)); assertEquals(0, processor.run(context, http).completed)
        assertEquals(done, queue.jobs(context)); assertArrayEquals(bytes, store.read(context, id(10), hash))
    }

    @Test fun readonlyDownloadPublishesRealBytesButCannotDeclareUploadOrChangeSelection() = runBlocking<Unit> {
        login(editing = false, revision = 2); val context = metadata.capture()
        rejected { store.install(context, id(10), hash, bytes) }
        rejected { queue.enqueueAsset(context, id(10)) }
        val original = metadata.library(context); val choice = metadata.selection(context)
        val job = queue.enqueueDownload(context, id(10), "dark")
        val (http, server) = http { input, _ -> normal(input) }
        assertEquals(IconTransferBatch(true, 1, 0, false, true), processor.run(context, http))
        assertArrayEquals(bytes, store.read(context, id(10), hash)); assertEquals(original, metadata.library(context))
        assertEquals(choice, metadata.selection(context)); assertEquals(1, queue.jobs(context).size)
        assertEquals(job.operationId, queue.jobs(context).single().operationId)
        assertEquals(IconTransferState.COMPLETE, queue.jobs(context).single().state)
        assertEquals(listOf("GET", "GET"), server.requests.map { it.method })
    }

    @Test fun strictDeclarationRepliesRejectContextContentUnknownDuplicateKeysAndQuotedNumbers() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10))
        val variants = listOf(record.replace(id(3), id(30)), record.replace("\"test\"", "\"changed\""),
            record.dropLast(1) + ",\"extra\":true}", record.replace("\"ready_variants\":[]", "\"ready_variants\":[],\"ready_variants\":[]"),
            record.replace("\"width\":1", "\"width\":\"1\""), record.replace("\"purpose\":\"general\"", "\"purpose\":2"))
        var reply = variants.first()
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else json(reply) }
        for (value in variants) {
            reply = value
            assertTrue(processor.run(context, http).stopped)
            val blocked = queue.jobs(context).single { it.state == IconTransferState.BLOCKED }
            assertEquals(IconTransferFailure.REMOTE_METADATA, blocked.failure)
            assertTrue(queue.jobs(context).none { it.state == IconTransferState.COMPLETE })
            queue.retryBlocked(context, blocked.operationId, blocked.generation)
        }
    }

    @Test fun responseFramingTypeCompressionRedirectAndSizeCannotBecomeConfirmation() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10))
        val replies = listOf(MaterialSocketServer.Reply(record.toByteArray(), type = "text/plain"),
            MaterialSocketServer.Reply(record.toByteArray(), type = "application/json; charset=ISO-8859-1"),
            MaterialSocketServer.Reply(record.toByteArray(), headers = mapOf("Content-Encoding" to "gzip")),
            MaterialSocketServer.Reply(byteArrayOf(), status = 302, headers = mapOf("Location" to "/followed")),
            MaterialSocketServer.Reply(byteArrayOf(), length = 1_048_577),
            MaterialSocketServer.Reply(ByteArray(1_048_577) { ' '.code.toByte() }, chunked = true, allowClientClose = true),
            MaterialSocketServer.Reply(record.toByteArray().copyOf(15), length = record.toByteArray().size))
        var reply = replies.first()
        val (http, server) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else reply }
        for (value in replies) {
            reply = value; assertTrue(processor.run(context, http).stopped)
            assertTrue(queue.jobs(context).none { it.state == IconTransferState.COMPLETE })
            val blocked = queue.jobs(context).singleOrNull { it.state == IconTransferState.BLOCKED }
            if (blocked != null) queue.retryBlocked(context, blocked.operationId, blocked.generation)
            assertTrue(queue.jobs(context).all { it.state == IconTransferState.PENDING })
        }
        assertTrue(server.requests.none { it.path == "/followed" })
    }

    @Test fun failedDownloadHeadersHashOrNativeImageKeepPendingReservationAndNoReadyBytes() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueDownload(context, id(10), "light")
        val good = MaterialSocketServer.Reply(bytes, type = "image/svg+xml", headers = mapOf("Cache-Control" to "private, no-store", "X-Content-Type-Options" to "nosniff"))
        val replies = listOf(good.copy(headers = emptyMap()), good.copy(bytes = bytes.copyOf().also { it[3] = 'X'.code.toByte() }),
            good.copy(bytes = bytes.copyOf(bytes.size - 1), length = bytes.size - 1))
        var reply = replies.first()
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else reply }
        for (value in replies) {
            reply = value; assertTrue(processor.run(context, http).stopped)
            assertNull(metadata.installation(context, id(10), hash).validationProfile)
            assertFalse(File(directory, "account-icons-v1").exists())
            val job = queue.jobs(context).single(); assertEquals(IconTransferState.BLOCKED, job.state)
            assertEquals(IconTransferFailure.REMOTE_METADATA, job.failure)
            queue.retryBlocked(context, job.operationId, job.generation)
        }
        // Validly hashed but invalid native content is blocked locally, never marked ready.
        val invalid = "not svg".toByteArray(); val invalidHash = MessageDigest.getInstance("SHA-256").digest(invalid).joinToString("") { "%02x".format(it) }
        val bad = asset.copy(assetId = id(11), light = blob.copy(sha256 = invalidHash, byteLength = invalid.size), dark = null)
        val first = queue.jobs(context).single { it.targetId == id(10) }; queue.block(requireNotNull(queue.prepareNext(context)), IconTransferFailure.REMOTE_METADATA)
        assertEquals(first.operationId, queue.jobs(context).single { it.targetId == id(10) }.operationId)
        metadata.reserveAsset(context, bad); queue.enqueueDownload(context, id(11), "light")
        reply = good.copy(bytes = invalid, length = invalid.size)
        assertTrue(processor.run(context, http).stopped)
        assertEquals(IconTransferFailure.LOCAL_CONTENT, queue.jobs(context).single { it.targetId == id(11) }.failure)
        assertNull(metadata.installation(context, id(11), invalidHash).validationProfile)
    }

    @Test fun actual401RefreshUsesCapturedOriginAndReusesSessionWithoutChangingQueueIdentity() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10)); store.install(context, id(10), hash, bytes)
        val attempts = AtomicInteger()
        val (http, server) = http { input, _ -> when {
            input.path.endsWith("/identity") -> json(identity())
            input.path == "/api/v1/auth/refresh" -> {
                assertNull(input.headers["authorization"]); assertEquals("""{"refresh_token":"synthetic-refresh"}""", input.body.toString(Charsets.UTF_8))
                json("""{"access_token":"synthetic-refreshed","refresh_token":"synthetic-next","user_id":"${id(1)}","username":"member","is_admin":false}""")
            }
            attempts.getAndIncrement() == 0 -> json("""{"detail":{"code":"TOKEN_EXPIRED"}}""", 401)
            else -> {
                assertEquals("Bearer synthetic-refreshed", input.headers["authorization"])
                if (input.path.contains("/content/")) json("""{"context":$binding,"asset_id":"${id(10)}","variant":"${input.path.substringAfterLast('/')}","blob":$blobJson}""") else json(record)
            }
        } }
        val generation = tokens.authenticationSnapshot()!!.session
        assertEquals(3, processor.run(context, http).completed)
        assertEquals(generation, tokens.authenticationSnapshot()!!.session)
        assertEquals("synthetic-refreshed", tokens.authenticationSnapshot()!!.accessToken)
        assertEquals(1, server.requests.count { it.path == "/api/v1/auth/refresh" })
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.COMPLETE })
    }

    @Test fun lateAccountSwitchOn401CannotRefreshAuthenticateOrConfirmOldIntent() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10))
        val sent = CountDownLatch(1); val release = CountDownLatch(1)
        val (http, server) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else {
            sent.countDown(); check(release.await(5, TimeUnit.SECONDS)); json("""{"detail":{"code":"TOKEN_EXPIRED"}}""", 401)
        } }
        supervisorScope {
            val task = async(Dispatchers.IO) { processor.run(context, http) }
            try {
                assertTrue(sent.await(5, TimeUnit.SECONDS)); login(id(50))
                release.countDown(); rejected { task.await() }
            } finally { release.countDown(); withContext(NonCancellable) { task.cancelAndJoin() } }
        }
        assertEquals(2, server.requests.size); assertTrue(server.requests.none { it.path.contains("/auth/") })
        login(); val current = metadata.capture(); assertEquals(IconTransferState.SENDING, queue.jobs(current).single { it.kind == IconTransferKind.DECLARE_ASSET }.state)
        assertTrue(queue.jobs(current).none { it.state == IconTransferState.COMPLETE })
    }

    @Test fun concurrentUnsafeCredentialDuring401CannotRetryOrExposeItsValue() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10))
        val original = tokens.authenticationSnapshot()!!
        val (http, server) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else {
            assertEquals("Bearer synthetic-access", input.headers["authorization"])
            runBlocking {
                assertTrue(tokens.saveRefreshedTokens(original, "synthetic\ninvalid", "synthetic-next", "member", id(1), false))
            }
            json("""{"detail":{"code":"TOKEN_EXPIRED"}}""", 401)
        } }
        assertTrue(processor.run(context, http).stopped)
        assertEquals(2, server.requests.size); assertTrue(server.requests.none { it.path.contains("/auth/") })
        assertEquals(original.session, tokens.authenticationSnapshot()!!.session)
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.PENDING })
    }

    @Test fun cancellingActual401RefreshClosesItsOwnedSocketAndJoinsBeforeDatabaseTeardown() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10))
        val stalled = CountDownLatch(1); val closed = CountDownLatch(1)
        val (http, server) = http { input, socket -> when {
            input.path.endsWith("/identity") -> json(identity())
            input.path == "/api/v1/auth/refresh" -> {
                stalled.countDown(); assertEquals(-1, socket.getInputStream().read()); closed.countDown(); null
            }
            else -> json("""{"detail":{"code":"TOKEN_EXPIRED"}}""", 401)
        } }
        val original = tokens.authenticationSnapshot()!!
        val task = async(Dispatchers.IO) { processor.run(context, http) }
        try {
            assertTrue(stalled.await(5, TimeUnit.SECONDS)); withTimeout(5000) { task.cancelAndJoin() }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
        } finally { withContext(NonCancellable) { task.cancelAndJoin() } }
        assertEquals(original.session, tokens.authenticationSnapshot()!!.session)
        assertEquals(original.accessToken, tokens.authenticationSnapshot()!!.accessToken)
        assertEquals(3, server.requests.size)
        assertEquals(IconTransferState.SENDING, queue.jobs(context).single { it.kind == IconTransferKind.DECLARE_ASSET }.state)
    }

    @Test fun cancellingStalledActualIoClosesCallBeforeReturnAndNextOwnerRecoversSameId() = runBlocking<Unit> {
        val context = metadata.capture(); val jobs = queue.enqueueAsset(context, id(10))
        val stalled = CountDownLatch(1); val socketClosed = CountDownLatch(1); var pause = true
        val (http, _) = http { input, socket -> if (pause && !input.path.endsWith("/identity")) {
            socket.getOutputStream().apply { write("HTTP/1.1 200 Fixture\r\nContent-Type: application/json\r\nContent-Length: 1024\r\n\r\n".toByteArray()); flush() }
            stalled.countDown(); assertEquals(-1, socket.getInputStream().read()); socketClosed.countDown(); null
        } else normal(input) }
        val task = async(Dispatchers.IO) { processor.run(context, http) }
        try {
            assertTrue(stalled.await(5, TimeUnit.SECONDS)); withTimeout(5000) { task.cancelAndJoin() }
            assertTrue(socketClosed.await(5, TimeUnit.SECONDS))
        } finally { withContext(NonCancellable) { task.cancelAndJoin() } }
        val sending = queue.jobs(context).single { it.state == IconTransferState.SENDING }
        pause = false; store.install(context, id(10), hash, bytes)
        val result = processor.run(context, http); assertEquals(1, result.recovered); assertEquals(3, result.completed)
        assertEquals(jobs.map { it.operationId }, queue.jobs(context).map { it.operationId })
        assertTrue(queue.jobs(context).single { it.operationId == sending.operationId }.generation > sending.generation)
    }

    @Test fun lostReplyReleasesPendingAndRetryKeepsImmutablePublicAndOperationIds() = runBlocking<Unit> {
        val context = metadata.capture(); val jobs = queue.enqueueAsset(context, id(10)); store.install(context, id(10), hash, bytes)
        var drop = true
        val (http, server) = http { input, _ -> if (drop && !input.path.endsWith("/identity")) { drop = false; null } else normal(input) }
        assertTrue(processor.run(context, http).stopped)
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.PENDING })
        assertEquals(3, processor.run(context, http).completed)
        assertEquals(jobs.map { it.operationId }, queue.jobs(context).map { it.operationId })
        val declarations = server.requests.filter { it.path == "/api/v2/appearance/assets/${id(10)}" }
        assertEquals(2, declarations.size); assertArrayEquals(declarations[0].body, declarations[1].body)
    }

    @Test fun capturedSessionAndReplicaChangesBeforeOrAfterResponseCannotEscapeToNewAuthority() = runBlocking<Unit> {
        val context = metadata.capture()
        val (http, server) = http { input, _ -> normal(input) }
        val original = tokens.iconAuthenticationSnapshot(context.access)!!
        assertTrue(tokens.saveRefreshedTokens(original, "same-generation", "next-refresh", "member", id(1), false))
        assertNotNull(tokens.iconAuthenticationSnapshot(context.access))
        tokens.saveDeviceRegistration(id(4), setOf("sync.read"), true, 2)
        assertNull(tokens.iconAuthenticationSnapshot(context.access)); rejected { http.session(context) { error("Must not enter") } }
        assertTrue(server.requests.isEmpty())
        login(); val next = metadata.capture(); var escaped: AccountIconHttp.Session? = null
        http.session(next) { escaped = it }
        assertEquals("MATERIAL_SCOPE_CHANGED", rejected { escaped!!.identity() }.message)
        tokens.saveServerIdentity(id(2), id(30)); assertNull(tokens.iconAuthenticationSnapshot(next.access))
        assertEquals(1, server.requests.size)
    }

    @Test fun lateSuccessAfterReloginServerEpochDeviceOrPermissionChangeLeavesOldClaimUnconfirmed() = runBlocking<Unit> {
        val sent = CountDownLatch(1); val release = CountDownLatch(1)
        val context = metadata.capture(); queue.enqueueAsset(context, id(10))
        val (http, server) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else {
            sent.countDown(); check(release.await(5, TimeUnit.SECONDS)); json(record)
        } }
        // Same-account reauthentication invalidates the old attempt even though namespace is equal.
        supervisorScope {
            val task = async(Dispatchers.IO) { processor.run(context, http) }
            try {
                assertTrue(sent.await(5, TimeUnit.SECONDS)); login(); release.countDown()
                rejected { task.await() }
            } finally { release.countDown(); withContext(NonCancellable) { task.cancelAndJoin() } }
        }
        val current = metadata.capture(); assertTrue(queue.jobs(current).none { it.state == IconTransferState.COMPLETE })
        val claim = queue.jobs(current).single { it.state == IconTransferState.SENDING }
        assertEquals(2, server.requests.size)
        for (change in listOf<suspend () -> Unit>(
            { tokens.saveServerIdentity(id(30), id(3)) },
            { tokens.saveServerIdentity(id(2), id(30)) },
            { tokens.saveDeviceRegistration(id(30), setOf("sync.read", "structure.write"), true, 2) },
            { tokens.saveDeviceRegistration(id(4), setOf("sync.read"), true, 2) }
        )) {
            login(); val captured = metadata.capture(); change()
            assertNull(tokens.iconAuthenticationSnapshot(captured.access))
            rejected { http.session(captured) { error("Must not enter") } }
            assertEquals(2, server.requests.size)
        }
        login(); assertEquals(claim, queue.jobs(metadata.capture()).single { it.operationId == claim.operationId })
    }

    @Test fun concurrentOwnersSerializeActualNetworkAndRecoverOnlyAfterPreviousOwnerJoined() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10)); store.install(context, id(10), hash, bytes)
        val sent = CountDownLatch(1); val release = CountDownLatch(1); val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val (http, server) = http { input, _ ->
            if (!input.path.endsWith("/identity") && first.compareAndSet(true, false)) {
                sent.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
            normal(input)
        }
        supervisorScope {
            val one = async(Dispatchers.IO) { processor.run(context, http) }
            var two: Deferred<IconTransferBatch>? = null
            try {
                assertTrue(sent.await(5, TimeUnit.SECONDS))
                two = async(start = CoroutineStart.UNDISPATCHED) { processor.run(context, http) }
                assertEquals(2, server.requests.size); assertFalse(two.isCompleted)
                assertEquals(1, queue.jobs(context).count { it.state == IconTransferState.SENDING })
                release.countDown(); assertEquals(3, one.await().completed)
                assertEquals(IconTransferBatch(true, 0, 0, false, false), two.await())
            } finally {
                release.countDown(); withContext(NonCancellable) { one.cancelAndJoin(); two?.cancelAndJoin() }
            }
        }
        assertEquals(2, server.requests.count { it.path.endsWith("/identity") })
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.COMPLETE })
    }

    @Test fun actualPngDownloadUsesCanonicalNativeStoreAndProductionDomainEntry() = runBlocking<Unit> {
        val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val png = try {
            bitmap.eraseColor(android.graphics.Color.GREEN)
            java.io.ByteArrayOutputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
        val digest = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        val fresh = asset.copy(assetId = id(11), light = IconBlob(digest, png.size, "image/png", 1, 1), dark = null)
        val context = metadata.capture(); metadata.reserveAsset(context, fresh)
        login(editing = false, revision = 2); queue.enqueueDownload(metadata.capture(), id(11), "light")
        http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else
            MaterialSocketServer.Reply(png, type = "image/png", headers = mapOf("Cache-Control" to "private, no-store", "X-Content-Type-Options" to "nosniff")) }
        val imports = AccountIconImport(metadata, store, queue)
        val icons = com.dayforge.domain.service.AccountIconController({ com.dayforge.domain.service.AccountIconRuntime(
            metadata, store, AccountIconRenderer(metadata, store), imports, AccountIconDocuments(imports, app.contentResolver), {}
        ) }, tokens)
        val production = AccountIconHttp(app, client, tokens, preferences, SelectedNetworkTransport())
        val service = com.dayforge.domain.service.AccountIconTransferService(icons, production)
        assertEquals(IconTransferBatch(true, 1, 0, false, true), service.transfer())
        assertArrayEquals(png, store.read(metadata.capture(), id(11), digest))
        val decoded = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        try { assertEquals(android.graphics.Color.GREEN, decoded.getPixel(0, 0)) } finally { decoded.recycle() }
        assertEquals(IconTransferState.COMPLETE, queue.jobs(metadata.capture()).single().state)
        icons.awaitImages(); icons.close()
    }

    @Test fun malformedRefreshNeverSavesCredentialsOrConfirmsIntentAndHasBoundedActualResponse() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10))
        val original = tokens.authenticationSnapshot()!!
        val valid = """{"access_token":"fresh","refresh_token":"next","user_id":"${id(1)}","username":"member","is_admin":false}"""
        val replies = listOf(json(valid.replace("\"is_admin\":false", "\"is_admin\":\"false\"")),
            json(valid.dropLast(1) + ",\"user_id\":\"${id(1)}\"}"), json(valid.replace(id(1), id(30))),
            json(valid.replace("\"fresh\"", "\"fresh\\ninvalid\"")), json(valid.replace("\"next\"", "\"next\\rinvalid\"")),
            json(valid.dropLast(1) + ",\"unknown\":true}"), MaterialSocketServer.Reply(byteArrayOf(), length = 65_537))
        var reply = replies.first()
        val (http, _) = http { input, _ -> when {
            input.path.endsWith("/identity") -> json(identity())
            input.path == "/api/v1/auth/refresh" -> reply
            else -> json("""{"detail":{"code":"TOKEN_EXPIRED"}}""", 401)
        } }
        for (value in replies) {
            reply = value; assertTrue(processor.run(context, http).stopped)
            val credentials = tokens.authenticationSnapshot()!!
            assertEquals(original.session, credentials.session); assertEquals(original.accessToken, credentials.accessToken)
            assertEquals(original.refreshToken, credentials.refreshToken)
            assertTrue(queue.jobs(context).all { it.state == IconTransferState.PENDING })
        }
    }

    @Test fun serverIdentityMismatchAndMutableOriginNeverSendMaterialPayloadElsewhere() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10)); val before = queue.jobs(context)
        val (wrong, _) = http { _, _ -> json(identity().replace(id(3), id(30))) }
        rejected { processor.run(context, wrong) }; assertEquals(before, queue.jobs(context))
        val elsewhere = MaterialSocketServer { _, _ -> error("Must not send credentials to changed origin") }; servers.add(elsewhere)
        val (http, current) = http { _, _ -> runBlocking { preferences.setServerUrl(elsewhere.origin.toString()) }; json(identity()) }
        assertTrue(processor.run(context, http).stopped)
        assertTrue(elsewhere.requests.isEmpty()); assertEquals(1, current.requests.size)
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.PENDING })
    }

    @Test fun finiteRemoteErrorsBlockExplicitlyBut503PreservesRetryAndAllActualMetadata() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10)); val owned = metadata.library(context)
        val replies = listOf(409 to "ASSET_ID_REUSED", 413 to "ASSET_QUOTA_EXCEEDED", 403 to "CAPABILITY_DENIED", 404 to "ASSET_NOT_FOUND", 422 to "ASSET_INVALID", 503 to "ASSET_UNAVAILABLE")
        val failures = listOf(IconTransferFailure.REMOTE_ID_REUSED, IconTransferFailure.REMOTE_QUOTA,
            IconTransferFailure.REMOTE_CAPABILITY, IconTransferFailure.REMOTE_NOT_FOUND, IconTransferFailure.REMOTE_METADATA, null)
        var response = replies.first()
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else json("""{"detail":{"code":"${response.second}"}}""", response.first) }
        replies.forEachIndexed { index, value ->
            response = value; assertTrue(processor.run(context, http).stopped)
            val job = queue.jobs(context).single { it.kind == IconTransferKind.DECLARE_ASSET }
            assertEquals(failures[index], job.failure)
            assertEquals(if (failures[index] == null) IconTransferState.PENDING else IconTransferState.BLOCKED, job.state)
            assertEquals(owned, metadata.library(context)); assertTrue(queue.jobs(context).none { it.state == IconTransferState.COMPLETE })
            if (job.state == IconTransferState.BLOCKED) queue.retryBlocked(context, job.operationId, job.generation)
        }
    }

    @Test fun actualThirtyTwoJobBoundReturnsLimitedThenResumesWithoutDuplicatingAnyIdentity() = runBlocking<Unit> {
        val context = metadata.capture()
        for (n in 10..27) { metadata.reserveAsset(context, asset.copy(assetId = id(n))); queue.enqueueAsset(context, id(n)) }
        store.install(context, id(10), hash, bytes); val original = queue.jobs(context)
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else {
            val target = input.path.substringAfter("/assets/").substringBefore('/')
            assertTrue(target in (10..27).map(::id))
            if (input.path.contains("/content/")) {
                assertArrayEquals(bytes, input.body)
                json("""{"context":$binding,"asset_id":"$target","variant":"${input.path.substringAfterLast('/')}","blob":$blobJson}""")
            } else json(record.replace(id(10), target))
        } }
        assertEquals(IconTransferBatch(true, 32, 0, false, false, true), processor.run(context, http))
        assertEquals(32, queue.jobs(context).count { it.state == IconTransferState.COMPLETE })
        assertEquals(IconTransferBatch(true, 22, 0, false, false), processor.run(context, http))
        assertEquals(original.map { it.operationId }, queue.jobs(context).map { it.operationId })
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.COMPLETE })
    }

    private fun reopenPendingContent() {
        database.close(); database = AccountIconDatabase.open(app)
        metadata = AccountIconRepository(database, tokens, AccountSessionCoordinator())
        store = AccountIconStore(metadata, AccountIconFiles(directory)); queue = AccountIconTransfers(database, metadata, store)
        processor = AccountIconTransferProcessor(metadata, store, queue)
    }
    private fun contentPending() = json("""{"detail":{"code":"ASSET_CONTENT_PENDING"}}""", 409)

    @Test fun pendingContentKeepsReadonlyIdentityAndColdRetryValidatesRealBytes() = runBlocking<Unit> {
        login(editing = false, revision = 2); val context = metadata.capture()
        val reservations = metadata.reservations(context); val library = metadata.library(context)
        val job = queue.enqueueDownload(context, id(10), "light")
        var ready = false
        val (http, server) = http { input, _ ->
            val reply = normal(input)
            if (!input.path.endsWith("/identity") && !ready) contentPending() else reply
        }
        assertEquals(IconTransferBatch(true, 0, 0, true, false, contentPending = 1), processor.run(context, http))
        val pending = queue.jobs(context).single()
        assertEquals(job.operationId, pending.operationId); assertEquals(2L, pending.generation)
        assertEquals(IconTransferState.PENDING, pending.state); assertNull(pending.failure)
        assertNull(metadata.installation(context, id(10), hash).validationProfile)
        assertEquals(reservations, metadata.reservations(context)); assertEquals(library, metadata.library(context))
        assertEquals(2, server.requests.size)
        reopenPendingContent(); assertEquals(listOf(pending), queue.jobs(context))
        ready = true
        assertEquals(IconTransferBatch(true, 1, 0, false, true), processor.run(context, http))
        val complete = queue.jobs(context).single()
        assertEquals(job.operationId, complete.operationId); assertEquals(4L, complete.generation)
        assertEquals(IconTransferState.COMPLETE, complete.state); assertNull(complete.failure)
        assertArrayEquals(bytes, store.read(context, id(10), hash))
        assertNotNull(metadata.installation(context, id(10), hash).validationProfile)
        assertEquals(reservations, metadata.reservations(context)); assertEquals(library, metadata.library(context))
        assertTrue(server.requests.all { it.method == "GET" }); assertEquals(4, server.requests.size)
    }

    @Test fun firstPendingDownloadCannotStarveTheDeclarationsAndUploadsItNeeds() = runBlocking<Unit> {
        val context = metadata.capture(); store.install(context, id(10), hash, bytes)
        var counter = 100
        queue = AccountIconTransfers(database, metadata, store, operationId = { id(counter++) })
        processor = AccountIconTransferProcessor(metadata, store, queue)
        val waiting = queue.enqueueDownload(context, id(10), "light")
        queue.enqueueAsset(context, id(10)); val before = queue.jobs(context)
        assertEquals(waiting.operationId, before.first().operationId)
        var published = false
        val (http, server) = http { input, _ ->
            val reply = normal(input)
            when {
                input.method == "GET" && input.path.contains("/content/") && !published -> contentPending()
                input.method == "PUT" && input.path.contains("/content/") -> { published = true; reply }
                else -> reply
            }
        }
        assertEquals(IconTransferBatch(true, 3, 0, true, false, contentPending = 1), processor.run(context, http))
        assertEquals("GET", server.requests[1].method); assertTrue(server.requests[1].path.endsWith("/content/light"))
        assertEquals(3, server.requests.count { it.method == "PUT" }); assertEquals(5, server.requests.size)
        assertTrue(published)
        val after = queue.jobs(context)
        assertEquals(before.map { it.operationId }, after.map { it.operationId })
        assertEquals(IconTransferState.PENDING, after.single { it.operationId == waiting.operationId }.state)
        assertTrue(after.filter { it.operationId != waiting.operationId }.all { it.state == IconTransferState.COMPLETE })
        assertEquals(IconTransferBatch(true, 1, 0, false, true), processor.run(context, http))
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.COMPLETE })
        assertEquals(before.map { it.operationId }, queue.jobs(context).map { it.operationId })
        assertArrayEquals(bytes, store.read(context, id(10), hash)); assertEquals(7, server.requests.size)
    }

    @Test fun pendingContentUsesThirtyTwoAttemptBudgetAndColdBatchesDoNotStarveLaterIntents() = runBlocking<Unit> {
        val writing = metadata.capture()
        for (n in 11..43) metadata.reserveAsset(writing, asset.copy(assetId = id(n)))
        login(editing = false, revision = 2); val context = metadata.capture()
        var counter = 1000
        queue = AccountIconTransfers(database, metadata, store, operationId = { id(counter++) })
        processor = AccountIconTransferProcessor(metadata, store, queue)
        for (n in 10..43) queue.enqueueDownload(context, id(n), "light")
        val original = queue.jobs(context); val reservations = metadata.reservations(context)
        var ready = false
        val (http, server) = http { input, _ ->
            val reply = normal(input)
            if (!input.path.endsWith("/identity") && !ready) contentPending() else reply
        }
        val waiting = IconTransferBatch(true, 0, 0, true, false, limited = true, contentPending = 32)
        assertEquals(waiting, processor.run(context, http))
        assertEquals(33, server.requests.size)
        val first = server.requests.filter { it.path.contains("/content/") }.map { it.path }
        assertEquals(32, first.distinct().size)
        assertEquals(32, queue.jobs(context).count { it.generation == 2L })
        assertEquals(2, queue.jobs(context).count { it.generation == 0L })
        reopenPendingContent()
        assertEquals(waiting, processor.run(context, http))
        val second = server.requests.drop(33).filter { it.path.contains("/content/") }.map { it.path }
        assertEquals(32, second.distinct().size)
        assertEquals(listOf("/api/v2/appearance/assets/${id(42)}/content/light",
            "/api/v2/appearance/assets/${id(43)}/content/light"), second.take(2))
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.PENDING && it.generation >= 2 && it.failure == null })
        assertEquals(30, queue.jobs(context).count { it.generation == 4L })
        ready = true
        assertEquals(IconTransferBatch(true, 32, 0, false, true, limited = true), processor.run(context, http))
        assertEquals(IconTransferBatch(true, 2, 0, false, true), processor.run(context, http))
        assertEquals(original.map { it.operationId }, queue.jobs(context).map { it.operationId })
        assertTrue(queue.jobs(context).all { it.kind == IconTransferKind.DOWNLOAD && it.state == IconTransferState.COMPLETE })
        assertEquals(reservations, metadata.reservations(context)); assertArrayEquals(bytes, store.read(context, id(43), hash))
    }

    @Test fun onlyExactPendingDownloadStatusIsDeferredAndOther409FailuresStayBlocked() = runBlocking<Unit> {
        login(editing = false, revision = 2); val context = metadata.capture()
        queue.enqueueDownload(context, id(10), "light")
        var response = 409 to "ASSET_ID_REUSED"
        val (http, _) = http { input, _ ->
            if (input.path.endsWith("/identity")) normal(input)
            else json("""{"detail":{"code":"${response.second}"}}""", response.first)
        }
        for ((value, expected) in listOf((409 to "ASSET_ID_REUSED") to IconTransferFailure.REMOTE_ID_REUSED,
            (422 to "ASSET_CONTENT_PENDING") to IconTransferFailure.REMOTE_METADATA,
            (409 to "ASSET_CONTENT_PENDING_EXTRA") to IconTransferFailure.REMOTE_METADATA)) {
            response = value
            assertEquals(IconTransferBatch(true, 0, 0, true, false), processor.run(context, http))
            val blocked = queue.jobs(context).single()
            assertEquals(IconTransferState.BLOCKED, blocked.state); assertEquals(expected, blocked.failure)
            assertNull(metadata.installation(context, id(10), hash).validationProfile)
            queue.retryBlocked(context, blocked.operationId, blocked.generation)
        }
        login(editing = true, revision = 3); val editing = metadata.capture()
        queue.enqueueAsset(editing, id(10)); response = 409 to "ASSET_CONTENT_PENDING"
        assertEquals(IconTransferBatch(true, 0, 0, true, false), processor.run(editing, http))
        val declaration = queue.jobs(editing).single { it.kind == IconTransferKind.DECLARE_ASSET }
        assertEquals(IconTransferState.BLOCKED, declaration.state)
        assertEquals(IconTransferFailure.REMOTE_METADATA, declaration.failure)
        assertNull(metadata.installation(editing, id(10), hash).validationProfile)
    }

    @Test fun pendingContentThenNetworkFailureRetainsBothIdentitiesAndWaitingSummary() = runBlocking<Unit> {
        login(editing = false, revision = 2); val context = metadata.capture(); var counter = 100
        queue = AccountIconTransfers(database, metadata, store, operationId = { id(counter++) })
        processor = AccountIconTransferProcessor(metadata, store, queue)
        queue.enqueueDownload(context, id(10), "light"); queue.enqueueDownload(context, id(10), "dark")
        val original = queue.jobs(context)
        val (http, server) = http { input, _ ->
            when {
                input.path.endsWith("/identity") -> normal(input)
                input.path.endsWith("/content/light") -> { normal(input); contentPending() }
                else -> { normal(input); null } // Actual peer closes before a response.
            }
        }
        assertEquals(IconTransferBatch(true, 0, 0, true, false, contentPending = 1), processor.run(context, http))
        assertEquals(original.map { it.operationId }, queue.jobs(context).map { it.operationId })
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.PENDING && it.generation == 2L && it.failure == null })
        assertNull(metadata.installation(context, id(10), hash).validationProfile); assertEquals(3, server.requests.size)
    }

    @Test fun accountSwitchAfterContentWaitCannotReleaseAnotherOldClaimAndFreshOwnerRecovers() = runBlocking<Unit> {
        login(editing = false, revision = 2); val context = metadata.capture(); var counter = 100
        queue = AccountIconTransfers(database, metadata, store, operationId = { id(counter++) })
        processor = AccountIconTransferProcessor(metadata, store, queue)
        val light = queue.enqueueDownload(context, id(10), "light")
        val dark = queue.enqueueDownload(context, id(10), "dark"); val original = queue.jobs(context)
        var switching = true
        val (http, server) = http { input, _ ->
            val reply = normal(input)
            when {
                !switching || input.path.endsWith("/identity") -> reply
                input.path.endsWith("/content/light") -> contentPending()
                else -> { runBlocking { login(owner = id(50), editing = false, revision = 2) }; contentPending() }
            }
        }
        assertEquals("ICON_SESSION_CHANGED", rejected { processor.run(context, http) }.message)
        assertTrue(queue.jobs(metadata.capture()).isEmpty()); assertEquals(3, server.requests.size)
        login(editing = false, revision = 2); val fresh = metadata.capture()
        val rows = queue.jobs(fresh)
        assertEquals(IconTransferState.PENDING, rows.single { it.operationId == light.operationId }.state)
        assertEquals(IconTransferState.SENDING, rows.single { it.operationId == dark.operationId }.state)
        assertEquals(original.map { it.operationId }, rows.map { it.operationId })
        switching = false
        assertEquals(IconTransferBatch(true, 2, 1, false, true), processor.run(fresh, http))
        assertTrue(queue.jobs(fresh).all { it.state == IconTransferState.COMPLETE })
        assertEquals(original.map { it.operationId }, queue.jobs(fresh).map { it.operationId })
        assertArrayEquals(bytes, store.read(fresh, id(10), hash))
    }

    @Test fun strictPublicIdentityRejectsQuotedVersionDuplicateKeysMalformedTimeAndUnknownFieldsBeforeClaim() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10)); val before = queue.jobs(context)
        val values = listOf(identity().replace("\"protocol_version\":5", "\"protocol_version\":\"5\""),
            identity().replace("2026-10-05T00:00:00Z", "invalid"), identity().dropLast(1) + ",\"unknown\":true}",
            identity().replace("\"protocol_version\":5", "\"protocol_version\":5,\"protocol_version\":5"))
        var value = values.first()
        val (http, server) = http { _, _ -> json(value) }
        for (reply in values) {
            value = reply
            assertTrue(rejected { processor.run(context, http) } is MaterialReplyInvalid)
            assertEquals(before, queue.jobs(context))
        }
        assertTrue(server.requests.all { it.path.endsWith("/identity") && it.headers["authorization"] == null })
        preferences.setServerUrl("not-a-server")
        val production = AccountIconHttp(app, client, tokens, preferences, SelectedNetworkTransport())
        assertEquals("MATERIAL_ROUTE_INVALID", rejected { processor.run(context, production) }.message)
        assertEquals(before, queue.jobs(context)); assertEquals(values.size, server.requests.size)
        preferences.setServerUrl(server.origin.toString())
        value = identity()
        val snapshot = tokens.authenticationSnapshot()!!
        assertTrue(tokens.saveRefreshedTokens(snapshot, "synthetic\ninvalid", "synthetic-refresh", "member", id(1), false))
        http.session(context) { session ->
            val attempt = requireNotNull(queue.prepareNext(context))
            assertEquals("MATERIAL_CREDENTIAL_INVALID", rejected { session.declareAsset(attempt) }.message)
            queue.release(attempt)
        }
        // Public admission remains credential-free; malformed local access never reaches the asset path.
        assertEquals(values.size + 1, server.requests.size)
        assertTrue(queue.jobs(context).all { it.state == IconTransferState.PENDING })
    }
}
