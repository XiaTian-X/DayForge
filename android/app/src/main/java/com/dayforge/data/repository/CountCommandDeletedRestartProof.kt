package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.domain.model.ChallengeRoundRecord
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Complete local restart dependencies of an accepted deleted activity. Metadata-only remote
 * records are not fabricated into local ACKs. Once ANY local side exists, all original sides
 * and the original materialized frontier/terminal/ACK/Plan proof are mandatory in this Tx. */
internal object CountCommandDeletedRestartProof {
    private const val LIMIT = 10_000
    private const val INVALID = "COMMAND_FOLLOWUP_DELETE_RESTART_SOURCE_CHANGED"

    suspend fun read(database: HabitDatabase, access: LocalSyncAccess, activity: String,
        metadata: ChallengeMetadata, origins: Set<String>, accepted: Set<String>): Map<String, String>? {
        return try {
        check(database.inTransaction())
        val requests = database.nextRequestDao()
        val restarts = database.nextRestartDao()
        val sent = ids(database, "SELECT requestId FROM next_transmissions WHERE kind=? ORDER BY requestId LIMIT 10001",
            arrayOf(NEXT_OPERATION)) ?: return null
        val materialized = ids(database, "SELECT operationId FROM next_restart_materializations ORDER BY operationId LIMIT 10001") ?: return null
        val plans = ids(database, "SELECT operationId FROM next_restart_plan_proofs ORDER BY operationId LIMIT 10001") ?: return null
        val rejected = ids(database, "SELECT requestId FROM next_rejections WHERE kind=? ORDER BY requestId LIMIT 10001",
            arrayOf(NEXT_OPERATION)) ?: return null
        val abandoned = ids(database, "SELECT requestId FROM next_abandoned_requests WHERE kind=? ORDER BY requestId LIMIT 10001",
            arrayOf(NEXT_OPERATION)) ?: return null
        val queued = queueMembers(database, activity, metadata.checkpoints.single { it.head.activityUuid == activity }
            .records.map { it.head.roundUuid }.toSet()) ?: return null
        require(origins.containsAll(accepted) && origins.containsAll(sent) && origins.containsAll(materialized) &&
            materialized.containsAll(plans) && origins.containsAll(rejected) && origins.containsAll(abandoned) &&
            origins.containsAll(queued.restartIds))

        val records = metadata.checkpoints.single { it.head.activityUuid == activity }.records
            .filter { it.head.generation > 0 }
        val byOperation = records.associateBy { requireNotNull(it.restartOperationUuid) }
        require(byOperation.size == records.size)
        val roundIds = records.map { it.head.roundUuid }.toSet()
        val local = origins + accepted + sent + materialized + plans + rejected + abandoned + queued.allIds
        val related = linkedSetOf<String>()
        related += queued.relatedIds
        // Retained metadata remains an independent member declaration if an origin's target
        // is damaged. A completely remote record keeps the original metadata-only contract.
        for (id in byOperation.keys) {
            currentCoroutineContext().ensureActive()
            if (id in local || NextRequestSql.rowHash(database, "one_time_transmissions", "operationId=?", arrayOf(id)) != null)
                related += id
        }
        for (id in origins) {
            currentCoroutineContext().ensureActive()
            requireNotNull(rowHash(database, "next_request_origins", id))
            val origin = requireNotNull(requests.origin(NEXT_OPERATION, id))
            val proposal = restartProposal(origin.intentJson)
            if (proposal != null) {
                require(proposal.operationId == id)
                if (proposal.head.activityUuid == activity || proposal.head.roundUuid in roundIds) related += id
            } else {
                val operation = decodeNextOperationIntent(origin.intentJson)
                require(operation.operationId == id)
                if (declaresOperation(Json.encodeToJsonElement(operation).jsonObject, activity, roundIds)) related += id
            }
        }
        val receipts = linkedMapOf<String, NextSyncOperationResult>()
        for (id in accepted) {
            currentCoroutineContext().ensureActive()
            requireNotNull(rowHash(database, "next_acceptances", id))
            val saved = requireNotNull(requests.acceptance(NEXT_OPERATION, id))
            val result = decodeFrozenSyncRequest(saved.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
            receipts[id] = result
            if (declaresRecord(result.entity, activity, roundIds) ||
                result.entityType == "challenge_round" && result.entityUuid in roundIds) related += id
        }
        // The other retained sides cannot hide a related request by changing only the
        // proposal/receipt discriminator. These are bounded inventories, never LIKE filters.
        for (id in sent) {
            currentCoroutineContext().ensureActive()
            requireNotNull(rowHash(database, "next_transmissions", id))
            val saved = requireNotNull(requests.transmission(NEXT_OPERATION, id))
            val wire = decodeNextOperationEnvelope(saved.wireBytes)
            if (wire.operations.any { declaresOperation(Json.encodeToJsonElement(it).jsonObject, activity, roundIds) }) related += id
        }
        for (id in materialized) {
            currentCoroutineContext().ensureActive()
            requireNotNull(rowHash(database, "next_restart_materializations", id))
            val saved = requireNotNull(restarts.materialization(id))
            val operation = decodeFrozenSyncRequest(saved.operationJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
            if (operation.payload["activity_uuid"] == JsonPrimitive(activity) || operation.entityUuid in roundIds) related += id
        }
        for (id in plans) {
            currentCoroutineContext().ensureActive()
            requireNotNull(rowHash(database, "next_restart_plan_proofs", id))
            val saved = requireNotNull(restarts.planProof(id))
            val plan = decodeFrozenSyncRequest(saved.planJson.toByteArray(Charsets.UTF_8), JsonObject.serializer())
            if (plan["public_id"] == JsonPrimitive(activity)) related += id
        }

        val store = NextRestartStore(database)
        val result = linkedMapOf<String, String>()
        for (id in related.sorted()) {
            currentCoroutineContext().ensureActive()
            require(id in origins)
            val source = requireNotNull(requests.origin(NEXT_OPERATION, id))
            val proposal = requireNotNull(restartProposal(source.intentJson))
            require(proposal.operationId == id && proposal.head.activityUuid == activity)
            // A live/unresolved request is not an accepted historical dependency.
            if (id !in accepted) return null
            require(rowHash(database, "next_rejections", id) == null && rowHash(database, "next_abandoned_requests", id) == null &&
                NextRequestSql.rowHash(database, "one_time_transmissions", "operationId=?", arrayOf(id)) == null)
            val record = requireNotNull(byOperation[id])
            val original = store.original(access, id)
            require(original.value == proposal && original.value.head == record.head &&
                original.value.deviceId == record.sourceDeviceUuid)
            // Same original recovery order: full materialization + actual Plan/timer
            // frontiers first, then the real accepted restart and contiguous Plan proof.
            val operation = store.recoveryMaterializedFrontier(access, original)
            val ready = NextRestartBindingStore(database).ready(access, original.reference)
            val receipt = requireNotNull(receipts[id])
            require(receipt.operationId == id && receipt.entityType == "challenge_round" &&
                receipt.entityUuid == record.head.roundUuid)
            receipt.entity?.let { entity ->
                val declared = decodeFrozenSyncRequest(entity.toString().toByteArray(Charsets.UTF_8), ChallengeRoundRecord.serializer())
                require(declared == record)
            }
            result[id] = CountCommandRecords.hash("accepted-deleted-restart-source", buildJsonObject {
                put("origin", original.hash)
                put("materialization", requireNotNull(rowHash(database, "next_restart_materializations", id)))
                put("transmission", requireNotNull(rowHash(database, "next_transmissions", id)))
                put("acceptance", requireNotNull(rowHash(database, "next_acceptances", id)))
                put("accepted_plan", ready.second)
                put("operation", Json.encodeToJsonElement(operation)); put("record", Json.encodeToJsonElement(record))
            }.toString())
        }
        result
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { throw IllegalStateException(INVALID, error) }
    }

    private fun declaresRecord(entity: JsonObject?, activity: String, rounds: Set<String>): Boolean {
        val head = entity?.get("head") as? JsonObject
        val intent = entity?.get("restart_intent") as? JsonObject
        return head?.get("activity_uuid") == JsonPrimitive(activity) || intent?.get("activity_uuid") == JsonPrimitive(activity) ||
            (head?.get("round_uuid") as? JsonPrimitive)?.contentOrNull in rounds ||
            (intent?.get("round_uuid") as? JsonPrimitive)?.contentOrNull in rounds
    }

    private fun declaresOperation(operation: JsonObject?, activity: String, rounds: Set<String>): Boolean {
        val actual = operation ?: return false
        // A direct known-round declaration survives even a malformed/missing payload.
        if (actual["entity_type"] == JsonPrimitive("challenge_round") &&
            (actual["entity_uuid"] as? JsonPrimitive)?.contentOrNull in rounds) return true
        val payload = actual["payload"] as? JsonObject ?: return false
        val restart = actual["entity_type"] == JsonPrimitive("challenge_round") ||
            "round_uuid" in payload || "expected_round_uuid" in payload
        return restart && (payload["activity_uuid"] == JsonPrimitive(activity) ||
            (actual["entity_uuid"] as? JsonPrimitive)?.contentOrNull in rounds ||
            (payload["round_uuid"] as? JsonPrimitive)?.contentOrNull in rounds)
    }

    private data class QueueMembers(val allIds: Set<String>, val restartIds: Set<String>, val relatedIds: Set<String>)

    private suspend fun queueMembers(database: HabitDatabase, activity: String, rounds: Set<String>): QueueMembers? {
        val keys = arrayListOf<Long>()
        database.openHelper.writableDatabase.query("SELECT id FROM sync_outbox ORDER BY id LIMIT 10001").use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (keys.size == LIMIT) return null
                require(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) > 0)
                keys += cursor.getLong(0)
            }
        }
        val all = linkedSetOf<String>(); val restartIds = linkedSetOf<String>(); val related = linkedSetOf<String>()
        for (key in keys) {
            currentCoroutineContext().ensureActive()
            requireNotNull(NextRequestSql.rowHash(database, "sync_outbox", "id=?", arrayOf(key)))
            val row = requireNotNull(database.syncOutboxDao().getById(key))
            require(row.id == key && isContractUuid(row.operationId) && all.add(row.operationId))
            val proposal = row.payloadJson?.let { restartProposal(it) }
            val alternative = if (row.payloadJson == null || proposal != null) false else {
                val body = decodeFrozenSyncRequest(row.payloadJson.toByteArray(Charsets.UTF_8), JsonObject.serializer())
                declaresOperation((body["operation"] as? JsonObject) ?: body, activity, rounds)
            }
            if (row.recordType == RESTART_RECORD || proposal != null || alternative) {
                require(row.recordType == RESTART_RECORD && proposal != null && proposal.operationId == row.operationId &&
                    row.entityUuid == proposal.head.activityUuid && row.wireEntityUuid == proposal.head.roundUuid)
                restartIds += row.operationId
                if (proposal.head.activityUuid == activity || proposal.head.roundUuid in rounds) related += row.operationId
            }
        }
        return QueueMembers(all, restartIds, related)
    }

    private suspend fun ids(database: HabitDatabase, query: String, args: Array<out Any> = emptyArray()): Set<String>? {
        val result = linkedSetOf<String>()
        database.openHelper.writableDatabase.query(query, args).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (result.size == LIMIT) return null
                require(cursor.getType(0) == Cursor.FIELD_TYPE_STRING)
                val id = cursor.getString(0); require(isContractUuid(id) && result.add(id))
            }
        }
        return result
    }

    private suspend fun rowHash(database: HabitDatabase, table: String, id: String): String? =
        if (table in setOf("next_restart_materializations", "next_restart_plan_proofs"))
            NextRequestSql.rowHash(database, table, "operationId=?", arrayOf(id))
        else NextRequestSql.rowHash(database, table, "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id))
}
