package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.isContractUuid
import java.time.LocalDate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class RecurringCompletionOriginal(val row: NextRequestOriginEntity, val payload: JsonObject,
    val rounds: NextRoundOperationIntent?)

/** Read-only ordinary fact evidence; no repair or replacement for retained original/wire bytes. */
internal class RecurringCompletionFactEvidence(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase

    suspend fun verify(habit: HabitEntity, facts: List<CompletionEntity>, policies: Map<LocalDate, CountDayPolicy>, account: String): Map<String, RecurringCompletionOriginal> {
        check(database.inTransaction())
        val originals = originals(habit, facts.mapTo(hashSetOf()) { it.uuid }, account)
        val reverted = acceptedReverts(habit)
        for (chunk in facts.chunked(128)) {
          val acceptedRows = acceptedFactRows(database, chunk.map { it.uuid })
          for (fact in chunk) {
            currentCoroutineContext().ensureActive()
            check(fact.uuid !in reverted) { "COUNT_FACT_INVALID" }
            val original = originals[fact.uuid]?.payload
            val accepted = if (acceptedRows == null) accepted(fact.uuid)
                else acceptedRows[fact.uuid]?.let { decodeAccepted(fact.uuid, it) }
            CountFactEvidenceRules.verify(habit, fact, policies[fact.businessDate], original,
                accepted, fact.uuid in reverted)
          }
        }
        return originals
    }

    private suspend fun accepted(id: String, revertParent: String? = null): SyncV2Change? {
        NextRequestSql.rowHash(database, "sync_entity_state", "entityType=? AND entityUuid=?",
            arrayOf("activity_event", id)) ?: return null
        val state = requireNotNull(database.syncOutboxDao().getState("activity_event", id))
        return decodeAccepted(id, state, revertParent)
    }

    private fun decodeAccepted(id: String, state: com.dayforge.data.local.entity.SyncEntityStateEntity,
        revertParent: String? = null): SyncV2Change? {
        check(state.payloadJson != null &&
            state.payloadHash == syncPayloadHash(state.payloadJson)) { "COUNT_FACT_INVALID" }
        val body = Json.parseToJsonElement(state.payloadJson).jsonObject
        if (revertParent != null && (body["activity_uuid"] != JsonPrimitive(revertParent) ||
                body["event_type"] != JsonPrimitive("revert"))) return null
        check(!state.deleted && state.revision > 0) { "COUNT_FACT_INVALID" }
        // Both private callers immediately validate with completion()/revert(). Those mappers
        // enforce the complete ordinary snapshot contract AND the parent/projection contract.
        // Do not separately repeat header/date/zone/source parsing before that same validation.
        return SyncV2Change(0, "activity_event", id, "upsert", state.revision, body,
            body.getValue("updated_at").jsonPrimitive.content)
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

    private suspend fun originals(habit: HabitEntity, needed: Set<String>, account: String): Map<String, RecurringCompletionOriginal> {
        if (needed.isEmpty()) return emptyMap()
        val found = hashMapOf<String, RecurringCompletionOriginal>()
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
        found: MutableMap<String, RecurringCompletionOriginal>) {
        val hashes = NextRequestSql.boundedRowHashes(sql, "next_request_origins", "requestId", ids, NEXT_OPERATION)
        val rows = if (hashes == null) emptyMap() else database.nextRequestDao().origins(NEXT_OPERATION, ids).associateBy { it.requestId }
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            requireNotNull(hashes?.get(id) ?: NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?",
                arrayOf(NEXT_OPERATION, id)))
            val origin = requireNotNull(if (hashes == null) database.nextRequestDao().origin(NEXT_OPERATION, id) else rows[id])
            check(origin.protocol == 5 && isContractUuid(origin.accountId) && origin.queueId > 0) { "COUNT_FACT_INVALID" }
            val rounds = roundOperationIntent(origin.intentJson)
            val operation = rounds?.operation ?: decodeNextOperationIntent(origin.intentJson)
            validateNextSyncOperation(operation)
            check(operation.operationId == id) { "COUNT_FACT_INVALID" }
            if (operation.entityType != "activity_event" || operation.action != "upsert" ||
                operation.payload["activity_uuid"] != JsonPrimitive(habit.uuid) || operation.payload["event_type"] !in quantityTypes) continue
            check(origin.accountId == account) { "COUNT_SESSION_CHANGED" }
            if (operation.entityUuid in needed) check(found.put(operation.entityUuid,
                RecurringCompletionOriginal(origin, operation.payload, rounds)) == null) { "COUNT_FACT_INVALID" }
        }
    }

    private companion object {
        val quantityTypes = setOf(JsonPrimitive("check_in"), JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))
    }
}
