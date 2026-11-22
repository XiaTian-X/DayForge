package com.dayforge.data.repository

import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Fixed zero-history peer derived from FormalCountJourneyServer. The old authenticated
 * namespace is unchanged. Production SyncManager owns bootstrap/SEND/ACK/PULL; this peer
 * has no database handle. One opt-in response loss exercises actual same-operation replay. */
internal class RetainedJourneyServer : AutoCloseable {
    val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.now(zone)
    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
    private val born = today.minusDays(5).atStartOfDay(zone).toInstant()
    private val json = Json { encodeDefaults = true }
    private val monitor = Any()
    private val births = linkedMapOf<String, ChallengeBirth>()
    private val results = linkedMapOf<String, NextSyncOperationResult>()
    private val responseStatuses = mutableListOf<String>()
    fun computedStatuses(): List<String> = synchronized(monitor) { responseStatuses.toList() }
    private val caps = listOf("sync.read", "structure.write", "facts.append", "timer.control")
    fun id(n: Int) = com.dayforge.data.local.legacyGenerationId(n)
    val selected = HabitEntity(uuid = id(10), name = "Journey COUNT", habitType = HabitType.COUNTING,
        iconResId = 0, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 1,
        targetCycles = null, parentHabitId = null,
        createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(), completionPolicy = "recurring",
        appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
        planMetadata = PlanStructureMetadata(born.toString(), 0, null, null, zone.id, null, null, null))
    val counts = listOf(selected)
    val metrics = listOf(MetricEntity(uuid = id(30), name = "Formal retained metric", unit = "kg",
        decimalPlaces = 3, iconResId = 0, colorHex = "#123456", createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(),
        appearance = ObjectAppearance(IconReference.Role("metric.custom"), "#123456", "object")))
    private val links = metrics.mapIndexed { index, metric -> HabitMetricLinkEntity(habitId = 0,
        habitUuid = selected.uuid, metricId = 0, metricUuid = metric.uuid, promptOnComplete = true,
        uuid = id(40 + index), createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli()) }
    private fun canonical(type: String, uuid: String, body: JsonObject, created: String = now) =
        SyncV2Change(0, type, uuid, "upsert", 1, JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid),
            "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(created), "updated_at" to JsonPrimitive(now),
            "deleted_at" to JsonNull)), now, id(4))
    private val plans = counts
    private var loseNextResponse = false
    var lostResponses: Int = 0
        private set
    fun loseNextPushResponse() = synchronized(monitor) { check(!loseNextResponse); loseNextResponse = true }
    fun pushIds(): List<String> = server.requests.filter { it.path == "/api/v2/sync/rounds/push" }.map {
        json.decodeFromString<RoundSyncPushRequest>(it.body.toString(Charsets.UTF_8)).operations.single().operationId
    }
    private fun metadata() = ChallengeMetadata(1, counts.map { habit ->
        val record = ChallengeRoundRecord(initialChallengeRoundHead(habit.uuid), null, null, null)
        ChallengeCheckpoint(record.head, listOf(record))
    }, births.values.toList())
    private fun reply(value: String) = MaterialSocketServer.Reply(value.toByteArray())
    val server = MaterialSocketServer { input, _ -> synchronized(monitor) {
        if (input.path.endsWith("/identity")) {
            assertNull(input.headers["authorization"])
            reply(buildJsonObject { put("server_instance_id", id(2)); put("sync_epoch", id(3)); put("protocol_version", 5)
                put("capabilities", JsonArray(listOf(JsonPrimitive("sync.read")))); put("server_time", now) }.toString())
        } else {
            assertEquals("Bearer synthetic-legacy-access", input.headers["authorization"])
            assertEquals("5", input.headers["x-dayforge-protocol"])
            assertEquals(id(2), input.headers["x-dayforge-server-instance"]); assertEquals(id(3), input.headers["x-dayforge-sync-epoch"])
            when (input.path) {
                "/api/v2/devices/register" -> {
                    val request = json.decodeFromString<DeviceRegisterRequest>(input.body.toString(Charsets.UTF_8))
                    assertEquals(5, request.protocolVersion)
                    reply(json.encodeToString(DeviceResponse(id(4), request.installationId, "android", isPrimaryEditor = true,
                        structuralEditEnabled = true, capabilityRevision = 3, capabilities = caps, lastSeenAt = now)))
                }
                "/api/v2/sync/rounds/bootstrap" -> {
                    assertTrue(input.target.contains("challenge_contract=1")); val meta = metadata()
                    val changes = plans.map { canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it), born.toString()) } +
                        metrics.map { canonical("metric", it.uuid, NextStructureMapper.writeMetric(it), born.toString()) } +
                        links.map { canonical("activity_metric_link", it.uuid, SyncV2Mapper.link(it), born.toString()) }
                    reply(json.encodeToString(RoundSyncBootstrapResponse(changes, 20, now, emptyList(), 1, meta.checkpoints, meta.births)))
                }
                "/api/v2/sync/rounds/changes" -> {
                    assertTrue(input.target.contains("challenge_contract=1")); val meta = metadata()
                    reply(json.encodeToString(RoundSyncPullResponse(emptyList(), 20, false, now, 1, meta.checkpoints, meta.births)))
                }
                "/api/v2/sync/rounds/push" -> push(input)
                else -> error("FORMAL_JOURNEY_UNEXPECTED_ROUTE:${input.path}")
            }
        }
    } }
    private fun push(input: MaterialSocketServer.Input): MaterialSocketServer.Reply? {
        val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
        val op = request.operations.single(); val context = request.contexts.single()
        when (op.entityType) {
            "activity_event" -> {
                assertEquals(selected.uuid, op.payload.getValue("activity_uuid").jsonPrimitive.content)
                assertEquals(initialChallengeRoundHead(selected.uuid), context.head)
            }
            "metric_observation" -> { assertNull(context.head); assertFalse(context.legacyInitial); assertTrue(context.affectedHeads.isEmpty()) }
            else -> error("FORMAL_JOURNEY_UNEXPECTED_OPERATION:${op.entityType}")
        }
        val value = results[op.operationId]?.copy(status = "already_applied") ?: run {
            val wire = Json.parseToJsonElement(input.body.toString(Charsets.UTF_8)).jsonObject.getValue("operations").jsonArray.single().jsonObject
            val payload = wire.getValue("payload").jsonObject; val received = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
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
                results[op.operationId] = it
                if (op.entityType == "activity_event") births[op.entityUuid] = ChallengeBirth("activity_event", op.entityUuid, requireNotNull(context.head))
            }
        }
        val meta = metadata()
        responseStatuses += value.status
        if (loseNextResponse) {
            loseNextResponse = false; lostResponses++
            return null // The real server accepted it; the real client receives no response.
        }
        return reply(json.encodeToString(RoundSyncPushResponse(listOf(value), 1, meta.checkpoints, meta.births)))
    }
    fun acceptedKinds(): Map<String, String> = synchronized(monitor) { results.mapValues { it.value.entityType }.toMap() }
    fun requireTerminal(expectedResults: Int, expectedPushes: Int) = synchronized(monitor) {
        assertEquals(expectedResults, results.size)
        assertEquals(expectedPushes, server.requests.count { it.path == "/api/v2/sync/rounds/push" })
        assertEquals(1, server.requests.count { it.path == "/api/v2/sync/rounds/bootstrap" })
        assertFalse(server.requests.any { it.path == "/api/v2/sync/push" })
        assertEquals(today, LocalDate.now(zone)); assertEquals(zone, ZoneId.systemDefault())
        assertFalse(loseNextResponse)
    }
    override fun close() = server.close()
}
