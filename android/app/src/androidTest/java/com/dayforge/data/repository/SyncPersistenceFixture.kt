package com.dayforge.data.repository

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.EndpointResolver
import com.dayforge.data.api.NetworkMonitor
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.SyncV2Api
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Before
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Shared real-device storage and scripted HTTP boundary; no repository/DAO mocks. */
abstract class SyncPersistenceFixture {
    private val context: Context = ApplicationProvider.getApplicationContext()
    protected val json = Json { ignoreUnknownKeys = true }
    private val alias = "dayforge.test.sync.${UUID.randomUUID()}"
    private val files = listOf("tokens", "preferences").map {
        File(context.cacheDir, "sync-$it-${UUID.randomUUID()}.preferences_pb")
    }
    private val scopes = mutableListOf<CoroutineScope>()
    protected lateinit var database: HabitDatabase
    protected lateinit var tokenStore: DataStore<Preferences>
    protected lateinit var tokens: TokenManager
    protected lateinit var repository: IncrementalSyncRepository
    private lateinit var networks: NetworkMonitor
    private lateinit var client: OkHttpClient
    protected val pushes = CopyOnWriteArrayList<JsonObject>()
    protected val cursors = CopyOnWriteArrayList<Long>()
    protected val paths = CopyOnWriteArrayList<String>()
    protected var server = "server-a"
    protected var epoch = "epoch-a"
    protected var onPush: (JsonObject) -> String = { acknowledge(it) }
    protected var onPull: (Long) -> String = { page(emptyList(), it) }
    protected var onRefresh: (JsonObject) -> String = { throw IOException("Unexpected refresh") }
    protected var onBootstrap: () -> String = { throw IOException("Unexpected bootstrap") }

    @Before fun setup() = runBlocking {
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase("habit_database")
        networks = NetworkMonitor(context.getSystemService(ConnectivityManager::class.java))
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            paths += path
            val body = request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                json.parseToJsonElement(buffer.readUtf8()).jsonObject
            }
            val response = when (request.method to path) {
                "POST" to "/api/auth/refresh" -> onRefresh(requireNotNull(body))
                "GET" to "/api/v2/system/identity" -> """{"server_instance_id":"$server","sync_epoch":"$epoch","protocol_version":4,"capabilities":["sync_v2","device_capabilities"],"server_time":"2026-09-20T00:00:00Z"}"""
                "POST" to "/api/v2/devices/register" -> """{"device_id":"device-a","installation_id":${body!!.getValue("installation_id")},"platform":"android","capabilities":["structure.write","facts.write"],"is_primary_editor":true}"""
                "POST" to "/api/v2/sync/push" -> {
                    val requestBody = requireNotNull(body)
                    pushes += requestBody
                    onPush(requestBody)
                }
                "GET" to "/api/v2/sync/changes" -> {
                    val cursor = requireNotNull(request.url.queryParameter("cursor")).toLong()
                    cursors += cursor
                    onPull(cursor)
                }
                "GET" to "/api/v2/sync/bootstrap" -> onBootstrap()
                else -> throw IOException("Unexpected sync request: ${request.method} $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("scripted boundary")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        openStores()
        tokens.saveTokens("synthetic-access", "synthetic-refresh", "member", "account-a", false)
        tokens.prepareSyncAccount("account-a")
        tokens.saveServerIdentity(server, epoch)
        tokens.saveSyncCursor(0)
    }

    private fun openStores() {
        database = HabitDatabaseProvider.getInstance(context)
        val stores = files.map { file ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scopes += scope
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        }
        tokenStore = stores[0]
        tokens = TokenManager(tokenStore, AndroidKeystoreTokenCipher(alias))
        val preferences = PreferencesManager(stores[1])
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/api/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        val api = retrofit.create(SyncV2Api::class.java)
        val merger = SyncV2Merger(database, database.habitDao(), database.completionDao(),
            database.timeLogDao(), database.metricDao(), database.metricLogDao(),
            database.habitMetricLinkDao(), database.syncOutboxDao(), database.syncConflictDao())
        repository = IncrementalSyncRepository(api,
            EndpointResolver(networks, preferences, tokens, json, SelectedNetworkTransport()),
            retrofit.create(AuthApi::class.java), tokens, preferences, database.habitDao(),
            database.completionDao(), database.timeLogDao(), database.metricDao(), database.metricLogDao(),
            database.habitMetricLinkDao(), database.syncOutboxDao(), database.syncConflictDao(),
            TimerSyncRepository(api, database.timeLogDao()), merger, json, AccountSessionCoordinator())
    }

    private suspend fun closeStores() {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        scopes.clear()
        if (::database.isInitialized) database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
    }

    protected suspend fun reopen() {
        closeStores()
        openStores()
    }

    @After fun cleanup() = runBlocking {
        closeStores()
        if (::networks.isInitialized) networks.close()
        if (::client.isInitialized) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        if (context.packageName == "com.dayforge.testbed") context.deleteDatabase("habit_database")
        files.forEach { it.delete() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        Unit
    }

    protected suspend fun insertGoal(title: String): HabitEntity {
        val entity = HabitEntity(name = title, habitType = HabitType.GOAL,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily)
        return entity.copy(id = database.habitDao().insert(entity))
    }

    protected fun operation(request: JsonObject) = request.getValue("operations").jsonArray.single().jsonObject

    protected fun acknowledge(request: JsonObject, status: String = "applied"): String = buildJsonObject {
        put("results", buildJsonArray {
            request.getValue("operations").jsonArray.forEach { element ->
                val op = element.jsonObject
                add(buildJsonObject {
                    put("operation_id", op.getValue("operation_id"))
                    put("entity_type", op.getValue("entity_type"))
                    put("entity_uuid", op.getValue("entity_uuid"))
                    put("status", status)
                    put("revision", 1)
                    put("entity", op.getValue("payload"))
                })
            }
        })
    }.toString()

    protected fun change(sequence: Long, uuid: String, title: String) = buildJsonObject {
        put("sequence", sequence)
        put("entity_type", "plan_node")
        put("entity_uuid", uuid)
        put("operation", "upsert")
        put("revision", 1)
        put("payload", buildJsonObject { put("node_kind", "goal"); put("title", title) })
        put("changed_at", "2026-09-20T00:00:00Z")
    }

    protected fun page(changes: List<JsonObject>, cursor: Long, more: Boolean = false) = buildJsonObject {
        put("changes", JsonArray(changes))
        put("next_cursor", cursor)
        put("has_more", more)
        put("server_time", "2026-09-20T00:00:00Z")
    }.toString()

    protected fun snapshot(changes: List<JsonObject>, cursor: Long) = buildJsonObject {
        put("changes", JsonArray(changes))
        put("next_cursor", cursor)
        put("server_time", "2026-09-20T00:00:00Z")
    }.toString()
}
