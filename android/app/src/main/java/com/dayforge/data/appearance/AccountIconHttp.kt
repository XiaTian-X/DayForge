@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractIntegerSerializer::class)

package com.dayforge.data.appearance

import android.content.Context
import android.net.ConnectivityManager
import com.dayforge.data.api.MaterialHttpRoute
import com.dayforge.data.api.MaterialCallCancellation
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.LocalIconAccess
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

internal class MaterialHttpFailure(val status: Int, val code: String?) : IOException("MATERIAL_HTTP_$status")
internal class MaterialReplyInvalid : IOException("MATERIAL_RESPONSE_INVALID")

@Serializable
internal data class MaterialServerIdentity(
    @SerialName("server_instance_id") val serverInstanceId: String,
    @SerialName("sync_epoch") val syncEpoch: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    val capabilities: List<String>, @SerialName("server_time") val serverTime: String
) {
    init {
        require(isContractUuid(serverInstanceId) && isContractUuid(syncEpoch) && protocolVersion > 0)
        require(capabilities.distinct().size == capabilities.size && capabilities.all { it.isNotBlank() })
        java.time.Instant.parse(serverTime)
    }
}

/** Real bounded transport. Does not activate a protocol, select a replica, mutate queues or schedule. */
@Singleton
class AccountIconHttp private constructor(
    private val shared: OkHttpClient, private val tokens: TokenManager,
    private val captureRoute: suspend () -> MaterialHttpRoute
) {
    @Inject constructor(@ApplicationContext context: Context, shared: OkHttpClient, tokens: TokenManager, preferences: PreferencesManager,
        selected: SelectedNetworkTransport) : this(shared, tokens, {
        val base = (preferences.activeServerUrl.first() ?: preferences.serverUrl.first()
            ?: "http://localhost:8000/").toHttpUrl()
        require(base.username.isEmpty() && base.password.isEmpty())
        // Loopback/adb reverse is independent of Wi-Fi/cellular; external origins must bind
        // an actual snapshot, not a SocketFactory that follows a later system default.
        val network = if (base.host in setOf("localhost", "127.0.0.1", "::1")) null
            else selected.currentNetwork() ?: context.getSystemService(ConnectivityManager::class.java).activeNetwork
                ?: throw IOException("MATERIAL_NETWORK_UNAVAILABLE")
        MaterialHttpRoute(base.newBuilder().encodedPath("/").query(null).fragment(null).build(), network)
    })

    internal constructor(shared: OkHttpClient, tokens: TokenManager, origin: HttpUrl) :
        this(shared, tokens, { MaterialHttpRoute(origin, null) })

    /** No material job may be claimed until actual public identity admits the exact v5 replica. */
    internal suspend fun <T> session(context: AccountIconContext, block: suspend (Session) -> T): T? {
        check(tokens.iconAuthenticationSnapshot(context.access) != null) { "ICON_SESSION_CHANGED" }
        val route = try { captureRoute() } catch (_: IllegalArgumentException) { throw IOException("MATERIAL_ROUTE_INVALID") }
        val builder = shared.newBuilder()
        builder.interceptors().removeAll { it is HttpLoggingInterceptor }
        builder.networkInterceptors().removeAll { it is HttpLoggingInterceptor }
        val client = route.bind(builder)
            // Never share pooled sockets with a mutable LAN/default-route client.
            .connectionPool(ConnectionPool(1, 1, TimeUnit.MINUTES))
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                if (!route.matches(chain.request().url) || tokensCheck(tokens, context) == null)
                    throw IOException("ICON_SESSION_CHANGED")
                chain.proceed(chain.request())
            }.build()
        val session = Session(client, tokens, context, route, requireNotNull(currentCoroutineContext()[Job]))
        try {
            val identity = session.identity()
            check(identity.serverInstanceId == context.namespace.serverInstanceId && identity.syncEpoch == context.namespace.syncEpoch) {
                "ICON_SERVER_IDENTITY_CHANGED"
            }
            if (identity.protocolVersion != 5) return null
            return block(session)
        } finally {
            // All calls join their actual IO before returning; only this private pool is evicted.
            session.close()
            client.connectionPool.evictAll()
        }
    }

    internal class Session internal constructor(
        private val client: OkHttpClient, private val tokens: TokenManager,
        private val context: AccountIconContext, private val route: MaterialHttpRoute, private val owner: Job
    ) {
        private var closed = false
        internal fun close() { closed = true }
        private val json = Json { encodeDefaults = true }
        internal suspend fun identity(): MaterialServerIdentity = value(request(MaterialHttpRoute.IDENTITY_PATH))

        internal suspend fun authorize(expected: AccountIconContext) {
            check(!closed && currentCoroutineContext()[Job] === owner && expected.access == context.access) { "MATERIAL_SCOPE_CHANGED" }
            check(tokens.iconAuthenticationSnapshot(context.access) != null) { "ICON_SESSION_CHANGED" }
        }

        suspend fun catalog(attempt: IconCatalogAttempt): AppearanceCatalogPage {
            require(attempt.context.access == context.access)
            val builder = request("/api/v2/appearance/catalog", query = true)
            val url = builder.build().url.newBuilder().addQueryParameter("after", attempt.after.toString())
                .addQueryParameter("limit", AccountIconRemoteCatalog.PAGE_LIMIT.toString())
            attempt.through?.let { url.addQueryParameter("through", it.toString()) }
            val page: AppearanceCatalogPage = value(builder.url(url.build()).get())
            valid { AccountIconRemoteCatalog.validatePage(attempt, page) }
            return page
        }

        private fun request(path: String, query: Boolean = false): Request.Builder {
            val url = route.origin.newBuilder().encodedPath(path)
            if (query) url.addQueryParameter("server_instance_id", context.namespace.serverInstanceId)
                .addQueryParameter("sync_epoch", context.namespace.syncEpoch).addQueryParameter("device_id", context.access.deviceId)
            return Request.Builder().url(url.build()).header("Accept-Encoding", "identity")
                .header("X-DayForge-Protocol", "5")
                .tag(AuthenticationSession::class.java, context.access.session.authentication)
                .tag(LocalIconAccess::class.java, context.access).tag(MaterialHttpRoute::class.java, route)
        }

        private fun checkAttempt(attempt: IconTransferAttempt, kind: IconTransferKind) {
            require(attempt.context.access == context.access && attempt.job.kind == kind && attempt.job.state == IconTransferState.SENDING)
            require(isContractUuid(attempt.job.targetId))
        }

        suspend fun declareAsset(attempt: IconTransferAttempt): AssetRecord {
            checkAttempt(attempt, IconTransferKind.DECLARE_ASSET)
            val declaration = AssetDeclaration(attempt.binding, requireNotNull(attempt.asset))
            require(declaration.asset.assetId == attempt.job.targetId)
            val response: AssetRecord = value(request("/api/v2/appearance/assets/${attempt.job.targetId}")
                .put(json.encodeToString(declaration).toRequestBody(JSON_TYPE)))
            valid { validateAssetRecordBinding(declaration, response) }
            return response
        }

        suspend fun declarePack(attempt: IconTransferAttempt): PackDeclaration {
            checkAttempt(attempt, IconTransferKind.DECLARE_PACK)
            val declaration = PackDeclaration(attempt.binding, requireNotNull(attempt.pack))
            require(declaration.pack.packId == attempt.job.targetId && declaration.pack.revision == attempt.job.revision)
            val response: PackDeclaration = value(request("/api/v2/appearance/packs/${attempt.job.targetId}/versions/${attempt.job.revision}")
                .put(json.encodeToString(declaration).toRequestBody(JSON_TYPE)))
            valid { validatePackBinding(declaration, response) }
            return response
        }

        suspend fun upload(attempt: IconTransferAttempt, bytes: ByteArray): AssetTransferReceipt {
            checkAttempt(attempt, IconTransferKind.UPLOAD)
            val asset = requireNotNull(attempt.asset)
            val blob = variant(attempt)
            require(bytes.size == blob.byteLength && hash(bytes) == blob.sha256)
            val response: AssetTransferReceipt = value(request(contentPath(attempt), query = true)
                .put(bytes.copyOf().toRequestBody(blob.mediaType.toMediaType())))
            valid { validateTransferBinding(AssetDeclaration(attempt.binding, asset), attempt.job.variant, response) }
            return response
        }

        suspend fun download(attempt: IconTransferAttempt): ByteArray {
            checkAttempt(attempt, IconTransferKind.DOWNLOAD)
            val blob = variant(attempt)
            val bytes = execute(request(contentPath(attempt), query = true).get().build(), blob.byteLength, blob.mediaType)
            if (bytes.size != blob.byteLength || hash(bytes) != blob.sha256) throw MaterialReplyInvalid()
            return bytes // Native validation/publication still belongs to the canonical file store.
        }

        private fun variant(attempt: IconTransferAttempt): IconBlob {
            require(attempt.asset?.assetId == attempt.job.targetId && attempt.job.variant in setOf("light", "dark"))
            return if (attempt.job.variant == "light") requireNotNull(attempt.asset).light else requireNotNull(attempt.asset?.dark)
        }
        private fun contentPath(attempt: IconTransferAttempt) =
            "/api/v2/appearance/assets/${attempt.job.targetId}/content/${attempt.job.variant}"

        private suspend inline fun <reified T> value(builder: Request.Builder): T {
            val bytes = execute(builder.build(), 1_048_576, "application/json")
            return valid {
                json.decodeFromString<T>(strictAppearanceJson(bytes, 1_048_576, {}, { throw MaterialReplyInvalid() }))
            }
        }

        private suspend fun execute(request: Request, limit: Int, type: String): ByteArray {
            check(!closed && currentCoroutineContext()[Job] === owner) { "MATERIAL_SCOPE_CHANGED" }
            return supervisorScope {
                check(tokens.iconAuthenticationSnapshot(context.access) != null) { "ICON_SESSION_CHANGED" }
                val cancellation = MaterialCallCancellation()
                val call = client.newCall(request.newBuilder().tag(MaterialCallCancellation::class.java, cancellation).build())
                cancellation.register(call)
                val worker = async(Dispatchers.IO) {
                    // The caller cancels the actual call and joins it; cancellation cannot close a
                    // response/stream still in use or leave a late value behind database teardown.
                    withContext(NonCancellable) {
                        call.execute().use { response ->
                            if (response.code != 200) throw MaterialHttpFailure(response.code, errorCode(response, call))
                            if (response.header("Content-Encoding")?.let { it != "identity" } == true) throw MaterialReplyInvalid()
                            val media = response.body?.contentType() ?: throw MaterialReplyInvalid()
                            if ("${media.type}/${media.subtype}" != type ||
                                (type == "application/json" && media.charset(Charsets.UTF_8) != Charsets.UTF_8)) throw MaterialReplyInvalid()
                            if (type != "application/json" &&
                                (response.header("X-Content-Type-Options") != "nosniff" ||
                                    !response.cacheControl.noStore || !response.cacheControl.isPrivate)) throw MaterialReplyInvalid()
                            bounded(response, limit, call)
                        }
                    }
                }
                val result = try { worker.await() } finally {
                    cancellation.cancel()
                    withContext(NonCancellable) { worker.join() }
                }
                check(tokens.iconAuthenticationSnapshot(context.access) != null) { "ICON_SESSION_CHANGED" }
                result
            }
        }

        private fun bounded(response: Response, limit: Int, call: Call): ByteArray {
            val body = response.body ?: throw MaterialReplyInvalid()
            if (body.contentLength() > limit) throw MaterialReplyInvalid()
            val output = ByteArrayOutputStream(minOf(limit, 8192))
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (true) {
                    if (call.isCanceled()) throw IOException("MATERIAL_CALL_CANCELLED")
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                    if (count == -1) break
                    if (count == 0 || output.size() + count > limit) throw MaterialReplyInvalid()
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }

        private fun errorCode(response: Response, call: Call): String? = try {
            val bytes = bounded(response, 4096, call)
            val text = strictAppearanceJson(bytes, 4096, {}, { throw MaterialReplyInvalid() })
            val code = json.parseToJsonElement(text).jsonObject["detail"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
            code?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) }
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }

        private fun <T> valid(block: () -> T): T = try { block() }
        catch (_: SerializationException) { throw MaterialReplyInvalid() }
        catch (_: IllegalArgumentException) { throw MaterialReplyInvalid() }
        catch (_: java.time.DateTimeException) { throw MaterialReplyInvalid() }

        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private companion object { val JSON_TYPE = "application/json".toMediaType() }
    }

    private companion object {
        fun tokensCheck(tokens: TokenManager, context: AccountIconContext) = runBlocking { tokens.iconAuthenticationSnapshot(context.access) }
    }
}
