package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.HabitDatabase
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Independent original-domain absence observation, NEVER an execution certificate.
 * The Store must separately prove current primary continuity, complete P/result membership,
 * original T1/result atomicity, fresh authorization and actual END. In particular a legacy
 * DELETE has no reserved new fact ID: target/source absence alone proves nothing about it.
 * This slower recovery-only path retains the existing full raw-storage limits and original
 * intent decoders; it does not turn the normal g fast-refusal into an absence shortcut.
 */
internal object CountCommandDomainNoEffects {
    class Checked internal constructor(val inputHash: String, val storageHash: String,
        val reserved: Set<Pair<String, String>>)

    suspend fun capture(database: HabitDatabase, intent: CountCommandRecords.Intent): Checked {
        check(database.inTransaction())
        val context = currentCoroutineContext()
        val sql = database.openHelper.writableDatabase
        NextRequestSql.requireOutboxEnabled(sql)
        // Original native row caps/type checks for BOTH queues; no selected-row-only bypass.
        NextRequestSql.sources(sql, "sync_outbox")
        NextRequestSql.sources(sql, "timer_command_outbox")
        val proof = NextReplicaStorageProof(database).readInTransaction()
        val tables = proof.contents.getValue("tables").jsonObject
        fun rows(table: String) = tables.getValue(table).jsonArray.map { it.jsonObject }
        fun text(row: JsonObject, key: String, nullable: Boolean = false): String? {
            val value = row.getValue(key).jsonObject
            require(value.keys == setOf("sqlite_type", "value"))
            val type = value.getValue("sqlite_type").jsonPrimitive.int
            if (nullable && type == Cursor.FIELD_TYPE_NULL) {
                require(value.getValue("value") == JsonNull); return null
            }
            require(type == Cursor.FIELD_TYPE_STRING && value.getValue("value").jsonPrimitive.isString) {
                "COMMAND_NO_EFFECTS_NATIVE_TYPE_CHANGED"
            }
            return value.getValue("value").jsonPrimitive.content
        }
        fun id(row: JsonObject, key: String): String = requireNotNull(text(row, key)).also {
            require(isContractUuid(it)) { "COMMAND_NO_EFFECTS_NATIVE_ID_CHANGED" }
        }
        val input = intent.body().getValue("input").jsonObject
        val reserved = when {
            intent.capture == CountCommandRecords.Capture.VERIFIED_NO_EFFECT -> emptySet()
            intent.action == CountCommandRecords.Action.METRIC_APPEND ->
                setOf("metric_observation" to CountCommandRecords.uuid(input, "reserved_fact_uuid"))
            intent.action == CountCommandRecords.Action.COUNT_ADD ->
                setOf("activity_event" to CountCommandRecords.uuid(input, "reserved_fact_uuid"))
            intent.action == CountCommandRecords.Action.COUNT_UNDO && !CountCommandLegacyInput.isLegacy(intent) ->
                setOf("activity_event" to CountCommandRecords.uuid(input, "reserved_revert_uuid"))
            intent.action == CountCommandRecords.Action.COUNT_UNDO -> emptySet()
            intent.action == CountCommandRecords.Action.LINKED_METRICS -> input.getValue("metrics").jsonArray.map { item ->
                "metric_observation" to CountCommandRecords.uuid(item.jsonObject, "reserved_fact_uuid")
            }.toSet().also { require(it.size == input.getValue("metrics").jsonArray.size && it.isNotEmpty()) }
            else -> error("COMMAND_REQUIRES_ORIGINAL_DOMAIN_HANDLER")
        }
        fun absent(type: String, uuid: String) {
            require(type to uuid !in reserved) { "COMMAND_EFFECT_OR_SOURCE_ALREADY_PRESENT" }
        }
        for ((table, type) in listOf("completions" to "activity_event", "metric_logs" to "metric_observation"))
            for (row in rows(table)) { context.ensureActive(); absent(type, id(row, "uuid")) }
        for (table in listOf("sync_entity_state", "next_challenge_births", "local_fact_submissions"))
            for (row in rows(table)) {
                context.ensureActive(); absent(requireNotNull(text(row, "entityType")), id(row, "entityUuid"))
            }
        for (row in rows("sync_outbox")) {
            context.ensureActive()
            val type = when (text(row, "recordType")) {
                "completion" -> "activity_event"
                "metric_log" -> "metric_observation"
                else -> continue // Other original kinds cannot be this command's fact.
            }
            absent(type, id(row, "entityUuid"))
            text(row, "wireEntityUuid", nullable = true)?.let { require(isContractUuid(it)); absent(type, it) }
        }
        val originKeys = hashSetOf<Pair<String, String>>()
        for (row in rows("next_request_origins")) {
            context.ensureActive()
            val kind = requireNotNull(text(row, "kind")); val requestId = id(row, "requestId")
            require(originKeys.add(kind to requestId)) { "COMMAND_NO_EFFECTS_DUPLICATE_ORIGIN" }
            val body = requireNotNull(text(row, "intentJson"))
            when (kind) {
                NEXT_OPERATION -> {
                    // The original private restart discriminator precedes the ordinary
                    // operation decoder. Its strict typed proposal contains no fact write;
                    // it remains a complete origin member for every transport/fate check.
                    val restart = restartProposal(body)
                    if (restart != null) {
                        require(restart.operationId == requestId) { "COMMAND_NO_EFFECTS_RESTART_ORIGIN_CHANGED" }
                    } else {
                        val operation = decodeNextOperationIntent(body)
                        require(operation.operationId == requestId)
                        absent(operation.entityType, operation.entityUuid)
                    }
                }
                NEXT_TIMER -> require(decodeNextTimerIntent(body).command.commandId == requestId)
                else -> error("COMMAND_NO_EFFECTS_UNKNOWN_ORIGIN_KIND")
            }
        }
        // An orphan transport/receipt/retirement must not hide a source whose original UUID
        // could no longer be inspected. Existing current-fate readers remain in use for
        // APPLIED results; this scan only establishes a COMPLETE absence candidate.
        for (table in listOf("next_transmissions", "next_acceptances", "next_rejections", "next_abandoned_requests"))
            for (row in rows(table)) {
                context.ensureActive()
                require(requireNotNull(text(row, "kind")) to id(row, "requestId") in originKeys) {
                    "COMMAND_NO_EFFECTS_SOURCE_FATE_INCOMPLETE"
                }
            }
        context.ensureActive()
        return Checked(intent.inputHash, proof.hash, java.util.Collections.unmodifiableSet(reserved.toSet()))
    }
}
