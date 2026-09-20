package com.dayforge.data.repository

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.dayforge.data.model.SyncProgress
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Real client persistence and wire encoding. Scripted transport is not server idempotency evidence. */
@RunWith(AndroidJUnit4::class)
class SyncDurabilityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val alias = "dayforge.test.sync.${UUID.randomUUID()}"
    private val files = listOf("tokens", "preferences").map {
        File(context.cacheDir, "sync-$it-${UUID.randomUUID()}.preferences_pb")
    }
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var database: HabitDatabase
    private lateinit var tokens: TokenManager
    private lateinit var repository: IncrementalSyncRepository
    private lateinit var networks: NetworkMonitor
    private lateinit var client: OkHttpClient
    private val pushes = CopyOnWriteArrayList<JsonObject>()
    private val cursors = CopyOnWriteArrayList<Long>()
    private val paths = CopyOnWriteArrayList<String>()
    private var server = "server-a"
    private var epoch = "epoch-a"
    private var onPush: (JsonObject) -> String = { acknowledge(it) }
    private var onPull: (Long) -> String = { page(emptyList(), it) }
    private var onBootstrap: () -> String = { throw IOException("Unexpected bootstrap") }

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
        tokens = TokenManager(stores[0], AndroidKeystoreTokenCipher(alias))
        val preferences = PreferencesManager(stores[1])
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
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

    private suspend fun reopen() {
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
        context.deleteDatabase("habit_database")
        files.forEach { it.delete() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        Unit
    }

    @Test fun bootstrap_persists_rows_and_cursor_without_echoing_server_data() = runBlocking {
        tokens.requireSyncBootstrap()
        val uuid = UUID.randomUUID().toString()
        onBootstrap = { snapshot(listOf(change(0, uuid, "Server goal")), 12) }
        repository.sync()
        reopen()
        assertEquals(12L, tokens.syncCursor.first())
        assertTrue(tokens.isSyncBootstrapped.first())
        assertEquals("Server goal", database.habitDao().getHabitByUuid(uuid)!!.name)
        assertEquals(1L, database.syncOutboxDao().getState("plan_node", uuid)!!.revision)
        assertEquals(0, database.syncOutboxDao().count())
        repository.sync()
        assertEquals(1, paths.count { it == "/api/v2/sync/bootstrap" })
        assertEquals(listOf(12L), cursors.toList())
    }

    @Test fun lost_response_replays_persisted_snapshot_before_uploading_a_newer_edit() = runBlocking {
        val habit = insertGoal("Original")
        val originalId = database.syncOutboxDao().getAll().single().operationId
        onPush = { throw IOException("Response lost after server commit") }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IOException)
        val prepared = database.syncOutboxDao().getAll().single()
        assertEquals(originalId, prepared.operationId)
        assertNotNull(prepared.attemptedAt)
        assertNotNull(prepared.payloadJson)
        reopen()
        assertEquals(prepared, database.syncOutboxDao().getAll().single())
        database.habitDao().update(habit.copy(name = "Newer edit"))
        onPush = { acknowledge(it, if (pushes.size == 2) "already_applied" else "applied") }
        repository.sync()
        assertEquals(3, pushes.size)
        assertEquals(pushes[0], pushes[1])
        val first = operation(pushes[0])
        val latest = operation(pushes[2])
        assertEquals(originalId, first.getValue("operation_id").jsonPrimitive.content)
        assertEquals("Original", first.getValue("payload").jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("Newer edit", latest.getValue("payload").jsonObject["title"]!!.jsonPrimitive.content)
        assertNotEquals(first["operation_id"], latest["operation_id"])
        reopen()
        assertEquals("Newer edit", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun interrupted_second_push_batch_reopens_with_only_unacknowledged_operations() = runBlocking {
        repeat(101) { insertGoal("Offline $it") }
        val calls = AtomicInteger()
        onPush = {
            if (calls.incrementAndGet() == 2) throw IOException("Second batch lost")
            acknowledge(it)
        }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IOException)
        reopen()
        val remaining = database.syncOutboxDao().getAll().single()
        assertEquals(operation(pushes[1])["operation_id"]!!.jsonPrimitive.content, remaining.operationId)
        repository.sync()
        assertEquals(listOf(100, 1, 1), pushes.map { it["operations"]!!.jsonArray.size })
        assertEquals(pushes[1], pushes[2])
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun partial_acknowledgement_removes_only_the_matching_operation() = runBlocking {
        insertGoal("First")
        insertGoal("Second")
        onPush = { request ->
            val firstOnly = JsonObject(request + ("operations" to JsonArray(listOf(request["operations"]!!.jsonArray.first()))))
            acknowledge(firstOnly)
        }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IllegalStateException)
        reopen()
        val remaining = database.syncOutboxDao().getAll().single()
        val unacknowledged = pushes.single()["operations"]!!.jsonArray[1].jsonObject
        assertEquals(unacknowledged["operation_id"]!!.jsonPrimitive.content, remaining.operationId)
        assertNull(database.syncOutboxDao().getState("plan_node", remaining.entityUuid))
        onPush = { acknowledge(it) }
        repository.sync()
        assertEquals(unacknowledged, operation(pushes.last()))
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun acknowledgement_without_revision_keeps_durable_request_for_retry() = runBlocking {
        insertGoal("Keep me")
        onPush = { request ->
            val response = json.parseToJsonElement(acknowledge(request)).jsonObject
            buildJsonObject {
                put("results", JsonArray(response.getValue("results").jsonArray.map {
                    JsonObject(it.jsonObject - "revision")
                }))
            }.toString()
        }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IllegalStateException)
        reopen()
        assertEquals(1, database.syncOutboxDao().count())
        onPush = { acknowledge(it) }
        repository.sync()
        assertEquals(pushes[0], pushes[1])
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun interrupted_pull_resumes_after_the_last_persisted_page() = runBlocking {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val pageTwo = AtomicInteger()
        onPull = { cursor ->
            when (cursor) {
                0L -> page(listOf(change(5, first, "Page one")), 5, true)
                5L -> {
                    if (pageTwo.incrementAndGet() == 1) throw IOException("Page two lost")
                    page(listOf(change(9, second, "Page two")), 9)
                }
                else -> throw IOException("Unexpected cursor $cursor")
            }
        }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IOException)
        reopen()
        assertEquals(5L, tokens.syncCursor.first())
        assertEquals("Page one", database.habitDao().getHabitByUuid(first)!!.name)
        assertNull(database.habitDao().getHabitByUuid(second))
        repository.sync()
        reopen()
        assertEquals(listOf(0L, 5L, 5L), cursors.toList())
        assertEquals(9L, tokens.syncCursor.first())
        assertEquals("Page two", database.habitDao().getHabitByUuid(second)!!.name)
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun failed_merge_rolls_back_the_whole_page_and_does_not_advance_cursor() = runBlocking {
        val first = UUID.randomUUID().toString()
        val broken = change(2, UUID.randomUUID().toString(), "Broken").let {
            JsonObject(it + ("entity_type" to JsonPrimitive("unknown_entity")))
        }
        onPull = { page(listOf(change(1, first, "Must roll back"), broken), 2) }
        onBootstrap = { throw IOException("Recovery unavailable") }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IOException)
        reopen()
        assertEquals(0L, tokens.syncCursor.first())
        assertNull(database.habitDao().getHabitByUuid(first))
        assertNull(database.syncOutboxDao().getState("plan_node", first))
        assertEquals(0, database.syncOutboxDao().count())
        // Suppression must have been restored even when the merge threw.
        insertGoal("New offline change")
        assertEquals(1, database.syncOutboxDao().count())
        onPull = { page(listOf(change(1, first, "Recovered")), 1) }
        repository.sync()
        reopen()
        assertEquals(1L, tokens.syncCursor.first())
        assertEquals("Recovered", database.habitDao().getHabitByUuid(first)!!.name)
    }

    @Test fun invalid_pagination_cannot_change_the_durable_cursor() = runBlocking {
        tokens.saveSyncCursor(7)
        onPull = { page(emptyList(), 7, true) }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IllegalStateException)
        reopen()
        assertEquals(7L, tokens.syncCursor.first())
        assertEquals(listOf(7L), cursors.toList())
        assertFalse(paths.contains("/api/v2/sync/bootstrap"))
    }

    @Test fun replaying_a_committed_page_keeps_one_entity_and_no_outgoing_echo() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        onPull = { page(listOf(change(5, uuid, "Replay safely")), 5) }
        repository.sync()
        val saved = database.habitDao().getHabitByUuid(uuid)!!
        // Model the Room-commit/DataStore-cursor gap. This is page replay, not a process-kill test.
        tokens.saveSyncCursor(0)
        reopen()
        repository.sync()
        reopen()
        val rows = database.habitDao().getAllHabitsOnce()
        assertEquals(1, rows.size)
        assertEquals(saved.id, rows.single().id)
        assertEquals(uuid, rows.single().uuid)
        assertEquals("Replay safely", rows.single().name)
        assertEquals(5L, tokens.syncCursor.first())
        assertEquals(listOf(0L, 0L), cursors.toList())
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun epoch_change_with_pending_intent_preserves_database_and_replica_metadata() = runBlocking {
        val habit = insertGoal("Unsynced")
        val pending = database.syncOutboxDao().getAll()
        tokens.saveSyncCursor(7)
        epoch = "epoch-b"
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is SyncEpochChangedException)
        reopen()
        assertEquals("epoch-a", tokens.syncEpoch.first())
        assertEquals(7L, tokens.syncCursor.first())
        assertEquals(pending, database.syncOutboxDao().getAll())
        assertEquals("Unsynced", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(listOf("/api/v2/system/identity"), paths.toList())
    }

    @Test fun clean_epoch_replaces_old_replica_and_persists_new_high_water_mark() = runBlocking {
        val old = UUID.randomUUID().toString()
        val fresh = UUID.randomUUID().toString()
        onPull = { page(listOf(change(7, old, "Old replica")), 7) }
        repository.sync()
        epoch = "epoch-b"
        onBootstrap = { snapshot(listOf(change(0, fresh, "New replica")), 12) }
        val progress = mutableListOf<SyncProgress>()
        repository.sync(progress::add)
        reopen()
        assertTrue(progress.contains(SyncProgress.Recovering))
        assertEquals("epoch-b", tokens.syncEpoch.first())
        assertEquals(12L, tokens.syncCursor.first())
        assertNull(database.habitDao().getHabitByUuid(old))
        assertEquals("New replica", database.habitDao().getHabitByUuid(fresh)!!.name)
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun another_server_is_rejected_before_registration_or_upload() = runBlocking {
        insertGoal("Private local change")
        val pending = database.syncOutboxDao().getAll()
        server = "server-b"
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is ServerIdentityMismatchException)
        reopen()
        assertEquals("server-a", tokens.serverInstanceId.first())
        assertEquals(pending, database.syncOutboxDao().getAll())
        assertEquals(listOf("/api/v2/system/identity"), paths.toList())
    }

    private suspend fun insertGoal(title: String): HabitEntity {
        val entity = HabitEntity(name = title, habitType = HabitType.GOAL,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily)
        return entity.copy(id = database.habitDao().insert(entity))
    }

    private fun operation(request: JsonObject) = request.getValue("operations").jsonArray.single().jsonObject

    private fun acknowledge(request: JsonObject, status: String = "applied"): String = buildJsonObject {
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

    private fun change(sequence: Long, uuid: String, title: String) = buildJsonObject {
        put("sequence", sequence)
        put("entity_type", "plan_node")
        put("entity_uuid", uuid)
        put("operation", "upsert")
        put("revision", 1)
        put("payload", buildJsonObject { put("node_kind", "goal"); put("title", title) })
        put("changed_at", "2026-09-20T00:00:00Z")
    }

    private fun page(changes: List<JsonObject>, cursor: Long, more: Boolean = false) = buildJsonObject {
        put("changes", JsonArray(changes))
        put("next_cursor", cursor)
        put("has_more", more)
        put("server_time", "2026-09-20T00:00:00Z")
    }.toString()

    private fun snapshot(changes: List<JsonObject>, cursor: Long) = buildJsonObject {
        put("changes", JsonArray(changes))
        put("next_cursor", cursor)
        put("server_time", "2026-09-20T00:00:00Z")
    }.toString()
}
