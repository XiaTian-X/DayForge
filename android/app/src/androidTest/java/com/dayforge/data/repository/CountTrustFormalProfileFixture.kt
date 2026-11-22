package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.HabitWithStats
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.dashboard.DashboardHabitListBuilder
import io.mockk.*
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.ExternalResource

/** Formal route/correctness and persistent-qualification evidence only, not a latency benchmark.
 * Every historical receipt comes from a real original sender/ACK against loopback HTTP.
 */
abstract class CountTrustFormalProfileFixture : NextObjectEditorFixture() {
    private val trace = FormalTrace(::countAddObservedQuery)
    @Volatile private var credentialCostProbe: CountTrustCredentialCostProbe? = null
    private val alias = "dayforge.test.formal.profile.${UUID.randomUUID()}"
    private val evidenceName = "formal-${UUID.randomUUID()}.jsonl"
    @get:Rule(order = -3) val keystoreCleanup = object : ExternalResource() {
        override fun after() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    }
    internal open fun sourceWindowNativeSpy(): SourceWindowNativeSpy? = null
    // Test-only scheduling/fault observation; production exposes no planning or SQL callback.
    internal open fun countAddObservedQuery(query: String) = Unit
    init { storage.isolatedBuilder = { context ->
        sourceWindowNativeSpy()?.build(context, trace) ?: HabitDatabaseProvider.build(context, trace)
    } }
    override fun tokenCipher(): TokenCipher {
        val real = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = real.encrypt(value)
            override fun decrypt(value: String): String? {
                trace.decrypted()
                val probe = credentialCostProbe
                return if (probe == null) real.decrypt(value) else probe.decrypt { real.decrypt(value) }
            }
        }
    }
    private val json = Json { encodeDefaults = true }
    private lateinit var zone: ZoneId
    private lateinit var today: LocalDate
    private lateinit var serverInstant: String
    private lateinit var rows: List<HabitEntity>
    private lateinit var http: NextSyncHttp
    private lateinit var server: MaterialSocketServer
    private val births = linkedMapOf<String, ChallengeBirth>()
    private var advanced: ChallengeRoundRecord? = null
    private val eventResults = linkedMapOf<String, NextSyncOperationResult>()
    private val historicalRequests = mutableListOf<String>()
    private val historicalUuids = linkedSetOf<String>()
    private val observations = linkedMapOf<String, Observation<*>>()
    private var functionalTerminal = false

    private data class Qualification(val ownerLive: Boolean, val contract: Int, val rootPresent: Boolean,
        val generation: Long, val dirty: Long, val qualified: Boolean, val allCountsEligible: Boolean,
        val commonHealthy: Boolean)
    private data class Observation<T>(val value: T, val sql: FormalTrace.Sample, val qualification: Qualification)
    private data class Home(val batch: DashboardReadBatch, val cards: List<HabitWithStats>)
    private data class CompactHome(val batch: DashboardProjectionBatch, val cards: List<HabitWithStats>)
    private data class Graph(val counts: CountHistoryReader, val habits: HabitRepository,
        val service: CheckInService, val dashboard: DashboardReadRepository,
        val list: DashboardHabitListBuilder, val prompt: LinkedMetricCoordinator)

    private fun record(kind: String, body: JsonObjectBuilder.() -> Unit) {
        val value = buildJsonObject {
            put("kind", kind); put("scenario", "formal_v5_initial0"); put("performance_measured", false)
            put("ui_pixels_measured", false); put("background_competition", false)
            body()
        }.toString()
        check(value.toByteArray(Charsets.UTF_8).size < 3900)
        val directory = File(app.filesDir, "dayforge-formal-profile-evidence").also { check(it.isDirectory || it.mkdirs()) }
        FileOutputStream(File(directory, evidenceName), true).use { out ->
            out.write((value + "\n").toByteArray(Charsets.UTF_8)); out.fd.sync()
        }
        Log.i("CountTrustFormalProfile", value)
    }
    private fun JsonObjectBuilder.qualification(value: Qualification) {
        put("owner_present_not_retired", value.ownerLive); put("challenge_contract", value.contract)
        put("root_present", value.rootPresent); put("generation", value.generation); put("dirty", value.dirty)
        put("root_qualified", value.qualified); put("both_counts_eligible", value.allCountsEligible)
        put("global_common", value.commonHealthy)
    }
    private fun initial(uuid: String) = ChallengeRoundRecord(initialChallengeRoundHead(uuid), null, null, null)
    private fun metadata() = ChallengeMetadata(1, rows.map { row ->
        val history = listOf(initial(row.uuid)) + listOfNotNull(advanced?.takeIf { it.head.activityUuid == row.uuid })
        ChallengeCheckpoint(history.last().head, history)
    }, births.values.toList())
    private fun canonical(type: String, uuid: String, body: JsonObject, created: String = serverInstant) =
        SyncV2Change(0, type, uuid, "upsert", 1, JsonObject(body + mapOf(
            "public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(created), "updated_at" to JsonPrimitive(serverInstant),
            "deleted_at" to JsonNull)), serverInstant, id(4))

    private suspend fun openFormal(history: Int, metricCount: Int = 1, historyFromBootstrap: Boolean = false, countdown: Boolean = false,
        compactGrowth: Boolean = false) {
        require(metricCount in 1..2)
        require(if (compactGrowth) history in setOf(80, 730) && historyFromBootstrap else history == 0 || history == 8)
        require(!historyFromBootstrap || history == 8 || compactGrowth)
        zone = ZoneId.systemDefault(); today = LocalDate.now(zone)
        serverInstant = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
        val rowsPerDayForCompactGrowth = 2L
        // Only the new formal growth diagnostic extends the declared creation horizon;
        // all original zero/eight-history fixtures keep their exact five-day initial plan.
        val born = today.minusDays(if (compactGrowth) history / rowsPerDayForCompactGrowth + 1L else 5L).atStartOfDay(zone).toInstant()
        val first = habit.copy(id = 0, name = "Formal daily A", targetValue = if (countdown) 1 else 3, isCountdown = countdown, targetCycles = null,
            createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(), isActive = true,
            planMetadata = requireNotNull(habit.planMetadata).copy(creationTimestamp = born.toString(), timezone = zone.id))
        val second = first.copy(uuid = id(4801), name = "Formal daily B", targetValue = 7)
        val remoteMetric = metric.copy(id = 0, createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli())
        rows = listOf(first, second)
        val remoteMetrics = listOf(remoteMetric) + if (metricCount == 2)
            listOf(remoteMetric.copy(uuid = id(9801), name = "Second actual prompt metric", unit = "cm")) else emptyList()
        val links = remoteMetrics.mapIndexed { index, value -> HabitMetricLinkEntity(habitId = 0, habitUuid = first.uuid, metricId = 0,
            metricUuid = value.uuid, promptOnComplete = true, uuid = id(9800 + index * 10),
            createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli()) }
        // F2's pre-existing history comes through the real formal server-bootstrap merger.
        // It is accepted server history, not fabricated local origins/transmissions/receipts.
        // The original fixture default still performs every historical local SEND and ACK.
        val bootstrapFacts = if (!historyFromBootstrap) emptyList() else (0 until history).map { index ->
            val row = rows[index % rows.size]
            val date = today.minusDays(index / rows.size.toLong() + 1)
            val at = date.atTime(12, 0).atZone(zone).toInstant().toString()
            val uuid = id(9900 + index)
            births[uuid] = ChallengeBirth("activity_event", uuid, initialChallengeRoundHead(row.uuid))
            historicalUuids += uuid
            val body = buildJsonObject {
                put("activity_uuid", row.uuid); put("event_type", "count_snapshot"); put("value", 1)
                put("count_policy", CountDayPolicy(row.targetValue, row.isCountdown).toJson())
                put("occurred_at", at); put("local_date", date.toString()); put("timezone", zone.id)
                put("note", "accepted server bootstrap history"); put("source_type", "app")
                put("source_device_id", id(4)); put("external_event_id", JsonNull)
                put("metadata", buildJsonObject {}); put("received_at", serverInstant)
                for (field in listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "reverts_event_uuid"))
                    put(field, JsonNull)
            }
            canonical("activity_event", uuid, body, at)
        }
        // The original shared fixture starts nonempty. Clearing retires its owner; reopening
        // and rebuilding every business-DB object prevents a false profile diagnosis.
        db.clearAllData(); storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        val configured = channel { input ->
            if (input.path.endsWith("/identity")) {
                assertNull(input.headers["authorization"])
                MaterialSocketServer.Reply(buildJsonObject {
                    put("server_instance_id", id(2)); put("sync_epoch", id(3)); put("protocol_version", 5)
                    put("capabilities", JsonArray(listOf(JsonPrimitive("sync.read")))); put("server_time", serverInstant)
                }.toString().toByteArray())
            } else {
                assertEquals("5", input.headers["x-dayforge-protocol"])
                assertEquals(id(2), input.headers["x-dayforge-server-instance"])
                assertEquals(id(3), input.headers["x-dayforge-sync-epoch"])
                when (input.path) {
                    "/api/v2/devices/register" -> {
                        val request = json.decodeFromString<DeviceRegisterRequest>(input.body.toString(Charsets.UTF_8))
                        assertEquals(5, request.protocolVersion)
                        MaterialSocketServer.Reply(json.encodeToString(DeviceResponse(id(4), request.installationId,
                            "android", isPrimaryEditor = true, structuralEditEnabled = true, capabilityRevision = 1,
                            capabilities = caps.toList(), lastSeenAt = serverInstant)).toByteArray())
                    }
                    "/api/v2/sync/rounds/bootstrap" -> {
                        assertTrue(input.target.contains("challenge_contract=1"))
                        val meta = metadata()
                        val changes = rows.map { canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it), born.toString()) } +
                            remoteMetrics.map { canonical("metric", it.uuid, NextStructureMapper.writeMetric(it), born.toString()) } +
                            links.map { canonical("activity_metric_link", it.uuid, SyncV2Mapper.link(it), born.toString()) } + bootstrapFacts
                        MaterialSocketServer.Reply(json.encodeToString(RoundSyncBootstrapResponse(changes, 20,
                            serverInstant, emptyList(), 1, meta.checkpoints, meta.births)).toByteArray())
                    }
                    "/api/v2/sync/rounds/changes" -> {
                        assertTrue(input.target.contains("challenge_contract=1"))
                        val meta = metadata()
                        MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(emptyList(), 20,
                            false, serverInstant, 1, meta.checkpoints, meta.births)).toByteArray())
                    }
                    "/api/v2/sync/rounds/push" -> roundReply(input)
                    else -> error("FORMAL_PROFILE_UNEXPECTED_ROUTE:${input.path}")
                }
            }
        }
        http = configured.first; server = configured.second
        val monitor = mockk<NetworkMonitor>()
        coEvery { monitor.localNetworks() } returns emptyList()
        val legacy = mockk<IncrementalSyncRepository>(relaxed = true)
        val resolver = EndpointResolver(monitor, preferences, tokens, json, SelectedNetworkTransport())
        BusinessSyncRepository(legacy, resolver, db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences)).sync()
        coVerify(exactly = 0) { legacy.syncForAuthentication(any(), any(), any()) }
        assertEquals(1, server.requests.count { it.path == "/api/v2/devices/register" })
        assertEquals(1, server.requests.count { it.path == "/api/v2/sync/rounds/bootstrap" })
        assertEquals(1, db.nextSyncStateDao().rows().single().challengeContract)
        assertNotNull(requireNotNull(creator.captureForNavigation()).rounds)
        rows = rows.map { requireNotNull(db.habitDao().getHabitByUuid(it.uuid)) }
        habit = rows.first(); metric = requireNotNull(db.metricDao().getMetricByUuid(remoteMetric.uuid))
        assertTrue(rows.all { it.habitType == com.dayforge.data.model.HabitType.COUNTING && it.targetCycles == null })
        assertEquals(0, count("next_restart_materializations")); assertEquals(0, count("next_restart_plan_proofs"))
        preferences.setNeverAskAgain(local(), habit.uuid, false)
        record("activation") { put("formal_discovery_registration_bootstrap", true); put("initial_generation", 0)
            put("finite_target", false); put("history_requested", history); put("zone", zone.id); put("today", today.toString())
            put("history_source", if (historyFromBootstrap) "server_bootstrap" else "real_local_send_ack") }
        if (!historyFromBootstrap) repeat(history) { index -> seedOne(index) }
        assertEquals(if (historyFromBootstrap) 0 else history, count("next_acceptances"))
        assertEquals(history, count("next_challenge_births"))
        assertEquals(0, count("sync_outbox")); assertEquals(history, historicalUuids.size)
        if (historyFromBootstrap) {
            assertTrue(historicalRequests.isEmpty())
            assertEquals(0, count("next_request_origins")); assertEquals(0, count("next_transmissions"))
            assertEquals(history, db.syncOutboxDao().getStatesForType("activity_event").size)
            for (uuid in historicalUuids) {
                assertNotNull(db.completionDao().getCompletionByUuid(uuid))
                val shadow = requireNotNull(db.syncOutboxDao().getState("activity_event", uuid))
                assertFalse(shadow.deleted); assertEquals(syncPayloadHash(requireNotNull(shadow.payloadJson)), shadow.payloadHash)
            }
        }
        record("seed_terminal") { put("history", history); put("real_http_count_acks", if (historyFromBootstrap) 0 else history)
            put("history_source", if (historyFromBootstrap) "server_bootstrap" else "real_local_send_ack")
            put("server_bootstrap_facts", bootstrapFacts.size)
            put("past_days_per_habit", history / 2); put("bootstrap_server_time", serverInstant)
            put("fixture_capacity_estimate", if (historyFromBootstrap)
                "F2 budget: replace ~78s repeated seed ACK work by one real bootstrap; complete new chain/oracle retained; unmeasured, ordinary150s unchanged"
                else "60-100s +50s uncertainty for first method; unmeasured, ordinary150s unchanged") }
    }
    private fun roundReply(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
        val operation = request.operations.single(); val context = request.contexts.single()
        when (operation.entityType) {
            "activity_event" -> {
                val activity = operation.payload.getValue("activity_uuid").jsonPrimitive.content
                assertTrue(metadata().checkpoints.single { it.head.activityUuid == activity }.records.any { it.head == context.head })
            }
            "metric_observation" -> {
                assertNull(context.head); assertFalse(context.legacyInitial); assertTrue(context.affectedHeads.isEmpty())
                assertEquals("upsert", operation.action); assertNull(operation.baseRevision)
            }
            else -> error("FORMAL_PROFILE_UNEXPECTED_OPERATION:${operation.entityType}")
        }
        val existing = eventResults[operation.operationId]
        val result = existing?.copy(status = "already_applied") ?: run {
            if (operation.entityType == "activity_event")
                births[operation.entityUuid] = ChallengeBirth("activity_event", operation.entityUuid, requireNotNull(context.head))
            // Reuse the independent wire serializer, replacing its old fixture timestamp in
            // every server-owned instant. Business occurred_at/local_date/timezone stay original.
            val receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
            val response = successReply(input) { canonical -> JsonObject(canonical + mapOf(
                "created_at" to JsonPrimitive(receivedAt), "updated_at" to JsonPrimitive(receivedAt),
                "received_at" to JsonPrimitive(receivedAt))) }
            json.decodeFromString<NextSyncPushResponse>(response.bytes.toString(Charsets.UTF_8)).results.single()
                .also { eventResults[operation.operationId] = it }
        }
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1,
            meta.checkpoints, meta.births)).toByteArray())
    }
    private suspend fun seedOne(index: Int) {
        assertSameDay()
        val row = rows[index % 2]; val day = today.minusDays(index / 2L + 1)
        val at = day.atTime(12, 0).atZone(zone).toInstant()
        val original = CompletionEntity(habitId = row.id, habitUuid = row.uuid, uuid = id(9900 + index),
            date = day.toDisplayMillis(zone), value = 1, actualCompletedAt = at.toEpochMilli(),
            createdAt = at.toEpochMilli(), recordedTimezone = zone.id, recordedLocalDate = day.toString())
        val producer = producer()
        producer.writeRounds(producer.captureRounds()) {
            NextCountDayStore(db).capture(row, original); db.completionDao().insert(original)
        }
        val queued = db.syncOutboxDao().getAll().single()
        assertEquals(original.uuid, queued.entityUuid)
        val intent = requireNotNull(roundOperationIntent(originalIntent(queued).intentJson))
        assertEquals(initialChallengeRoundHead(row.uuid), intent.context.head)
        val requests = sender(http)
        val delivery = requireNotNull(requests.sendOperation(access(), queued.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivery))
        db.withTransaction { requests.requireAcceptedCountInTransaction(access(), queued.operationId) }
        historicalRequests += queued.operationId; historicalUuids += original.uuid
    }
    private fun graph(): Graph {
        val counts = CountHistoryReader(db, tokens, sessions); val checks = CheckHistoryReader(db, tokens, sessions)
        val widgets = WidgetFactReader(db, tokens, sessions, preferences, counts, checks)
        val habits = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
            nextObjectEditor = editor, countHistoryReader = counts, checkHistoryReader = checks,
            recurringHabitReader = RecurringHabitReader(db, tokens, sessions, editor, counts, checks),
            widgetFactReader = widgets, countActions = CountActionReader(db, tokens, sessions))
        val metrics = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao(),
            nextObjectEditor = editor, widgetFactReader = widgets)
        val status = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao(), counts, checks),
            db.completionDao(), db.timeLogDao(), countHistoryReader = counts, checkHistoryReader = checks)
        return Graph(counts, habits, CheckInService(habits, db.completionDao(), db.timeLogDao(), CountActionGate(tokens)),
            DashboardReadRepository(db, tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions), counts, checks),
            DashboardHabitListBuilder(status), LinkedMetricCoordinator(app, preferences, metrics, habits))
    }
    private suspend fun qualification(): Qualification = sessions.exclusive { db.withTransaction {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db)); assertFalse(owner.isRetired)
        val contract = db.nextSyncStateDao().rows().single().challengeContract
        val access = requireNotNull(tokens.localCoreWriteAccess())
        owner.maintenance(db) {
            val sql = db.openHelper.writableDatabase
            CountTrustSchema.requireControl(sql)
            val control = sql.query("SELECT generation,dirty FROM count_trust_control WHERE id=1").use {
                assertTrue(it.moveToFirst()); it.getLong(0) to it.getLong(1)
            }
            val header = CountTrustSeal.read(sql)
            val opened = CountTrustKernel(db, tokens, sessions).open(access)
            Qualification(true, contract, header != null, control.first, control.second,
                opened != null && control.second == 0L && header?.generation == control.first,
                opened != null && rows.all(opened::eligible), opened?.index?.get("global_common") == JsonPrimitive(true))
        }
    } }
    private suspend fun <T> observe(name: String, body: suspend () -> T): Observation<T> {
        assertSameDay(); trace.begin()
        var ended = false
        try {
            val value = body(); val sql = trace.end(); ended = true
            val q = qualification()
            record("stage") { put("stage", name); put("returned", true); qualification(q)
                put("query_count", sql.original.queries); put("original_history_proofs", sql.original.fullProofSentinels)
                put("source_full_scans", sql.sourceScans)
                put("scoped_lifetime_detail_scans", sql.original.scopedLifetimeDetailScans)
                put("ack_global_shadow_scans", sql.shadowScans); put("common_fact_uuid_scans", sql.uuidScans)
                put("fresh_decryptions", sql.decryptions); put("unmatched_callback_transactions", sql.original.unmatched) }
            return Observation(value, sql, q).also { observations[name] = it }
        } finally { if (!ended) {
            val sql = trace.end()
            record("stage") { put("stage", name); put("returned", false)
                put("original_history_proofs", sql.original.fullProofSentinels); put("ack_global_shadow_scans", sql.shadowScans) }
        } }
    }
    private suspend fun home(graph: Graph): Home {
        val batch = graph.dashboard.read(ZonedDateTime.now(zone)); val cards = graph.list.project(batch)
        assertTrue(graph.dashboard.publish(batch) {})
        assertEquals(rows.map { it.id }.toSet(), batch.counts.keys)
        assertEquals(rows.map { it.id }.toSet(), cards.map { it.habit.id }.toSet())
        assertTrue(batch.completions.isEmpty())
        for (card in cards) {
            assertNull(card.countHistory); assertSame(batch.counts.getValue(card.habit.id), card.countSummary)
            assertEquals(metadata().checkpoints.single { it.head.activityUuid == card.habit.uuid }.head, card.countSummary!!.roundHead)
        }
        return Home(batch, cards)
    }
    private suspend fun compactHome(graph: Graph): CompactHome {
        val batch = graph.dashboard.readProjection(ZonedDateTime.now(zone)); val cards = graph.list.project(batch)
        assertTrue(graph.dashboard.publish(batch) {})
        assertEquals(rows.map { it.id }.toSet(), batch.counts.keys)
        assertEquals(rows.map { it.id }.toSet(), cards.map { it.habit.id }.toSet())
        assertTrue(batch.completions.isEmpty())
        for (card in cards) {
            assertNull(card.countHistory); assertNull(card.countSummary); assertSame(batch.counts.getValue(card.habit.id), card.countDisplay)
            assertEquals(metadata().checkpoints.single { it.head.activityUuid == card.habit.uuid }.head, card.countDisplay!!.roundHead)
        }
        return CompactHome(batch, cards)
    }
    private suspend fun contents() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun measuredContents(stage: String): JsonObject {
        val began = SystemClock.elapsedRealtimeNanos()
        val image = contents()
        val elapsed = SystemClock.elapsedRealtimeNanos() - began
        var blobBytes = 0L
        image.getValue("tables").jsonObject.values.forEach { rows -> rows.jsonArray.forEach { row ->
            row.jsonObject.values.forEach { raw ->
                val cell = raw.jsonObject
                if (cell.getValue("sqlite_type").jsonPrimitive.int == android.database.Cursor.FIELD_TYPE_BLOB)
                    blobBytes += cell.getValue("value").jsonPrimitive.content.length / 2L
            }
        } }
        record("raw_snapshot_diagnostic") { put("stage", stage); put("elapsed_ns", elapsed); put("blob_bytes", blobBytes) }
        return image
    }
    private fun assertSameDay() { assertEquals(zone, ZoneId.systemDefault()); assertEquals(today, LocalDate.now(zone)) }
    private fun assertReplayMarkerOnly(before: JsonObject, after: JsonObject, header: CountTrustSeal.Header?) {
        val tables = before.getValue("tables").jsonObject
        val row = tables.getValue("count_trust_control").jsonArray.single().jsonObject
        fun integer(name: String): Long {
            val value = row.getValue(name).jsonObject
            assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER, value.getValue("sqlite_type").jsonPrimitive.int)
            return value.getValue("value").jsonPrimitive.long
        }
        assertEquals(1L, integer("id")); assertTrue(integer("dirty") in 0L..1L)
        val storage = row.getValue("storageId").jsonObject
        assertEquals(android.database.Cursor.FIELD_TYPE_STRING, storage.getValue("sqlite_type").jsonPrimitive.int)
        assertTrue(isContractUuid(storage.getValue("value").jsonPrimitive.content))
        fun updated(name: String, value: Long) = JsonObject(row.getValue(name).jsonObject + ("value" to JsonPrimitive(value)))
        val expectedRow = JsonObject(row + mapOf("generation" to updated("generation", Math.addExact(integer("generation"), 1L)),
            "dirty" to updated("dirty", if (header == null) 1L else 0L)))
        // Actual formal replay still executes one same-value challenge marker UPDATE.
        // Preserve every other typed control member, all schema/rows and all four other local tables.
        val expectedTables = tables.toMutableMap()
        expectedTables["count_trust_control"] = JsonArray(listOf(expectedRow))
        if (header != null) {
            // The same semantic directory is retained; only the exact committed generation,
            // revision and corresponding MAC may advance. No arbitrary root/node changes.
            val expectedHeader = CountTrustSeal().prepare(header.binding, header.schemaHash, header.root,
                Math.addExact(header.revision, 1L), Math.addExact(header.generation, 1L))
            val oldRoot = tables.getValue("count_trust_root").jsonArray.single().jsonObject
            fun changed(name: String, value: JsonPrimitive) = JsonObject(oldRoot.getValue(name).jsonObject + ("value" to value))
            val root = JsonObject(oldRoot + mapOf(
                "generation" to changed("generation", JsonPrimitive(expectedHeader.generation)),
                "revision" to changed("revision", JsonPrimitive(expectedHeader.revision)),
                "mac" to changed("mac", JsonPrimitive(expectedHeader.mac().joinToString("") { "%02x".format(it.toInt() and 255) }))))
            expectedTables["count_trust_root"] = JsonArray(listOf(root))
        }
        val expected = JsonObject(before + ("tables" to JsonObject(expectedTables)))
        assertEquals(expected, after)
    }

    internal suspend fun requireFormalPersistentReuse() {
        openFormal(8)
        var graph = graph()
        val first = observe("first_home") { home(graph) }
        assertEquals(8L, first.value.batch.counts.values.sumOf { it.totalQuantity })
        assertTrue(first.value.batch.counts.values.all { it.todayQuantity == 0L })
        val warm = observe("warm_home") { home(graph) }
        assertEquals(first.value.batch.counts, warm.value.batch.counts)
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        graph = graph()
        val persisted = observe("same_file_reopen_home") { home(graph) }
        assertEquals(warm.value.batch.counts, persisted.value.batch.counts)
        val displayed = requireNotNull(persisted.value.batch.counts.getValue(habit.id).actionIdentity)
        val promptRequest = graph.prompt.captureCompletionPrompt()
        val saved = observe("actual_count_service") { graph.service.incrementCount(app, habit.id, displayed) }
        val result = saved.value as? CheckInResult.Success ?: error("FORMAL_COUNT_NOT_SAVED")
        assertTrue(result.recorded); assertFalse(result.goalReached)
        val source = requireNotNull(result.followUp); val uuid = requireNotNull(source.claim.completionUuid)
        val postSave = observe("post_save_common_home") { home(graph) }
        assertEquals(1L, postSave.value.batch.counts.getValue(habit.id).todayQuantity)
        val queued = db.syncOutboxDao().getAll().single()
        assertEquals(uuid, queued.entityUuid)
        assertEquals(initialChallengeRoundHead(habit.uuid), requireNotNull(roundOperationIntent(originalIntent(queued).intentJson)).context.head)
        val requests = sender(http)
        val sent = observe("actual_round_send") { requireNotNull(requests.sendOperation(access(), queued.operationId)) }
        val ack = observe("actual_round_ack") { requests.acceptOperation(sent.value) }
        assertEquals(NextOperationAcceptance.COMMITTED, ack.value)
        val terminal = observe("terminal_common_home") { home(graph) }
        assertEquals(postSave.value.batch.counts, terminal.value.batch.counts)
        val prompt = observe("necessary_prompt") {
            graph.prompt.showCompletionPrompt(source, promptRequest)
            requireNotNull(graph.prompt.postCheckInState.value)
        }
        assertEquals(uuid, prompt.value.factPrompt?.completion?.claim?.completionUuid)
        assertEquals(listOf(metric.id), prompt.value.linkedMetrics.map { it.metricId })
        graph.prompt.closePrompt(prompt.value); assertNull(graph.prompt.postCheckInState.value)
        assertEquals(9, count("next_acceptances")); assertEquals(9, count("next_challenge_births"))
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("metric_logs"))
        val original = measuredContents("before_original_oracle")
        // Separate original-oracle window both calibrates full-proof observation and keeps it
        // out of the warm consumer counts. Never require kernel.details to run this oracle.
        val originalHistories = linkedMapOf<Long, CountHistory>()
        val oracle = observe("independent_original_oracle") { sessions.exclusive { db.withTransaction {
            for (request in historicalRequests + queued.operationId) requests.requireAcceptedCountInTransaction(access(), request)
            val full = RecurringCompletionEvidenceReader(db, tokens, sessions)
            rows.forEach { seeded ->
                val current = terminal.value.batch.habits.single { it.uuid == seeded.uuid }
                val evidence = full.readInTransaction(current, today)
                val expected = CountHistory(today, evidence.policies,
                    evidence.completions.groupBy { it.businessDate }.mapValues { (_, facts) -> facts.sumOf { it.value.toLong() } },
                    evidence.policies[today] ?: CountDayPolicy(current.targetValue, current.isCountdown),
                    evidence.unknownDates, evidence.completions, evidence.roundHead, evidence.countIdentity)
                assertEquals(terminal.value.batch.counts.getValue(current.id), expected.summary)
                assertEquals(initialChallengeRoundHead(current.uuid), expected.roundHead)
                originalHistories[current.id] = expected
            }
        } } }
        assertTrue("Original reader must calibrate the callback sentinel", oracle.sql.original.fullProofSentinels >= 2)
        assertEquals(original, measuredContents("after_original_oracle"))
        for (row in rows) {
            val details = graph.counts.read(requireNotNull(db.habitDao().getHabitById(row.id)), today)
            assertEquals(terminal.value.batch.counts.getValue(row.id), details.summary)
            assertEquals(originalHistories.getValue(row.id), details)
        }
        record("tail_step") { put("stage", "exact_details_complete") }
        val replayRoot = db.withTransaction { CountTrustSeal.read(db.openHelper.writableDatabase) }
        assertEquals(NextOperationAcceptance.REPLAYED, requests.acceptOperation(sent.value))
        record("tail_step") { put("stage", "replay_accepted") }
        assertReplayMarkerOnly(original, measuredContents("after_replay"), replayRoot)
        assertEquals(historicalUuids + uuid, db.completionDao().getAllCompletionsOnce().map { it.uuid }.toSet())
        assertEquals(9, server.requests.count { it.path == "/api/v2/sync/rounds/push" })
        assertFalse(server.requests.any { it.path == "/api/v2/sync/push" })
        assertSameDay(); functionalTerminal = true
        val freshAuthorityObserved = observations.filterKeys { it != "independent_original_oracle" }.values.all { it.sql.decryptions > 0 }
        val failures = buildList {
            if (!freshAuthorityObserved) add("fresh_keystore_authority_observation_missing")
            for (name in listOf("first_home", "warm_home", "same_file_reopen_home", "terminal_common_home")) {
                val q = observations.getValue(name).qualification
                if (!q.qualified || !q.allCountsEligible || !q.commonHealthy) add("$name:formal_persistent_qualification_missing")
            }
            for (name in listOf("warm_home", "same_file_reopen_home", "post_save_common_home", "terminal_common_home"))
                if (observations.getValue(name).sql.original.fullProofSentinels != 0L) add("$name:repeated_full_history_proof")
            if (ack.sql.shadowScans != 0L || ack.sql.uuidScans != 0L) add("actual_round_ack:repeated_global_fact_audit")
        }
        record("terminal_before_requirement_assert") {
            put("functional_terminal_verified", functionalTerminal); put("original_oracle", true); put("real_historical_acks", 8)
            put("real_new_ack", 1); put("replay_exact_marker_control_delta", 1); put("all_other_replay_raw_unchanged", true); put("necessary_prompt_verified", true)
            put("same_batch_home_cards", true); put("fresh_authority_observed", freshAuthorityObserved)
            put("persistent_requirement_passed", failures.isEmpty()); put("failures", JsonArray(failures.map(::JsonPrimitive)))
            put("evidence_file", evidenceName); put("ordinary_case_timeout_ms", 150000)
        }
        assertTrue("FORMAL_V5_PERSISTENT_STATE_NOT_REUSED; functional terminal/JSON retained: ${failures.joinToString()}", failures.isEmpty())
    }

    private data class MetricPilotPhase(val name: String, val start: Long, val end: Long,
        val returned: Boolean, var sample: FormalTrace.Sample?, val failure: String? = null)
    private var metricPilotPhases: MutableList<MetricPilotPhase>? = null

    /** Budget-discovery instrumentation only. No added DB call, root read, callback Job,
     * scheduler or business computation. Existing observed phases keep their existing trace.
     */
    private suspend fun <T> metricPilotPhase(name: String, captureTrace: Boolean = true,
        body: suspend () -> T): T {
        val phases = metricPilotPhases ?: return body()
        val start = SystemClock.elapsedRealtimeNanos()
        if (captureTrace) trace.begin()
        val value = try { body() } catch (error: Throwable) {
            val end = SystemClock.elapsedRealtimeNanos()
            phases += MetricPilotPhase(name, start, end, false, if (captureTrace) trace.end() else null, error.javaClass.name)
            throw error
        }
        val end = SystemClock.elapsedRealtimeNanos()
        phases += MetricPilotPhase(name, start, end, true, if (captureTrace) trace.end() else null)
        return value
    }
    private fun metricPilotAttach(name: String, sample: FormalTrace.Sample) {
        val phases = metricPilotPhases ?: return
        val phase = phases.single { it.name == name }
        check(phase.sample == null); phase.sample = sample
    }

    /** Exactly the already verified bootstrap8 F2 work/terminal, plus passive stage clocks and
     * existing callback counters. No extra reopen, replay, native snapshot or SQL/footer check.
     * This new diagnostic case has a predeclared 300s capacity; existing ordinary safety
     * cases stay 150s. This discovers stage budgets; it does not certify product latency.
     */
    internal suspend fun formalMetricEightStageBudgetPilot() {
        check(metricPilotPhases == null)
        val phases = arrayListOf<MetricPilotPhase>(); metricPilotPhases = phases
        val start = SystemClock.elapsedRealtimeNanos(); var completed = false
        try {
            actualFormalMetricPromptChain(8)
            completed = true
        } finally {
            val end = SystemClock.elapsedRealtimeNanos(); metricPilotPhases = null
            for (phase in phases) record("formal_f2_budget_pilot_stage") {
                put("history", 8); put("history_source", "server_bootstrap"); put("stage", phase.name)
                put("start_ns", phase.start); put("end_ns", phase.end); put("elapsed_ns", phase.end - phase.start)
                put("returned", phase.returned); put("failure_class", phase.failure?.let(::JsonPrimitive) ?: JsonNull)
                val sample = phase.sample
                put("query_trace_available", sample != null)
                if (sample != null) {
                    put("query_callback_queries", sample.original.queries); put("query_callback_writes", sample.original.writes)
                    put("original_history_proofs", sample.original.fullProofSentinels)
                    put("begin_callbacks", sample.original.beginCount); put("end_callbacks", sample.original.endCount)
                    put("unmatched_transactions", sample.original.unmatched)
                    put("begin_to_end_callback_max_ns", sample.original.callbackSpanMax)
                    put("begin_to_confirmed_completion_upper_bound_max_ns", sample.original.completionUpperBoundMax)
                    put("fresh_decrypt_attempts", sample.decryptions); put("global_shadow_scans", sample.shadowScans)
                    put("global_uuid_scans", sample.uuidScans)
                }
                put("performance_gate_evaluated", false); put("ui_pixels_measured", false); put("percentile", false)
                put("background_competition", false); put("database_lock_occupancy_measured", false)
                credentialCostProbe?.let { put("real_credential_decrypt_cost", it.summary(phase.start, phase.end)) }
            }
            record("formal_f2_budget_pilot_terminal") {
                put("history", 8); put("history_source", "server_bootstrap")
                put("complete_original_chain_and_oracle", completed); put("original_workflow_ns", end - start)
                put("captured_stage_sum_ns", phases.sumOf { it.end - it.start })
                put("residual_fixture_and_uninstrumented_ns", end - start - phases.sumOf { it.end - it.start })
                put("ordinary_safety_case_ceiling_ms", 150000); put("diagnostic_case_ceiling_ms", 300000)
                put("added_cold_reopens", 0); put("added_oracle_calls", 0)
                put("performance_gate_evaluated", false); put("cold_recovery_measured", false)
                put("pending_budgets", "save500ms_result_prompt700ms_cold2s_resource150ms")
                put("harness_ack_precedes_prompt", true); put("ui_pixels_measured", false); put("percentile", false)
                put("background_competition", false); put("precise_metric_commit_measured", false)
                put("test_setup_cleanup_and_report_flush_outside_workflow_timer", true)
            }
        }
    }

    /** Complete compact consumers with the existing passive phase observer. All preparation,
     * full original terminal oracle and cleanup remain in the declared JUnit case capacity.
     * These are individual component samples, never a UI/P95/throughput acceptance gate. */
    internal suspend fun compactFormalStageBudgetPilot(history: Int) {
        require(history in setOf(8, 80, 730))
        check(metricPilotPhases == null)
        val phases = arrayListOf<MetricPilotPhase>(); metricPilotPhases = phases
        val start = SystemClock.elapsedRealtimeNanos(); var completed = false
        try {
            actualCompactFormalMetricPromptChain(history)
            completed = true
        } finally {
            val end = SystemClock.elapsedRealtimeNanos(); metricPilotPhases = null
            for (phase in phases) record("compact_f2_budget_pilot_stage") {
                put("history", history); put("history_source", "server_bootstrap"); put("stage", phase.name)
                put("start_ns", phase.start); put("end_ns", phase.end); put("elapsed_ns", phase.end - phase.start)
                put("returned", phase.returned); put("failure_class", phase.failure?.let(::JsonPrimitive) ?: JsonNull)
                val sample = phase.sample
                put("query_trace_available", sample != null)
                if (sample != null) {
                    put("query_callback_queries", sample.original.queries); put("query_callback_writes", sample.original.writes)
                    put("original_history_proofs", sample.original.fullProofSentinels)
                    put("begin_callbacks", sample.original.beginCount); put("end_callbacks", sample.original.endCount)
                    put("unmatched_transactions", sample.original.unmatched)
                    put("begin_to_end_callback_max_ns", sample.original.callbackSpanMax)
                    put("begin_to_confirmed_completion_upper_bound_max_ns", sample.original.completionUpperBoundMax)
                    put("fresh_decrypt_attempts", sample.decryptions); put("global_shadow_scans", sample.shadowScans)
                    put("global_uuid_scans", sample.uuidScans)
                }
                put("performance_gate_evaluated", false); put("ui_pixels_measured", false); put("percentile", false)
                put("background_competition", false); put("database_lock_occupancy_measured", false)
                credentialCostProbe?.let { put("real_credential_decrypt_cost", it.summary(phase.start, phase.end)) }
            }
            record("compact_f2_budget_pilot_terminal") {
                put("history", history); put("history_source", "server_bootstrap")
                put("complete_original_chain_and_oracle", completed); put("compact_workflow_ns", end - start)
                put("captured_stage_sum_ns", phases.sumOf { it.end - it.start })
                put("residual_fixture_and_uninstrumented_ns", end - start - phases.sumOf { it.end - it.start })
                put("ordinary_safety_case_ceiling_ms", 150000); put("diagnostic_case_ceiling_ms", when (history) { 8 -> 300000; 80 -> 900000; else -> 1800000 })
                put("added_cold_reopens", 0); put("added_oracle_calls", 1)
                put("performance_gate_evaluated", false); put("cold_recovery_measured", false)
                put("pending_budgets", "save500ms_result_prompt700ms_cold2s_resource150ms")
                put("harness_ack_precedes_prompt", true); put("ui_pixels_measured", false); put("percentile", false)
                put("background_competition", false); put("precise_metric_commit_measured", false)
                put("test_setup_cleanup_and_report_flush_outside_workflow_timer", true)
                put("production_compact_dashboard_and_prompt", true); put("old_detail_oracle_retained", true)
                put("original_creation_horizon_extended_only_for_growth", history > 8)
            }
        }
    }

    /** Complete verified bootstrap8 F2 chain first; additional diagnostics are read-only and
     * cannot establish authority or replace the original terminal oracle. Not a cold/P95/UI test.
     */
    internal suspend fun formalMetricEightOpenFixedCostDiagnostic() {
        check(credentialCostProbe == null)
        val probe = CountTrustCredentialCostProbe(); credentialCostProbe = probe
        try {
            formalMetricEightStageBudgetPilot()
            val addedBlockStart = SystemClock.elapsedRealtimeNanos()
            val beforeSnapshotStart = SystemClock.elapsedRealtimeNanos()
            val before = contents()
            val beforeSnapshotEnd = SystemClock.elapsedRealtimeNanos()
            trace.begin()
            val start = SystemClock.elapsedRealtimeNanos()
            val outcome = try { Result.success(CountTrustOpenCostProbe(db, tokens, sessions, rows.toList()).run()) }
                catch (failure: Throwable) { Result.failure<List<JsonObject>>(failure) }
            val sample = trace.end()
            val breakdown = outcome.getOrThrow() // Includes cancellation; never converted into a successful diagnosis.
            val end = SystemClock.elapsedRealtimeNanos()
            val afterSnapshotStart = SystemClock.elapsedRealtimeNanos()
            val after = contents()
            val afterSnapshotEnd = SystemClock.elapsedRealtimeNanos()
            assertEquals("Read-only diagnostic must preserve ALL raw durable contents", before, after)
            val qualificationStart = SystemClock.elapsedRealtimeNanos()
            val finalQualification = qualification()
            val qualificationEnd = SystemClock.elapsedRealtimeNanos()
            assertTrue(finalQualification.qualified)
            val addedBlockEnd = SystemClock.elapsedRealtimeNanos()
            assertEquals("The added warm diagnostic must not requalify by a full proof", 0L, sample.original.fullProofSentinels)
            assertTrue(sample.decryptions >= 6)
            for (item in breakdown) record("formal_open_fixed_cost_component") {
                put("history", 8); put("history_source", "server_bootstrap")
                item.forEach { (key, value) -> put(key, value) }
                put("production_internal_instrumentation", false)
                put("primitive_replay_not_original_open_internal_timing", true)
                put("percentile", false); put("performance_gate_evaluated", false)
            }
            record("formal_open_fixed_cost_terminal") {
                put("history", 8); put("original_full_chain_and_oracle_retained", true)
                put("all_raw_durable_contents_unchanged", true); put("fresh_authority_each_entry_and_end", true)
                put("actual_transaction_end_receipts_checked", true); put("probe_only_elapsed_ns", end - start)
                put("added_read_only_block_elapsed_ns", addedBlockEnd - addedBlockStart)
                put("before_raw_snapshot_ns", beforeSnapshotEnd - beforeSnapshotStart)
                put("after_raw_snapshot_ns", afterSnapshotEnd - afterSnapshotStart)
                put("post_probe_qualification_ns", qualificationEnd - qualificationStart)
                put("added_block_excludes_original_chain_and_new_report_flush", true)
                put("probe_excludes_two_raw_snapshots_and_post_qualification", true)
                put("public_callback_queries", sample.original.queries); put("public_callback_writes", sample.original.writes)
                put("original_history_proofs", sample.original.fullProofSentinels)
                put("fresh_decrypt_attempts_in_probe", sample.decryptions); put("probe_decrypt_cost", probe.summary(start, end))
                put("post_probe_qualification_decrypt_cost", probe.summary(qualificationStart, qualificationEnd))
                put("added_read_only_block_decrypt_cost", probe.summary(addedBlockStart, addedBlockEnd))
                put("actual_open_passes_in_probe", 2); put("primitive_replay_passes_in_probe", 1)
                put("extra_post_qualification_kernel_open_calls", 1); put("actual_open_calls_after_chain_total", 3)
                put("fresh_authority_calls_in_probe", 6); put("extra_post_qualification_fresh_authority_calls", 1)
                put("fresh_authority_calls_after_chain_total", 7)
                put("precise_commit_or_database_lock_time", false); put("cold_recovery_measured", false)
                put("performance_gate_evaluated", false); put("percentile", false)
            }
        } finally { credentialCostProbe = null }
    }

    /** Real formal production journey; no latency or UI-pixel claim. The prompt actually saves
     * both values through LinkedMetricCoordinator, then each actual metric delivery is accepted.
     */
    internal suspend fun actualFormalMetricPromptChain(history: Int) {
        metricPilotPhase("setup_formal_bootstrap") { openFormal(history, metricCount = 2, historyFromBootstrap = history > 0) }
        val graph = graph(); val first = metricPilotPhase("first_home_and_qualification") { home(graph) }
        assertEquals(history.toLong(), first.batch.counts.values.sumOf { it.totalQuantity })
        val request = graph.prompt.captureCompletionPrompt()
        val saved = metricPilotPhase("first_count_service") {
            graph.service.incrementCount(app, habit.id, requireNotNull(first.batch.counts.getValue(habit.id).actionIdentity))
        } as CheckInResult.Success
        assertTrue(saved.recorded)
        val follow = requireNotNull(saved.followUp)
        val countQueue = metricPilotPhase("first_count_source_lookup") { db.syncOutboxDao().getAll().single() }
        val requests = sender(http)
        val countDelivery = metricPilotPhase("first_count_send") { requireNotNull(requests.sendOperation(access(), countQueue.operationId)) }
        assertEquals(NextOperationAcceptance.COMMITTED, metricPilotPhase("first_count_ack") { requests.acceptOperation(countDelivery) })
        val before = metricPilotPhase("first_count_post_ack_home") { home(graph) }
        metricPilotPhase("first_count_necessary_prompt_after_ack") { graph.prompt.showCompletionPrompt(follow, request) }
        val state = requireNotNull(graph.prompt.postCheckInState.value)
        val prompt = requireNotNull(state.factPrompt)
        assertEquals(2, state.linkedMetrics.size)
        val input = state.linkedMetrics.mapIndexed { index, info ->
            com.dayforge.ui.components.MetricValueInput(info.metricId, 21.125 + index, "actual formal metric $index")
        }
        val local = observe("actual_multi_metric_prompt_save") {
            metricPilotPhase("actual_multi_metric_prompt_save", captureTrace = false) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    graph.prompt.recordMetricValues(habit.id, input, expectedFactPrompt = prompt)
                }
            }
        }
        metricPilotAttach("actual_multi_metric_prompt_save", local.sql)
        assertTrue(local.value); assertEquals(input, prompt.submitted)
        assertEquals(2, count("metric_logs")); assertEquals(2, count("sync_outbox"))
        assertTrue("Actual prompt submission must maintain qualification", local.qualification.qualified)
        assertEquals("Only the two original entry source audits remain", 2L, local.sql.sourceScans)
        assertEquals(0L, local.sql.original.fullProofSentinels)
        assertTrue(local.sql.decryptions > 0)
        val queued = metricPilotPhase("actual_metric_sources_lookup") { db.syncOutboxDao().getAll() }
        assertEquals(setOf("metric_log"), queued.map { it.recordType }.toSet())
        assertEquals(before.batch.counts, metricPilotPhase("post_metric_home") { home(graph) }.batch.counts)
        assertTrue(metricPilotPhase("same_live_prompt_repeat") {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                graph.prompt.recordMetricValues(habit.id, input, expectedFactPrompt = prompt)
            }
        })
        assertEquals("Same live submitted prompt must not append again", 2, count("metric_logs"))
        assertEquals(2, count("sync_outbox"))
        val accepted = arrayListOf<String>()
        for ((index, row) in queued.withIndex()) {
            val formal = metricPilotPhase("metric_${index}_original_lookup") { requireNotNull(roundOperationIntent(originalIntent(row).intentJson)) }
            assertEquals("metric_observation", formal.operation.entityType)
            assertNull(formal.context.head); assertFalse(formal.initialCreation)
            assertTrue(formal.context.affectedHeads.isEmpty()); assertFalse(formal.context.legacyInitial)
            assertNull(formal.goalChildFrontier); assertNull(formal.restartFrontier); assertNull(formal.countPlanFrontier)
            assertNull(db.nextStructuralCausalDao().dependency(row.operationId))
            val delivery = metricPilotPhase("metric_${index}_send") { requireNotNull(requests.sendOperation(access(), row.operationId)) }
            val ack = observe("actual_formal_metric_ack_$index") {
                metricPilotPhase("actual_formal_metric_ack_$index", captureTrace = false) { requests.acceptOperation(delivery) }
            }
            metricPilotAttach("actual_formal_metric_ack_$index", ack.sql)
            assertEquals(NextOperationAcceptance.COMMITTED, ack.value)
            assertTrue(ack.qualification.qualified); assertEquals(0L, ack.sql.shadowScans); assertEquals(0L, ack.sql.uuidScans)
            assertEquals(0L, ack.sql.original.fullProofSentinels); assertTrue(ack.sql.decryptions > 0)
            assertEquals(NextOperationAcceptance.REPLAYED, metricPilotPhase("metric_${index}_replay") { requests.acceptOperation(delivery) })
            accepted += row.operationId
        }
        assertEquals(history + 1, count("next_challenge_births"))
        assertEquals(0, count("sync_outbox")); assertEquals(2, count("metric_logs"))
        val afterMetrics = metricPilotPhase("metric_acks_complete_home") { home(graph) }
        assertEquals(before.batch.counts, afterMetrics.batch.counts)
        graph.prompt.closePrompt(state)
        val nextRequest = graph.prompt.captureCompletionPrompt()
        val next = observe("count_after_actual_metric_acks") {
            metricPilotPhase("count_after_actual_metric_acks", captureTrace = false) {
                graph.service.incrementCount(app, habit.id, requireNotNull(afterMetrics.batch.counts.getValue(habit.id).actionIdentity))
            }
        }
        metricPilotAttach("count_after_actual_metric_acks", next.sql)
        val nextSuccess = next.value as CheckInResult.Success
        assertTrue(nextSuccess.recorded); assertTrue(next.qualification.qualified)
        assertEquals("Real COUNT after both metric ACKs uses the same entry-only source contract", 2L, next.sql.sourceScans)
        assertEquals(0L, next.sql.original.fullProofSentinels)
        val lastQueue = metricPilotPhase("next_count_source_lookup") { db.syncOutboxDao().getAll().single() }
        val lastDelivery = metricPilotPhase("next_count_send") { requireNotNull(requests.sendOperation(access(), lastQueue.operationId)) }
        assertEquals(NextOperationAcceptance.COMMITTED, metricPilotPhase("next_count_ack") { requests.acceptOperation(lastDelivery) })
        val terminal = metricPilotPhase("terminal_common_home") { home(graph) }
        assertEquals(2L, terminal.batch.counts.getValue(habit.id).todayQuantity)
        assertEquals(history + 2L, terminal.batch.counts.values.sumOf { it.totalQuantity })
        assertEquals(historicalUuids + countQueue.entityUuid + lastQueue.entityUuid,
            db.completionDao().getAllCompletionsOnce().map { it.uuid }.toSet())
        metricPilotPhase("next_count_necessary_prompt_after_ack") {
            graph.prompt.showCompletionPrompt(requireNotNull(nextSuccess.followUp), nextRequest)
        }
        val nextPrompt = requireNotNull(graph.prompt.postCheckInState.value)
        assertEquals(input.map { it.metricId }.toSet(), nextPrompt.linkedMetrics.map { it.metricId }.toSet())
        for (value in input) assertEquals(value.value, requireNotNull(db.metricLogDao().getLatestLog(value.metricId)).value, 0.0)
        graph.prompt.closePrompt(nextPrompt)
        val rawBefore = metricPilotPhase("terminal_native_before_oracle") { contents() }
        metricPilotPhase("terminal_original_oracle") { sessions.exclusive { db.withTransaction {
            for (id in historicalRequests + countQueue.operationId + lastQueue.operationId)
                requests.requireAcceptedCountInTransaction(access(), id)
            for (id in accepted) assertEquals("metric_observation", requests.recoveryAcceptedFactProof(access(), id).first.entityType)
            val full = RecurringCompletionEvidenceReader(db, tokens, sessions)
            for (row in rows) {
                val current = requireNotNull(db.habitDao().getHabitById(row.id))
                val original = full.readInTransaction(current, today)
                val expected = CountHistory(today, original.policies,
                    original.completions.groupBy { it.businessDate }.mapValues { (_, facts) -> facts.sumOf { it.value.toLong() } },
                    original.policies[today] ?: CountDayPolicy(current.targetValue, current.isCountdown),
                    original.unknownDates, original.completions, original.roundHead, original.countIdentity)
                assertEquals(expected.summary, terminal.batch.counts.getValue(row.id))
            }
        } } }
        assertEquals(rawBefore, metricPilotPhase("terminal_native_after_oracle") { contents() })
        assertFalse(server.requests.any { it.path == "/api/v2/sync/push" })
        assertTrue(metricPilotPhase("terminal_qualification_guard") { qualification() }.qualified)
        record("actual_metric_chain_terminal") {
            put("history", history); put("history_source", if (history > 0) "server_bootstrap" else "empty")
            put("historical_local_acks", 0); put("actual_prompt_values", 2); put("real_metric_acks", 2)
            put("count_after_metric_acks", true); put("metric_births", 0); put("metric_dependencies", 0)
            put("original_terminal_oracle", true); put("performance_measured", false)
        }
    }

    /** New command recovery scenario. Existing F2 methods and every original assertion
     * above remain unchanged. This method requires the complete v20 storage/bridge candidate;
     * merely merging the command core is not enough to admit this physical test.
     */
    internal suspend fun commandParentAndActualMetricGroupSurviveDiskReopen() {
        openFormal(0, metricCount = 2)
        val graph = graph(); val first = home(graph)
        val promptRequest = graph.prompt.captureCompletionPrompt()
        val saved = graph.service.incrementCount(app, habit.id,
            requireNotNull(first.batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        val parent = requireNotNull(saved.command)
        assertTrue(saved.recorded); assertTrue(parent.recorded)
        val source = requireNotNull(saved.followUp)
        assertEquals(source.claim.completionUuid, parent.factUuid)
        graph.prompt.showCompletionPrompt(source, promptRequest, command = parent)
        val state = requireNotNull(graph.prompt.postCheckInState.value)
        val prompt = requireNotNull(state.factPrompt)
        assertSame(parent, prompt.command); assertEquals(2, state.linkedMetrics.size)
        val inputs = state.linkedMetrics.mapIndexed { index, info ->
            com.dayforge.ui.components.MetricValueInput(info.metricId, 31.125 + index, "durable actual child $index")
        }
        assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            graph.prompt.recordMetricValues(habit.id, inputs, expectedFactPrompt = prompt)
        })
        val child = requireNotNull(prompt.submittedCommand)
        assertNotEquals(parent.commandId, child.commandId)
        assertEquals(2, count("metric_logs")); assertEquals(3, count("sync_outbox"))
        val store = CountCommandStore(db, tokens, sessions)
        val p = store.resolve(parent.committed.reference); val m = store.resolve(child.committed.reference)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, p.historical.status.delivery)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, m.historical.status.delivery)
        assertEquals(child.commandId, p.historical.status.child); assertEquals(parent.commandId, m.historical.status.parent)
        assertEquals(2, requireNotNull(m.historical.outcome).effects.size)
        val empty = store.discover(8); assertTrue(empty.complete); assertTrue(empty.commands.isEmpty())
        val beforeRepeat = commandNativeImage()
        assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            graph.prompt.recordMetricValues(habit.id, inputs, expectedFactPrompt = prompt)
        })
        assertTrue("Idempotent T2 cannot mutate the authenticated result/catalog", beforeRepeat == commandNativeImage())
        assertEquals(2, count("metric_logs")); assertEquals(3, count("sync_outbox"))
        val requests = sender(http); val queued = db.syncOutboxDao().getAll()
        val accepted = arrayListOf<String>()
        for (row in queued) {
            val original = requireNotNull(roundOperationIntent(originalIntent(row).intentJson))
            if (row.recordType == "metric_log") {
                assertEquals("metric_observation", original.operation.entityType)
                assertNull(original.context.head); assertTrue(original.context.affectedHeads.isEmpty())
                assertNull(db.nextStructuralCausalDao().dependency(row.operationId))
            }
            val sent = requireNotNull(requests.sendOperation(access(), row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(sent))
            assertEquals(NextOperationAcceptance.REPLAYED, requests.acceptOperation(sent))
            accepted += row.operationId
        }
        assertEquals(0, count("sync_outbox")); assertEquals(3, count("next_acceptances"))
        val terminal = home(graph)
        assertEquals(1L, terminal.batch.counts.getValue(habit.id).todayQuantity)
        val beforeOracle = contents(); val primaryBeforeOracle = commandNativeImage()
        sessions.exclusive { db.withTransaction {
            for (row in queued) if (row.recordType == "completion") requests.requireAcceptedCountInTransaction(access(), row.operationId)
                else assertEquals("metric_observation", requests.recoveryAcceptedFactProof(access(), row.operationId).first.entityType)
            val full = RecurringCompletionEvidenceReader(db, tokens, sessions)
            for (row in rows) {
                val current = requireNotNull(db.habitDao().getHabitById(row.id)); val original = full.readInTransaction(current, today)
                val expected = CountHistory(today, original.policies,
                    original.completions.groupBy { it.businessDate }.mapValues { (_, facts) -> facts.sumOf { it.value.toLong() } },
                    original.policies[today] ?: CountDayPolicy(current.targetValue, current.isCountdown),
                    original.unknownDates, original.completions, original.roundHead, original.countIdentity)
                assertEquals(expected.summary, terminal.batch.counts.getValue(row.id))
            }
        } }
        assertTrue(beforeOracle == contents()); assertTrue(primaryBeforeOracle == commandNativeImage())
        graph.prompt.closePrompt(state)
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val fresh = CountCommandStore(db, tokens, sessions)
        assertEquals(p.historical.outcome?.text(), fresh.resolve(parent.committed.reference).historical.outcome?.text())
        assertEquals(m.historical.outcome?.text(), fresh.resolve(child.committed.reference).historical.outcome?.text())
        val pending = fresh.discover(8); assertTrue(pending.complete); assertTrue(pending.commands.isEmpty())
        assertTrue("Recovery reads must not resign or repair any command row", primaryBeforeOracle == commandNativeImage())
        assertEquals(terminal.batch.counts, home(graph()).batch.counts)
        assertEquals(1, count("completions")); assertEquals(2, count("metric_logs")); assertEquals(0, count("sync_outbox"))
        assertFalse(server.requests.any { it.path == "/api/v2/sync/push" }); assertEquals(3, accepted.size)
        assertSameDay()
    }

    /** The new historical-delivery path after real rounds bootstrap and three SEND/ACKs.
     * The child uses the same private repository writer as the prepared prompt; deliberately
     * stop before the live coordinator's T2, close the form, then recover the original IDs.
     * This is backend delivery evidence, not a new form, actual UI acceptance, or PID kill. */
    internal suspend fun completedRoundsMetricGroupDeliversAfterActualAcks() {
        openFormal(0, metricCount = 2)
        val graph = graph(); val first = home(graph)
        val request = graph.prompt.captureCompletionPrompt()
        val saved = graph.service.incrementCount(app, habit.id,
            requireNotNull(first.batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        val parent = requireNotNull(saved.command); val source = requireNotNull(saved.followUp)
        assertTrue(saved.recorded); assertEquals(source.claim.completionUuid, parent.factUuid)
        val requests = sender(http)
        val countSource = db.syncOutboxDao().getAll().single()
        val countIntent = requireNotNull(roundOperationIntent(originalIntent(countSource).intentJson))
        assertEquals("activity_event", countIntent.operation.entityType)
        assertEquals(initialChallengeRoundHead(habit.uuid), countIntent.context.head)
        val countDelivery = requireNotNull(requests.sendOperation(access(), countSource.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(countDelivery))
        assertEquals(NextOperationAcceptance.REPLAYED, requests.acceptOperation(countDelivery))
        assertEquals(0, count("sync_outbox")); assertEquals(1, count("next_acceptances"))

        graph.prompt.showCompletionPrompt(source, request, command = parent)
        val state = requireNotNull(graph.prompt.postCheckInState.value)
        val prompt = requireNotNull(state.factPrompt)
        assertSame(parent, prompt.command); assertNotNull(prompt.completion)
        assertEquals(2, state.linkedMetrics.size)
        val targets = prompt.metrics.map { requireNotNull(it.value) }
        val values = targets.mapIndexed { index, target ->
            MetricValueDraft(target.id, 41.125 + index, "accepted rounds historical group $index")
        }
        val child = requireNotNull(creatingMetrics().recordValuesWithReceipt(values,
            authority = requireNotNull(prompt.metrics.first().authority), expectedMetrics = targets,
            promptHabit = prompt.habit, promptFact = prompt.source, promptCompletionSource = true,
            parentCommand = parent).command)
        assertNotEquals(parent.commandId, child.commandId)
        val beforeClose = contents(); graph.prompt.closePrompt(state)
        assertNull(graph.prompt.postCheckInState.value)
        assertTrue("Closing the original form must not deliver either command", beforeClose == contents())
        val initialStore = CountCommandStore(db, tokens, sessions)
        val parentBefore = initialStore.resolve(parent.committed.reference).historical
        val childBefore = initialStore.resolve(child.committed.reference).historical
        assertEquals(CountCommandRecords.Delivery.METRICS_COMMITTED, parentBefore.status.delivery)
        assertEquals(CountCommandRecords.Delivery.RESULT_PENDING, childBefore.status.delivery)
        assertEquals(CountCommandRecords.Execution.APPLIED, childBefore.status.execution)
        assertEquals(child.commandId, parentBefore.status.child); assertEquals(parent.commandId, childBefore.status.parent)
        assertEquals(2, requireNotNull(childBefore.outcome).effects.size)

        val metricSources = db.syncOutboxDao().getAll()
        assertEquals(2, metricSources.size); assertEquals(setOf("metric_log"), metricSources.map { it.recordType }.toSet())
        for (row in metricSources) {
            val original = requireNotNull(roundOperationIntent(originalIntent(row).intentJson))
            assertEquals("metric_observation", original.operation.entityType)
            assertNull(original.context.head); assertTrue(original.context.affectedHeads.isEmpty())
            assertFalse(original.context.legacyInitial); assertFalse(original.initialCreation)
            assertNull(db.nextStructuralCausalDao().dependency(row.operationId))
            val delivery = requireNotNull(requests.sendOperation(access(), row.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivery))
            assertEquals(NextOperationAcceptance.REPLAYED, requests.acceptOperation(delivery))
        }
        assertEquals(1, count("completions")); assertEquals(2, count("metric_logs"))
        assertEquals(0, count("sync_outbox")); assertEquals(3, count("next_acceptances"))
        assertEquals(1, count("next_challenge_births"))
        assertFalse(server.requests.any { it.path == "/api/v2/sync/push" })
        assertEquals(3, server.requests.count { it.path == "/api/v2/sync/rounds/push" })
        val afterAcks = contents(); val immutableCommands = commandNativeImage()
        sessions.exclusive { db.withTransaction {
            requests.requireAcceptedCountInTransaction(access(), countSource.operationId)
            for (row in metricSources) assertEquals("metric_observation",
                requests.recoveryAcceptedFactProof(access(), row.operationId).first.entityType)
            val current = requireNotNull(db.habitDao().getHabitById(habit.id))
            val original = RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(current, today)
            assertEquals(setOf(requireNotNull(parent.factUuid)), original.completions.map { it.uuid }.toSet())
            assertEquals(1L, original.completions.sumOf { it.value.toLong() })
            assertEquals(initialChallengeRoundHead(habit.uuid), original.roundHead)
        } }
        assertTrue("The original accepted-fact oracles are read-only", afterAcks == contents())
        assertTrue(immutableCommands == commandNativeImage())

        fun cell(value: Any?): JsonObject = buildJsonObject {
            put("sqlite_type", when (value) {
                null -> android.database.Cursor.FIELD_TYPE_NULL
                is String -> android.database.Cursor.FIELD_TYPE_STRING
                is ByteArray -> android.database.Cursor.FIELD_TYPE_BLOB
                is Int, is Long -> android.database.Cursor.FIELD_TYPE_INTEGER
                else -> error("Unexpected primary native type")
            })
            put("value", when (value) {
                null -> JsonNull
                is String -> JsonPrimitive(value)
                is ByteArray -> JsonPrimitive(replicaStorageBlobHex(value))
                is Int -> JsonPrimitive(value.toLong())
                is Long -> JsonPrimitive(value)
                else -> error("Unexpected primary native value")
            })
        }
        fun control(image: JsonObject) = image.getValue("tables").jsonObject
            .getValue("count_trust_control").jsonArray.single().jsonObject.also {
                assertEquals(setOf("__recovery_rowid", "id", "storageId", "generation", "dirty", "commandRootHash"), it.keys)
                assertEquals(cell(1L), it.getValue("id")); assertEquals(cell(1L), it.getValue("__recovery_rowid"))
            }
        suspend fun authenticatedContents(): Pair<JsonObject, CountCommandSeal.Authenticated> = db.withTransaction {
            val image = NextReplicaStorageProof(db).readInTransaction().contents
            val primary = CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase)
            val captured = image.getValue("tables").jsonObject.getValue("count_command_recovery_roots").jsonArray.single().jsonObject
            assertEquals(CountCommandNativeRows.ROOT_COLUMNS.toSet() + "__recovery_rowid", captured.keys)
            assertEquals(cell(1L), captured.getValue("__recovery_rowid"))
            CountCommandNativeRows.ROOT_COLUMNS.zip(CountCommandNativeRows.rootBindings(primary.native())).forEach { (name, value) ->
                assertTrue("Captured primary native cell changed: $name", captured.getValue(name) == cell(value))
            }
            assertEquals(cell(CountCommandRootAnchor.digest(primary.native())), control(image).getValue("commandRootHash"))
            image to primary
        }
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val store = CountCommandStore(db, tokens, sessions)
        val (beforeT2, oldRoot) = authenticatedContents()
        val preferencesBefore = dataStore.data.first().asMap().mapKeys { it.key.name }
        val proof = requireNotNull(store.completedMetricGroup(parent.committed.reference, preferences))
        assertEquals(parent.committed.reference, proof.parent); assertEquals(child.committed.reference, proof.child)
        assertEquals(2, proof.count); assertTrue(proof.pending); assertTrue(proof.preferenceReady)
        assertTrue("Historical proof cannot repair or resign", beforeT2 == contents())
        store.deliverCompletedMetricGroup(proof, preferences) { true }
        val (afterT2, newRoot) = authenticatedContents()
        assertEquals(2L, oldRoot.header.pending.count); assertEquals(0L, newRoot.header.pending.count)
        assertEquals(Math.addExact(oldRoot.header.revision, 2L), newRoot.header.revision)
        assertEquals(oldRoot.header.copy(catalog = newRoot.header.catalog, pending = newRoot.header.pending,
            revision = newRoot.header.revision), newRoot.header)
        val expectedControl = JsonObject(control(beforeT2) +
            ("commandRootHash" to cell(CountCommandRootAnchor.digest(newRoot.native()))))
        assertEquals(expectedControl, control(afterT2))
        val commands = CountCommandV20Schema.tables + "count_command_results"
        val expectedTables = beforeT2.getValue("tables").jsonObject.filterKeys { it !in commands } +
            ("count_trust_control" to JsonArray(listOf(expectedControl)))
        val actualTables = afterT2.getValue("tables").jsonObject.filterKeys { it !in commands }
        val changedTables = (expectedTables.keys + actualTables.keys).filter { expectedTables[it] != actualTables[it] }.sorted()
        assertTrue("T2 changed non-command native storage; tables=${changedTables.take(12)}",
            JsonObject(beforeT2 + ("tables" to JsonObject(expectedTables))) ==
                JsonObject(afterT2 + ("tables" to JsonObject(actualTables))))
        assertTrue("Historical T2 never clears an unrelated preference",
            preferencesBefore == dataStore.data.first().asMap().mapKeys { it.key.name })
        for ((reference, original) in listOf(parent.committed.reference to parentBefore, child.committed.reference to childBefore)) {
            val delivered = store.resolve(reference).historical
            assertEquals(original.intent.text(), delivered.intent.text())
            assertEquals(requireNotNull(original.outcome).text(), requireNotNull(delivered.outcome).text())
            assertEquals(CountCommandRecords.Execution.APPLIED, delivered.status.execution)
            assertEquals(CountCommandRecords.Delivery.DELIVERED, delivered.status.delivery)
            assertEquals(original.status.parent, delivered.status.parent); assertEquals(original.status.child, delivered.status.child)
        }
        val empty = store.discover(8); assertTrue(empty.complete); assertTrue(empty.commands.isEmpty())
        assertEquals(2, count("count_command_intents")); assertEquals(2, count("count_command_status"))
        assertEquals(2, count("count_command_results")); assertEquals(1, count("completions")); assertEquals(2, count("metric_logs"))
        val terminal = contents()
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val reopened = CountCommandStore(db, tokens, sessions)
        val repeat = requireNotNull(reopened.completedMetricGroup(child.committed.reference, preferences))
        assertFalse(repeat.pending); assertEquals(parent.committed.reference, repeat.parent); assertEquals(child.committed.reference, repeat.child)
        reopened.deliverCompletedMetricGroup(repeat, preferences) { true }
        assertTrue("Cold same-ID repeat changes no durable native cell", terminal == contents())
        assertTrue(preferencesBefore == dataStore.data.first().asMap().mapKeys { it.key.name })
        assertEquals(3, server.requests.count { it.path == "/api/v2/sync/rounds/push" })
        assertSameDay()
    }

    internal suspend fun originalNoTargetDecisionNeverDeletesALaterCountAfterReopen() {
        openFormal(0, metricCount = 1)
        val graph = graph(); val first = home(graph)
        val noop = graph.service.decrementCount(app, habit.id,
            requireNotNull(first.batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        assertFalse(noop.recorded)
        val receipt = requireNotNull(noop.command)
        assertFalse(receipt.recorded); assertNull(receipt.factUuid)
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
        val store = CountCommandStore(db, tokens, sessions)
        val terminal = store.resolve(receipt.committed.reference)
        assertEquals(CountCommandRecords.Capture.VERIFIED_NO_EFFECT, terminal.historical.intent.capture)
        assertEquals(CountCommandRecords.NoEffectReason.NO_CURRENT_COUNT, terminal.historical.outcome?.reason)
        val input = terminal.historical.intent.body().getValue("input").jsonObject
        assertFalse("No synthetic business time or allocation for the old no-target branch",
            input.keys.any { it in setOf("occurred_at", "timezone", "reserved_fact_uuid", "reserved_revert_uuid") })
        val added = graph.service.incrementCount(app, habit.id,
            requireNotNull(home(graph).batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        assertTrue(added.recorded)
        val addedUuid = requireNotNull(added.command).factUuid
        val before = contents(); val commandsBefore = commandNativeImage()
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val restored = CountCommandStore(db, tokens, sessions).resolve(receipt.committed.reference)
        assertEquals(terminal.historical.outcome?.text(), restored.historical.outcome?.text())
        assertTrue(before == contents()); assertTrue(commandsBefore == commandNativeImage())
        assertEquals(listOf(addedUuid), db.completionDao().getAllCompletionsOnce().map { it.uuid })
        assertEquals(1L, home(graph()).batch.counts.getValue(habit.id).todayQuantity)
        assertEquals(1, count("sync_outbox")); assertEquals(0, count("metric_logs")); assertSameDay()
    }

    internal suspend fun originalWindowCloseRetainsCommandButExplicitSkipPersistsT2() {
        openFormal(0, metricCount = 2)
        val graph = graph(); val first = home(graph); val httpBefore = server.requests.size
        val saved = graph.service.incrementCount(app, habit.id,
            requireNotNull(first.batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        val parent = requireNotNull(saved.command); val source = requireNotNull(saved.followUp)
        val store = CountCommandStore(db, tokens, sessions)
        val original = store.resolve(parent.committed.reference)
        val business = db.completionDao().getAllCompletionsOnce()
        val queued = db.syncOutboxDao().getAll()
        val origins = queued.map { originalIntent(it) }
        graph.prompt.showCompletionPrompt(source, graph.prompt.captureCompletionPrompt(), command = parent)
        val firstPrompt = requireNotNull(graph.prompt.postCheckInState.value)
        assertEquals(2, firstPrompt.linkedMetrics.size)
        val beforeClose = commandNativeImage()
        graph.prompt.closePrompt(firstPrompt)
        assertNull(graph.prompt.postCheckInState.value)
        assertTrue(beforeClose == commandNativeImage())
        assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING,
            store.resolve(parent.committed.reference).historical.status.delivery)
        assertEquals(listOf(parent.commandId), store.discover(8).commands.map { it.reference.commandId })

        graph.prompt.showCompletionPrompt(source, graph.prompt.captureCompletionPrompt(), command = parent)
        val displayed = requireNotNull(graph.prompt.postCheckInState.value)
        graph.prompt.skipPrompt(habit.id, expectedFactPrompt = requireNotNull(displayed.factPrompt))
        assertNull(graph.prompt.postCheckInState.value)
        val closed = store.resolve(parent.committed.reference)
        assertEquals(CountCommandRecords.Delivery.CLOSED, closed.historical.status.delivery)
        assertEquals(original.historical.outcome?.text(), closed.historical.outcome?.text())
        assertTrue(store.discover(8).complete); assertTrue(store.discover(8).commands.isEmpty())
        val afterSkip = commandNativeImage()
        store.markFollowUpClosed(parent.committed.reference, requireNotNull(parent.factUuid), habit.uuid)
        assertTrue("Same-ID unknown-T2 retry is read-only", afterSkip == commandNativeImage())
        assertEquals(business, db.completionDao().getAllCompletionsOnce()); assertEquals(queued, db.syncOutboxDao().getAll())
        assertEquals(origins, queued.map { originalIntent(it) }); assertEquals(0, count("metric_logs"))
        val shown = home(graph).batch.counts.getValue(habit.id)
        val full = RecurringCompletionEvidenceReader(db, tokens, sessions).read(requireNotNull(db.habitDao().getHabitById(habit.id)), today)
        assertEquals(full.completions.sumOf { it.value.toLong() }, shown.todayQuantity)
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val reopened = CountCommandStore(db, tokens, sessions)
        assertEquals(closed.historical.status, reopened.resolve(parent.committed.reference).historical.status)
        assertTrue(reopened.discover(8).commands.isEmpty())
        assertTrue(afterSkip == commandNativeImage())
        assertEquals(business, db.completionDao().getAllCompletionsOnce()); assertEquals(queued, db.syncOutboxDao().getAll())
        assertEquals(1L, home(graph()).batch.counts.getValue(habit.id).todayQuantity)
        assertEquals(0, count("metric_logs")); assertEquals(httpBefore, server.requests.size); assertSameDay()
    }

    /** A real durable child T0 survives a private native-T1 rollback. Corrected input may
     * supersede it only with complete unchanged-generation NoEffects, never a missing outbox.
     */
    internal suspend fun actualMetricResubmissionRequiresProvenUncommittedChild(mode: String) {
        require(mode in setOf("rolled_back", "live", "advanced"))
        openFormal(0, metricCount = 2)
        val graph = graph(); val first = home(graph)
        val saved = graph.service.incrementCount(app, habit.id,
            requireNotNull(first.batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        val parent = requireNotNull(saved.command); val source = requireNotNull(saved.followUp)
        graph.prompt.showCompletionPrompt(source, graph.prompt.captureCompletionPrompt(), command = parent)
        val state = requireNotNull(graph.prompt.postCheckInState.value); val prompt = requireNotNull(state.factPrompt)
        val definitions = prompt.metrics.map { requireNotNull(it.value) }
        val authority = requireNotNull(prompt.metrics.first().authority)
        val links = definitions.map { requireNotNull(db.habitMetricLinkDao().getLink(habit.id, it.id)) }
        val oldInput = CountCommandInputs.metrics(db, UUID.randomUUID().toString(), parent, authority,
            requireNotNull(tokens.localCoreWriteAccess()).capturedDeviceId, prompt.habit, prompt.source,
            definitions.map { MetricValueDraft(it.id, 2.0, "old uncommitted input") }, definitions, links,
            System.currentTimeMillis(), ZoneId.systemDefault().id)
        val store = CountCommandStore(db, tokens, sessions)
        val registered = store.registerChild(parent.committed.reference, oldInput)
        val ticket = requireNotNull(registered.ticket)
        val beforeNative = contents(); val oldRows = commandNativeImage()
        if (mode != "live") {
            var entered = false
            val fixed = IllegalStateException("COMMAND_TEST_ABORT_CHILD_BEFORE_BUSINESS")
            val failure = runCatching {
                store.producerTransaction<Unit>(ticket, requireNotNull(tokens.localCoreWriteAccess())) {
                    assertTrue(db.inTransaction()); entered = true; throw fixed
                }
            }.exceptionOrNull()
            assertTrue(entered); assertSame(fixed, failure)
            assertTrue("Actual failed T1 rolls back completely", beforeNative == contents())
            assertTrue(oldRows == commandNativeImage())
        }
        if (mode == "advanced") db.withTransaction {
            // Real same-value business DML advances the authoritative complete dirty counter.
            // It does not fabricate a metric outcome, source, or a new user action.
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
        }
        val immutableBeforeRetry = contents(); val oldCommands = commandNativeImage()
        val corrected = state.linkedMetrics.map { com.dayforge.ui.components.MetricValueInput(it.metricId, 7.25, "corrected explicit input") }
        val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            graph.prompt.recordMetricValues(habit.id, corrected, expectedFactPrompt = prompt)
        }
        if (mode == "advanced" || mode == "live") {
            assertFalse(success); assertNull(prompt.submittedCommand); assertNull(prompt.submitted)
            assertTrue(immutableBeforeRetry == contents()); assertTrue(oldCommands == commandNativeImage())
            assertEquals(0, count("metric_logs")); assertEquals(1, count("sync_outbox"))
            val current = store.resolve(registered.reference)
            assertEquals(CountCommandRecords.Execution.PREPARED, current.historical.status.execution)
            assertNull(current.historical.outcome)
            assertEquals(oldInput.commandId, store.resolve(parent.committed.reference).historical.status.child)
            if (mode == "live") {
                store.releaseUnadmitted(ticket)
                assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    graph.prompt.recordMetricValues(habit.id, corrected, expectedFactPrompt = prompt)
                })
            } else {
                assertEquals(2, store.discover(8).commands.size)
                assertSameDay(); return
            }
        } else assertTrue(success)
        val child = requireNotNull(prompt.submittedCommand)
        assertNotEquals(oldInput.commandId, child.commandId)
        val old = store.resolve(registered.reference)
        assertEquals(oldInput.text(), old.historical.intent.text()); assertNull(old.historical.outcome)
        assertEquals(CountCommandRecords.Execution.INVALIDATED, old.historical.status.execution)
        assertEquals(CountCommandRecords.Delivery.CLOSED, old.historical.status.delivery)
        assertEquals("EXPLICIT_RESUBMISSION_AFTER_COMPLETE_NO_EFFECTS", old.historical.status.reason)
        assertEquals(child.commandId, store.resolve(parent.committed.reference).historical.status.child)
        assertTrue(store.discover(8).commands.isEmpty()); assertEquals(2, count("metric_logs"))
        assertEquals(3, count("sync_outbox")); assertEquals(1, count("completions"))
        val result = store.resolve(child.committed.reference).historical
        assertEquals(CountCommandRecords.Execution.APPLIED, result.status.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, result.status.delivery)
        val current = home(graph).batch.counts.getValue(habit.id)
        val full = RecurringCompletionEvidenceReader(db, tokens, sessions).read(requireNotNull(db.habitDao().getHabitById(habit.id)), today)
        assertEquals(full.completions.sumOf { it.value.toLong() }, current.todayQuantity)
        val beforeReopen = commandNativeImage()
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val reopened = CountCommandStore(db, tokens, sessions)
        assertEquals(old.historical.status, reopened.resolve(registered.reference).historical.status)
        assertEquals(result.outcome?.text(), reopened.resolve(child.committed.reference).historical.outcome?.text())
        assertTrue(reopened.discover(8).commands.isEmpty()); assertTrue(beforeReopen == commandNativeImage()); assertSameDay()
    }

    internal suspend fun commandNeverAskDecisionNeedsCurrentProducerAndSurvivesColdReopen() {
        openFormal(0)
        val graph = graph(); val shown = home(graph)
        val request = graph.prompt.captureCompletionPrompt()
        val result = graph.service.incrementCount(app, habit.id,
            requireNotNull(shown.batch.counts.getValue(habit.id).actionIdentity)) as com.dayforge.data.model.CheckInResult.Success
        val receipt = requireNotNull(result.command); val source = requireNotNull(result.followUp)
        val store = CountCommandStore(db, tokens, sessions)
        assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING, store.resolve(receipt.committed.reference).historical.status.delivery)
        val current = graph.prompt.captureCompletionPrompt() // The earlier request is now stale.
        preferences.setNeverAskAgain(source.claim.session(), source.habit.uuid, true)
        fun cell(value: Any?): JsonObject = buildJsonObject {
            put("sqlite_type", when (value) {
                null -> android.database.Cursor.FIELD_TYPE_NULL
                is String -> android.database.Cursor.FIELD_TYPE_STRING
                is ByteArray -> android.database.Cursor.FIELD_TYPE_BLOB
                is Int, is Long -> android.database.Cursor.FIELD_TYPE_INTEGER
                else -> error("Unexpected primary native type")
            })
            put("value", when (value) {
                null -> JsonNull
                is String -> JsonPrimitive(value)
                is ByteArray -> JsonPrimitive(replicaStorageBlobHex(value))
                is Int -> JsonPrimitive(value.toLong())
                is Long -> JsonPrimitive(value)
                else -> error("Unexpected primary native value")
            })
        }
        fun control(image: JsonObject): JsonObject =
            image.getValue("tables").jsonObject.getValue("count_trust_control").jsonArray.single().jsonObject.also {
                assertEquals(setOf("__recovery_rowid", "id", "storageId", "generation", "dirty", "commandRootHash"), it.keys)
                assertEquals(cell(1L), it.getValue("__recovery_rowid")); assertEquals(cell(1L), it.getValue("id"))
            }
        suspend fun authenticatedContents(): Pair<JsonObject, CountCommandSeal.Authenticated> = db.withTransaction {
            val image = NextReplicaStorageProof(db).readInTransaction().contents
            val primary = CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase)
            val actual = image.getValue("tables").jsonObject.getValue("count_command_recovery_roots").jsonArray.single().jsonObject
            assertEquals(CountCommandNativeRows.ROOT_COLUMNS.toSet() + "__recovery_rowid", actual.keys)
            assertEquals(cell(1L), actual.getValue("__recovery_rowid"))
            CountCommandNativeRows.ROOT_COLUMNS.zip(CountCommandNativeRows.rootBindings(primary.native())).forEach { (name, value) ->
                assertTrue("Captured primary native cell changed: $name", actual.getValue(name) == cell(value))
            }
            assertEquals(cell(CountCommandRootAnchor.digest(primary.native())), control(image).getValue("commandRootHash"))
            image to primary
        }
        fun changedFields(expected: JsonObject, actual: JsonObject): String {
            val left = expected.getValue("tables").jsonObject; val right = actual.getValue("tables").jsonObject
            val changed = (left.keys + right.keys).filter { left[it] != right[it] }.sorted()
            val fields = changed.take(12).map { table ->
                val a = left[table]?.jsonArray.orEmpty(); val b = right[table]?.jsonArray.orEmpty()
                val rows = (0 until maxOf(a.size, b.size)).asSequence().filter { a.getOrNull(it) != b.getOrNull(it) }.take(3).map { i ->
                    val oldRow = a.getOrNull(i)?.jsonObject.orEmpty(); val newRow = b.getOrNull(i)?.jsonObject.orEmpty()
                    val keys = (oldRow.keys + newRow.keys).filter { oldRow[it] != newRow[it] }.sorted()
                    "$i:${keys.take(8).joinToString(",") { it.take(64) }}${if (keys.size > 8) ",..." else ""}"
                }.joinToString(";")
                "${table.take(64)}[${a.size}->${b.size}]{$rows}"
            }
            val top = (expected.keys + actual.keys).filter { it != "tables" && expected[it] != actual[it] }.sorted()
            return "top=${top.take(8)} changedTables=${changed.size} ${fields.joinToString(" ")}".take(4096)
        }
        val (before, rootBefore) = authenticatedContents(); val primaryBefore = commandNativeImage()
        graph.prompt.showCompletionPrompt(source, request, command = receipt)
        assertNull(graph.prompt.postCheckInState.value)
        assertTrue(before == contents()); assertTrue(primaryBefore == commandNativeImage())
        graph.prompt.showCompletionPrompt(source, current, command = receipt)
        assertNull(graph.prompt.postCheckInState.value)
        val (after, rootAfter) = authenticatedContents()
        assertEquals(Math.addExact(rootBefore.header.revision, 1L), rootAfter.header.revision)
        assertEquals(rootBefore.header.copy(catalog = rootAfter.header.catalog, pending = rootAfter.header.pending,
            revision = rootAfter.header.revision), rootAfter.header)
        assertEquals(1L, rootBefore.header.pending.count); assertEquals(0L, rootAfter.header.pending.count)
        val oldControl = control(before); val newControl = control(after)
        assertTrue("T2 changed business control: ${changedFields(before, after)}",
            oldControl.filterKeys { it != "commandRootHash" } == newControl.filterKeys { it != "commandRootHash" })
        val expectedControl = JsonObject(oldControl + ("commandRootHash" to cell(CountCommandRootAnchor.digest(rootAfter.native()))))
        assertEquals(expectedControl, newControl)
        val excluded = com.dayforge.data.local.CountCommandV20Schema.tables + "count_command_results"
        val expectedTables = before.getValue("tables").jsonObject.filterKeys { it !in excluded } +
            ("count_trust_control" to JsonArray(listOf(expectedControl)))
        val expected = JsonObject(before + ("tables" to JsonObject(expectedTables)))
        val actual = JsonObject(after + ("tables" to JsonObject(after.getValue("tables").jsonObject.filterKeys { it !in excluded })))
        // Only the exact authenticated anchor is normalized; original id/storage/g/dirty,
        // rowid, whole schema/version and every other non-command native cell still match.
        assertTrue("T2 changed non-command storage: ${changedFields(expected, actual)}", expected == actual)
        assertEquals(CountCommandRecords.Delivery.CLOSED, store.resolve(receipt.committed.reference).historical.status.delivery)
        assertTrue(store.discover(8).commands.isEmpty())
        val closedPrimary = commandNativeImage()
        graph.prompt.showCompletionPrompt(source, current, command = receipt)
        assertTrue(closedPrimary == commandNativeImage())
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        assertEquals(CountCommandRecords.Delivery.CLOSED,
            CountCommandStore(db, tokens, sessions).resolve(receipt.committed.reference).historical.status.delivery)
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); assertEquals(0, count("metric_logs"))
        sessions.exclusive { db.withTransaction {
            val originalSource = db.syncOutboxDao().getAll().single()
            assertEquals(originalIntent(originalSource), NextOriginalRequestProofStore(db).liveOrigin(access(), NEXT_OPERATION, originalSource.operationId))
            val row = requireNotNull(db.habitDao().getHabitById(habit.id))
            assertEquals(1L, RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(row, today)
                .completions.sumOf { it.value.toLong() })
        } }
        assertEquals(1L, home(graph()).batch.counts.getValue(habit.id).todayQuantity); assertSameDay()
    }

    /** Stable ID was supplied by a known entry; this exercises the real typed Widget
     * adapter without asserting that Android task handoff or process death has been tested.
     */
    internal suspend fun widgetCommandKnownIdSurvivesReopenWithoutAnotherBusinessWrite() {
        openFormal(0)
        val graph = graph(); home(graph)
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val original = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions)).read(current).claim
        val commandId = UUID.randomUUID().toString()
        val saved = graph.service.widgetCountActionWithReceipt(app, original, "increment", commandId)
        val receipt = requireNotNull(saved.current.command)
        assertEquals(commandId, receipt.commandId); assertTrue(receipt.recorded)
        assertNotNull(saved.current.followUp); assertEquals(receipt.factUuid, saved.current.followUp?.claim?.completionUuid)
        assertEquals(receipt.factUuid, saved.completionSource?.claim?.completionUuid)
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("next_request_origins"))
        assertEquals(1, count("count_command_intents")); assertEquals(1, count("count_command_results"))
        val stored = CountCommandStore(db, tokens, sessions).resolve(receipt.committed.reference)
        assertEquals(original, CountCommandInputs.restoredWidget(stored.historical.intent.body().getValue("input").jsonObject))
        assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING, stored.historical.status.delivery)
        val before = contents(); val primaryBefore = commandNativeImage()
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val fresh = graph()
        repeat(2) {
            val recovered = fresh.service.resumeWidgetCountCommand(app, original, "increment", commandId)
            assertTrue(recovered is WidgetCountCommandRecovery.Committed)
            assertEquals(receipt.reference, (recovered as WidgetCountCommandRecovery.Committed).receipt.reference)
        }
        assertTrue(before == contents()); assertTrue(primaryBefore == commandNativeImage())
        sessions.exclusive { db.withTransaction {
            val source = db.syncOutboxDao().getAll().single()
            assertEquals(originalIntent(source), NextOriginalRequestProofStore(db).liveOrigin(access(), NEXT_OPERATION, source.operationId))
            val row = requireNotNull(db.habitDao().getHabitById(habit.id))
            assertEquals(1L, RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(row, today)
                .completions.sumOf { it.value.toLong() })
        } }
        assertEquals(1L, home(fresh).batch.counts.getValue(habit.id).todayQuantity)
        assertEquals(1, CountCommandStore(db, tokens, sessions).discover(8).commands.size)
        assertEquals(0, count("metric_logs")); assertSameDay()
    }

    /** A real default-zone change gives deterministic post-write current-date/zone boundaries
     * without changing the device clock or waiting for midnight. The original reader is the
     * oracle; it remains current while only the separate source binds the original day.
     */
    internal suspend fun widgetCommandCurrentClockAndSourceClockRemainSeparate(crossDate: Boolean) {
        val previous = java.util.TimeZone.getDefault()
        val initial = if (crossDate) "Pacific/Pago_Pago" else "Etc/UTC"
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(initial))
        try {
            openFormal(0)
            val graph = graph(); home(graph)
            val reader = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
            val row = requireNotNull(db.habitDao().getHabitById(habit.id))
            val claim = reader.read(row).claim
            val authority = requireNotNull(editor.habit(habit.id).authority)
            val saved = graph.habits.logCompletionWithReceipt(app, row.id, authority = authority,
                expectedHabitUuid = row.uuid, widgetClaim = claim)
            val receipt = requireNotNull(saved.command)
            val next = if (crossDate) "Pacific/Kiritimati" else listOf("Etc/GMT+1", "Etc/GMT-1").first {
                Instant.now().atZone(ZoneId.of(it)).toLocalDate().toString() == claim.date
            }
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(next))
            val originalCurrent = reader.afterWrite(claim)
            val currentAndSource = reader.afterCountCommand(claim, requireNotNull(receipt.factUuid))
            val current = currentAndSource.current; val source = requireNotNull(currentAndSource.completionSource)
            assertEquals(next, originalCurrent.claim.timezone)
            assertEquals(originalCurrent.claim.date, current.claim.date)
            assertEquals(originalCurrent.claim.timezone, current.claim.timezone)
            assertEquals(originalCurrent.completed, current.completed)
            assertEquals(originalCurrent.targetProgress, current.targetProgress)
            assertEquals(originalCurrent.hasFailed, current.hasFailed)
            assertEquals(originalCurrent.count?.todayQuantity, current.count?.todayQuantity)
            assertEquals(originalCurrent.claim.countPolicy, current.claim.countPolicy)
            if (crossDate) {
                assertNotEquals(claim.date, current.claim.date)
                assertEquals(0L, requireNotNull(current.count).todayQuantity)
            } else assertEquals(claim.date, current.claim.date)
            assertEquals(claim.date, source.claim.date); assertEquals(claim.timezone, source.claim.timezone)
            assertEquals(receipt.factUuid, source.claim.completionUuid)
            assertEquals(1L, requireNotNull(source.count).todayQuantity)
            assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("count_command_results"))
            // Restore the fixture's original zone before its unchanged full original oracle.
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(initial))
            sessions.exclusive { db.withTransaction {
                val originalSource = db.syncOutboxDao().getAll().single()
                assertEquals(originalIntent(originalSource), NextOriginalRequestProofStore(db).liveOrigin(access(), NEXT_OPERATION, originalSource.operationId))
                val habit = requireNotNull(db.habitDao().getHabitById(row.id))
                assertEquals(1L, RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(habit, today)
                    .completions.sumOf { it.value.toLong() })
            } }
            assertEquals(CountCommandRecords.Delivery.FOLLOW_UP_PENDING,
                CountCommandStore(db, tokens, sessions).resolve(receipt.committed.reference).historical.status.delivery)
            assertEquals(0, count("metric_logs")); assertSameDay()
        } finally { java.util.TimeZone.setDefault(previous) }
    }

    internal suspend fun widgetCommandFollowUpKeepsCommittedFactWhenLatestHasAdvanced() {
        openFormal(0)
        val graph = graph(); home(graph)
        val reader = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
        val original = reader.read(requireNotNull(db.habitDao().getHabitById(habit.id))).claim
        val first = graph.service.widgetCountActionWithReceipt(app, original, "increment", UUID.randomUUID().toString())
        val firstReceipt = requireNotNull(first.current.command)
        val second = graph.service.widgetCountActionWithReceipt(app, original, "increment", UUID.randomUUID().toString())
        val secondReceipt = requireNotNull(second.current.command)
        assertNotEquals(firstReceipt.factUuid, secondReceipt.factUuid)
        val before = contents(); val primaryBefore = commandNativeImage()
        val current = reader.afterWrite(original)
        assertEquals(secondReceipt.factUuid, current.claim.completionUuid) // The old API remains latest/current.
        val read = reader.afterCountCommand(original, requireNotNull(firstReceipt.factUuid))
        val exact = requireNotNull(read.completionSource)
        assertEquals(secondReceipt.factUuid, read.current.claim.completionUuid)
        assertEquals(firstReceipt.factUuid, exact.claim.completionUuid)
        assertEquals(original.storageId, exact.claim.storageId)
        assertEquals(original.roundHead, exact.claim.roundHead)
        assertEquals(2L, requireNotNull(exact.count).todayQuantity)
        assertTrue(before == contents()); assertTrue(primaryBefore == commandNativeImage())
        sessions.exclusive { db.withTransaction {
            db.syncOutboxDao().getAll().forEach { source ->
                assertEquals(originalIntent(source), NextOriginalRequestProofStore(db).liveOrigin(access(), NEXT_OPERATION, source.operationId))
            }
            val row = requireNotNull(db.habitDao().getHabitById(habit.id))
            assertEquals(2L, RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(row, today)
                .completions.sumOf { it.value.toLong() })
        } }
        assertEquals(2, count("count_command_results")); assertEquals(2, CountCommandStore(db, tokens, sessions).discover(8).commands.size)
        assertEquals(0, count("metric_logs")); assertSameDay()
    }

    internal suspend fun widgetCommandMissingOrDifferentCaptureNeverRegistersOrReexecutes() {
        openFormal(0)
        val graph = graph(); home(graph)
        val row = requireNotNull(db.habitDao().getHabitById(habit.id))
        val claim = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions)).read(row).claim
        val id = UUID.randomUUID().toString()
        val before = contents(); val primaryBefore = commandNativeImage()
        val missing = graph.service.resumeWidgetCountCommand(app, claim, "increment", id)
        assertEquals(WidgetCountCommandRecovery.Unresolved(id, "NO_COMPLETE_PERSISTENT_INTENT"), missing)
        assertTrue(before == contents()); assertTrue(primaryBefore == commandNativeImage())
        assertEquals(0, count("count_command_intents")); assertEquals(0, count("completions"))
        val saved = graph.service.widgetCountActionWithReceipt(app, claim, "increment", id)
        val receipt = requireNotNull(saved.current.command)
        val committed = contents(); val primary = commandNativeImage()
        val different = claim.copy(date = today.plusDays(1).toString())
        val wrong = try { graph.service.resumeWidgetCountCommand(app, different, "increment", id); null }
            catch (error: IllegalArgumentException) { error }
        assertEquals("COMMAND_WIDGET_INPUT_CHANGED", wrong?.message)
        assertTrue(committed == contents()); assertTrue(primary == commandNativeImage())
        assertEquals(1, count("completions")); assertEquals(1, count("count_command_results"))
        val actual = graph.service.resumeWidgetCountCommand(app, claim, "increment", id)
        assertTrue(actual is WidgetCountCommandRecovery.Committed)
        assertEquals(receipt.reference, (actual as WidgetCountCommandRecovery.Committed).receipt.reference)
        sessions.exclusive { db.withTransaction {
            val source = db.syncOutboxDao().getAll().single()
            assertEquals(originalIntent(source), NextOriginalRequestProofStore(db).liveOrigin(access(), NEXT_OPERATION, source.operationId))
            val current = requireNotNull(db.habitDao().getHabitById(row.id))
            assertEquals(row.copy(activityRate = current.activityRate,
                activityRateUpdatedAt = current.activityRateUpdatedAt), current)
            assertEquals(1L, RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(current, today)
                .completions.sumOf { it.value.toLong() })
        } }
        assertEquals(1L, home(graph).batch.counts.getValue(habit.id).todayQuantity)
        assertEquals(0, count("metric_logs")); assertSameDay()
    }

    /** Real T0 is isolated from the COUNT generation/root. Both first-day and active-day
     * C paths retain the full pre-stage and post-stage OLD/NEW barriers. Counters measure
     * actual native verification attempts; the successful result/original oracle/END and
     * durable rows are separate required assertions, not inferred from a counter.
     */
    internal suspend fun commandMetadataPreservesCountProofAndUsesActualClosedC(spy: SourceWindowNativeSpy) {
        openFormal(0, metricCount = 2)
        val graph = graph()
        val refs = arrayListOf<CountCommandStore.Reference>()
        repeat(2) { index ->
            val shown = home(graph)
            val current = requireNotNull(db.habitDao().getHabitById(habit.id))
            val authority = requireNotNull(editor.habit(habit.id).authority)
            val beforeQualification = commandCountQualificationImage(refs)
            val at = Instant.now(); val capturedZone = ZoneId.systemDefault()
            val attempt = editor.beginCountAdd(current, authority, at, capturedZone, 1)
            val intent = attempt.input
            val ref = CountCommandStore.Reference(intent.commandId, intent.inputHash, intent.namespace.hash, intent.namespace.dataset)
            refs += ref
            assertCommandT0Qualification(beforeQualification, commandCountQualificationImage(refs), intent)
            assertEquals(index, count("completions")); assertEquals(index, count("sync_outbox"))
            val prepared = CountCommandStore(db, tokens, sessions).resolve(ref)
            assertEquals(CountCommandRecords.Execution.PREPARED, prepared.historical.status.execution)
            assertNull(prepared.historical.outcome)
            val beforeClosed = spy.countAddClosedEffectBarriers()
            val beforeCellReads = spy.countAddClosedEffectReads()
            val result = graph.habits.logCompletionWithReceipt(app, habit.id, authority = authority,
                countIdentity = requireNotNull(shown.batch.counts.getValue(habit.id).actionIdentity), preparedCommand = attempt)
            assertTrue(result.value > 0)
            val receipt = requireNotNull(result.command)
            assertEquals(ref, receipt.committed.reference); assertTrue(receipt.recorded)
            assertEquals("Both independent native 4/6 barriers must run around command stage", beforeClosed + 2, spy.countAddClosedEffectBarriers())
            assertEquals("Both verifications must read every independent OLD/NEW side",
                beforeCellReads + if (index == 0) 16 else 10, spy.countAddClosedEffectReads())
            assertEquals(index + 1, count("completions")); assertEquals(index + 1, count("sync_outbox"))
            assertEquals(index + 1, count("next_request_origins"))
            assertEquals(index + 1, count("count_command_results"))
            val stored = CountCommandStore(db, tokens, sessions).resolve(ref)
            assertEquals(CountCommandRecords.Execution.APPLIED, stored.historical.status.execution)
            assertEquals(receipt.factUuid, stored.historical.outcome?.primaryFactUuid)
            assertTrue("Command metadata may not force the next +1 to full-proof fallback", qualification().qualified)
        }
        val terminal = home(graph)
        assertEquals(2L, terminal.batch.counts.getValue(habit.id).todayQuantity)
        val businessBefore = contents(); val primaryBefore = commandNativeImage()
        sessions.exclusive { db.withTransaction {
            db.syncOutboxDao().getAll().forEach { source ->
                assertEquals(originalIntent(source), NextOriginalRequestProofStore(db).liveOrigin(access(), NEXT_OPERATION, source.operationId))
            }
            val current = requireNotNull(db.habitDao().getHabitById(habit.id))
            val original = RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(current, today)
            assertEquals(2L, original.completions.sumOf { it.value.toLong() })
            assertEquals(original.policies[today], terminal.batch.counts.getValue(habit.id).todayPolicy)
        } }
        assertTrue(businessBefore == contents()); assertTrue(primaryBefore == commandNativeImage())
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val fresh = CountCommandStore(db, tokens, sessions)
        refs.forEach { assertEquals(CountCommandRecords.Execution.APPLIED, fresh.resolve(it).historical.status.execution) }
        val pending = fresh.discover(8)
        assertTrue(pending.complete); assertEquals(2, pending.commands.size)
        assertTrue("Reading history is not delivery of required prompts", primaryBefore == commandNativeImage())
        assertEquals(2L, home(graph()).batch.counts.getValue(habit.id).todayQuantity)
        assertEquals(0, count("metric_logs")); assertSameDay()
    }

    /** This is a real native END-return loss and disk reopen, NOT a process-death test.
     * The immutable identity is known from real T0, never guessed from pending absence.
     */
    internal suspend fun actualCommandCountEndLossResolvesSameIdWithoutReplay(spy: SourceWindowNativeSpy) {
        openFormal(0, metricCount = 2)
        val graph = graph(); val shown = home(graph)
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val attempt = editor.beginCountAdd(current, authority, Instant.now(), ZoneId.systemDefault(), 1)
        val input = attempt.input
        val ref = CountCommandReference(input.commandId, input.inputHash, input.namespace.hash, input.namespace.dataset)
        var returned: CountMutationResult<Long>? = null
        var failure: Exception? = null
        spy.armCountAddFault(SourceWindowNativeSpy.CountAddFault.END_AFTER_COMMIT)
        try {
            returned = graph.habits.logCompletionWithReceipt(app, habit.id, authority = authority,
                countIdentity = requireNotNull(shown.batch.counts.getValue(habit.id).actionIdentity), preparedCommand = attempt)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failure = error
        } finally { spy.end() }
        assertTrue("Fault must follow the actual C origin and native committed END", spy.countAddFaultTouched())
        assertEquals("COUNT_ADD_TEST_END_OUTCOME_LOST", requireNotNull(failure).message)
        assertNull("Unknown END cannot publish a receipt", returned)
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        val before = contents(); val primaryBefore = commandNativeImage()
        val closedBefore = spy.countAddClosedEffectReads()
        val fresh = graph()
        val recovered = fresh.habits.resumeCountCommand(app, ref)
        assertTrue(recovered is CountCommandRecovery.HistoricalCommitted)
        val receipt = (recovered as CountCommandRecovery.HistoricalCommitted).receipt
        assertEquals(ref, receipt.reference); assertTrue(receipt.recorded)
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("count_command_results"))
        assertEquals(listOf(receipt.factUuid), db.completionDao().getAllCompletionsOnce().map { it.uuid })
        assertTrue(fresh.habits.resumeCountCommand(app, ref) is CountCommandRecovery.HistoricalCommitted)
        assertEquals("Receipt recovery must not attempt C again", closedBefore, spy.countAddClosedEffectReads())
        assertTrue(before == contents()); assertTrue(primaryBefore == commandNativeImage())
        sessions.exclusive { db.withTransaction {
            val source = db.syncOutboxDao().getAll().single()
            assertEquals(originalIntent(source), NextOriginalRequestProofStore(db).liveOrigin(access(), NEXT_OPERATION, source.operationId))
            val row = requireNotNull(db.habitDao().getHabitById(habit.id))
            val original = RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(row, today)
            assertEquals(1L, original.completions.sumOf { it.value.toLong() })
        } }
        assertEquals(1L, home(fresh).batch.counts.getValue(habit.id).todayQuantity)
        assertEquals(1, CountCommandStore(db, tokens, sessions).discover(8).commands.size)
        assertTrue(primaryBefore == commandNativeImage()); assertEquals(0, count("metric_logs")); assertSameDay()
    }

    /** The caller catches a real pure-B failure and stays alive. This must release only the
     * unadmitted process lease, retain durable PREPARED, and permit the original-ID recovery.
     * No suspend producer/editor is mocked; the only fault is the synchronous pure planner.
     */
    internal suspend fun commandPurePreparationFailureReleasesLiveCallerLease(spy: SourceWindowNativeSpy) {
        openFormal(0, metricCount = 2)
        val graph = graph(); val shown = home(graph)
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val attempt = editor.beginCountAdd(current, authority, Instant.now(), ZoneId.systemDefault(), 1)
        val input = attempt.input
        val ref = CountCommandReference(input.commandId, input.inputHash, input.namespace.hash, input.namespace.dataset)
        val caller = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        val mainBefore = contents(); val primaryBefore = commandNativeImage()
        val closedBefore = spy.countAddClosedEffectReads()
        val pureCalls = java.util.concurrent.atomic.AtomicInteger()
        var failure: Exception? = null
        mockkObject(CountAddNativePlan.Companion)
        try {
            every { CountAddNativePlan.prepare(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()) } answers {
                pureCalls.incrementAndGet()
                assertFalse("A must have ended before the genuine pure-B fault", db.inTransaction())
                throw IllegalStateException("COMMAND_TEST_PURE_B_FAILED")
            }
            try {
                graph.habits.logCompletionWithReceipt(app, habit.id, authority = authority,
                    countIdentity = requireNotNull(shown.batch.counts.getValue(habit.id).actionIdentity), preparedCommand = attempt)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failure = error
            }
        } finally { unmockkObject(CountAddNativePlan.Companion) }
        assertEquals(1, pureCalls.get()); assertEquals("COMMAND_TEST_PURE_B_FAILED", requireNotNull(failure).message)
        assertSame(caller, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]); assertTrue(caller.isActive)
        assertEquals(closedBefore, spy.countAddClosedEffectReads())
        assertTrue(mainBefore == contents()); assertTrue(primaryBefore == commandNativeImage())
        val durable = CountCommandStore(db, tokens, sessions).resolve(ref.native())
        assertEquals(CountCommandRecords.Execution.PREPARED, durable.historical.status.execution)
        assertNull(durable.historical.outcome)
        // No Job completion, new owner, native reopen or blind new-ID retry is involved.
        val recovered = graph.habits.resumeCountCommand(app, ref)
        assertTrue("The same living caller must not remain ORIGINAL_OPERATION_STILL_RUNNING", recovered is CountCommandRecovery.HistoricalCommitted)
        val receipt = (recovered as CountCommandRecovery.HistoricalCommitted).receipt
        assertEquals(ref, receipt.reference); assertTrue(receipt.recorded)
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("count_command_results"))
        assertEquals(listOf(receipt.factUuid), db.completionDao().getAllCompletionsOnce().map { it.uuid })
        val committedMain = contents(); val committedPrimary = commandNativeImage()
        assertTrue(graph.habits.resumeCountCommand(app, ref) is CountCommandRecovery.HistoricalCommitted)
        assertTrue(committedMain == contents()); assertTrue(committedPrimary == commandNativeImage())
        sessions.exclusive { db.withTransaction {
            val source = db.syncOutboxDao().getAll().single()
            assertEquals(originalIntent(source), NextOriginalRequestProofStore(db).liveOrigin(access(), NEXT_OPERATION, source.operationId))
            val row = requireNotNull(db.habitDao().getHabitById(habit.id))
            assertEquals(1L, RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(row, today)
                .completions.sumOf { it.value.toLong() })
        } }
        assertEquals(1L, home(graph).batch.counts.getValue(habit.id).todayQuantity); assertSameDay()
    }

    internal suspend fun commandTopEntryRejectsAmbientBeforeRegistration(mode: Int) {
        require(mode in 0..2)
        openFormal(0, metricCount = 2); val graph = graph(); val shown = home(graph)
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val before = contents(); val primaryBefore = commandNativeImage()
        var rejected = false; var heldAccount = false
        suspend fun attempt() {
            try {
                graph.habits.logCompletionWithReceipt(app, habit.id, authority = authority,
                    countIdentity = requireNotNull(shown.batch.counts.getValue(habit.id).actionIdentity))
            } catch (error: IllegalStateException) {
                val expected = if (mode == 0) "COMMAND_TOP_LEVEL_TRANSACTION_REQUIRED" else
                    "Cannot access database on a different coroutine context inherited from a suspending transaction."
                assertEquals(expected, error.message); rejected = true
            }
            assertTrue("Top-level command must reject before T0/owning a nested account lock", rejected)
        }
        kotlinx.coroutines.withTimeout(15_000) {
            when (mode) {
                0 -> db.withTransaction { assertTrue(db.inTransaction()); attempt() }
                1 -> db.withTransaction { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    assertFalse(db.inTransaction()); attempt()
                } }
                else -> sessions.exclusive {
                    heldAccount = true
                    db.withTransaction { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        assertFalse(db.inTransaction()); attempt()
                    } }
                }
            }
        }
        assertEquals(mode == 2, heldAccount)
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("count_command_intents"))
        assertTrue(before == contents()); assertTrue(primaryBefore == commandNativeImage()); assertSameDay()
    }

    internal suspend fun commandNormalTopEntryOwnsT0AndOneActualT1() {
        openFormal(0, metricCount = 2); val graph = graph(); val shown = home(graph)
        val authority = requireNotNull(editor.habit(habit.id).authority)
        assertFalse(db.inTransaction())
        val result = graph.habits.logCompletionWithReceipt(app, habit.id, authority = authority,
            countIdentity = requireNotNull(shown.batch.counts.getValue(habit.id).actionIdentity))
        val receipt = requireNotNull(result.command)
        assertTrue(result.value > 0 && receipt.recorded)
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox"))
        assertEquals(1, count("count_command_intents")); assertEquals(1, count("count_command_results"))
        val resolved = CountCommandStore(db, tokens, sessions).resolve(receipt.committed.reference)
        assertEquals(CountCommandRecords.Execution.APPLIED, resolved.historical.status.execution)
        assertEquals(receipt.factUuid, resolved.historical.outcome?.primaryFactUuid)
        val before = contents(); val commandsBefore = commandNativeImage()
        sessions.exclusive { db.withTransaction {
            val source = db.syncOutboxDao().getAll().single()
            assertEquals(originalIntent(source), NextOriginalRequestProofStore(db).liveOrigin(access(), NEXT_OPERATION, source.operationId))
            val row = requireNotNull(db.habitDao().getHabitById(habit.id))
            assertEquals(1L, RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(row, today)
                .completions.sumOf { it.value.toLong() })
        } }
        assertTrue(before == contents()); assertTrue(commandsBefore == commandNativeImage())
        assertEquals(1L, home(graph).batch.counts.getValue(habit.id).todayQuantity); assertSameDay()
    }

    internal suspend fun commandAlreadyCancelledEntryDoesNotRegister() {
        openFormal(0, metricCount = 2); val graph = graph(); val shown = home(graph)
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val before = contents(); val primaryBefore = commandNativeImage()
        var caughtCancellation = false
        kotlinx.coroutines.coroutineScope {
            val child = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                kotlinx.coroutines.currentCoroutineContext().cancel()
                try {
                    graph.habits.logCompletionWithReceipt(app, habit.id, authority = authority,
                        countIdentity = requireNotNull(shown.batch.counts.getValue(habit.id).actionIdentity))
                } catch (error: kotlinx.coroutines.CancellationException) {
                    caughtCancellation = true
                    throw error
                }
            }
            child.join()
        }
        assertTrue(caughtCancellation)
        assertTrue(before == contents()); assertTrue(primaryBefore == commandNativeImage())
        assertEquals(0, count("count_command_intents")); assertEquals(0, count("completions")); assertSameDay()
    }

    private data class CommandCountQualification(
        val count: Map<String, List<Map<String, Pair<Int, String?>>>>,
        val primary: CountCommandSeal.Authenticated,
        val currentNamespace: CountCommandRecords.Namespace,
        val installation: String?,
        val generation: Long,
        val commands: Map<String, CountCommandRowPlan.Aggregate>
    )

    /** This fixed two-command oracle authenticates the actual root/anchor/schema and every
     * expected catalog member in the same read TX. It never signs or repairs either root. */
    private suspend fun commandCountQualificationImage(refs: List<CountCommandStore.Reference>): CommandCountQualification =
        sessions.exclusive { db.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess())
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            val result = owner.maintenance(db) {
                val sql = db.openHelper.writableDatabase
                val token = owner.capturePrimaryReadToken(db)
                val caller = kotlinx.coroutines.currentCoroutineContext()
                val lifetime = { owner.requirePrimaryReadToken(db, token); caller.ensureActive() }
                val primary = CountCommandStorageInitialization.verifyRootAnchor(sql)
                val control = CountCommandRootAnchor.read(sql)
                val namespace = CountCommandRecords.Namespace(access.session.authentication.userId,
                    access.session.serverInstanceId, access.session.syncEpoch, access.capturedDeviceId,
                    control.storage, primary.header.datasetId)
                val installation = tokens.registeredInstallationId()
                primary.header.registration?.let { assertEquals(installation, it.installationId) }
                val count = listOf("count_trust_control", "count_trust_root").associateWith { table ->
                    sql.query("SELECT rowid AS __qualification_rowid,* FROM main.`$table` ORDER BY rowid").use { c ->
                        buildList { while (c.moveToNext()) add(buildMap {
                            for (index in 0 until c.columnCount) {
                                val type = c.getType(index)
                                val value = when (type) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> null
                                    android.database.Cursor.FIELD_TYPE_BLOB -> replicaStorageBlobHex(c.getBlob(index))
                                    android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toRawBits().toString()
                                    else -> c.getString(index)
                                }
                                put(c.getColumnName(index), type to value)
                            }
                        }) }
                    }
                }
                val row = count.getValue("count_trust_control").single()
                assertEquals(setOf("__qualification_rowid", "id", "storageId", "generation", "dirty", "commandRootHash"), row.keys)
                assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER to "1", row.getValue("__qualification_rowid"))
                assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER to "1", row.getValue("id"))
                assertEquals(android.database.Cursor.FIELD_TYPE_STRING to control.storage, row.getValue("storageId"))
                assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER to control.generation.toString(), row.getValue("generation"))
                assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER to control.dirty.toString(), row.getValue("dirty"))
                assertEquals(android.database.Cursor.FIELD_TYPE_STRING to CountCommandRootAnchor.digest(primary.native()), row.getValue("commandRootHash"))
                assertEquals(refs.size, refs.map { it.commandId }.toSet().size)
                assertEquals(refs.size.toLong(), primary.header.catalog?.count ?: 0L)
                val ids = refs.map { it.commandId }.toSet()
                for (table in listOf("count_command_intents", "count_command_status")) {
                    val actual = sql.query("SELECT commandId FROM main.`$table` ORDER BY commandId").use { c ->
                        buildSet { while (c.moveToNext()) { assertEquals(android.database.Cursor.FIELD_TYPE_STRING, c.getType(0)); add(c.getString(0)) } }
                    }
                    assertEquals(ids, actual)
                }
                val commands = if (primary.header.mode == CountCommandRootCodec.Mode.EMPTY) {
                    assertTrue(refs.isEmpty()); assertNull(primary.header.catalog)
                    assertEquals(CountCommandPendingRules.Frontier(null, null, 0), primary.header.pending)
                    emptyMap()
                } else {
                    assertEquals(CountCommandRootCodec.Mode.ACCOUNT_BOUND, primary.header.mode)
                    assertEquals(namespace.catalogBinding, primary.header.accountBinding)
                    CountCommandDirectory(sql, namespace.catalogBinding, requireNotNull(primary.header.catalog), lifetime, lifetime).read { directory ->
                        refs.associate { ref ->
                            val member = requireNotNull(directory.member(ref.commandId))
                            val aggregate = CountCommandNativeRows(sql, lifetime).aggregate(member, lifetime)
                            assertEquals(ref.inputHash, aggregate.intent.inputHash)
                            assertEquals(ref.namespaceHash, aggregate.intent.namespace.hash)
                            assertEquals(ref.datasetId, aggregate.intent.namespace.dataset)
                            ref.commandId to aggregate
                        }
                    }
                }
                val outcomes = sql.query("SELECT commandId FROM main.count_command_results ORDER BY commandId").use { c ->
                    buildSet { while (c.moveToNext()) { assertEquals(android.database.Cursor.FIELD_TYPE_STRING, c.getType(0)); add(c.getString(0)) } }
                }
                assertEquals(commands.filterValues { it.outcome != null }.keys, outcomes)
                lifetime()
                CommandCountQualification(count, primary, namespace, installation, control.generation, commands)
            }
            assertEquals(access, tokens.localCoreWriteAccess())
            result
        } }

    private fun assertCommandT0Qualification(before: CommandCountQualification, after: CommandCountQualification,
        intent: CountCommandRecords.Intent) {
        assertEquals(before.currentNamespace, intent.namespace)
        assertEquals(before.currentNamespace, after.currentNamespace)
        assertEquals(before.installation, after.installation)
        val oldControl = before.count.getValue("count_trust_control").single()
        val expectedControl = oldControl + ("commandRootHash" to
            (android.database.Cursor.FIELD_TYPE_STRING to CountCommandRootAnchor.digest(after.primary.native())))
        assertEquals("T0 must retain COUNT rowid/id/storage/g/dirty and every complete COUNT root cell",
            before.count + ("count_trust_control" to listOf(expectedControl)), after.count)
        val oldRoot = before.primary.header
        val bound = when (oldRoot.mode) {
            CountCommandRootCodec.Mode.EMPTY -> oldRoot.copy(mode = CountCommandRootCodec.Mode.ACCOUNT_BOUND,
                namespace = intent.namespace, catalog = CountCommandDirectory.empty(intent.namespace.catalogBinding).root,
                revision = Math.addExact(oldRoot.revision, 1L))
            CountCommandRootCodec.Mode.ACCOUNT_BOUND -> if (oldRoot.namespace == intent.namespace) oldRoot else {
                assertTrue(requireNotNull(oldRoot.namespace).permitsInitialRegistration(intent.namespace))
                assertNull(oldRoot.registration)
                oldRoot.copy(namespace = intent.namespace, registration = CountCommandRootCodec.Registration(
                    requireNotNull(oldRoot.namespaceHash), requireNotNull(before.installation)),
                    revision = Math.addExact(oldRoot.revision, 1L))
            }
            CountCommandRootCodec.Mode.LEGACY_UNENROLLED -> error("The formal T0 fixture must not enroll old results")
            CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED -> error("The formal T0 fixture does not cover retained legacy results")
        }
        val prepared = CountCommandTransitionRules.prepared(intent, before.generation)
        val plan = CountCommandPendingRules.append(bound.pending, prepared,
            bound.pending.tail?.let { before.commands.getValue(it).status })
        val expected = before.commands.toMutableMap()
        for (change in plan.changes) {
            val old = expected[change.after.commandId]
            assertEquals(change.before, old?.status)
            expected[change.after.commandId] = if (old == null) CountCommandRowPlan.Aggregate(intent, change.after, null)
                else CountCommandRowPlan.Aggregate(old.intent, change.after, old.outcome)
        }
        assertEquals(expected.mapValues { it.value.member() }, after.commands.mapValues { it.value.member() })
        assertEquals(intent.text(), after.commands.getValue(intent.commandId).intent.text())
        assertNull(after.commands.getValue(intent.commandId).outcome)
        assertEquals(before.commands.size.toLong() + 1L, requireNotNull(after.primary.header.catalog).count)
        assertEquals(bound.copy(catalog = after.primary.header.catalog, pending = plan.after,
            revision = Math.addExact(bound.revision, 1L)), after.primary.header)
    }

    private suspend fun commandNativeImage(): List<Pair<String, List<List<Pair<Int, String?>>>>> = db.withTransaction {
        (com.dayforge.data.local.CountCommandV20Schema.tables + "count_command_results").sorted().map { table ->
            table to db.openHelper.writableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                    val type = cursor.getType(index)
                    type to when (type) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it.toInt() and 255) }
                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toRawBits().toString()
                        else -> cursor.getString(index)
                    }
                }) }
            }
        }
    }

    internal suspend fun actualCompactFormalMetricPromptChain(history: Int) {
        require(history in setOf(0, 8, 80, 730))
        metricPilotPhase("setup_formal_bootstrap") { openFormal(history, metricCount = 2,
            historyFromBootstrap = history > 0, compactGrowth = history > 8) }
        val graph = graph(); val first = metricPilotPhase("first_home_and_qualification") { compactHome(graph) }
        // Seed magnitude is an original fact contract, not a field the new card requests.
        assertEquals(history.toLong(), metricPilotPhase("initial_fact_values_oracle") {
            db.completionDao().getAllCompletionsOnce().sumOf { it.value.toLong() }
        })
        val request = graph.prompt.captureCompletionPrompt()
        val saved = metricPilotPhase("first_count_service") {
            graph.service.incrementCount(app, habit.id, requireNotNull(first.batch.counts.getValue(habit.id).actionIdentity))
        } as CheckInResult.Success
        assertTrue(saved.recorded)
        val follow = requireNotNull(saved.followUp)
        assertNull(follow.count); assertNotNull(follow.countDisplay)
        assertEquals(saved.progress, requireNotNull(follow.countDisplay).qualifiedDayCount)
        val countQueue = metricPilotPhase("first_count_source_lookup") { db.syncOutboxDao().getAll().single() }
        val requests = sender(http)
        val countDelivery = metricPilotPhase("first_count_send") { requireNotNull(requests.sendOperation(access(), countQueue.operationId)) }
        assertEquals(NextOperationAcceptance.COMMITTED, metricPilotPhase("first_count_ack") { requests.acceptOperation(countDelivery) })
        val before = metricPilotPhase("first_count_post_ack_home") { compactHome(graph) }
        metricPilotPhase("first_count_necessary_prompt_after_ack") { graph.prompt.showCompletionPrompt(follow, request) }
        val state = requireNotNull(graph.prompt.postCheckInState.value)
        val prompt = requireNotNull(state.factPrompt)
        assertEquals(2, state.linkedMetrics.size)
        val input = state.linkedMetrics.mapIndexed { index, info ->
            com.dayforge.ui.components.MetricValueInput(info.metricId, 21.125 + index, "actual formal metric $index")
        }
        val local = observe("actual_multi_metric_prompt_save") {
            metricPilotPhase("actual_multi_metric_prompt_save", captureTrace = false) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    graph.prompt.recordMetricValues(habit.id, input, expectedFactPrompt = prompt)
                }
            }
        }
        metricPilotAttach("actual_multi_metric_prompt_save", local.sql)
        assertTrue(local.value); assertEquals(input, prompt.submitted)
        assertEquals(2, count("metric_logs")); assertEquals(2, count("sync_outbox"))
        assertTrue("Actual prompt submission must maintain qualification", local.qualification.qualified)
        assertEquals("Only the two original entry source audits remain", 2L, local.sql.sourceScans)
        assertEquals(0L, local.sql.original.fullProofSentinels)
        assertTrue(local.sql.decryptions > 0)
        val queued = metricPilotPhase("actual_metric_sources_lookup") { db.syncOutboxDao().getAll() }
        assertEquals(setOf("metric_log"), queued.map { it.recordType }.toSet())
        assertEquals(before.batch.counts, metricPilotPhase("post_metric_home") { compactHome(graph) }.batch.counts)
        assertTrue(metricPilotPhase("same_live_prompt_repeat") {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                graph.prompt.recordMetricValues(habit.id, input, expectedFactPrompt = prompt)
            }
        })
        assertEquals("Same live submitted prompt must not append again", 2, count("metric_logs"))
        assertEquals(2, count("sync_outbox"))
        val accepted = arrayListOf<String>()
        for ((index, row) in queued.withIndex()) {
            val formal = metricPilotPhase("metric_${index}_original_lookup") { requireNotNull(roundOperationIntent(originalIntent(row).intentJson)) }
            assertEquals("metric_observation", formal.operation.entityType)
            assertNull(formal.context.head); assertFalse(formal.initialCreation)
            assertTrue(formal.context.affectedHeads.isEmpty()); assertFalse(formal.context.legacyInitial)
            assertNull(formal.goalChildFrontier); assertNull(formal.restartFrontier); assertNull(formal.countPlanFrontier)
            assertNull(db.nextStructuralCausalDao().dependency(row.operationId))
            val delivery = metricPilotPhase("metric_${index}_send") { requireNotNull(requests.sendOperation(access(), row.operationId)) }
            val ack = observe("actual_formal_metric_ack_$index") {
                metricPilotPhase("actual_formal_metric_ack_$index", captureTrace = false) { requests.acceptOperation(delivery) }
            }
            metricPilotAttach("actual_formal_metric_ack_$index", ack.sql)
            assertEquals(NextOperationAcceptance.COMMITTED, ack.value)
            assertTrue(ack.qualification.qualified); assertEquals(0L, ack.sql.shadowScans); assertEquals(0L, ack.sql.uuidScans)
            assertEquals(0L, ack.sql.original.fullProofSentinels); assertTrue(ack.sql.decryptions > 0)
            assertEquals(NextOperationAcceptance.REPLAYED, metricPilotPhase("metric_${index}_replay") { requests.acceptOperation(delivery) })
            accepted += row.operationId
        }
        assertEquals(history + 1, count("next_challenge_births"))
        assertEquals(0, count("sync_outbox")); assertEquals(2, count("metric_logs"))
        val afterMetrics = metricPilotPhase("metric_acks_complete_home") { compactHome(graph) }
        assertEquals(before.batch.counts, afterMetrics.batch.counts)
        graph.prompt.closePrompt(state)
        val nextRequest = graph.prompt.captureCompletionPrompt()
        val next = observe("count_after_actual_metric_acks") {
            metricPilotPhase("count_after_actual_metric_acks", captureTrace = false) {
                graph.service.incrementCount(app, habit.id, requireNotNull(afterMetrics.batch.counts.getValue(habit.id).actionIdentity))
            }
        }
        metricPilotAttach("count_after_actual_metric_acks", next.sql)
        val nextSuccess = next.value as CheckInResult.Success
        assertTrue(nextSuccess.recorded); assertTrue(next.qualification.qualified)
        assertEquals("Real COUNT after both metric ACKs uses the same entry-only source contract", 2L, next.sql.sourceScans)
        assertEquals(0L, next.sql.original.fullProofSentinels)
        val lastQueue = metricPilotPhase("next_count_source_lookup") { db.syncOutboxDao().getAll().single() }
        val lastDelivery = metricPilotPhase("next_count_send") { requireNotNull(requests.sendOperation(access(), lastQueue.operationId)) }
        assertEquals(NextOperationAcceptance.COMMITTED, metricPilotPhase("next_count_ack") { requests.acceptOperation(lastDelivery) })
        val terminal = metricPilotPhase("terminal_common_home") { compactHome(graph) }
        assertEquals(2L, terminal.batch.counts.getValue(habit.id).todayQuantity)
        val terminalFacts = db.completionDao().getAllCompletionsOnce()
        assertEquals(history + 2L, terminalFacts.sumOf { it.value.toLong() })
        assertEquals(historicalUuids + countQueue.entityUuid + lastQueue.entityUuid,
            terminalFacts.map { it.uuid }.toSet())
        metricPilotPhase("next_count_necessary_prompt_after_ack") {
            graph.prompt.showCompletionPrompt(requireNotNull(nextSuccess.followUp), nextRequest)
        }
        val nextPrompt = requireNotNull(graph.prompt.postCheckInState.value)
        assertEquals(input.map { it.metricId }.toSet(), nextPrompt.linkedMetrics.map { it.metricId }.toSet())
        for (value in input) assertEquals(value.value, requireNotNull(db.metricLogDao().getLatestLog(value.metricId)).value, 0.0)
        graph.prompt.closePrompt(nextPrompt)
        val rawBefore = metricPilotPhase("terminal_native_before_oracle") { contents() }
        metricPilotPhase("terminal_original_oracle") { sessions.exclusive { db.withTransaction {
            for (id in historicalRequests + countQueue.operationId + lastQueue.operationId)
                requests.requireAcceptedCountInTransaction(access(), id)
            for (id in accepted) assertEquals("metric_observation", requests.recoveryAcceptedFactProof(access(), id).first.entityType)
            val full = RecurringCompletionEvidenceReader(db, tokens, sessions)
            for (row in rows) {
                val current = requireNotNull(db.habitDao().getHabitById(row.id))
                val original = full.readInTransaction(current, today)
                val expected = CountHistory(today, original.policies,
                    original.completions.groupBy { it.businessDate }.mapValues { (_, facts) -> facts.sumOf { it.value.toLong() } },
                    original.policies[today] ?: CountDayPolicy(current.targetValue, current.isCountdown),
                    original.unknownDates, original.completions, original.roundHead, original.countIdentity)
                val projected = projectCountDisplay(current, expected.summary, terminal.batch.time.zone,
                    terminal.batch.time.toInstant().toEpochMilli())
                assertEquals(projected, terminal.batch.counts.getValue(row.id))
            }
        } } }
        assertEquals(rawBefore, metricPilotPhase("terminal_native_after_oracle") { contents() })
        assertFalse(server.requests.any { it.path == "/api/v2/sync/push" })
        assertTrue(metricPilotPhase("terminal_qualification_guard") { qualification() }.qualified)
        record("actual_compact_metric_chain_terminal") {
            put("history", history); put("history_source", if (history > 0) "server_bootstrap" else "empty")
            put("historical_local_acks", 0); put("actual_prompt_values", 2); put("real_metric_acks", 2)
            put("count_after_metric_acks", true); put("metric_births", 0); put("metric_dependencies", 0)
            put("original_terminal_oracle", true); put("compact_dashboard_entry", true)
            put("compact_service_follow_up", true); put("performance_measured", false)
        }
    }

    internal enum class CompactDamage { GLOBAL_MISSING, GLOBAL_BYTES, YEAR_BYTES, INVALID_COVERAGE }
    private suspend fun compactEdit(values: Map<String, JsonElement?>) = sessions.exclusive {
        db.withTransaction {
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            val context = requireNotNull(CountTrustKernel(db, tokens, sessions).open(requireNotNull(tokens.localCoreWriteAccess())))
            owner.maintenance(db) {
                context.index.putAll(values)
                CountTrustSeal.store(db.openHelper.writableDatabase, CountTrustSeal().prepare(context.header.binding,
                    context.header.schemaHash, context.index.root, Math.addExact(context.header.revision, 1L), context.header.generation))
            }
        }
    }
    private suspend fun compactValue(key: String): JsonElement = sessions.exclusive { db.withTransaction {
        val context = requireNotNull(CountTrustKernel(db, tokens, sessions).open(requireNotNull(tokens.localCoreWriteAccess())))
        // Own plain JSON across END; never retain Context/index or its native read token.
        Json.parseToJsonElement(requireNotNull(context.index.get(key)).toString())
    } }
    private suspend fun compactBody(digest: String, replacement: ByteArray? = null): ByteArray = db.withTransaction {
        requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            val sql = db.openHelper.writableDatabase
            val old = sql.query("SELECT typeof(body),body FROM count_trust_nodes WHERE digest=?", arrayOf(digest)).use {
                assertTrue(it.moveToFirst()); assertEquals("blob", it.getString(0))
                it.getBlob(1).copyOf().also { _ -> assertFalse(it.moveToNext()) }
            }
            if (replacement != null) sql.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(replacement, digest))
            old
        }
    }
    internal suspend fun compactRelatedBlockRejectsAndRestores(fault: CompactDamage) {
        openFormal(0, metricCount = 2); val graph = graph(); val initial = compactHome(graph)
        val saved = graph.service.incrementCount(app, habit.id, initial.batch.counts.getValue(habit.id).actionIdentity) as CheckInResult.Success
        assertTrue(saved.recorded); assertNotNull(requireNotNull(saved.followUp).countDisplay)
        val expected = compactHome(graph)
        val head = metadata().checkpoints.single { it.head.activityUuid == habit.uuid }.head
        val scope = CountDisplayBlocks.Scope(habit.uuid, head)
        val key = when (fault) {
            CompactDamage.INVALID_COVERAGE -> CountDisplayBlocks.COVERAGE_KEY
            CompactDamage.YEAR_BYTES -> scope.yearKey(today.year)
            else -> scope.globalKey()
        }
        val old = compactValue(key)
        var damagedDigest: String? = null; var originalBytes: ByteArray? = null
        when (fault) {
            CompactDamage.GLOBAL_MISSING -> compactEdit(mapOf(key to null))
            CompactDamage.INVALID_COVERAGE -> compactEdit(mapOf(key to JsonPrimitive("invalid compact coverage")))
            CompactDamage.GLOBAL_BYTES, CompactDamage.YEAR_BYTES -> {
                val bytes = old.toString().toByteArray(Charsets.UTF_8)
                assertTrue(bytes.size <= com.dayforge.data.history.CountTrustIndex.CHUNK_BYTES)
                val digest = com.dayforge.data.history.CountTrustIndex.hash(bytes)
                val actual = compactBody(digest)
                assertArrayEquals(bytes, actual)
                originalBytes = actual; damagedDigest = digest
                compactBody(digest, actual.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
            }
        }
        val damaged = contents()
        val failure = try { compactHome(graph); null }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { error }
        assertNotNull(failure)
        val expectedMessage = when (fault) {
            CompactDamage.GLOBAL_MISSING -> "COUNT_DISPLAY_GLOBAL_MISSING"
            CompactDamage.INVALID_COVERAGE -> "COUNT_DISPLAY_COVERAGE_CHANGED"
            else -> "COUNT_TRUST_NODE_CHANGED"
        }
        assertEquals(expectedMessage, failure?.message)
        assertEquals(damaged, contents())
        if (damagedDigest != null) compactBody(requireNotNull(damagedDigest), requireNotNull(originalBytes))
        else compactEdit(mapOf(key to old))
        val restored = compactHome(graph)
        assertEquals(expected.batch.counts, restored.batch.counts)
        assertTrue(qualification().qualified)
    }
    internal suspend fun compactAbsentCoverageKeepsTheOriginalLegalReader() {
        openFormal(0); val graph = graph(); val before = compactHome(graph)
        compactEdit(mapOf(CountDisplayBlocks.COVERAGE_KEY to null))
        val raw = contents()
        val fallback = compactHome(graph)
        assertEquals(before.batch.counts, fallback.batch.counts)
        assertEquals(raw, contents())
        assertEquals(0L, graph.counts.readSummary(requireNotNull(db.habitDao().getHabitById(habit.id)), today).todayQuantity)
    }
    internal suspend fun compactGenericUndoAndTypedFirstDayMaintainTheSameResults() {
        openFormal(0); val graph = graph(); val initial = compactHome(graph)
        val first = graph.service.incrementCount(app, habit.id, initial.batch.counts.getValue(habit.id).actionIdentity) as CheckInResult.Success
        assertTrue(first.recorded)
        val second = creatingHabits().logCompletion(app, habit.id, 2,
            authority = requireNotNull(editor.habit(habit.id).authority))
        assertTrue(second > 0)
        val reached = compactHome(graph)
        assertEquals(3L, reached.batch.counts.getValue(habit.id).todayQuantity)
        assertEquals(1, reached.batch.counts.getValue(habit.id).qualifiedDayCount)
        // Accept both real requests first: pending undo legally invalidates the old fast
        // shape, whereas an accepted undo appends a revert source and exercises maintenance.
        val requests = sender(http)
        for (queued in db.syncOutboxDao().getAll()) {
            val delivery = requireNotNull(requests.sendOperation(access(), queued.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivery))
        }
        assertEquals(0, count("sync_outbox"))
        val undo = observe("compact_accepted_undo") {
            graph.service.decrementCount(app, habit.id, reached.batch.counts.getValue(habit.id).actionIdentity)
        }
        val undone = undo.value as CheckInResult.Success
        assertTrue(undone.recorded); assertFalse(undone.completed); assertEquals(0, undone.progress)
        assertTrue(undo.qualification.qualified)
        assertEquals(0L, undo.sql.original.fullProofSentinels)
        val terminal = compactHome(graph)
        assertEquals(1L, terminal.batch.counts.getValue(habit.id).todayQuantity)
        val original = graph.counts.readSummary(requireNotNull(db.habitDao().getHabitById(habit.id)), today)
        assertEquals(projectCountDisplay(requireNotNull(db.habitDao().getHabitById(habit.id)), original,
            terminal.batch.time.zone, terminal.batch.time.toInstant().toEpochMilli()), terminal.batch.counts.getValue(habit.id))
        assertTrue(qualification().qualified)
    }
    internal suspend fun compactRetainedDatabaseReopenUsesTheAuthenticatedProjection() {
        openFormal(8, historyFromBootstrap = true)
        var graph = graph(); val initial = compactHome(graph)
        val result = graph.service.incrementCount(app, habit.id, initial.batch.counts.getValue(habit.id).actionIdentity) as CheckInResult.Success
        assertTrue(result.recorded)
        val before = compactHome(graph); val raw = contents()
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
        graph = graph()
        val reopened = observe("compact_retained_database_reopen") { compactHome(graph) }
        assertEquals(before.batch.counts, reopened.value.batch.counts)
        assertTrue(reopened.qualification.qualified)
        assertEquals(0L, reopened.sql.original.fullProofSentinels)
        assertEquals("Retained valid projection must not issue a new root or alter native state", raw, contents())
        record("compact_reopen_scope") {
            put("retained_database_and_keystore", true); put("same_process_new_room_owner", true)
            put("real_process_death", false); put("latency_acceptance", false)
        }
    }

    /** Rebind all authenticated original leaves into the preceding v3 format. No content is
     * invented from the new projection; only v4-only leaves are removed, and every old value
     * is obtained through the original authenticated index before signing the legacy tuple.
     */
    private suspend fun installEnvironmentV3ProjectionPredecessor(): CountTrustSeal.Header = sessions.exclusive {
        db.withTransaction {
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            val access = requireNotNull(tokens.localCoreWriteAccess())
            owner.maintenance(db) {
                val sql = db.openHelper.writableDatabase
                val old = requireNotNull(CountTrustSeal.read(sql))
                val index = com.dayforge.data.history.CountTrustIndex(sql, old.binding, old.root) {}
                index.verifyContents()
                val pending = ArrayDeque<com.dayforge.data.history.HistoryBlockCodec.Ref>()
                pending.addLast(old.root)
                val values = linkedMapOf<String, JsonElement>()
                while (pending.isNotEmpty()) {
                    val reference = pending.removeLast()
                    val bytes = sql.query("SELECT body FROM count_trust_nodes WHERE digest=?", arrayOf(reference.digest)).use {
                        assertTrue(it.moveToFirst()); val body = it.getBlob(0); assertFalse(it.moveToNext()); body
                    }
                    when (val node = com.dayforge.data.history.HistoryBlockCodec.decode(reference, bytes)) {
                        is com.dayforge.data.history.HistoryBlockCodec.Branch -> node.children.forEach(pending::addLast)
                        is com.dayforge.data.history.HistoryBlockCodec.Leaf -> node.records.forEach { row ->
                            val name = com.dayforge.data.history.CountTrustValueCodec.pointer(row).name
                            val compact = name == CountDisplayBlocks.COVERAGE_KEY || name.startsWith("members:[\"formal.display.")
                            if (!compact) values[name] = index.get(name) ?: JsonNull
                        }
                    }
                }
                assertTrue(values.containsKey(CountTrustValidationEnvironment.KEY))
                assertTrue(values.containsKey(CountTrustRoundEvidence.METADATA))
                assertTrue(values.size > 10)
                values["format"] = JsonPrimitive(CountTrustSeal.LEGACY_ENVIRONMENT_VALIDATOR)
                val storage = sql.query("SELECT storageId FROM count_trust_control WHERE id=1").use {
                    assertTrue(it.moveToFirst()); it.getString(0)
                }
                val binding = CountTrustSeal.binding(access, storage, CountTrustSeal.LEGACY_ENVIRONMENT_VALIDATOR)
                val root = com.dayforge.data.history.CountTrustIndex.build(sql, binding, values) {}
                CountTrustSeal().prepare(binding, old.schemaHash, root, old.revision + 1,
                    old.generation, CountTrustSeal.LEGACY_ENVIRONMENT_VALIDATOR).also { CountTrustSeal.store(sql, it) }
            }
        }
    }

    internal suspend fun compactV3UpgradeChecksAllOldBytesAndMacBeforeReissuing() {
        openFormal(0); val graph = graph(); val before = compactHome(graph)
        val legacy = installEnvironmentV3ProjectionPredecessor()
        val bytes = compactBody(legacy.root.digest)
        compactBody(legacy.root.digest, bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        val corrupt = contents()
        assertNotNull(rejected { compactHome(graph) })
        assertEquals("Known version is not permission to overwrite damaged old content", corrupt, contents())
        compactBody(legacy.root.digest, bytes)
        suspend fun mac(value: ByteArray) = db.withTransaction {
            requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
                db.openHelper.writableDatabase.execSQL("UPDATE count_trust_root SET mac=? WHERE id=1", arrayOf(value))
            }
        }
        mac(ByteArray(32)); val badMac = contents()
        val rejectedMac = rejected { compactHome(graph) }
        assertNotNull(rejectedMac); assertEquals("COUNT_TRUST_SEAL_INVALID", requireNotNull(rejectedMac).message)
        assertEquals(badMac, contents()); mac(legacy.mac())
        val upgraded = observe("compact_v3_complete_checked_upgrade") { compactHome(graph) }
        assertEquals(before.batch.counts, upgraded.value.batch.counts)
        assertTrue(upgraded.sql.original.fullProofSentinels >= 2)
        assertTrue(upgraded.qualification.qualified)
        db.withTransaction { requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            val header = requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase))
            assertEquals(CountTrustSeal.VALIDATOR, header.validator)
            assertTrue(header.revision > legacy.revision)
        } }
        val stable = contents(); val warm = observe("compact_v4_warm_after_upgrade") { compactHome(graph) }
        assertEquals(upgraded.value.batch.counts, warm.value.batch.counts)
        assertEquals(0L, warm.sql.original.fullProofSentinels); assertEquals(stable, contents())
    }

    internal suspend fun compactCalendarTravelFallsBackToTheExactOriginalDisplayAlgorithms() {
        openFormal(8, historyFromBootstrap = true); val graph = graph(); compactHome(graph)
        val otherZone = if (zone == ZoneId.of("Etc/UTC")) ZoneId.of("Pacific/Kiritimati") else ZoneId.of("Etc/UTC")
        val time = ZonedDateTime.now(otherZone); val raw = contents()
        val projected = graph.dashboard.readProjection(time)
        for (current in projected.habits.filter { it.id in projected.counts }) {
            val full = graph.counts.readSummary(current, time.toLocalDate())
            assertEquals(projectCountDisplay(current, full, otherZone, time.toInstant().toEpochMilli()),
                projected.counts.getValue(current.id))
        }
        assertEquals(raw, contents())
        assertFalse("The original zone publication fence still applies", graph.dashboard.publish(projected) {
            fail("A non-current-zone batch must not be published")
        })
        assertEquals(raw, contents())
    }

    internal suspend fun compactSameHabitAckWithALocalSuccessorKeepsTheCommonCurrentResult() {
        openFormal(0); val graph = graph(); val initial = compactHome(graph)
        val first = graph.service.incrementCount(app, habit.id, initial.batch.counts.getValue(habit.id).actionIdentity) as CheckInResult.Success
        assertTrue(first.recorded)
        val firstSource = db.syncOutboxDao().getAll().single()
        val requests = sender(http)
        val delivery = requireNotNull(requests.sendOperation(access(), firstSource.operationId))
        val beforeNext = compactHome(graph)
        val second = graph.service.incrementCount(app, habit.id, beforeNext.batch.counts.getValue(habit.id).actionIdentity) as CheckInResult.Success
        assertTrue(second.recorded)
        val pending = compactHome(graph)
        val ack = observe("compact_same_habit_ack_with_successor") {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { requests.acceptOperation(delivery) }
        }
        assertEquals(NextOperationAcceptance.COMMITTED, ack.value)
        assertTrue(ack.qualification.qualified); assertEquals(0L, ack.sql.original.fullProofSentinels)
        val after = compactHome(graph)
        assertEquals(pending.batch.counts, after.batch.counts)
        assertEquals(2L, after.batch.counts.getValue(habit.id).todayQuantity)
        assertEquals(1, count("sync_outbox"))
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val original = graph.counts.readSummary(current, today)
        assertEquals(projectCountDisplay(current, original, after.batch.time.zone, after.batch.time.toInstant().toEpochMilli()),
            after.batch.counts.getValue(habit.id))
        assertEquals(NextOperationAcceptance.REPLAYED, requests.acceptOperation(delivery))
        assertEquals(after.batch.counts, compactHome(graph).batch.counts)
    }

    internal suspend fun compactExtraLegalAckMetadataInvalidatesAndRebuildsThroughTheOriginalProof() {
        openFormal(0); val graph = graph(); val initial = compactHome(graph)
        val saved = graph.service.incrementCount(app, habit.id, initial.batch.counts.getValue(habit.id).actionIdentity) as CheckInResult.Success
        assertTrue(saved.recorded)
        val before = compactHome(graph)
        val requests = sender(http); val queue = db.syncOutboxDao().getAll().single()
        val delivery = requireNotNull(requests.sendOperation(access(), queue.operationId))
        val metadata = requireNotNull(delivery.challengeMetadata)
        val extra = ChallengeBirth("activity_event", id(9841), initialChallengeRoundHead(rows.last().uuid))
        assertEquals(NextOperationAcceptance.COMMITTED,
            requests.acceptOperation(delivery.copy(challengeMetadata = metadata.copy(births = metadata.births + extra))))
        assertFalse("Extra legitimate metadata must retire optional qualification", qualification().qualified)
        val rebuilt = observe("compact_extra_ack_original_rebuild") { compactHome(graph) }
        assertTrue(rebuilt.sql.original.fullProofSentinels >= 2)
        assertTrue(rebuilt.qualification.qualified)
        assertEquals(before.batch.counts, rebuilt.value.batch.counts)
        assertEquals(1L, rebuilt.value.batch.counts.getValue(habit.id).todayQuantity)
        assertTrue(db.nextChallengeDao().births().any { it.entityUuid == extra.entityUuid })
    }

    internal suspend fun compactPublicationStillRequiresFreshOriginalAuthority() {
        openFormal(0); val graph = graph(); val shown = compactHome(graph)
        val before = contents()
        sessions.exclusive { tokens.clearTokens() }
        assertFalse(graph.dashboard.publish(shown.batch) { fail("Old compact data must not publish") })
        assertEquals(before, contents())
    }

    internal suspend fun localMetricUnexpectedNoopRollsBackWholeGroup() {
        openFormal(0, metricCount = 2); val graph = graph(); val displayed = home(graph)
        val saved = graph.service.incrementCount(app, habit.id,
            requireNotNull(displayed.batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        val claim = requireNotNull(saved.followUp).claim
        val currentHabit = requireNotNull(db.habitDao().getHabitById(habit.id))
        val currentMetrics = db.metricDao().getAllMetricsOnce().associateBy { it.id }
        val logs = currentMetrics.values.map { MetricLogEntity(metricId = it.id, date = System.currentTimeMillis(),
            value = 7.25, unit = it.unit, recordedTimezone = zone.id) }
        val before = contents()
        val producer = producer(); val scope = producer.captureRounds()
        val failure = rejected { producer.writeRounds(scope) {
            val ticket = requireNotNull(CountTrustKernel.beginMetricBatch(db, logs, currentMetrics, currentHabit, claim))
            val ids = db.metricLogDao().insertAll(logs)
            CountTrustKernel.bindMetricBatch(db, ticket, ids)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
        } }
        assertEquals("METRIC_LOCAL_EXTRA_EFFECT", failure.message)
        assertEquals(before, contents()); assertEquals(0, count("metric_logs")); assertTrue(qualification().qualified)
    }

    internal suspend fun originalMetricFallbackStillSaves(dropCoverage: Boolean) {
        openFormal(0, metricCount = 2); val graph = graph(); val shown = home(graph)
        val promptRequest = graph.prompt.captureCompletionPrompt()
        val saved = graph.service.incrementCount(app, habit.id,
            requireNotNull(shown.batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        graph.prompt.showCompletionPrompt(requireNotNull(saved.followUp), promptRequest)
        val state = requireNotNull(graph.prompt.postCheckInState.value); val prompt = requireNotNull(state.factPrompt)
        if (dropCoverage) sessions.exclusive { db.withTransaction {
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            val context = requireNotNull(CountTrustKernel(db, tokens, sessions).open(requireNotNull(tokens.localCoreWriteAccess())))
            owner.maintenance(db) {
                context.index.putAll(mapOf(CountTrustKernel.OBSERVATION_COVERAGE_KEY to null))
                CountTrustSeal.store(db.openHelper.writableDatabase, CountTrustSeal().prepare(context.header.binding,
                    context.header.schemaHash, context.index.root, context.header.revision + 1, context.header.generation))
            }
        } }
        val values = state.linkedMetrics.mapIndexed { index, info -> com.dayforge.ui.components.MetricValueInput(
            info.metricId, if (!dropCoverage && index == 0) -0.0 else 12.125 + index, "original fallback $index") }
        val result = observe(if (dropCoverage) "metric_missing_coverage_fallback" else "metric_negative_zero_fallback") {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                graph.prompt.recordMetricValues(habit.id, values, expectedFactPrompt = prompt)
            }
        }
        assertTrue(result.value); assertEquals(2, count("metric_logs"))
        assertTrue("Original fallback must remain observable", result.sql.original.fullProofSentinels > 0)
        assertEquals("Unsupported metric values/coverage retain all original source audits", 8L, result.sql.sourceScans)
        assertTrue(result.sql.decryptions > 0)
        for (input in values) {
            val log = requireNotNull(db.metricLogDao().getLatestLog(input.metricId))
            assertEquals(input.value, log.value, 0.0)
            val source = db.syncOutboxDao().getAll().single { it.recordType == "metric_log" && it.entityUuid == log.uuid }
            val original = requireNotNull(roundOperationIntent(originalIntent(source).intentJson))
            assertNull(original.context.head)
            assertEquals(SyncV2Mapper.metricObservation(log,
                requireNotNull(db.metricDao().getMetricById(input.metricId)).uuid), original.operation.payload)
        }
        graph.prompt.closePrompt(state)
        assertEquals(1L, home(graph).batch.counts.getValue(habit.id).todayQuantity)
        assertTrue(qualification().qualified)
    }

    internal suspend fun failedSecondMetricInsertRollsBackWholeGroup() {
        openFormal(0, metricCount = 2); val graph = graph(); val shown = home(graph)
        val saved = graph.service.incrementCount(app, habit.id,
            requireNotNull(shown.batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        val claim = requireNotNull(saved.followUp).claim
        val currentHabit = requireNotNull(db.habitDao().getHabitById(habit.id))
        val metrics = db.metricDao().getAllMetricsOnce().associateBy { it.id }
        val logs = metrics.values.map { MetricLogEntity(metricId = it.id, date = System.currentTimeMillis(),
            value = 3.125, unit = it.unit, recordedTimezone = zone.id) }
        val before = contents(); val producer = producer(); val scope = producer.captureRounds()
        var registered = false; var firstInserted = false; var secondAttempted = false
        val failure = rejected { producer.writeRounds(scope) {
            requireNotNull(CountTrustKernel.beginMetricBatch(db, logs, metrics, currentHabit, claim))
            registered = true
            val firstId = db.metricLogDao().insertForSync(logs.first())
            assertTrue(firstId > 0)
            assertEquals(logs.first().copy(id = firstId), db.metricLogDao().getById(firstId))
            assertEquals(1, db.syncOutboxDao().getAll().count {
                it.recordType == "metric_log" && it.entityUuid == logs.first().uuid
            })
            firstInserted = true
            // Deterministic real SQLite FK failure after the first fact and queue were inserted.
            assertNull(db.metricDao().getMetricById(Long.MAX_VALUE))
            secondAttempted = true
            db.metricLogDao().insertForSync(logs.last().copy(metricId = Long.MAX_VALUE))
        } }
        val diagnostic = "${failure.javaClass.name}:${failure.message}"
        record("metric_fk_rollback_diagnostic") {
            put("registered", registered); put("first_fact_and_source_inserted", firstInserted)
            put("second_fk_insert_attempted", secondAttempted); put("failure_class", failure.javaClass.name)
            put("failure_message", failure.message.orEmpty().take(160))
        }
        assertEquals(before, contents()); assertEquals(0, count("metric_logs")); assertTrue(qualification().qualified)
        assertTrue("Real plan registration was not reached; $diagnostic", registered)
        assertTrue("Real first fact/source insertion was not reached; $diagnostic", firstInserted)
        assertTrue("Second FK insertion was not reached; $diagnostic", secondAttempted)
        assertTrue("Expected actual SQLite FK rejection after first insertion; $diagnostic",
            failure is android.database.sqlite.SQLiteConstraintException)
    }

    /** Real bootstrap and original common read for independent COUNT plan/read-set gates. */
    internal suspend fun openCountAddPlanInputs(countdown: Boolean = false): List<HabitEntity> {
        openFormal(0, metricCount = 2, countdown = countdown)
        home(graph())
        assertTrue(qualification().qualified)
        return rows.map { requireNotNull(db.habitDao().getHabitByUuid(it.uuid)) }
    }

    /** Same existing loopback protocol oracle; no fake receipt or accepted source injection. */
    internal fun countAddPlanSender(): NextCoreRequestStore = sender(http)

    internal data class MetricScopeInputs(val currentHabit: HabitEntity, val metrics: Map<Long, MetricEntity>,
        val logs: List<MetricLogEntity>, val claim: WidgetFactClaim, val before: JsonObject,
        val header: CountTrustSeal.Header)

    /** Scope-gate setup uses a real formal bootstrap, actual COUNT and its current fact claim.
     * No history seed, root injection, custom producer or test-side maintenance bypass is used.
     */
    internal suspend fun openMetricScopeInputs(): MetricScopeInputs {
        openFormal(0, metricCount = 2)
        val graph = graph(); val shown = home(graph)
        val saved = graph.service.incrementCount(app, habit.id,
            requireNotNull(shown.batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        assertTrue(saved.recorded); assertTrue(qualification().qualified)
        val currentHabit = requireNotNull(db.habitDao().getHabitById(habit.id))
        val metrics = db.metricDao().getAllMetricsOnce().associateBy { it.id }
        assertEquals(2, metrics.size)
        val logs = metrics.values.map { MetricLogEntity(metricId = it.id, date = System.currentTimeMillis(),
            value = 9.125, unit = it.unit, note = "actual private scope gate", recordedTimezone = zone.id) }
        val before = contents()
        val header = db.withTransaction { requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase)) }
        return MetricScopeInputs(currentHabit, metrics, logs, requireNotNull(saved.followUp).claim, before, header)
    }

    /** Runs inside the real original writeRounds callback. Repeat original prompt, parent and
     * deletion checks before reaching the internal hook; the test controls only its binding.
     */
    internal suspend fun registerAndInsertMetricScope(inputs: MetricScopeInputs): Pair<CountTrustKernel.MetricInsertTicket, List<Long>> {
        assertTrue(db.inTransaction())
        val widgets = WidgetFactReader(db, tokens, sessions, preferences,
            CountHistoryReader(db, tokens, sessions), CheckHistoryReader(db, tokens, sessions))
        widgets.requireCompletionInTransaction(inputs.claim)
        assertEquals(inputs.currentHabit.uuid, db.habitDao().getVisibleHabitById(inputs.currentHabit.id)?.uuid)
        val deletion = NextMetricDeletionStore(db)
        for (metric in inputs.metrics.values) {
            deletion.requireWritable(metric.uuid)
            val current = requireNotNull(db.metricDao().getMetricById(metric.id))
            assertEquals(metric.uuid, current.uuid)
            assertEquals(NextStructureMapper.writeMetric(metric), NextStructureMapper.writeMetric(current))
            val link = requireNotNull(db.habitMetricLinkDao().getLink(inputs.currentHabit.id, metric.id))
            deletion.requireLinkWritable(link)
            assertTrue(link.isActive && link.promptOnComplete)
            assertEquals(inputs.currentHabit.uuid, link.habitUuid); assertEquals(metric.uuid, link.metricUuid)
        }
        val ticket = requireNotNull(CountTrustKernel.beginMetricBatch(db, inputs.logs, inputs.metrics,
            inputs.currentHabit, inputs.claim))
        val ids = db.metricLogDao().insertAll(inputs.logs)
        assertEquals(2, ids.size); assertEquals(2, ids.distinct().size)
        for ((log, id) in inputs.logs.zip(ids)) {
            assertEquals(log.copy(id = id), db.metricLogDao().getById(id))
            val sources = db.syncOutboxDao().getAll().filter { it.recordType == "metric_log" && it.entityUuid == log.uuid }
            assertEquals("The real insert must reach the existing outbox trigger", 1, sources.size)
            assertTrue(sources.single().id > 0); assertEquals(log.uuid, sources.single().wireEntityUuid)
            assertNull(db.nextRequestDao().origin(NEXT_OPERATION, sources.single().operationId))
        }
        assertEquals("Registration and real insertion cannot pre-issue a new root", inputs.header,
            CountTrustSeal.read(db.openHelper.writableDatabase))
        return ticket to ids
    }

    internal suspend fun assertMetricScopeRolledBack(inputs: MetricScopeInputs) {
        assertEquals("Every native durable value and the old root must survive rollback", inputs.before, contents())
        assertEquals(inputs.header, db.withTransaction { CountTrustSeal.read(db.openHelper.writableDatabase) })
        assertEquals(0, count("metric_logs"))
        assertTrue(db.syncOutboxDao().getAll().none { it.recordType == "metric_log" })
        assertTrue("Read-only qualification must remain usable without a rebuild", qualification().qualified)
    }

    internal suspend fun assertMetricScopeCommitted(inputs: MetricScopeInputs, ids: List<Long>) {
        assertEquals(2, count("metric_logs")); assertEquals(inputs.logs.size, ids.size)
        val sources = db.syncOutboxDao().getAll().filter { it.recordType == "metric_log" }
        assertEquals(inputs.logs.map { it.uuid }.toSet(), sources.map { it.entityUuid }.toSet())
        for ((log, id) in inputs.logs.zip(ids)) {
            assertEquals(log.copy(id = id), db.metricLogDao().getById(id))
            val source = sources.single { it.entityUuid == log.uuid }
            val origin = originalIntent(source)
            assertEquals(source.id, origin.queueId)
            val formal = requireNotNull(roundOperationIntent(origin.intentJson))
            assertEquals("metric_observation", formal.operation.entityType); assertNull(formal.context.head)
            assertNull(db.nextStructuralCausalDao().dependency(source.operationId))
        }
        assertTrue("The full original producer/footer must maintain the root", qualification().qualified)
        val committed = db.withTransaction { requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase)) }
        assertEquals(inputs.header.revision + 1, committed.revision)
        assertEquals(inputs.header.generation + 6, committed.generation)
    }

    /** Same real +1 entry with a nonempty immutable queue, then a legal non-profile quantity.
     * Query counts locate the omitted scans; complete source/origin and original history remain.
     */
    internal suspend fun countSourceEntryOnlyAndOriginalFallback() {
        openFormal(0, metricCount = 2)
        val graph = graph()
        val firstView = home(graph)
        val first = observe("source_first_count") {
            graph.service.incrementCount(app, habit.id, requireNotNull(firstView.batch.counts.getValue(habit.id).actionIdentity))
        }
        assertTrue((first.value as CheckInResult.Success).recorded)
        assertEquals(2L, first.sql.sourceScans); assertTrue(first.qualification.qualified)
        val retained = db.syncOutboxDao().getAll().single()
        val secondView = home(graph)
        val second = observe("source_count_with_backlog") {
            graph.service.incrementCount(app, habit.id, requireNotNull(secondView.batch.counts.getValue(habit.id).actionIdentity))
        }
        assertTrue((second.value as CheckInResult.Success).recorded)
        assertEquals(2L, second.sql.sourceScans); assertTrue(second.qualification.qualified)
        assertEquals(retained, db.syncOutboxDao().getById(retained.id))
        val beforeUnsupported = db.syncOutboxDao().getAll()
        val thirdView = home(graph)
        val unsupported = observe("source_legal_quantity_fallback") {
            graph.habits.logCompletion(app, habit.id, 2,
                countIdentity = requireNotNull(thirdView.batch.counts.getValue(habit.id).actionIdentity))
        }
        assertTrue(unsupported.value > 0L)
        assertEquals(8L, unsupported.sql.sourceScans)
        beforeUnsupported.forEach { assertEquals(it, db.syncOutboxDao().getById(it.id)) }
        assertEquals(3, count("sync_outbox")); assertEquals(3, count("next_request_origins"))
        val terminal = home(graph)
        assertEquals(4L, terminal.batch.counts.getValue(habit.id).todayQuantity)
        val before = contents()
        sessions.exclusive { db.withTransaction {
            val full = RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(
                requireNotNull(db.habitDao().getHabitById(habit.id)), today)
            assertEquals(4L, full.completions.sumOf { it.value.toLong() })
            db.syncOutboxDao().getAll().forEach { queue ->
                val origin = originalIntent(queue)
                assertEquals(NextRequestSql.rowHash(db, "sync_outbox", "id=?", arrayOf(queue.id)), origin.sourceHash)
                assertEquals(queue.entityUuid, decodeNextOperationIntent(origin.intentJson).entityUuid)
            }
        } }
        assertEquals(before, contents())
    }

    /** A fixed test-only native fault fires AFTER the real repository's rate statement.
     * The measured SQL window starts there, excluding baseline rate preparation work.
     * The selected route determines an exact ordering assertion; observed results never
     * decide which expectation to accept. All business/authority paths remain production.
     */
    internal suspend fun countSourceLateOversizedSourceRejects(field: SourceWindowNativeSpy.LateSourceField) =
        countSourceLateOversizedSourceRejects(field, widgetFallback = false)

    internal suspend fun countSourceLateOversizedWidgetSourceRejects(field: SourceWindowNativeSpy.LateSourceField) =
        countSourceLateOversizedSourceRejects(field, widgetFallback = true)

    private suspend fun countSourceLateOversizedSourceRejects(field: SourceWindowNativeSpy.LateSourceField,
        widgetFallback: Boolean) {
        val spy = requireNotNull(sourceWindowNativeSpy())
        openFormal(0)
        val graph = graph(); val displayed = home(graph)
        val identity = requireNotNull(displayed.batch.counts.getValue(habit.id).actionIdentity)
        // A real widget claim deterministically excludes CountAddExecutor through the
        // existing widgetClaim != null entry condition, while quantity1 retains the original
        // CountLocalSourcePlan. Quantity2 would disable that plan as well, before metadata.
        val widgetClaim = if (widgetFallback) {
            val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
            WidgetFactReader(db, tokens, sessions, preferences, graph.counts,
                CheckHistoryReader(db, tokens, sessions)).read(current).claim.also { claim ->
                assertEquals(current.uuid, claim.habitUuid); assertEquals(identity.roundHead, claim.roundHead)
                assertNull(claim.completionUuid)
            }
        } else null
        suspend fun perform(): Any = if (widgetFallback) {
            graph.habits.logCompletion(app, habit.id, 1, widgetClaim = requireNotNull(widgetClaim), countIdentity = identity)
        } else graph.service.incrementCount(app, habit.id, identity)
        val before = contents()
        spy.armAfterNextRateFault(field)
        val failure = try { perform(); null }
            catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error; error }
        val observed = spy.finishRateFault()
        assertTrue("The real rate UPDATE and late source UPDATE must each affect exactly one row", observed.injected)
        assertTrue("Expected original source refusal; got ${failure?.javaClass?.simpleName}:${failure?.message}", failure is NextRequestException)
        assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (failure as NextRequestException).reason)
        val window = observed.queries
        assertFalse(window.overflow)
        if (widgetFallback) assertTrue("The original widget producer must check source-window metadata before its fallback refusal",
            window.count(SourceWindowNativeSpy.Kind.JOURNAL_METADATA_SCALAR) > 0)
        else assertEquals("Typed +1 must refuse this source at its point preflight, before journal processing", 0,
            window.count(SourceWindowNativeSpy.Kind.JOURNAL_METADATA_SCALAR))
        assertTrue("Original source IDs must be bounded before the original size refusal",
            window.count(SourceWindowNativeSpy.Kind.SOURCE_IDS_BOUNDED) > 0)
        assertTrue("The original row size preflight must really run", window.count(SourceWindowNativeSpy.Kind.SOURCE_ROW_SIZE_PREFLIGHT) > 0)
        assertEquals(0, window.count(SourceWindowNativeSpy.Kind.JOURNAL_HEADERS_UNBOUNDED))
        assertEquals(0, window.count(SourceWindowNativeSpy.Kind.JOURNAL_CELLS_FULL))
        assertEquals(0, window.count(SourceWindowNativeSpy.Kind.SOURCE_ROW_FULL))
        assertTrue("The complete typed raw image must roll back exactly", before == contents())
        assertTrue(qualification().qualified)
        val retry = perform()
        if (widgetFallback) assertTrue("The same original widget entry must accept the legitimate retry", (retry as Long) > 0L)
        else assertTrue((retry as CheckInResult.Success).recorded)
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("next_request_origins"))
        assertTrue(qualification().qualified)
    }

    internal suspend fun countSourcePrivateScopeRejects(mode: String) {
        require(mode in setOf("child", "duplicate", "old", "cancel"))
        openFormal(0)
        val graph = graph(); home(graph)
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val writer = producer(); val captured = writer.captureRounds()
        val before = contents()
        var old: CountTrustKernel.CountSourceTicket? = null
        suspend fun writeOne(bind: suspend (CountTrustKernel.CountSourceTicket, Long) -> Unit) {
            writer.writeRounds(captured) {
                val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, value = 1,
                    date = today.atStartOfDay(zone).toInstant().toEpochMilli(),
                    actualCompletedAt = System.currentTimeMillis(), recordedTimezone = zone.id)
                val ticket = requireNotNull(CountTrustKernel.beginCountSource(db, current, fact))
                NextCountDayStore(db).capture(current, fact)
                val inserted = db.completionDao().insert(fact)
                bind(ticket, inserted)
            }
        }
        if (mode == "old") {
            val first = rejected { writeOne { ticket, id ->
                old = ticket; CountTrustKernel.bindCountSource(db, ticket, id)
                error("COUNT_SOURCE_TEST_ROLLBACK")
            } }
            assertEquals("COUNT_SOURCE_TEST_ROLLBACK", first.message)
            assertEquals(before, contents())
        }
        if (mode == "cancel") {
            val touched = java.util.concurrent.atomic.AtomicBoolean(false)
            kotlinx.coroutines.coroutineScope {
                val cancelled = launch {
                    writeOne { ticket, id ->
                        CountTrustKernel.bindCountSource(db, ticket, id)
                        touched.set(true)
                        kotlinx.coroutines.currentCoroutineContext().cancel()
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    }
                }
                cancelled.join(); assertTrue(cancelled.isCancelled)
            }
            assertTrue(touched.get())
        } else {
            var firstBindingReturned = false
            val failure = rejected { writeOne { ticket, id -> when (mode) {
                "child" -> kotlinx.coroutines.coroutineScope { CountTrustKernel.bindCountSource(db, ticket, id) }
                "duplicate" -> {
                    CountTrustKernel.bindCountSource(db, ticket, id); firstBindingReturned = true
                    CountTrustKernel.bindCountSource(db, ticket, id)
                }
                "old" -> CountTrustKernel.bindCountSource(db, requireNotNull(old), id)
                else -> error("Unexpected COUNT source scope case")
            } } }
            assertEquals(if (mode == "child") "COUNT_SOURCE_SCOPE_EXPIRED" else "COUNT_SOURCE_TICKET_CHANGED", failure.message)
            if (mode == "duplicate") assertTrue("The first real binding must finish before the duplicate rejection", firstBindingReturned)
        }
        assertEquals(before, contents()); assertTrue(qualification().qualified)
        val ready = home(graph)
        val saved = graph.service.incrementCount(app, habit.id,
            requireNotNull(ready.batch.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
        assertTrue(saved.recorded); assertTrue(qualification().qualified)
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("next_request_origins"))
    }

    internal suspend fun warmOldSessionCannotWriteOrAccept() {
        openFormal(0); val graph = graph()
        val warmed = observe("warm_authority_home") { home(graph) }
        val displayed = requireNotNull(warmed.value.batch.counts.getValue(habit.id).actionIdentity)
        val first = graph.service.incrementCount(app, habit.id, displayed) as CheckInResult.Success
        assertTrue(first.recorded)
        val queue = db.syncOutboxDao().getAll().single(); val requests = sender(http)
        val delivery = requireNotNull(requests.sendOperation(access(), queue.operationId))
        val stable = contents(); val oldAccess = access()
        // Same-account reauthentication revokes the old session even on a permanently bound device.
        tokens.saveLoginSession("replacement-access", "replacement-refresh", "member", id(1), false)
        assertNotEquals(oldAccess.session, access().session)
        val writeFailure = rejected { graph.service.incrementCount(app, habit.id, displayed) }
        assertEquals("COUNT_ACTION_EXPIRED", writeFailure.message)
        assertEquals(stable, contents())
        val ackFailure = rejected { requests.acceptOperation(delivery) }
        assertTrue(ackFailure is NextRequestException)
        assertEquals(NextRequestException.Reason.STALE_ACCESS, (ackFailure as NextRequestException).reason)
        assertEquals(stable, contents())
        assertEquals(1, count("sync_outbox")); assertEquals(1, count("completions")); assertEquals(0, count("next_acceptances"))
        assertEquals(1, count("next_transmissions")); assertEquals(0, count("next_challenge_births"))
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        record("revoked_session_terminal") { put("old_action_reason", writeFailure.message); put("old_ack_reason", ackFailure.reason.name)
            put("whole_raw_unchanged", true); put("real_prepared_delivery", true); put("old_session_revoked", true)
            put("outbox_preserved", 1); put("acceptances", 0); put("evidence_file", evidenceName) }
    }

    private suspend fun newFormalDelivery(graph: Graph): NextCoreDelivery<NextSyncPushResponse> {
        val shown = home(graph).batch.counts.getValue(habit.id)
        assertTrue("Formal root must exist before the new operation: " + CountTrustKernel.lastQualificationFailure,
            qualification().qualified)
        val result = graph.service.incrementCount(app, habit.id, requireNotNull(shown.actionIdentity)) as CheckInResult.Success
        assertTrue(result.recorded)
        assertTrue("Local write must maintain the root without a read rebuilding it", qualification().qualified)
        val row = db.syncOutboxDao().getAll().single()
        return requireNotNull(sender(http).sendOperation(access(), row.operationId)).also {
            assertTrue("Freezing the wire must retain formal qualification", qualification().qualified)
        }
    }

    private class SidecarRollback(val image: JsonObject) : RuntimeException()

    internal suspend fun sidecarMatchesOriginalAndOrderedNativeEffects() {
        openFormal(0); val delivery = newFormalDelivery(graph())
        val stable = contents()
        val sideEffect = rejected { db.withTransaction {
            val kernel = CountTrustKernel(db, tokens, sessions)
            val capture = requireNotNull(kernel.capture(requireNotNull(tokens.localCoreWriteAccess())))
            val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, delivery.requestId))
            val adapter = CountTrustRoundAck(db)
            val plan = requireNotNull(adapter.prepare(capture.context, access(), requireNotNull(roundOperationIntent(origin.intentJson)),
                NextChallengeStore(db).activeInTransaction(access()), requireNotNull(delivery.challengeMetadata)))
            adapter.apply(plan)
            // A real same-value business write must appear in the native log, even though a
            // final-value diff alone would miss it. Do not depend on a blocked trigger install.
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
            kernel.finish(capture, CountTrustKernel.Mutation.ACK, plan.effects, plan)
        } }
        assertEquals("COUNT_ACK_EXTRA_EFFECT", sideEffect.message)
        assertEquals(stable, contents())
        val expected = try { db.withTransaction {
            val core = requireNotNull(tokens.localCoreWriteAccess())
            val context = requireNotNull(CountTrustKernel(db, tokens, sessions).open(core))
            val original = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, delivery.requestId))
            val adapter = CountTrustRoundAck(db)
            val plan = requireNotNull(adapter.prepare(context, access(), requireNotNull(roundOperationIntent(original.intentJson)),
                NextChallengeStore(db).activeInTransaction(access()), requireNotNull(delivery.challengeMetadata)))
            assertNotNull(rejected { (plan.metadata.births as MutableList<ChallengeBirth>).clear() })
            assertNotNull(rejected { (plan.metadata.checkpoints as MutableList<ChallengeCheckpoint>).clear() })
            assertNotNull(rejected { (plan.metadata.checkpoints.first().records as MutableList<ChallengeRoundRecord>).clear() })
            val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
            val boundary = runtime.maintenance(db) { runtime.captureChangeBoundary() }
            adapter.apply(plan)
            val changes = runtime.maintenance(db) { runtime.pendingChanges(boundary) }
            assertEquals(plan.effects.size, changes.size)
            plan.effects.zip(changes).forEach { (effect, change) ->
                val fields = CountTrustJournal.COLUMNS.getValue(change.table)
                val table = context.rows.table(change.table)
                fun value(cells: List<CountTrustJournal.Cell>?) = cells?.let {
                    CountTrustRows.Row(table, table.columns.map { name -> it[fields.indexOf(name)] }).value
                }
                assertEquals(effect.table, change.table); assertEquals(effect.before?.value, value(change.old))
                assertEquals(effect.after?.value, value(change.new))
            }
            throw SidecarRollback(contents())
        } } catch (rollback: SidecarRollback) { rollback.image }
        assertEquals(stable, contents())
        db.withTransaction { NextChallengeStore(db).acknowledgeInTransaction(access(), requireNotNull(delivery.challengeMetadata)) }
        assertEquals("Independent original merger must yield every same typed row, including control", expected, contents())
    }

    internal suspend fun extraMetadataFallsBackAndWrongBirthRejects() {
        openFormal(0); val graph = graph(); val delivery = newFormalDelivery(graph)
        val before = contents(); val original = requireNotNull(delivery.challengeMetadata)
        val own = original.births.single()
        val wrong = original.copy(births = listOf(own.copy(head = initialChallengeRoundHead(rows.last().uuid))))
        assertNotNull(rejected { sender(http).acceptOperation(delivery.copy(challengeMetadata = wrong)) })
        assertEquals(before, contents())
        val extra = ChallengeBirth("activity_event", id(9841), initialChallengeRoundHead(rows.last().uuid))
        val expanded = original.copy(births = original.births + extra)
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery.copy(challengeMetadata = expanded)))
        assertTrue(db.nextChallengeDao().births().any { it.entityUuid == extra.entityUuid })
        assertFalse("Unknown legitimate metadata must invalidate, not forge coverage", qualification().qualified)
        val rebuilt = home(graph)
        assertEquals(1L, rebuilt.batch.counts.getValue(habit.id).todayQuantity)
        assertTrue(qualification().qualified)
        assertEquals(0, count("sync_outbox")); assertEquals(1, count("next_acceptances"))
        assertEquals(rebuilt.batch.counts.getValue(habit.id), RecurringCompletionEvidenceReader(db, tokens, sessions)
            .read(requireNotNull(db.habitDao().getHabitById(habit.id)), today).let { source ->
                CountHistory(today, source.policies, source.completions.groupBy { it.businessDate }
                    .mapValues { (_, facts) -> facts.sumOf { it.value.toLong() } }, source.policies[today],
                    source.unknownDates, source.completions, source.roundHead, source.countIdentity).summary
            })
    }

    internal suspend fun knownLegacyRootRebuildsButBadMacDoesNotRepair() {
        openFormal(0); val graph = graph(); home(graph)
        db.withTransaction {
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            val access = requireNotNull(tokens.localCoreWriteAccess())
            owner.maintenance(db) {
                val sql = db.openHelper.writableDatabase; val old = requireNotNull(CountTrustSeal.read(sql))
                val storage = sql.query("SELECT storageId FROM count_trust_control WHERE id=1").use { assertTrue(it.moveToFirst()); it.getString(0) }
                val binding = CountTrustSeal.binding(access, storage, CountTrustSeal.LEGACY_VALIDATOR)
                val root = com.dayforge.data.history.CountTrustIndex.build(sql, binding,
                    mapOf("format" to JsonPrimitive(CountTrustSeal.LEGACY_VALIDATOR), "eligible:${habit.uuid}" to JsonPrimitive(true))) {}
                CountTrustSeal.store(sql, CountTrustSeal().prepare(binding, old.schemaHash, root, old.revision + 1,
                    old.generation, CountTrustSeal.LEGACY_VALIDATOR))
            }
        }
        val legacyNode = db.withTransaction {
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            owner.maintenance(db) {
                val sql = db.openHelper.writableDatabase
                val digest = requireNotNull(CountTrustSeal.read(sql)).root.digest
                val bytes = sql.query("SELECT body FROM count_trust_nodes WHERE digest=?", arrayOf(digest)).use {
                    assertTrue(it.moveToFirst()); it.getBlob(0)
                }
                val changed = bytes.copyOf(); changed[0] = (changed[0].toInt() xor 1).toByte()
                sql.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(changed, digest))
                digest to bytes
            }
        }
        val invalidLegacy = contents()
        assertNotNull(rejected { home(graph) })
        assertEquals("Version retirement must not hide a broken legacy node", invalidLegacy, contents())
        db.withTransaction { requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            db.openHelper.writableDatabase.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(legacyNode.second, legacyNode.first))
        } }
        val rebuilt = observe("legacy_format_rebuild") { home(graph) }
        assertEquals(0L, rebuilt.value.batch.counts.getValue(habit.id).todayQuantity)
        assertTrue(rebuilt.sql.original.fullProofSentinels >= 2)
        assertTrue(qualification().qualified)
        db.withTransaction {
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            owner.maintenance(db) {
                val header = requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase))
                assertEquals(CountTrustSeal.VALIDATOR, header.validator)
                val damaged = header.mac(); damaged[0] = (damaged[0].toInt() xor 1).toByte()
                db.openHelper.writableDatabase.execSQL("UPDATE count_trust_root SET mac=? WHERE id=1", arrayOf(damaged))
            }
        }
        val before = contents()
        assertNotNull(rejected { home(graph) })
        assertEquals("An invalid MAC must not be silently replaced by a new root", before, contents())
    }

    internal suspend fun acceptedRoundsShareDayRuleWithoutMixingFacts() {
        openFormal(0); val graph = graph()
        val oldDelivery = newFormalDelivery(graph)
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(oldDelivery))
        val oldFact = db.completionDao().getAllCompletionsOnce().single()
        val oldPolicy = requireNotNull(db.countDayDao().get(habit.id, today.toString())).policy
        val requests = sender(http)
        val merger = NextSyncMergeStore(db, tokens, sessions,
            OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)),
            NextTimerRequestStore(db, tokens, sessions, requests))
        val state = requireNotNull(merger.state(access(), true))
        advanced = ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, id(9851), 1), id(4), id(9852),
            ChallengeRestartIntent(habit.uuid, id(9851), initialChallengeRoundUuid(habit.uuid), 0, 1))
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val newPlan = canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(current.copy(targetValue = 8)),
            Instant.ofEpochMilli(current.createdAt).toString()).let { change ->
            change.copy(sequence = state.cursor + 1, revision = 2, payload = JsonObject(change.payload + ("revision" to JsonPrimitive(2))))
        }
        val metadata = metadata()
        merger.page(access(), state, RoundSyncPullResponse(listOf(newPlan), state.cursor + 1, false, serverInstant,
            1, metadata.checkpoints, metadata.births))
        val reset = home(graph).batch.counts.getValue(habit.id)
        assertEquals(requireNotNull(advanced).head, reset.roundHead); assertEquals(0L, reset.todayQuantity)
        assertEquals(oldPolicy, reset.todayPolicy); assertTrue(qualification().qualified)
        val freshDelivery = newFormalDelivery(graph)
        assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(freshDelivery))
        assertTrue(qualification().qualified)
        val final = observe("new_round_warm") { home(graph) }
        assertEquals(0L, final.sql.original.fullProofSentinels)
        val visible = graph.counts.read(requireNotNull(db.habitDao().getHabitById(habit.id)), today)
        assertEquals(1L, visible.todayQuantity); assertEquals(oldPolicy, visible.todayPolicy)
        assertEquals(1, visible.completions.size); assertNotEquals(oldFact.uuid, visible.completions.single().uuid)
        assertEquals(2, db.completionDao().getAllCompletionsOnce().size)
        val original = RecurringCompletionEvidenceReader(db, tokens, sessions).read(requireNotNull(db.habitDao().getHabitById(habit.id)), today)
        assertEquals(original.completions, visible.completions); assertEquals(original.roundHead, visible.roundHead)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE completions SET value=value+1 WHERE id=?", arrayOf(oldFact.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val damaged = contents()
        assertNotNull(rejected { home(graph) })
        assertEquals("Hidden old-round corruption must still reject without repair", damaged, contents())
    }

    private class FormalTrace(private val observe: (String) -> Unit) : RoomDatabase.QueryCallback {
        val original = CountTrustProductionCostTrace()
        private var active = false
        private val shadows = AtomicLong(); private val uuids = AtomicLong(); private val decrypts = AtomicLong()
        private val sourceScans = AtomicLong()
        data class Sample(val original: CountTrustProductionCostTrace.Sample, val shadowScans: Long,
            val uuidScans: Long, val decryptions: Long, val sourceScans: Long)
        @Synchronized fun begin() { check(!active); shadows.set(0); uuids.set(0); decrypts.set(0); sourceScans.set(0); original.begin(); active = true }
        @Synchronized fun end(): Sample { check(active); active = false
            return Sample(original.end(SystemClock.elapsedRealtimeNanos()), shadows.get(), uuids.get(), decrypts.get(), sourceScans.get()) }
        @Synchronized fun decrypted() { if (active) decrypts.incrementAndGet() }
        @Synchronized override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            observe(sqlQuery)
            if (!active) return
            original.onQuery(sqlQuery, bindArgs)
            val text = sqlQuery.trim().replace('`', ' ').replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
            if (text == "select id from sync_outbox order by id limit 10001" ||
                text == "select id from timer_command_outbox order by id limit 10001") sourceScans.incrementAndGet()
            if (text == "select entityuuid from sync_entity_state where entitytype='activity_event' limit 10001") shadows.incrementAndGet()
            if (text == "select 1 from completions group by uuid having count(*) > 1 limit 1") uuids.incrementAndGet()
        }
    }
}
