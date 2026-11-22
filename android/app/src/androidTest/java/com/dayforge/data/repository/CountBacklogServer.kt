package com.dayforge.data.repository

import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Fixed two-habit, two-metric real HTTP peer. It never supplies a database or an ACK receipt.
 * The one optional wait delays a fully validated real response outside the server monitor;
 * timestamps describe server receipt/readiness/release-to-transport, not client receipt. */
internal class CountBacklogServer : AutoCloseable {
    val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.now(zone)
    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
    private val born = today.minusDays(5).atStartOfDay(zone).toInstant()
    private val json = Json { encodeDefaults = true }
    private val monitor = Any()
    private val births = linkedMapOf<String, ChallengeBirth>()
    private val results = linkedMapOf<String, NextSyncOperationResult>()
    private val caps = listOf("sync.read", "structure.write", "facts.append", "timer.control")
    fun id(n: Int) = "ac620000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    val selected = HabitEntity(uuid = id(10), name = "Backlog COUNT A", habitType = HabitType.COUNTING,
        iconResId = 0, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 1,
        targetCycles = null, parentHabitId = null,
        createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(), completionPolicy = "recurring",
        appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
        planMetadata = PlanStructureMetadata(born.toString(), 0, null, null, zone.id, null, null, null))
    val counts = listOf(selected, selected.copy(uuid = id(11), name = "Backlog COUNT B", parentHabitId = null, targetValue = 7))
    val metrics = (0..1).map { index -> MetricEntity(uuid = id(30 + index), name = "Backlog metric ${index + 1}",
        unit = if (index == 0) "kg" else "cm", decimalPlaces = 3, iconResId = 0, colorHex = "#123456",
        createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(),
        appearance = ObjectAppearance(IconReference.Role("metric.custom"), "#123456", "object")) }
    private val links = metrics.mapIndexed { index, metric -> HabitMetricLinkEntity(habitId = 0,
        habitUuid = selected.uuid, metricId = 0, metricUuid = metric.uuid, promptOnComplete = true,
        uuid = id(40 + index), createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli()) }
    private fun canonical(type: String, uuid: String, body: JsonObject, created: String = now) =
        SyncV2Change(0, type, uuid, "upsert", 1, JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid),
            "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(created), "updated_at" to JsonPrimitive(now),
            "deleted_at" to JsonNull)), now, id(4))
    private val historical = (0 until 8).map { index ->
        val habit = counts[index % 2]; val day = today.minusDays(index / 2L + 1)
        val at = day.atTime(12, 0).atZone(zone).toInstant().toString(); val uuid = id(100 + index)
        births[uuid] = ChallengeBirth("activity_event", uuid, initialChallengeRoundHead(habit.uuid))
        canonical("activity_event", uuid, buildJsonObject {
            put("activity_uuid", habit.uuid); put("event_type", "count_snapshot"); put("value", 1)
            put("count_policy", CountDayPolicy(habit.targetValue, false).toJson())
            put("occurred_at", at); put("local_date", day.toString()); put("timezone", zone.id)
            put("note", "synthetic historical COUNT"); put("source_type", "app"); put("source_device_id", id(4))
            put("external_event_id", JsonNull); put("metadata", buildJsonObject {}); put("received_at", now)
            for (key in listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "reverts_event_uuid")) put(key, JsonNull)
        }, at)
    }
    private val plans = counts
    private fun metadata() = ChallengeMetadata(1, counts.map { habit ->
        val record = ChallengeRoundRecord(initialChallengeRoundHead(habit.uuid), null, null, null)
        ChallengeCheckpoint(record.head, listOf(record))
    }, births.values.toList())
    private fun reply(value: String) = MaterialSocketServer.Reply(value.toByteArray())
    data class RequestSnapshot(
        val sequence: Int, val path: String, val requestId: String?, val entityType: String?,
        val entityUuid: String?, val receivedNanos: Long, val responseReadyNanos: Long?,
        val responseReleasedNanos: Long?, val replayed: Boolean
    )
    data class Snapshot(
        val requests: List<RequestSnapshot>, val acceptedKinds: Map<String, String>,
        val gateArmed: Boolean, val heldRequestId: String?, val heldNanos: Long?,
        val releaseRequestedNanos: Long?, val releasedNanos: Long?, val gateTimedOut: Boolean
    )
    private data class PreparedReply(val response: MaterialSocketServer.Reply, val sequence: Int, val gated: Boolean)
    private val observations = mutableListOf<RequestSnapshot>()
    private val originalRequests = linkedMapOf<String, ByteArray>()
    private val logicalRequests = linkedMapOf<String, String>()
    private val release = CountDownLatch(1)
    val held = CompletableDeferred<String>()
    private var gateArmed = false
    private var heldRequestId: String? = null
    private var heldNanos: Long? = null
    private var releaseRequestedNanos: Long? = null
    private var releasedNanos: Long? = null
    private var gateTimedOut = false

    fun armFirstMetricResponseGate() = synchronized(monitor) {
        check(!gateArmed && heldRequestId == null && release.count == 1L)
        check(results.values.none { it.entityType == "metric_observation" })
        gateArmed = true
    }

    /** Idempotent cleanup release; only a release after the actual hold qualifies a gated run. */
    fun releaseHeldResponse() {
        synchronized(monitor) {
            if (gateArmed && releaseRequestedNanos == null) releaseRequestedNanos = SystemClock.elapsedRealtimeNanos()
        }
        release.countDown()
    }

    val server = MaterialSocketServer { input, _ ->
        val received = SystemClock.elapsedRealtimeNanos()
        val prepared = synchronized(monitor) {
            check(observations.size < 128) { "BACKLOG_PEER_REQUEST_CAPACITY" }
            val sequence = observations.size
            observations += RequestSnapshot(sequence, input.path, null, null, null, received, null, null, false)
            val response = respond(input, sequence)
            val ready = SystemClock.elapsedRealtimeNanos()
            observations[sequence] = observations[sequence].copy(responseReadyNanos = ready)
            val row = observations[sequence]
            val gated = gateArmed && heldRequestId == null && row.entityType == "metric_observation"
            if (gated) {
                check(release.count == 1L && releaseRequestedNanos == null) { "BACKLOG_GATE_RELEASED_BEFORE_HOLD" }
                heldRequestId = requireNotNull(row.requestId)
                heldNanos = ready
            }
            PreparedReply(response, sequence, gated)
        }
        if (prepared.gated) {
            check(held.complete(requireNotNull(synchronized(monitor) { heldRequestId })))
            // A fixed server-side wait only; no Room/account/UI lock is held here.
            if (!release.await(12, TimeUnit.SECONDS)) {
                synchronized(monitor) { gateTimedOut = true }
                error("BACKLOG_REAL_RESPONSE_GATE_TIMEOUT")
            }
        }
        synchronized(monitor) {
            val at = SystemClock.elapsedRealtimeNanos()
            observations[prepared.sequence] = observations[prepared.sequence].copy(responseReleasedNanos = at)
            if (prepared.gated) releasedNanos = at
        }
        prepared.response
    }

    private fun respond(input: MaterialSocketServer.Input, sequence: Int): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) {
            assertEquals("GET", input.method); assertTrue(input.body.isEmpty()); assertNull(input.headers["authorization"])
            return reply(buildJsonObject { put("server_instance_id", id(2)); put("sync_epoch", id(3)); put("protocol_version", 5)
                put("capabilities", JsonArray(listOf(JsonPrimitive("sync.read")))); put("server_time", now) }.toString())
        }
        assertEquals("Bearer synthetic-epoch-access", input.headers["authorization"])
        assertEquals("5", input.headers["x-dayforge-protocol"])
        assertEquals(id(2), input.headers["x-dayforge-server-instance"])
        assertEquals(id(3), input.headers["x-dayforge-sync-epoch"])
        return when (input.path) {
            "/api/v2/devices/register" -> {
                assertEquals("POST", input.method)
                val request = json.decodeFromString<DeviceRegisterRequest>(input.body.toString(Charsets.UTF_8))
                assertEquals(5, request.protocolVersion)
                reply(json.encodeToString(DeviceResponse(id(4), request.installationId, "android", isPrimaryEditor = true,
                    structuralEditEnabled = true, capabilityRevision = 1, capabilities = caps, lastSeenAt = now)))
            }
            "/api/v2/sync/rounds/bootstrap" -> {
                assertEquals("GET", input.method); assertTrue(input.body.isEmpty())
                assertTrue(input.target.contains("challenge_contract=1")); val meta = metadata()
                val changes = plans.map { canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it), born.toString()) } +
                    metrics.map { canonical("metric", it.uuid, NextStructureMapper.writeMetric(it), born.toString()) } +
                    links.map { canonical("activity_metric_link", it.uuid, SyncV2Mapper.link(it), born.toString()) } + historical
                reply(json.encodeToString(RoundSyncBootstrapResponse(changes, 20, now, emptyList(), 1, meta.checkpoints, meta.births)))
            }
            "/api/v2/sync/rounds/changes" -> {
                assertEquals("GET", input.method); assertTrue(input.body.isEmpty())
                assertTrue(input.target.contains("challenge_contract=1")); val meta = metadata()
                reply(json.encodeToString(RoundSyncPullResponse(emptyList(), 20, false, now, 1, meta.checkpoints, meta.births)))
            }
            "/api/v2/sync/rounds/push" -> push(input, sequence)
            else -> error("BACKLOG_PEER_UNEXPECTED_ROUTE:${input.path}")
        }
    }

    private fun push(input: MaterialSocketServer.Input, sequence: Int): MaterialSocketServer.Reply {
        assertEquals("POST", input.method)
        val body = input.body.toString(Charsets.UTF_8)
        val request = json.decodeFromString<RoundSyncPushRequest>(body)
        assertEquals(1, request.challengeContract); assertEquals(id(4), request.deviceId)
        val op = request.operations.single(); val context = request.contexts.single()
        assertEquals(op.operationId, context.sourceUuid)
        assertEquals("upsert", op.action); assertNull(op.baseRevision)
        assertFalse(context.legacyInitial); assertTrue(context.affectedHeads.isEmpty())
        assertEquals("app", op.payload.getValue("source_type").jsonPrimitive.content)
        assertEquals(today.toString(), op.payload.getValue("local_date").jsonPrimitive.content)
        assertEquals(zone.id, op.payload.getValue("timezone").jsonPrimitive.content)
        assertEquals(today, Instant.parse(op.payload.getValue("occurred_at").jsonPrimitive.content).atZone(zone).toLocalDate())
        val logical = when (op.entityType) {
            "activity_event" -> {
                val activity = op.payload.getValue("activity_uuid").jsonPrimitive.content
                val habit = counts.single { it.uuid == activity }
                assertEquals(initialChallengeRoundHead(activity), context.head)
                assertEquals("count_snapshot", op.payload.getValue("event_type").jsonPrimitive.content)
                assertEquals(1, op.payload.getValue("value").jsonPrimitive.int)
                assertEquals(CountDayPolicy(habit.targetValue, false).toJson(), op.payload.getValue("count_policy"))
                "count/$activity"
            }
            "metric_observation" -> {
                assertNull(context.head)
                val metric = metrics.single { it.uuid == op.payload.getValue("metric_uuid").jsonPrimitive.content }
                val index = metrics.indexOf(metric)
                assertEquals((41.125 + index).toRawBits(), op.payload.getValue("value").jsonPrimitive.double.toRawBits())
                assertEquals(metric.unit, op.payload.getValue("unit").jsonPrimitive.content)
                assertEquals("", op.payload.getValue("note").jsonPrimitive.content)
                "metric/${metric.uuid}"
            }
            else -> error("BACKLOG_PEER_UNEXPECTED_OPERATION:${op.entityType}")
        }
        val previous = results[op.operationId]
        observations[sequence] = observations[sequence].copy(requestId = op.operationId,
            entityType = op.entityType, entityUuid = op.entityUuid, replayed = previous != null)
        val value = if (previous != null) {
            assertArrayEquals("Replayed operation must retain the full original request bytes",
                requireNotNull(originalRequests[op.operationId]), input.body)
            assertEquals(op.operationId, logicalRequests[logical])
            previous.copy(status = "already_applied")
        } else {
            assertFalse("Another ID cannot duplicate this fixed logical operation", logicalRequests.containsKey(logical))
            assertTrue(results.size < 4)
            assertFalse(births.containsKey(op.entityUuid))
            assertTrue(results.values.none { it.entityUuid == op.entityUuid })
            val wire = Json.parseToJsonElement(body).jsonObject.getValue("operations").jsonArray.single().jsonObject
            val payload = wire.getValue("payload").jsonObject
            val received = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
            val canonical = buildJsonObject {
                payload.forEach { (key, value) -> put(key, value) }
                put("public_id", wire.getValue("entity_uuid")); put("revision", 1); put("created_at", received)
                put("updated_at", received); put("deleted_at", JsonNull); put("received_at", received)
                put("note", payload["note"] ?: JsonPrimitive("")); put("source_type", payload["source_type"] ?: JsonPrimitive("app"))
                put("source_device_id", payload["source_device_id"]?.takeUnless { it == JsonNull } ?: JsonPrimitive(id(4)))
                put("external_event_id", payload["external_event_id"] ?: JsonNull); put("metadata", payload["metadata"] ?: buildJsonObject {})
                if (op.entityType == "activity_event") {
                    put("value", payload["value"] ?: JsonNull)
                    for (key in listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "reverts_event_uuid")) put(key, payload[key] ?: JsonNull)
                }
            }
            val result = buildJsonObject { put("operation_id", op.operationId); put("entity_type", op.entityType)
                put("entity_uuid", op.entityUuid); put("status", "applied"); put("revision", 1); put("entity", canonical) }
            json.decodeFromJsonElement<NextSyncOperationResult>(result).also {
                originalRequests[op.operationId] = input.body.copyOf()
                logicalRequests[logical] = op.operationId
                results[op.operationId] = it
                if (op.entityType == "activity_event") births[op.entityUuid] = ChallengeBirth("activity_event", op.entityUuid, requireNotNull(context.head))
            }
        }
        val meta = metadata()
        return reply(json.encodeToString(RoundSyncPushResponse(listOf(value), 1, meta.checkpoints, meta.births)))
    }

    fun acceptedKinds(): Map<String, String> = synchronized(monitor) { results.mapValues { it.value.entityType }.toMap() }
    fun snapshot(): Snapshot = synchronized(monitor) {
        Snapshot(observations.toList(), results.mapValues { it.value.entityType }.toMap(), gateArmed,
            heldRequestId, heldNanos, releaseRequestedNanos, releasedNanos, gateTimedOut)
    }
    fun assertTerminal() = synchronized(monitor) {
        assertEquals(4, results.size); assertEquals(4, originalRequests.size)
        assertEquals(counts.map { "count/${it.uuid}" }.toSet() + metrics.map { "metric/${it.uuid}" }.toSet(), logicalRequests.keys)
        assertEquals(2, results.values.count { it.entityType == "activity_event" })
        assertEquals(2, results.values.count { it.entityType == "metric_observation" })
        assertEquals(1, observations.count { it.path == "/api/v2/sync/rounds/bootstrap" })
        assertFalse(observations.any { it.path == "/api/v2/sync/push" })
        assertTrue(observations.all { it.responseReadyNanos != null && it.responseReleasedNanos != null &&
            it.receivedNanos <= it.responseReadyNanos && it.responseReadyNanos <= it.responseReleasedNanos })
        assertFalse(gateTimedOut)
        if (gateArmed) {
            assertTrue(held.isCompleted); assertFalse(held.isCancelled)
            assertEquals("metric_observation", results.getValue(requireNotNull(heldRequestId)).entityType)
            assertTrue(requireNotNull(heldNanos) <= requireNotNull(releaseRequestedNanos))
            assertTrue(requireNotNull(releaseRequestedNanos) <= requireNotNull(releasedNanos))
        } else {
            assertFalse(held.isCompleted); assertNull(heldRequestId); assertNull(heldNanos)
            assertNull(releaseRequestedNanos); assertNull(releasedNanos)
        }
        assertEquals(10, births.size)
        assertEquals(today, LocalDate.now(zone)); assertEquals(zone, ZoneId.systemDefault())
    }
    override fun close() { releaseHeldResponse(); server.close() }
}
