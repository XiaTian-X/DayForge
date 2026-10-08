package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.isContractUuid
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Read-only evidence for effective quantities, not a repair or a replacement for retained wire bytes. */
internal class CountFactEvidence(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase

    suspend fun verify(habit: HabitEntity, facts: List<CompletionEntity>, policies: Map<LocalDate, CountDayPolicy>, account: String) {
        check(database.inTransaction())
        val originals = originals(habit, facts.mapTo(hashSetOf()) { it.uuid }, account)
        val reverted = acceptedReverts(habit)
        for (fact in facts) {
            currentCoroutineContext().ensureActive()
            check(fact.uuid !in reverted) { "COUNT_FACT_INVALID" }
            val original = originals[fact.uuid]
            val accepted = accepted(fact.uuid)
            if (accepted != null) {
                check(accepted.payload["event_type"] in quantityTypes) { "COUNT_FACT_INVALID" }
                val wasCheckIn = accepted.payload["event_type"] == JsonPrimitive("check_in")
                check(fact == NextCommonFactMapper.completion(accepted, habit, fact) &&
                    NextCommonFactMapper.countPolicy(accepted.payload) ==
                        (if (wasCheckIn) null else policies[fact.businessDate])) { "COUNT_FACT_INVALID" }
                // If retained locally, it must still be the same immutable fact, not a different sender's snapshot.
                original?.let { NextCommonFactProof.requireOriginalPayload(it, accepted,
                    accepted.payload.getValue("source_device_id").jsonPrimitive.content) }
            } else if (original != null) {
                val policy = NextCommonFactMapper.countPolicy(original)
                // A tracking-mode edit does not erase old check-ins or relabel them as known counts.
                val wasCheckIn = original["event_type"] == JsonPrimitive("check_in")
                val expected = SyncV2Mapper.completion(fact, if (wasCheckIn) habit.copy(habitType = HabitType.CHECK_IN) else habit)
                check(JsonObject(original.filterKeys { it != "count_policy" }) == expected &&
                    policy == (if (wasCheckIn) null else policies[fact.businessDate]) && (!wasCheckIn || fact.value == 1)) { "COUNT_FACT_INVALID" }
                val instant = Instant.ofEpochMilli(requireNotNull(fact.actualCompletedAt) { "COUNT_FACT_INVALID" })
                val zone = ZoneId.of(fact.recordedTimezone)
                check(instant.atZone(zone).toLocalDate() == fact.businessDate &&
                    fact.date == fact.businessDate.atStartOfDay(zone).toInstant().toEpochMilli()) { "COUNT_FACT_INVALID" }
            } else {
                // Unproven legacy quantities remain unknown; a known day's effective fact needs its own proof.
                check(fact.businessDate !in policies) { "COUNT_FACT_INVALID" }
            }
        }
    }

    private suspend fun accepted(id: String, revertParent: String? = null): SyncV2Change? {
        NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
            arrayOf("activity_event", id)) ?: return null
        val state = requireNotNull(database.syncOutboxDao().getState("activity_event", id))
        check(state.payloadJson != null &&
            state.payloadHash == syncPayloadHash(state.payloadJson)) { "COUNT_FACT_INVALID" }
        val body = Json.parseToJsonElement(state.payloadJson).jsonObject
        if (revertParent != null && (body["activity_uuid"] != JsonPrimitive(revertParent) ||
                body["event_type"] != JsonPrimitive("revert"))) return null
        check(!state.deleted && state.revision > 0) { "COUNT_FACT_INVALID" }
        return SyncV2Change(0, "activity_event", id, "upsert", state.revision, body,
            body.getValue("updated_at").jsonPrimitive.content).also(NextCommonFactMapper::validateOrdinaryFactSnapshot)
    }

    private suspend fun acceptedReverts(habit: HabitEntity): Set<String> {
        val targets = hashSetOf<String>()
        // A coarse SQL probe only selects candidates; full decoding verifies the exact identity and body.
        sql.query("SELECT entityUuid FROM sync_entity_state WHERE entityType=? AND payloadJson LIKE ? AND payloadJson LIKE ?",
            arrayOf("activity_event", "%${habit.uuid}%", "%\"event_type\"%:%\"revert\"%")).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val event = accepted(cursor.getString(0), habit.uuid) ?: continue
                targets += NextCommonFactMapper.revert(event, habit)
            }
        }
        return targets
    }

    private suspend fun originals(habit: HabitEntity, needed: Set<String>, account: String): Map<String, JsonObject> {
        if (needed.isEmpty()) return emptyMap()
        val found = hashMapOf<String, JsonObject>()
        // One streamed pass per activity, not one lifetime scan per fact. Batches only bound allocation,
        // never the number of days/events a user may retain; no JSON1 dependency on older Android SQLite.
        sql.query("SELECT requestId FROM next_request_origins WHERE kind=? AND intentJson LIKE ? AND intentJson LIKE ?",
            arrayOf(NEXT_OPERATION, "%${habit.uuid}%", "%\"entity_type\"%:%\"activity_event\"%")).use { cursor ->
            val batch = ArrayList<String>(128)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                batch += cursor.getString(0)
                if (batch.size == 128) { originalBatch(batch, habit, needed, account, found); batch.clear() }
            }
            if (batch.isNotEmpty()) originalBatch(batch, habit, needed, account, found)
        }
        return found
    }

    private suspend fun originalBatch(ids: List<String>, habit: HabitEntity, needed: Set<String>, account: String,
        found: MutableMap<String, JsonObject>) {
        val hashes = NextRequestSql.boundedRowHashes(sql, "next_request_origins", "requestId", ids, NEXT_OPERATION)
        val rows = if (hashes == null) emptyMap() else database.nextRequestDao().origins(NEXT_OPERATION, ids).associateBy { it.requestId }
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            requireNotNull(hashes?.get(id) ?: NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?",
                arrayOf(NEXT_OPERATION, id)))
            val origin = requireNotNull(if (hashes == null) database.nextRequestDao().origin(NEXT_OPERATION, id) else rows[id])
            check(origin.protocol == 5 && isContractUuid(origin.accountId) && origin.queueId > 0) { "COUNT_FACT_INVALID" }
            val operation = decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
            validateNextSyncOperation(operation)
            check(operation.operationId == id) { "COUNT_FACT_INVALID" }
            if (operation.entityType != "activity_event" || operation.action != "upsert" ||
                operation.payload["activity_uuid"] != JsonPrimitive(habit.uuid) || operation.payload["event_type"] !in quantityTypes) continue
            check(origin.accountId == account) { "COUNT_SESSION_CHANGED" }
            if (operation.entityUuid in needed) check(found.put(operation.entityUuid, operation.payload) == null) { "COUNT_FACT_INVALID" }
        }
    }

    private companion object {
        val quantityTypes = setOf(JsonPrimitive("check_in"), JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))
    }
}
