package com.dayforge.data.api

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.authenticator.TokenAuthenticator
import com.dayforge.data.api.dto.*
import com.dayforge.data.api.interceptor.AuthInterceptor
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Independent raw wire fixtures, actual sockets/authentication and file DataStore on the phone. */
@RunWith(AndroidJUnit4::class)
class NextSyncHttpTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var client: OkHttpClient
    private lateinit var data: DataStore<Preferences>
    private val servers = mutableListOf<MaterialSocketServer>()
    private fun id(n: Int) = "a9600000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val time = "2026-10-06T00:00:00Z"
    private val capabilities = setOf("sync.read", "structure.write", "facts.append", "timer.control")
    private val fixture by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("next/api.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }
    private fun json(text: String, status: Int = 200) = MaterialSocketServer.Reply(text.toByteArray(), status)
    private fun identity(version: Int = 5) = """{"server_instance_id":"${id(2)}","sync_epoch":"${id(3)}","protocol_version":$version,"capabilities":["sync.read","structure.write"],"server_time":"$time"}"""
    private fun registration() = """{"device_id":"${id(4)}","installation_id":"installation","platform":"android","device_class":"interactive","app_version":null,"display_name":null,"is_primary_editor":true,"structural_edit_enabled":true,"capability_revision":1,"capabilities":["sync.read","structure.write","facts.append","timer.control"],"last_seen_at":"$time"}"""
    private fun timer(target: Int = 60, countdown: Boolean = true) = """{"session_id":"${id(10)}","activity_uuid":"${id(11)}","state":"running","controller_device_id":"${id(4)}","control_generation":1,"revision":1,"next_command_sequence":2,"started_at":"$time","state_changed_at":"$time","ended_at":null,"timezone":"Asia/Shanghai","is_countdown":$countdown,"target_seconds":$target,"max_duration_seconds":86400,"active_elapsed_ms":0,"last_heartbeat_at":null,"completed_event_id":null}"""
    private fun status() = """{"session":${timer()},"server_time":"$time"}"""
    private fun commandsReply() = """{"results":[{"command_id":"${id(12)}","session_id":"${id(10)}","status":"applied","error_code":null,"message":null,"session":${timer()}}],"server_time":"$time"}"""
    private fun commands() = TimerCommandBatchRequest(id(4), listOf(TimerCommandRequest(id(12), id(10), 1, "start", time, 0,
        activityUuid = id(11), timezone = "Asia/Shanghai")))
    private fun push() = NextSyncPushRequest(id(4), listOf(SyncV2Operation(id(20), "metric", id(21), "upsert",
        payload = buildJsonObject { put("deliberately_bad_domain", true) })))
    private fun pushReply() = """{"results":[{"operation_id":"${id(20)}","entity_type":"metric","entity_uuid":"${id(21)}","status":"rejected","error_code":"INVALID_PAYLOAD","message":null,"entity":null}]}"""
    private suspend fun login(owner: String = id(1), register: Boolean = true) {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        if (register) {
            tokens.saveServerIdentity(id(2), id(3))
            tokens.saveDeviceRegistration(id(4), capabilities, true, 1)
        }
    }
    private suspend fun capture(): LocalSyncAccess = requireNotNull(tokens.localSyncAccess())
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        directory = Files.createTempDirectory(app.filesDir.toPath(), "core-http-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") })
        tokens = TokenManager(data); preferences = PreferencesManager(data)
        client = OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(preferences)).addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, preferences, SelectedNetworkTransport())).build()
        login()
    }
    @After fun cleanup() = runBlocking<Unit> {
        var failure: Throwable? = null
        suspend fun finish(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        servers.forEach { finish { it.close() } }
        finish { if (::client.isInitialized) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }
        finish { if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin() }
        finish { if (::directory.isInitialized) assertTrue(directory.deleteRecursively()) }
        failure?.let { throw it }
    }
    private suspend fun http(respond: (MaterialSocketServer.Input, Socket) -> MaterialSocketServer.Reply?): Pair<NextSyncHttp, MaterialSocketServer> {
        val server = MaterialSocketServer(respond); servers.add(server)
        preferences.setServerUrl(server.origin.toString())
        return NextSyncHttp(client, tokens, server.origin) to server
    }
    private fun headers(input: MaterialSocketServer.Input, access: String = "synthetic-access") {
        assertEquals("identity", input.headers["accept-encoding"])
        if (input.path.endsWith("/identity")) {
            assertNull(input.headers["authorization"]); assertNull(input.headers["x-dayforge-protocol"])
            assertNull(input.headers["x-dayforge-server-instance"]); assertNull(input.headers["x-dayforge-sync-epoch"])
        } else {
            assertEquals("Bearer $access", input.headers["authorization"])
            assertEquals("5", input.headers["x-dayforge-protocol"])
            assertEquals(id(2), input.headers["x-dayforge-server-instance"]); assertEquals(id(3), input.headers["x-dayforge-sync-epoch"])
        }
    }
    private fun normal(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        headers(input)
        return when (input.path) {
            "/api/v2/system/identity" -> json(identity())
            "/api/v2/devices/register" -> json(registration())
            "/api/v2/sync/push" -> json(pushReply())
            "/api/v2/sync/bootstrap" -> json(fixture.getValue("bootstrap").toString())
            "/api/v2/sync/changes" -> json("""{"changes":[],"next_cursor":7,"has_more":false,"server_time":"$time"}""")
            "/api/v2/timers/commands" -> json(commandsReply())
            "/api/v2/timers/active", "/api/v2/timers/${id(10)}" -> json(status())
            "/api/v2/timers/${id(10)}/heartbeat" -> json("""{"accepted":true,"session":${timer()},"server_time":"$time"}""")
            else -> error("Unexpected path ${input.path}")
        }
    }
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { if (error is CancellationException) throw error; return error }
        throw AssertionError("Must reject")
    }
    private fun parsed(input: MaterialSocketServer.Input) = Json.parseToJsonElement(input.body.toString(Charsets.UTF_8)).jsonObject

    @Test fun actualEightPathsCaptureHeadersKeepOriginalIdsAndDoNotPublishReplicaOrCursor() = runBlocking<Unit> {
        val context = capture(); val (http, server) = http { input, _ -> normal(input) }
        val registration = requireNotNull(tokens.registrationSyncAccess(id(2), id(3)))
        assertEquals(id(4), http.session(registration) { it.register(DeviceRegisterRequest("installation", 5, "android", "interactive")) }!!.deviceId)
        assertEquals(context, capture())
        assertEquals(7L, http.session(context) { session ->
            assertEquals("INVALID_PAYLOAD", session.push(push()).results.single().errorCode)
            val bootstrap = session.bootstrap(); assertEquals(1, bootstrap.oneTimeCheckpoints.single().state.version)
            assertEquals("applied", session.commands(commands()).results.single().status)
            assertEquals(id(10), session.active().session!!.sessionId)
            assertEquals(id(10), session.status(id(10)).session!!.sessionId)
            assertTrue(session.heartbeat(id(10), 1).accepted)
            session.pull(7, 1000).nextCursor
        })
        assertEquals(10, server.requests.size)
        assertEquals(0L, tokens.syncCursor.first()); assertEquals(context, capture())
        val sentPush = parsed(server.requests.single { it.path.endsWith("/push") })
        assertEquals(id(4), sentPush.getValue("device_id").jsonPrimitive.content)
        assertEquals(id(20), sentPush.getValue("operations").jsonArray.single().jsonObject.getValue("operation_id").jsonPrimitive.content)
        assertTrue(sentPush.getValue("operations").jsonArray.single().jsonObject.getValue("payload").jsonObject.getValue("deliberately_bad_domain").jsonPrimitive.boolean)
        assertEquals("POST", server.requests.single { it.path.endsWith("/register") }.method)
        assertEquals(5, parsed(server.requests.single { it.path.endsWith("/register") }).getValue("protocol_version").jsonPrimitive.int)
        val pull = server.requests.single { it.path.endsWith("/changes") }
        assertEquals("GET", pull.method); assertEquals("/api/v2/sync/changes?device_id=${id(4)}&cursor=7&limit=1000", pull.target)
        assertEquals(id(12), parsed(server.requests.single { it.path.endsWith("/commands") }).getValue("commands").jsonArray.single().jsonObject.getValue("command_id").jsonPrimitive.content)
        assertEquals("/api/v2/timers/active?device_id=${id(4)}", server.requests.single { it.path.endsWith("/active") }.target)
        assertEquals(1, parsed(server.requests.single { it.path.endsWith("/heartbeat") }).getValue("control_generation").jsonPrimitive.int)
    }

    @Test fun firstRegistrationDoesNotInventDeviceOrPersistDiscoveredReplica() = runBlocking<Unit> {
        tokens.clearTokens(); login(register = false)
        assertNull(tokens.localSyncAccess()); assertNull(tokens.serverInstanceId.first())
        val registration = requireNotNull(tokens.registrationSyncAccess(id(2), id(3)))
        val (http, server) = http { input, _ -> normal(input) }
        assertEquals(id(4), http.session(registration) { it.register(DeviceRegisterRequest("installation", 5, "android", "interactive")) }!!.deviceId)
        assertNull(tokens.syncDeviceId.first()); assertNull(tokens.serverInstanceId.first()); assertNull(tokens.syncEpoch.first())
        assertEquals(2, server.requests.size)
        tokens.saveServerIdentity(id(2), id(30))
        assertNull(tokens.registrationSyncAccess(id(2), id(3)))
        rejected { http.session(registration) { error("Must not enter") } }; assertEquals(2, server.requests.size)
    }

    @Test fun oldAndFutureServersNeverEnterPrivateWorkOrAttachCredentials() = runBlocking<Unit> {
        val context = capture()
        for (version in listOf(4, 6)) {
            val (http, server) = http { input, _ -> headers(input); json(identity(version)) }
            assertNull(http.session(context) { error("Must not enter") })
            assertEquals(1, server.requests.size); assertEquals(context, capture()); assertEquals(0L, tokens.syncCursor.first())
        }
    }

    @Test fun identityMismatchIsExplicitAndNeverRetagsCapturedEpoch() = runBlocking<Unit> {
        val context = capture()
        for ((field, code) in listOf(id(2) to "SERVER_IDENTITY_MISMATCH", id(3) to "SYNC_EPOCH_MISMATCH")) {
            val (http, server) = http { _, _ -> json(identity().replace(field, id(30))) }
            val failure = rejected { http.session(context) { error("Must not enter") } } as NextSyncHttpFailure
            assertEquals(409, failure.status); assertEquals(code, failure.code); assertEquals(1, server.requests.size)
            assertEquals(context, capture())
        }
    }

    @Test fun coercedVersionNeverAllowsPrivateWorkOrChangesPersistedAuthority() = runBlocking<Unit> {
        val context = capture()
        for (value in listOf("5e0", "5.0", "\"5\"", "+5", "05", "2147483648")) {
            val (http, server) = http { _, _ -> json(identity().replace("\"protocol_version\":5", "\"protocol_version\":$value")) }
            assertTrue(rejected { http.session(context) { error("Must not enter") } } is NextSyncReplyInvalid)
            assertEquals(1, server.requests.size); assertNull(server.requests.single().headers["authorization"])
            assertEquals(context, capture())
        }
    }

    @Test fun partialRegistrationAndCoercedScalarsCannotCreateAuthorityProof() = runBlocking<Unit> {
        val context = requireNotNull(tokens.registrationSyncAccess(id(2), id(3)))
        var reply = registration()
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else json(reply) }
        val original = Json.parseToJsonElement(registration()).jsonObject
        val broken = listOf(original - "capability_revision", original - "device_class", original - "last_seen_at",
            original + ("capability_revision" to JsonPrimitive("1")), original + ("is_primary_editor" to JsonPrimitive("true")),
            original + ("installation_id" to JsonPrimitive(5)), original + ("last_seen_at" to JsonNull),
            original + ("device_id" to JsonPrimitive(id(30))))
        // The returned ID is assigned by the server; a different canonical new ID is legitimate.
        for (raw in broken.dropLast(1)) {
            reply = JsonObject(raw).toString()
            assertTrue(rejected { http.session(context) { it.register(DeviceRegisterRequest("installation", 5, "android", "interactive")) } } is NextSyncReplyInvalid)
        }
        reply = JsonObject(broken.last()).toString()
        assertEquals(id(30), http.session(context) { it.register(DeviceRegisterRequest("installation", 5, "android", "interactive")) }!!.deviceId)
        assertEquals(id(4), tokens.syncDeviceId.first())
    }

    @Test fun typedCursorBooleanIntegersStringsUnknownAndDuplicateJsonFailClosed() = runBlocking<Unit> {
        val context = capture()
        val good = """{"changes":[],"next_cursor":7,"has_more":false,"server_time":"$time"}"""
        val broken = listOf(good.replace("\"next_cursor\":7", "\"next_cursor\":\"7\""),
            good.replace("\"next_cursor\":7", "\"next_cursor\":7.0"), good.replace("\"next_cursor\":7", "\"next_cursor\":9223372036854775808"),
            good.replace("\"next_cursor\":7", "\"next_cursor\":7e0"), good.replace("\"next_cursor\":7", "\"next_cursor\":70e-1"),
            good.replace("\"next_cursor\":7", "\"next_cursor\":7E+0"), good.replace("\"next_cursor\":7", "\"next_cursor\":07"),
            good.replace("\"next_cursor\":7", "\"next_cursor\":+7"),
            good.replace("\"has_more\":false", "\"has_more\":\"false\""), good.replace("\"$time\"", "5"),
            good.dropLast(1) + ",\"extra\":true}", good.dropLast(1) + ",\"next_cursor\":7}", good + " {}",
            good.replace("\"server_time\"", "\"server_\\u0074ime\"").dropLast(1) + ",\"server_time\":\"$time\"}",
            good.replace(time, "\\ud800"), good.replace("[]", "[[[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]]]"))
        var reply = good
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else json(reply) }
        for (raw in broken) {
            reply = raw; assertTrue(rejected { http.session(context) { it.pull(7) } } is NextSyncReplyInvalid)
            assertEquals(0L, tokens.syncCursor.first())
        }
    }

    @Test fun malformedUtf8AndBodyFramingRedirectCompressionTypeAndCharsetNeverReturnData() = runBlocking<Unit> {
        val context = capture(); val good = status().toByteArray()
        val replies = listOf(MaterialSocketServer.Reply(byteArrayOf(0xc3.toByte(), 0x28)),
            MaterialSocketServer.Reply(good, type = "text/html"), MaterialSocketServer.Reply(good, type = "application/json; charset=iso-8859-1"),
            MaterialSocketServer.Reply(good, type = "application/json; charset=unknown-charset"),
            MaterialSocketServer.Reply(good, type = "application/json; charset=utf-8; charset=iso-8859-1"),
            MaterialSocketServer.Reply(good, headers = mapOf("Content-Type" to "application/json")),
            MaterialSocketServer.Reply(good, headers = mapOf("Content-Encoding" to "gzip")),
            MaterialSocketServer.Reply(good, status = 302, headers = mapOf("Location" to "/must-not-follow")),
            MaterialSocketServer.Reply(good, length = good.size + 1))
        var reply = replies.first()
        val (http, server) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else reply }
        for (raw in replies) { reply = raw; assertTrue(rejected { http.session(context) { it.active() } } is IOException) }
        assertEquals(replies.size * 2, server.requests.size)
        assertTrue(server.requests.none { it.path == "/must-not-follow" }); assertEquals(context, capture())
    }

    @Test fun oversizedKnownAndChunkedRepliesFailWithoutTruncatingOrAdvancingCursor() = runBlocking<Unit> {
        val context = capture()
        val replies = listOf(MaterialSocketServer.Reply(byteArrayOf(), length = 8 * 1024 * 1024 + 1),
            MaterialSocketServer.Reply(ByteArray(8 * 1024 * 1024 + 1) { 32 }, chunked = true, allowClientClose = true))
        var reply = replies.first()
        val (http, server) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else reply }
        for (raw in replies) { reply = raw; assertTrue(rejected { http.session(context) { it.pull(7) } } is NextSyncReplyInvalid) }
        assertEquals(4, server.requests.size); assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun oversizedAsciiAndUtf8RequestsStopDuringEncodingBeforePrivateTraffic() = runBlocking<Unit> {
        val context = capture()
        val (http, server) = http { input, _ -> assertTrue(input.path.endsWith("/identity")); json(identity()) }
        for (text in listOf("a".repeat(1_048_576), "😀".repeat(300_000))) {
            val request = push().copy(operations = listOf(push().operations.single().copy(
                payload = buildJsonObject { put("oversized", text) })))
            assertTrue(rejected { http.session(context) { it.push(request) } } is IllegalArgumentException)
            assertEquals(context, capture()); assertEquals(0L, tokens.syncCursor.first())
        }
        assertEquals(2, server.requests.size); assertTrue(server.requests.all { it.headers["authorization"] == null })
    }

    @Test fun separateTaskConflictAndFullBootstrapCheckpointsSurviveActualWire() = runBlocking<Unit> {
        val context = capture()
        val push = Json.decodeFromJsonElement<NextSyncPushRequest>(fixture.getValue("push")).copy(deviceId = id(4))
        var result = fixture.getValue("conflict").toString()
        val (http, _) = http { input, _ -> when {
            input.path.endsWith("/identity") -> json(identity())
            input.path.endsWith("/bootstrap") -> json(fixture.getValue("bootstrap").toString())
            else -> json("""{"results":[$result]}""")
        } }
        val conflict = http.session(context) { it.push(push) }!!.results.single()
        assertNotNull(conflict.oneTimeConflict); assertNull(conflict.entity); assertNull(conflict.revision)
        assertEquals(1, http.session(context) { it.bootstrap() }!!.oneTimeCheckpoints.single().state.version)
        result = fixture.getValue("accepted").toString()
        assertNotNull(http.session(context) { it.push(push) }!!.results.single().entity)
        result = JsonObject(fixture.getValue("accepted").jsonObject + ("operation_id" to JsonPrimitive(id(30)))).toString()
        assertTrue(rejected { http.session(context) { it.push(push) } } is NextSyncReplyInvalid)
    }

    @Test fun missingHistoryOrCheckpointAndPartialOldStructureCannotPretendRestored() = runBlocking<Unit> {
        val context = capture(); val original = fixture.getValue("bootstrap").jsonObject
        val changes = original.getValue("changes").jsonArray
        val node = changes.first().jsonObject
        val payload = node.getValue("payload").jsonObject
        val partialNode = JsonObject(node + ("payload" to JsonObject(payload - "appearance")))
        val broken = listOf(JsonObject(original - "one_time_checkpoints"),
            JsonObject(original + ("changes" to JsonArray(changes.take(1)))),
            JsonObject(original + ("one_time_checkpoints" to JsonArray(emptyList()))),
            JsonObject(original + ("changes" to JsonArray(listOf(partialNode) + changes.drop(1)))))
        var reply = original.toString()
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else json(reply) }
        for (raw in broken) { reply = raw.toString(); assertTrue(rejected { http.session(context) { it.bootstrap() } } is NextSyncReplyInvalid) }
        reply = """{"changes":[${JsonObject(partialNode + ("sequence" to JsonPrimitive(8)))}],"next_cursor":8,"has_more":false,"server_time":"$time"}"""
        assertTrue(rejected { http.session(context) { it.pull(7) } } is NextSyncReplyInvalid)
        assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun pushMustEchoEveryOriginalTupleExactlyAndCannotDiscardRejectedOperation() = runBlocking<Unit> {
        val context = capture(); val original = Json.parseToJsonElement(pushReply()).jsonObject
        val result = original.getValue("results").jsonArray.single().jsonObject
        val broken = listOf(JsonArray(emptyList()), JsonArray(listOf(result, result)),
            JsonArray(listOf(JsonObject(result + ("entity_uuid" to JsonPrimitive(id(30)))))),
            JsonArray(listOf(JsonObject(result + ("entity_type" to JsonPrimitive("plan_node"))))))
        var reply = original.toString()
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else json(reply) }
        for (raw in broken) {
            reply = JsonObject(mapOf("results" to raw)).toString()
            assertTrue(rejected { http.session(context) { it.push(push()) } } is NextSyncReplyInvalid)
        }
        reply = pushReply(); assertEquals("rejected", http.session(context) { it.push(push()) }!!.results.single().status)
    }

    @Test fun sparseGlobalSequenceIsLegalButRepeatedReverseFutureEmptyMoreOrRetreatFail() = runBlocking<Unit> {
        val context = capture()
        fun change(sequence: Long) = """{"sequence":$sequence,"entity_type":"metric","entity_uuid":"${id(21)}","operation":"delete","revision":2,"payload":{},"changed_at":"$time","origin_device_id":null}"""
        fun page(changes: String, cursor: Long, more: Boolean = false) = """{"changes":[$changes],"next_cursor":$cursor,"has_more":$more,"server_time":"$time"}"""
        var reply = page(change(10) + "," + change(17), 17)
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else json(reply) }
        assertEquals(listOf(10L, 17L), http.session(context) { it.pull(7) }!!.changes.map { it.sequence })
        for (raw in listOf(page(change(10) + "," + change(10), 10), page(change(17) + "," + change(10), 17),
            page(change(17), 10), page("", 7, true), page("", 6), page(change(10), 17, true))) {
            reply = raw; assertTrue(rejected { http.session(context) { it.pull(7) } } is NextSyncReplyInvalid)
        }
        assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun timerZeroTargetBothModesAndFullMillisecondsArePreserved() = runBlocking<Unit> {
        val context = capture(); var reply = status()
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else json(reply) }
        for (countdown in listOf(true, false)) {
            reply = """{"session":${timer(0, countdown).replace("\"active_elapsed_ms\":0", "\"active_elapsed_ms\":86400000")},"server_time":"$time"}"""
            val received = http.session(context) { it.status(id(10)) }!!.session!!
            assertEquals(0, received.targetSeconds); assertEquals(countdown, received.isCountdown); assertEquals(86_400_000L, received.activeElapsedMs)
        }
    }

    @Test fun explicitKnownUtf8AliasesAndQuotedCharsetRemainValidOnActualWire() = runBlocking<Unit> {
        val context = capture(); var type = "application/json"
        val (http, _) = http { input, _ -> MaterialSocketServer.Reply(
            (if (input.path.endsWith("/identity")) identity() else status()).toByteArray(), type = type) }
        for (value in listOf("application/json", "application/json; charset=utf-8", "Application/JSON; CHARSET=\"UTF-8\"", "application/json; charset=utf8")) {
            type = value; assertEquals(id(10), http.session(context) { it.active() }!!.session!!.sessionId)
        }
    }

    @Test fun timerWrongEchoAndCoercionOverflowOrUnknownStateCannotAcknowledgeCommand() = runBlocking<Unit> {
        val context = capture(); var reply = commandsReply()
        val broken = listOf(reply.replace(id(12), id(30)), reply.replace(id(10), id(30)),
            reply.replace("\"control_generation\":1", "\"control_generation\":\"1\""),
            reply.replace("\"revision\":1", "\"revision\":2147483648"), reply.replace("\"is_countdown\":true", "\"is_countdown\":\"true\""),
            reply.replace("\"revision\":1", "\"revision\":1e0"), reply.replace("\"active_elapsed_ms\":0", "\"active_elapsed_ms\":0e0"),
            reply.replace("\"active_elapsed_ms\":0", "\"active_elapsed_ms\":86400001"), reply.replace("\"running\"", "\"unknown\""),
            reply.replace("Asia/Shanghai", "unknown/zone"))
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else json(reply) }
        for (raw in broken) { reply = raw; assertTrue(rejected { http.session(context) { it.commands(commands()) } } is NextSyncReplyInvalid) }
        reply = status().replace(id(10), id(30))
        assertTrue(rejected { http.session(context) { it.status(id(10)) } } is NextSyncReplyInvalid)
        reply = """{"accepted":true,"session":${timer().replace(id(4), id(30))},"server_time":"$time"}"""
        assertTrue(rejected { http.session(context) { it.heartbeat(id(10), 1) } } is NextSyncReplyInvalid)
        reply = reply.replace("\"accepted\":true", "\"accepted\":false")
        assertFalse(http.session(context) { it.heartbeat(id(10), 1) }!!.accepted)
    }

    @Test fun responseLossKeepsExactOperationAndCommandBodiesWithoutImplicitRetry() = runBlocking<Unit> {
        val context = capture(); var drop = true
        val (http, server) = http { input, _ -> if (drop && !input.path.endsWith("/identity")) null else normal(input) }
        assertTrue(rejected { http.session(context) { it.push(push()) } } is IOException)
        assertEquals(2, server.requests.size); drop = false
        assertEquals("rejected", http.session(context) { it.push(push()) }!!.results.single().status)
        val sent = server.requests.filter { it.path.endsWith("/push") }; assertEquals(2, sent.size)
        assertArrayEquals(sent[0].body, sent[1].body); assertEquals(sent[0].headers, sent[1].headers)
        drop = true; assertTrue(rejected { http.session(context) { it.commands(commands()) } } is IOException)
        assertEquals(6, server.requests.size); drop = false
        assertEquals("applied", http.session(context) { it.commands(commands()) }!!.results.single().status)
        val commandBodies = server.requests.filter { it.path.endsWith("/commands") }
        assertArrayEquals(commandBodies[0].body, commandBodies[1].body); assertEquals(context, capture())
    }

    @Test fun replicaRotationAfterDiscoveryRejectsOriginalHeadersWithoutSavingNewContext() = runBlocking<Unit> {
        val context = capture()
        val (http, server) = http { input, _ ->
            headers(input)
            if (input.path.endsWith("/identity")) json(identity()) else json("""{"detail":{"code":"SYNC_EPOCH_MISMATCH"}}""", 409)
        }
        val failure = rejected { http.session(context) { it.push(push()) } } as NextSyncHttpFailure
        assertEquals(409, failure.status); assertEquals("SYNC_EPOCH_MISMATCH", failure.code)
        assertEquals(2, server.requests.size); assertEquals(context, capture()); assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun finiteErrorsRetainSafeCodeAndRetryAfterButNeverExposeServerPayload() = runBlocking<Unit> {
        val context = capture(); var code = "DATABASE_BUSY"; var retry = "2"
        val (http, _) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else
            MaterialSocketServer.Reply("""{"detail":{"code":"$code","message":"private diagnostic"}}""".toByteArray(), 503, headers = mapOf("Retry-After" to retry)) }
        val failure = rejected { http.session(context) { it.push(push()) } } as NextSyncHttpFailure
        assertEquals("DATABASE_BUSY", failure.code); assertEquals(2, failure.retryAfterSeconds); assertEquals("SYNC_HTTP_503", failure.message)
        code = "unsafe private diagnostic"; retry = "999999"
        val invalid = rejected { http.session(context) { it.push(push()) } } as NextSyncHttpFailure
        assertNull(invalid.code); assertNull(invalid.retryAfterSeconds); assertEquals("SYNC_HTTP_503", invalid.message)
    }

    @Test fun changedAccountLoginReplicaDeviceOrCapabilitiesInvalidateLateReplyAndNextRequest() = runBlocking<Unit> {
        val changes: List<suspend () -> Unit> = listOf({ login(id(30)) }, { login() },
            { tokens.saveServerIdentity(id(30), id(3)) }, { tokens.saveServerIdentity(id(2), id(30)) },
            { tokens.saveDeviceRegistration(id(30), capabilities, true, 1) },
            { tokens.saveDeviceRegistration(id(4), capabilities - "facts.append", true, 1) },
            { tokens.saveDeviceRegistration(id(4), capabilities, true, 2) })
        for (change in changes) {
            login(); val context = capture(); val sent = CountDownLatch(1); val release = CountDownLatch(1)
            val (http, server) = http { input, _ ->
                if (!input.path.endsWith("/identity")) { sent.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                normal(input)
            }
            supervisorScope {
                val task = async(Dispatchers.IO) { rejected { http.session(context) { it.push(push()) } } }
                try {
                    assertTrue(sent.await(5, TimeUnit.SECONDS)); change(); release.countDown()
                    assertTrue(task.await() is IOException)
                } finally { release.countDown(); withContext(NonCancellable) { task.cancelAndJoin() } }
            }
            rejected { http.session(context) { error("Must not enter") } }; assertEquals(2, server.requests.size)
            assertEquals(0L, tokens.syncCursor.first())
        }
    }

    @Test fun mutableOriginAndEscapedSessionNeverSendPrivateDataToReplacementServer() = runBlocking<Unit> {
        val context = capture()
        val elsewhere = MaterialSocketServer { _, _ -> error("Must not send credentials") }; servers.add(elsewhere)
        val (http, server) = http { input, _ ->
            assertTrue(input.path.endsWith("/identity")); headers(input)
            runBlocking { preferences.setServerUrl(elsewhere.origin.toString()) }; json(identity())
        }
        assertTrue(rejected { http.session(context) { it.push(push()) } } is IOException)
        assertEquals(1, server.requests.size); assertTrue(elsewhere.requests.isEmpty())
        val (working, actual) = http { input, _ -> normal(input) }; var escaped: NextSyncHttp.Session? = null
        working.session(context) { escaped = it }
        assertTrue(rejected { escaped!!.active() } is IllegalStateException); assertEquals(1, actual.requests.size)
        working.session(context) { session ->
            val error = withContext(Dispatchers.IO) { rejected { session.active() } }
            assertTrue(error is IllegalStateException)
        }
        assertEquals(2, actual.requests.size)
    }

    private fun refreshReply() = """{"access_token":"fresh-access","refresh_token":"fresh-refresh","user_id":"${id(1)}","username":"member","is_admin":false}"""
    @Test fun actualRefreshKeepsReplicaHeadersAndOriginalOperationIdentity() = runBlocking<Unit> {
        val context = capture()
        val (http, server) = http { input, _ -> when (input.path) {
            "/api/v2/system/identity" -> normal(input)
            "/api/v1/auth/refresh" -> {
                assertNull(input.headers["authorization"]); assertEquals("synthetic-refresh", parsed(input).getValue("refresh_token").jsonPrimitive.content)
                json(refreshReply())
            }
            else -> {
                headers(input, if (input.headers["authorization"] == "Bearer fresh-access") "fresh-access" else "synthetic-access")
                if (input.headers["authorization"] == "Bearer fresh-access") json(pushReply()) else json("{}", 401)
            }
        } }
        assertEquals("rejected", http.session(context) { it.push(push()) }!!.results.single().status)
        assertEquals(context, capture()); assertEquals("fresh-access", tokens.authenticationSnapshot()!!.accessToken)
        assertEquals(4, server.requests.size)
        val bodies = server.requests.filter { it.path.endsWith("/push") }; assertArrayEquals(bodies[0].body, bodies[1].body)
        assertEquals(bodies[0].headers["x-dayforge-sync-epoch"], bodies[1].headers["x-dayforge-sync-epoch"])
    }

    @Test fun refreshedTokensCannotCommitAfterReplicaDeviceOrPermissionChange() = runBlocking<Unit> {
        val changes: List<suspend () -> Unit> = listOf({ tokens.saveServerIdentity(id(2), id(30)) },
            { tokens.saveDeviceRegistration(id(30), capabilities, true, 1) },
            { tokens.saveDeviceRegistration(id(4), capabilities - "timer.control", true, 2) })
        for (change in changes) {
            login(); val context = capture(); val sent = CountDownLatch(1); val release = CountDownLatch(1)
            val (http, server) = http { input, _ -> when (input.path) {
                "/api/v2/system/identity" -> normal(input)
                "/api/v1/auth/refresh" -> { sent.countDown(); check(release.await(5, TimeUnit.SECONDS)); json(refreshReply()) }
                else -> json("{}", 401)
            } }
            supervisorScope {
                val task = async(Dispatchers.IO) { rejected { http.session(context) { it.push(push()) } } }
                try {
                    assertTrue(sent.await(5, TimeUnit.SECONDS)); change(); release.countDown(); assertTrue(task.await() is IOException)
                } finally { release.countDown(); withContext(NonCancellable) { task.cancelAndJoin() } }
            }
            val credentials = tokens.authenticationSnapshot()!!
            assertEquals(context.session.authentication, credentials.session); assertEquals("synthetic-access", credentials.accessToken)
            assertEquals("synthetic-refresh", credentials.refreshToken); assertEquals(3, server.requests.size)
        }
    }

    @Test fun currentSnapshotRequiredInsideRefreshDataStoreTransactionNotOnlyBeforeIt() = runBlocking<Unit> {
        val context = capture(); val credentials = tokens.authenticationSnapshot()!!
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val queued = TokenManager(object : DataStore<Preferences> {
            override val data = this@NextSyncHttpTest.data.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                reached.complete(Unit); release.await()
                return this@NextSyncHttpTest.data.updateData(transform)
            }
        })
        supervisorScope {
            val saved = async(Dispatchers.IO) { queued.saveRefreshedSyncTokens(credentials, context,
                "fresh-access", "fresh-refresh", "member", id(1), false) }
            try {
                withTimeout(5000) { reached.await() }
                tokens.saveDeviceRegistration(id(4), capabilities - "facts.append", true, 1)
                release.complete(Unit); assertFalse(saved.await())
            } finally { release.complete(Unit); withContext(NonCancellable) { saved.cancelAndJoin() } }
        }
        assertEquals("synthetic-access", tokens.authenticationSnapshot()!!.accessToken)
        val current = capture()
        assertTrue(tokens.saveRefreshedSyncTokens(credentials, current, "fresh-access", "fresh-refresh", "member", id(1), false))
        assertEquals(current, capture()); assertEquals("fresh-refresh", tokens.authenticationSnapshot()!!.refreshToken)
    }

    @Test fun lateRejectedRefreshCannotEraseCredentialsAfterReplicaChanged() = runBlocking<Unit> {
        val context = capture(); val sent = CountDownLatch(1); val release = CountDownLatch(1)
        val (http, server) = http { input, _ -> when (input.path) {
            "/api/v2/system/identity" -> json(identity())
            "/api/v1/auth/refresh" -> { sent.countDown(); check(release.await(5, TimeUnit.SECONDS)); json("{}", 401) }
            else -> json("{}", 401)
        } }
        supervisorScope {
            val task = async(Dispatchers.IO) { rejected { http.session(context) { it.active() } } }
            try {
                assertTrue(sent.await(5, TimeUnit.SECONDS)); tokens.saveServerIdentity(id(2), id(30))
                release.countDown(); assertTrue(task.await() is IOException)
            } finally { release.countDown(); withContext(NonCancellable) { task.cancelAndJoin() } }
        }
        assertEquals("synthetic-access", tokens.authenticationSnapshot()!!.accessToken)
        assertEquals("synthetic-refresh", tokens.authenticationSnapshot()!!.refreshToken)
        assertEquals(id(30), tokens.syncEpoch.first()); assertEquals(3, server.requests.size)
    }

    @Test fun currentDefinitivelyRejectedRefreshStillInvalidatesAuthenticationOnly() = runBlocking<Unit> {
        val context = capture()
        val (http, server) = http { input, _ -> if (input.path.endsWith("/identity")) json(identity()) else json("{}", 401) }
        assertTrue(rejected { http.session(context) { it.active() } } is IOException)
        assertNull(tokens.authenticationSnapshot()); assertEquals(id(1), tokens.syncAccountId.first())
        assertEquals(id(4), tokens.syncDeviceId.first()); assertEquals(id(3), tokens.syncEpoch.first()); assertEquals(3, server.requests.size)
    }

    @Test fun badRefreshWireNeverSavesPartialCredentialsOrRetriesPrivateWork() = runBlocking<Unit> {
        val context = capture(); var reply = json(refreshReply())
        val broken = listOf(json(refreshReply().replace("\"is_admin\":false", "\"is_admin\":\"false\"")),
            json(refreshReply().replace(id(1), id(30))), json(refreshReply().replace("fresh-access", "fresh\\ninvalid")),
            json(refreshReply().dropLast(1) + ",\"user_id\":\"${id(1)}\"}"),
            MaterialSocketServer.Reply(refreshReply().toByteArray(), type = "application/json; charset=unknown-charset"),
            MaterialSocketServer.Reply(refreshReply().toByteArray(), headers = mapOf("Content-Type" to "application/json")),
            MaterialSocketServer.Reply(byteArrayOf(), length = 65_537))
        val (http, server) = http { input, _ -> when (input.path) {
            "/api/v2/system/identity" -> json(identity())
            "/api/v1/auth/refresh" -> reply
            else -> json("{}", 401)
        } }
        for (raw in broken) {
            reply = raw; assertTrue(rejected { http.session(context) { it.active() } } is IOException)
            assertEquals("synthetic-access", tokens.authenticationSnapshot()!!.accessToken)
            assertEquals("synthetic-refresh", tokens.authenticationSnapshot()!!.refreshToken); assertEquals(context, capture())
        }
        assertEquals(broken.size * 3, server.requests.size)
    }

    @Test fun unknownDevicePermissionsOrUnsafeCapturedCredentialsCannotStartPersonalTraffic() = runBlocking<Unit> {
        val context = capture(); val (http, server) = http { _, _ -> error("Must not reach server") }
        tokens.saveDeviceRegistration(id(4), emptySet(), false, 1)
        assertNull(tokens.localSyncAccess()); rejected { http.session(context) { error("Must not enter") } }
        assertTrue(server.requests.isEmpty())
        login(); tokens.saveLoginSession("synthetic\ninvalid", "synthetic-refresh", "member", id(1), false)
        val invalid = capture()
        // Public identity is anonymous even with a bad local token; private attachment refuses it.
        val (bad, actual) = http { input, _ -> assertTrue(input.path.endsWith("/identity")); json(identity()) }
        assertTrue(rejected { bad.session(invalid) { it.active() } } is IOException)
        assertEquals(1, actual.requests.size); assertNull(actual.requests.single().headers["authorization"])
    }

    @Test fun concurrent401RequestsReuseOneRefreshAndRetainCapturedTuple() = runBlocking<Unit> {
        val context = capture(); val refreshing = AtomicInteger(); val expired = CountDownLatch(2)
        val (http, server) = http { input, _ -> when (input.path) {
            "/api/v2/system/identity" -> json(identity())
            "/api/v1/auth/refresh" -> { refreshing.incrementAndGet(); json(refreshReply()) }
            else -> if (input.headers["authorization"] == "Bearer fresh-access") {
                headers(input, "fresh-access"); json(status())
            } else { headers(input); expired.countDown(); check(expired.await(5, TimeUnit.SECONDS)); json("{}", 401) }
        } }
        supervisorScope {
            val one = async(Dispatchers.IO) { http.session(context) { it.active() } }
            val two = async(Dispatchers.IO) { http.session(context) { it.status(id(10)) } }
            try { assertNotNull(one.await()!!.session); assertNotNull(two.await()!!.session) }
            finally { withContext(NonCancellable) { one.cancelAndJoin(); two.cancelAndJoin() } }
        }
        assertEquals(1, refreshing.get()); assertEquals(7, server.requests.size); assertEquals(context, capture())
    }

    @Test fun cancellingMainAndRefreshCallsClosesActualSocketsAndJoinsIo() = runBlocking<Unit> {
        for (refresh in listOf(false, true)) {
            login(); val context = capture(); val sent = CountDownLatch(1); val closed = CountDownLatch(1)
            val (http, server) = http { input, socket -> when {
                input.path.endsWith("/identity") -> json(identity())
                refresh && input.path != "/api/v1/auth/refresh" -> json("{}", 401)
                else -> {
                    if (!refresh) socket.getOutputStream().apply {
                        write("HTTP/1.1 200 Fixture\r\nContent-Type: application/json\r\nContent-Length: 1024\r\n\r\n".toByteArray()); flush()
                    }
                    sent.countDown(); assertEquals(-1, socket.getInputStream().read()); closed.countDown(); null
                }
            } }
            val task = async(Dispatchers.IO) { http.session(context) { it.active() } }
            try {
                assertTrue(sent.await(5, TimeUnit.SECONDS)); withTimeout(5000) { task.cancelAndJoin() }
                assertTrue(closed.await(5, TimeUnit.SECONDS)); assertTrue(task.isCompleted)
            } finally { withContext(NonCancellable) { task.cancelAndJoin() } }
            assertEquals(if (refresh) 3 else 2, server.requests.size)
            assertEquals(context, capture()); assertEquals("synthetic-access", tokens.authenticationSnapshot()!!.accessToken)
        }
    }
}
