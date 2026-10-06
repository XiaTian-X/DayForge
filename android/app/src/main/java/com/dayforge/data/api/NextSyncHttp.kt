package com.dayforge.data.api

import android.content.Context
import android.net.ConnectivityManager
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.isContractUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

internal class NextSyncHttpFailure(val status: Int, val code: String?, val retryAfterSeconds: Int? = null) :
    IOException("SYNC_HTTP_$status")
internal class NextSyncReplyInvalid : IOException("SYNC_RESPONSE_INVALID")

/** Actual v5 core HTTP. No queue consumption, cursor activation or protocol promotion. */
@Singleton
internal class NextSyncHttp private constructor(
    private val shared: OkHttpClient, private val tokens: TokenManager,
    private val captureRoute: suspend () -> MaterialHttpRoute
) {
    @Inject constructor(@ApplicationContext context: Context, shared: OkHttpClient, tokens: TokenManager,
        preferences: PreferencesManager, selected: SelectedNetworkTransport) : this(shared, tokens, {
        val base = (preferences.activeServerUrl.first() ?: preferences.serverUrl.first()
            ?: "http://localhost:8000/").toHttpUrl()
        require(base.username.isEmpty() && base.password.isEmpty())
        val network = if (base.host in setOf("localhost", "127.0.0.1", "::1")) null
            else selected.currentNetwork() ?: context.getSystemService(ConnectivityManager::class.java).activeNetwork
                ?: throw IOException("SYNC_NETWORK_UNAVAILABLE")
        MaterialHttpRoute(base.newBuilder().encodedPath("/").query(null).fragment(null).build(), network)
    })

    internal constructor(shared: OkHttpClient, tokens: TokenManager, origin: HttpUrl) :
        this(shared, tokens, { MaterialHttpRoute(origin, null) })

    /** Only exact public v5 proof admits calls. Null means unsupported, never sync success. */
    suspend fun <T> session(captured: LocalSyncAccess, block: suspend (Session) -> T): T? {
        val context = captured.copy(capabilities = captured.capabilities.toSet())
        authorize(tokens, context)
        val route = try { captureRoute() } catch (_: IllegalArgumentException) { throw IOException("SYNC_ROUTE_INVALID") }
        val builder = shared.newBuilder()
        builder.interceptors().removeAll { it is HttpLoggingInterceptor }
        builder.networkInterceptors().removeAll { it is HttpLoggingInterceptor }
        val client = route.bind(builder).connectionPool(ConnectionPool(1, 1, TimeUnit.MINUTES))
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                if (!route.matches(chain.request().url)) throw IOException("SYNC_ROUTE_CHANGED")
                runBlocking { authorize(tokens, context) }
                chain.proceed(chain.request())
            }.build()
        val session = Session(client, tokens, context, route, requireNotNull(currentCoroutineContext()[Job]))
        try {
            val identity = session.identity()
            if (identity.serverInstanceId != context.session.serverInstanceId)
                throw NextSyncHttpFailure(409, "SERVER_IDENTITY_MISMATCH")
            if (identity.syncEpoch != context.session.syncEpoch)
                throw NextSyncHttpFailure(409, "SYNC_EPOCH_MISMATCH")
            if (identity.protocolVersion != 5) return null
            return block(session)
        } finally {
            session.close()
            client.connectionPool.evictAll()
        }
    }

    internal class Session internal constructor(
        private val client: OkHttpClient, private val tokens: TokenManager, private val context: LocalSyncAccess,
        private val route: MaterialHttpRoute, private val owner: Job
    ) {
        private var closed = false
        private val json = Json { encodeDefaults = true }
        internal fun close() { closed = true }

        internal suspend fun identity(): ServerIdentityResponse = value(
            request(MaterialHttpRoute.IDENTITY_PATH, public = true), ServerIdentityResponse.serializer(), SMALL_REPLY
        ).also {
            valid {
                require(isContractUuid(it.serverInstanceId) && isContractUuid(it.syncEpoch) && it.protocolVersion > 0)
                require(it.capabilities.distinct().size == it.capabilities.size && it.capabilities.all(String::isNotBlank))
                Instant.parse(it.serverTime)
            }
        }

        suspend fun register(body: DeviceRegisterRequest): DeviceResponse {
            require(context.deviceId == null && body.protocolVersion == 5)
            val response = value(post("/api/v2/devices/register", DeviceRegisterRequest.serializer(), body),
                DeviceResponse.serializer(), SMALL_REPLY, setOf("device_class", "capability_revision", "capabilities",
                    "is_primary_editor", "structural_edit_enabled", "last_seen_at"))
            valid {
                require(isContractUuid(response.deviceId) && response.installationId == body.installationId &&
                    response.platform == body.platform && response.deviceClass == body.deviceClass && response.capabilityRevision > 0)
                require(response.capabilities.distinct().size == response.capabilities.size &&
                    response.capabilities.all(String::isNotBlank) && "sync.read" in response.capabilities)
                Instant.parse(requireNotNull(response.lastSeenAt))
            }
            return response
        }

        suspend fun push(body: NextSyncPushRequest): NextSyncPushResponse {
            require(body.deviceId == device())
            val frozen = freeze(NextSyncPushRequest.serializer(), body)
            require(frozen.operations.map { it.operationId }.distinct().size == frozen.operations.size)
            val response = value(post("/api/v2/sync/push", NextSyncPushRequest.serializer(), frozen), NextSyncPushResponse.serializer())
            valid {
                val operations = frozen.operations.associateBy { it.operationId }
                require(response.results.size == operations.size && response.results.map { it.operationId }.distinct().size == operations.size)
                response.results.forEach { validateTaskResultBinding(requireNotNull(operations[it.operationId]), it) }
            }
            return response
        }

        suspend fun bootstrap(): NextSyncBootstrapResponse = value(
            request("/api/v2/sync/bootstrap").queryDevice(), NextSyncBootstrapResponse.serializer(), BOOTSTRAP_REPLY
        ).also { valid { require(it.nextCursor >= 0); Instant.parse(it.serverTime); it.changes.forEach(::validateChange) } }

        suspend fun pull(cursor: Long, limit: Int = 500): NextSyncPullResponse {
            require(cursor >= 0 && limit in 1..1000)
            val builder = request("/api/v2/sync/changes").queryDevice()
            val response = value(builder.url(builder.build().url.newBuilder().addQueryParameter("cursor", cursor.toString())
                .addQueryParameter("limit", limit.toString()).build()), NextSyncPullResponse.serializer())
            valid {
                require(response.nextCursor >= cursor && response.changes.size <= limit)
                var previous = cursor
                response.changes.forEach { change ->
                    validateChange(change)
                    require(change.sequence > previous && change.sequence <= response.nextCursor)
                    previous = change.sequence
                }
                if (response.hasMore) require(response.changes.isNotEmpty() && previous == response.nextCursor)
                Instant.parse(response.serverTime)
            }
            return response
        }

        suspend fun commands(body: TimerCommandBatchRequest): TimerCommandBatchResponse {
            require(body.deviceId == device() && body.commands.size in 1..100)
            val frozen = freeze(TimerCommandBatchRequest.serializer(), body)
            val commands = frozen.commands.associateBy { it.commandId }
            require(commands.size == frozen.commands.size && commands.keys.all(::isContractUuid) && frozen.commands.all { isContractUuid(it.sessionId) })
            val response = value(post("/api/v2/timers/commands", TimerCommandBatchRequest.serializer(), frozen), TimerCommandBatchResponse.serializer())
            valid {
                require(response.results.size == commands.size && response.results.map { it.commandId }.distinct().size == commands.size)
                response.results.forEach { result ->
                    require(requireNotNull(commands[result.commandId]).sessionId == result.sessionId &&
                        result.status in setOf("applied", "already_applied", "conflict", "rejected"))
                    result.session?.let { validateTimer(it); require(it.sessionId == result.sessionId) }
                }
                Instant.parse(response.serverTime)
            }
            return response
        }

        suspend fun active(): TimerStatusResponse = value(request("/api/v2/timers/active").queryDevice(), TimerStatusResponse.serializer())
            .also { valid { it.session?.let(::validateTimer); Instant.parse(it.serverTime) } }

        suspend fun status(sessionId: String): TimerStatusResponse {
            require(isContractUuid(sessionId))
            return value(request("/api/v2/timers/$sessionId").queryDevice(), TimerStatusResponse.serializer()).also { valid {
                it.session?.let { timer -> validateTimer(timer); require(timer.sessionId == sessionId) }; Instant.parse(it.serverTime)
            } }
        }

        suspend fun heartbeat(sessionId: String, generation: Int): TimerHeartbeatResponse {
            require(isContractUuid(sessionId) && generation > 0)
            val response = value(post("/api/v2/timers/$sessionId/heartbeat", TimerHeartbeatRequest.serializer(),
                TimerHeartbeatRequest(device(), generation)), TimerHeartbeatResponse.serializer())
            valid {
                validateTimer(response.session); require(response.session.sessionId == sessionId)
                if (response.accepted) require(response.session.controllerDeviceId == device() && response.session.controlGeneration == generation)
                Instant.parse(response.serverTime)
            }
            return response
        }

        private fun device(): String = requireNotNull(context.deviceId)
        private fun Request.Builder.queryDevice(): Request.Builder = url(build().url.newBuilder().addQueryParameter("device_id", device()).build())
        private fun request(path: String, public: Boolean = false): Request.Builder {
            val builder = Request.Builder().url(route.origin.newBuilder().encodedPath(path).build()).header("Accept-Encoding", "identity")
                .tag(AuthenticationSession::class.java, context.session.authentication).tag(LocalSyncAccess::class.java, context)
                .tag(MaterialHttpRoute::class.java, route)
            if (!public) builder.header("X-DayForge-Protocol", "5")
                .header("X-DayForge-Server-Instance", requireNotNull(context.session.serverInstanceId))
                .header("X-DayForge-Sync-Epoch", requireNotNull(context.session.syncEpoch))
            return builder
        }
        private suspend fun <T> freeze(serializer: KSerializer<T>, body: T): T = withContext(Dispatchers.Default) {
            json.decodeFromString(serializer, encodeBounded(serializer, body).toString(Charsets.UTF_8))
        }
        private suspend fun <T> post(path: String, serializer: KSerializer<T>, body: T): Request.Builder {
            val bytes = withContext(Dispatchers.Default) { encodeBounded(serializer, body) }
            return request(path).post(bytes.toRequestBody(JSON_TYPE))
        }

        @OptIn(ExperimentalSerializationApi::class)
        private suspend fun <T> encodeBounded(serializer: KSerializer<T>, body: T): ByteArray {
            val caller = currentCoroutineContext()
            val output = object : ByteArrayOutputStream(8192) {
                override fun write(value: Int) {
                    caller.ensureActive(); require(size() < 1_048_576); super.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    caller.ensureActive(); require(length <= 1_048_576 - size()); super.write(bytes, offset, length)
                }
            }
            return output.use {
                json.encodeToStream(serializer, body, output)
                output.toByteArray()
            }
        }

        private suspend fun <T> value(builder: Request.Builder, serializer: KSerializer<T>, limit: Int = NORMAL_REPLY,
            requiredFields: Set<String> = emptySet()): T {
            val bytes = execute(builder.build(), limit)
            val result = withContext(Dispatchers.Default) {
                val caller = currentCoroutineContext()
                decodeSyncReply(bytes, limit, serializer, { caller.ensureActive() }, requiredFields)
            }
            authorize(tokens, context)
            return result
        }

        private suspend fun execute(request: Request, limit: Int): ByteArray {
            check(!closed && currentCoroutineContext()[Job] === owner) { "SYNC_SCOPE_CHANGED" }
            return supervisorScope {
                authorize(tokens, context)
                val cancellation = MaterialCallCancellation()
                val call = client.newCall(request.newBuilder().tag(MaterialCallCancellation::class.java, cancellation).build())
                cancellation.register(call)
                val worker = async(Dispatchers.IO) {
                    withContext(NonCancellable) {
                        call.execute().use { response ->
                            if (response.code != 200) throw NextSyncHttpFailure(response.code, errorCode(response, call),
                                response.header("Retry-After")?.toIntOrNull()?.takeIf { it in 0..86_400 })
                            if (response.header("Content-Encoding")?.let { it != "identity" } == true) throw NextSyncReplyInvalid()
                            requireSyncJsonType(response)
                            bounded(response, limit, call)
                        }
                    }
                }
                val result = try { worker.await() } finally {
                    cancellation.cancel()
                    withContext(NonCancellable) { worker.join() }
                }
                authorize(tokens, context)
                result
            }
        }

        private fun bounded(response: Response, limit: Int, call: Call): ByteArray {
            val body = response.body ?: throw NextSyncReplyInvalid()
            if (body.contentLength() > limit) throw NextSyncReplyInvalid()
            val output = ByteArrayOutputStream(minOf(limit, 8192))
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (call.isCanceled()) throw IOException("SYNC_CALL_CANCELLED")
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                    if (count == -1) break
                    if (count == 0 || output.size() + count > limit) throw NextSyncReplyInvalid()
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }

        private fun errorCode(response: Response, call: Call): String? = try {
            val bytes = bounded(response, 4096, call)
            val text = com.dayforge.data.appearance.strictAppearanceJson(bytes, 4096, {}, { throw NextSyncReplyInvalid() })
            val code = Json.parseToJsonElement(text).jsonObject["detail"]?.jsonObject?.get("code") as? JsonPrimitive
            code?.takeIf { it.isString && it.content.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) }?.content
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }

        private fun validateChange(change: SyncV2Change) {
            require(isContractUuid(change.entityUuid) && change.revision > 0 && change.sequence >= 0 &&
                change.entityType in setOf("plan_node", "metric", "activity_event", "metric_observation", "activity_metric_link") &&
                change.operation in setOf("upsert", "delete"))
            change.originDeviceId?.let { require(isContractUuid(it)) }
            Instant.parse(change.changedAt)
            if (change.operation == "upsert" && change.entityType in setOf("plan_node", "metric"))
                validateNextAppearancePayload(change.payload, change.entityType == "metric")
        }

        private fun validateTimer(timer: TimerSessionResponse) {
            require(listOf(timer.sessionId, timer.activityUuid, timer.controllerDeviceId).all(::isContractUuid))
            require(timer.state in setOf("running", "paused", "completed", "cancelled") && timer.controlGeneration > 0 &&
                timer.revision > 0 && timer.nextCommandSequence > 0 && timer.targetSeconds >= 0 && timer.maxDurationSeconds in 1..86_400 &&
                timer.activeElapsedMs in 0..timer.maxDurationSeconds.toLong() * 1000)
            Instant.parse(timer.startedAt); Instant.parse(timer.stateChangedAt); ZoneId.of(timer.timezone)
            timer.endedAt?.let(Instant::parse); timer.lastHeartbeatAt?.let(Instant::parse)
            timer.completedEventId?.let { require(isContractUuid(it)) }
        }

        private suspend fun <T> valid(block: () -> T): T {
            val result = withContext(Dispatchers.Default) {
                try { block() }
                catch (_: IllegalArgumentException) { throw NextSyncReplyInvalid() }
                catch (_: NoSuchElementException) { throw NextSyncReplyInvalid() }
                catch (_: java.time.DateTimeException) { throw NextSyncReplyInvalid() }
            }
            authorize(tokens, context)
            return result
        }
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
        const val SMALL_REPLY = 65_536
        const val NORMAL_REPLY = 8 * 1024 * 1024
        const val BOOTSTRAP_REPLY = 32 * 1024 * 1024
        suspend fun authorize(tokens: TokenManager, context: LocalSyncAccess) {
            if (tokens.syncAuthenticationSnapshot(context) == null) throw IOException("SYNC_SESSION_CHANGED")
        }
    }
}
