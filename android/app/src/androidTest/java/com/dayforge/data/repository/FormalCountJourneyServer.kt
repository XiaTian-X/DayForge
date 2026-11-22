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

/** Fixed synthetic wire peer, adapted from the original formal profile fixture. The real
 * Hilt SyncManager performs discovery/register/bootstrap/SEND/ACK; no database is supplied. */
internal class FormalCountJourneyServer(nested: Boolean) : AutoCloseable {
    val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.now(zone)
    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
    private val born = today.minusDays(5).atStartOfDay(zone).toInstant()
    private val json = Json { encodeDefaults = true }
    private val monitor = Any()
    private val births = linkedMapOf<String, ChallengeBirth>()
    private val results = linkedMapOf<String, NextSyncOperationResult>()
    private val caps = listOf("sync.read", "structure.write", "facts.append", "timer.control")
    fun id(n: Int) = "ac610000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    val goal = HabitEntity(uuid = id(9), name = "Journey group", habitType = HabitType.GOAL,
        iconResId = 0, colorHex = "#123456", schedule = HabitSchedule.Daily,
        createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(),
        appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
        planMetadata = PlanStructureMetadata(born.toString(), 0, null, null, null, null, null, null))
    val selected = HabitEntity(uuid = id(10), name = "Journey COUNT", habitType = HabitType.COUNTING,
        iconResId = 0, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 1,
        targetCycles = null, parentHabitId = goal.uuid.takeIf { nested },
        createdAt = born.toEpochMilli(), updatedAt = born.toEpochMilli(), completionPolicy = "recurring",
        appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
        planMetadata = PlanStructureMetadata(born.toString(), 0, null, null, zone.id, null, null, null))
    val counts = listOf(selected, selected.copy(uuid = id(11), name = "Other COUNT", parentHabitId = null, targetValue = 7))
    val metrics = (0..1).map { index -> MetricEntity(uuid = id(30 + index), name = "Journey metric ${index + 1}",
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
    private val plans = (if (nested) listOf(goal) else emptyList()) + counts
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
            assertEquals("5", input.headers["x-dayforge-protocol"])
            assertEquals(id(2), input.headers["x-dayforge-server-instance"]); assertEquals(id(3), input.headers["x-dayforge-sync-epoch"])
            when (input.path) {
                "/api/v2/devices/register" -> {
                    val request = json.decodeFromString<DeviceRegisterRequest>(input.body.toString(Charsets.UTF_8))
                    assertEquals(5, request.protocolVersion)
                    reply(json.encodeToString(DeviceResponse(id(4), request.installationId, "android", isPrimaryEditor = true,
                        structuralEditEnabled = true, capabilityRevision = 1, capabilities = caps, lastSeenAt = now)))
                }
                "/api/v2/sync/rounds/bootstrap" -> {
                    assertTrue(input.target.contains("challenge_contract=1")); val meta = metadata()
                    val changes = plans.map { canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it), born.toString()) } +
                        metrics.map { canonical("metric", it.uuid, NextStructureMapper.writeMetric(it), born.toString()) } +
                        links.map { canonical("activity_metric_link", it.uuid, SyncV2Mapper.link(it), born.toString()) } + historical
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
    private fun push(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
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
        return reply(json.encodeToString(RoundSyncPushResponse(listOf(value), 1, meta.checkpoints, meta.births)))
    }
    fun acceptedKinds(): Map<String, String> = synchronized(monitor) { results.mapValues { it.value.entityType }.toMap() }
    fun assertTerminal() = synchronized(monitor) {
        assertEquals(3, results.size)
        assertEquals(3, server.requests.count { it.path == "/api/v2/sync/rounds/push" })
        assertEquals(1, server.requests.count { it.path == "/api/v2/sync/rounds/bootstrap" })
        assertFalse(server.requests.any { it.path == "/api/v2/sync/push" })
        assertEquals(9, births.size); assertEquals(today, LocalDate.now(zone)); assertEquals(zone, ZoneId.systemDefault())
    }
    override fun close() = server.close()
}
