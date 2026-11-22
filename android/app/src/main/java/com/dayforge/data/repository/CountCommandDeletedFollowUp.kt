package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** SELECT-only witness for an original accepted COUNT followed by an exact accepted activity
 * deletion. Projection absence is only a consistency check AFTER positive source/ACK proofs.
 * The caller still owns fresh authority, Root/member/g, NoEffects, NoHint and the real END.
 * No pending-delete/FK-skip proof, pulled tombstone, replacement authority or network is used. */
internal object CountCommandDeletedFollowUp {
    private const val LIMIT = 10_000

    suspend fun read(database: HabitDatabase, tokens: TokenManager, intent: CountCommandRecords.Intent,
        outcome: CountCommandOutcome, access: LocalCoreWriteAccess): String? {
        check(database.inTransaction())
        val sync = tokens.localSyncAccess() ?: return null
        require(sync.session == access.session && sync.deviceId == access.capturedDeviceId) {
            "COMMAND_FOLLOWUP_AUTHORITY_CHANGED"
        }
        val input = intent.body().getValue("input").jsonObject
        val activity = CountCommandRecords.uuid(input, "habit_uuid")
        val factUuid = requireNotNull(outcome.primaryFactUuid)
        val frozen = outcome.evidence.records.single { it.role == CountCommandEvidence.Role.INSERTED_FACT && it.text("uuid") == factUuid }
        require(frozen.table == "completions" && frozen.nullableText("habitUuid") == activity &&
            frozen.number("habitId") == CountCommandRecords.integer(input, "habit_id"))
        val originalSummary = outcome.origins.single { it.entityUuid == factUuid }
        val sql = database.openHelper.writableDatabase
        val dao = database.nextRequestDao()
        val origins = ids(database, "SELECT requestId FROM next_request_origins WHERE kind=? ORDER BY requestId LIMIT 10001") ?: return null
        val accepted = ids(database, "SELECT requestId FROM next_acceptances WHERE kind=? ORDER BY requestId LIMIT 10001") ?: return null
        require(origins.containsAll(accepted)) { "COMMAND_FOLLOWUP_REQUEST_ORPHAN" }
        val checkpoint = NextChallengeStore(database).activeInTransaction(sync)
        val restartProofs = CountCommandDeletedRestartProof.read(database, sync, activity, checkpoint.second,
            origins, accepted) ?: return null
        val acceptedDeclarations = linkedSetOf<String>()
        for (id in accepted) {
            currentCoroutineContext().ensureActive()
            requireNotNull(hash(database, "next_acceptances", id))
            val receipt = requireNotNull(dao.acceptance(NEXT_OPERATION, id))
            require(receipt.kind == NEXT_OPERATION && receipt.requestId == id &&
                receipt.resultHash == nextRequestHash(receipt.resultJson.toByteArray(Charsets.UTF_8)))
            val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
            // Either independently retained side can declare this target. A damaged origin
            // must not hide a related accepted receipt before full source validation.
            if (result.entityUuid == activity || result.entity?.get("public_id") == JsonPrimitive(activity)) {
                require(result.entityType == "plan_node" && result.entityUuid == activity &&
                    result.entity?.get("public_id") == JsonPrimitive(activity)) { "COMMAND_FOLLOWUP_DELETE_SOURCE_CHANGED" }
                acceptedDeclarations += id
            }
        }
        val structural = NextStructuralCausalStore(database)
        val deletions = arrayListOf<String>()
        val relatedProofs = linkedMapOf<String, String>()
        // Full bounded original inventory: no LIKE/JSON spelling prefilter or LIMIT 1 may hide
        // a contradictory accepted source for this UUID. Overflow cannot prove uniqueness.
        for (id in origins) {
            currentCoroutineContext().ensureActive()
            requireNotNull(hash(database, "next_request_origins", id))
            val source = requireNotNull(dao.origin(NEXT_OPERATION, id))
            val proposal = restartProposal(source.intentJson)
            if (proposal != null) {
                require(proposal.operationId == id && id !in acceptedDeclarations) { "COMMAND_FOLLOWUP_DELETE_SOURCE_CHANGED" }
                continue
            }
            val operation = decodeNextOperationIntent(source.intentJson)
            require(operation.operationId == id)
            if (operation.entityUuid != activity) {
                require(id !in acceptedDeclarations) { "COMMAND_FOLLOWUP_DELETE_SOURCE_CHANGED" }
                continue
            }
            require(operation.entityType == "plan_node") { "COMMAND_FOLLOWUP_DELETE_SOURCE_CHANGED" }
            // The first slice is deliberately exact accepted originals. Unsettled or retired
            // related structure, including a superseded alias without its own ACK, stays pending.
            if (id !in accepted) return null
            noContradictoryResult(database, id)
            val proof = structural.recoveryAcceptedOperationProof(id, sync)
            require(proof.first == operation)
            relatedProofs[id] = proof.second
            if (operation.action == "delete") {
                require(operation.payload.isEmpty()) { "COMMAND_FOLLOWUP_DELETE_SOURCE_CHANGED" }
                deletions += id
            }
        }
        if (deletions.size != 1 || originalSummary.requestId !in accepted) return null
        val deleteId = deletions.single()
        val deletion = requireNotNull(dao.origin(NEXT_OPERATION, deleteId))
        val original = requireNotNull(dao.origin(NEXT_OPERATION, originalSummary.requestId))
        require(NextRequestSql.immutableOriginHash(original) == originalSummary.immutableOriginHash &&
            deletion.queueId > original.queueId) { "COMMAND_FOLLOWUP_DELETE_ORDER_CHANGED" }
        noContradictoryResult(database, originalSummary.requestId)
        val countProof = NextAcceptedFactProofStore(database).read(sync, originalSummary.requestId)
        val count = requireNotNull(roundOperationIntent(original.intentJson))
        val deleted = requireNotNull(roundOperationIntent(deletion.intentJson))
        require(count.operation == countProof.first && count.operation.entityType == "activity_event" &&
            count.operation.action == "upsert" && count.operation.entityUuid == factUuid &&
            count.operation.payload["activity_uuid"] == JsonPrimitive(activity) &&
            count.operation.payload["event_type"] in setOf(JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot")) &&
            count.capturedDeviceId == sync.deviceId && deleted.capturedDeviceId == sync.deviceId) {
            "COMMAND_FOLLOWUP_ORIGINAL_ROUND_CHANGED"
        }
        val captured = input.getValue("round").jsonObject
        require(captured.keys == setOf("profile", "head", "initial", "restart") &&
            captured["profile"] == JsonPrimitive("rounds/v1"))
        val head = Json.decodeFromJsonElement<ChallengeRoundHead>(captured.getValue("head"))
        require(head.activityUuid == activity && count.context.head == head)
        require(checkpoint.second.requireBirth("activity_event", factUuid, activity).head == head)
        val lineage = structural.recoveryLineage(deleteId, sync)
        require(lineage.actualRequestId == deleteId && lineage.links.isNotEmpty() &&
            lineage.links.first().actualKey == NextRecoveryKey(NEXT_OPERATION, deleteId) &&
            lineage.links.all { it.state == NextRecoveryRequestState.ACCEPTED }) {
            "COMMAND_FOLLOWUP_DELETE_ANCESTRY_UNRESOLVED"
        }
        // The authenticated T1 retains the exact original row. ACK shadows retain normalized
        // server fields after FK projections/day rows have legitimately gone; do not fabricate
        // a HabitEntity, count_days row or current projection to feed a live-history reader.
        val countChange = acceptedChange(database, sync, originalSummary.requestId, count.operation)
        val policy = requireNotNull(NextCommonFactMapper.countPolicy(count.operation.payload))
        CountFactEvidenceRules.verifyPendingFields(activity, Math.toIntExact(frozen.number("value")),
            frozen.nullableNumber("actualCompletedAt"), frozen.number("date"), frozen.text("recordedLocalDate"),
            frozen.text("recordedTimezone"), policy, count.operation.payload)
        require(countChange.operation == "upsert")
        val countShadow = exactShadow(database, countChange)
        val deleteChange = acceptedChange(database, sync, deleteId, deleted.operation)
        require(deleteChange.operation == "delete" && deleteChange.entityType == "plan_node" &&
            deleteChange.entityUuid == activity && deleteChange.payload["node_kind"] == JsonPrimitive("activity"))
        val deleteShadow = exactShadow(database, deleteChange)
        // Positive proofs above establish the lawful deletion. Absence now only rejects a
        // moved/recreated UUID, recycled native identity, partial projection or live source.
        requireAbsent(database, "habits", "id=? OR uuid=?", arrayOf<Any>(CountCommandRecords.integer(input, "habit_id"), activity))
        requireAbsent(database, "completions", "id=? OR uuid=? OR habitId=? OR habitUuid=?",
            arrayOf<Any>(frozen.number("id"), factUuid, frozen.number("habitId"), activity))
        requireAbsent(database, "count_days", "habitId=? OR habitUuid=?", arrayOf<Any>(frozen.number("habitId"), activity))
        requireAbsent(database, "sync_outbox", "(recordType='habit' AND entityUuid=?) OR referenceUuid=?", arrayOf(activity, activity))
        require(tokens.localCoreWriteAccess() == access && tokens.localSyncAccess() == sync &&
            NextChallengeStore(database).activeInTransaction(sync) == checkpoint) { "COMMAND_FOLLOWUP_AUTHORITY_CHANGED" }
        return CountCommandRecords.hash("accepted-deleted-followup-domain", buildJsonObject {
            put("activity", activity); put("original_fact", factUuid); put("count_proof", countProof.second)
            put("count_shadow", countShadow); put("delete_id", deleteId); put("delete_shadow", deleteShadow)
            put("lineage", lineage.evidenceHash); put("related", JsonObject(relatedProofs.mapValues { JsonPrimitive(it.value) }))
            put("restart_sources", JsonObject(restartProofs.mapValues { JsonPrimitive(it.value) }))
            put("metadata", Json.encodeToJsonElement(checkpoint.second))
        }.toString())
    }

    private suspend fun ids(database: HabitDatabase, query: String): Set<String>? {
        val result = linkedSetOf<String>()
        database.openHelper.writableDatabase.query(query, arrayOf(NEXT_OPERATION)).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (result.size == LIMIT) return null
                require(cursor.getType(0) == Cursor.FIELD_TYPE_STRING)
                val id = cursor.getString(0)
                require(isContractUuid(id) && result.add(id)) { "COMMAND_FOLLOWUP_REQUEST_DUPLICATE" }
            }
        }
        return result
    }
    private suspend fun hash(database: HabitDatabase, table: String, id: String): String? =
        NextRequestSql.rowHash(database, table, "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id))

    private suspend fun noContradictoryResult(database: HabitDatabase, id: String) {
        require(hash(database, "next_rejections", id) == null && hash(database, "next_abandoned_requests", id) == null &&
            NextRequestSql.rowHash(database, "one_time_transmissions", "operationId=?", arrayOf(id)) == null) {
            "COMMAND_FOLLOWUP_CONTRADICTORY_RESULT"
        }
    }
    private suspend fun acceptedChange(database: HabitDatabase, sync: LocalSyncAccess, id: String,
        operation: com.dayforge.data.api.dto.SyncV2Operation): SyncV2Change {
        val receipt = requireNotNull(database.nextRequestDao().acceptance(NEXT_OPERATION, id))
        val result = decodeFrozenSyncRequest(receipt.resultJson.toByteArray(Charsets.UTF_8), NextSyncOperationResult.serializer())
        require(result.status == "applied")
        return NextOrdinaryResultMapper.validate(operation, result, requireNotNull(sync.deviceId))
    }
    private suspend fun exactShadow(database: HabitDatabase, change: SyncV2Change): String {
        val hash = requireNotNull(NextRequestSql.rowHash(database, "sync_entity_state", "entityType=? AND entityUuid=?",
            arrayOf(change.entityType, change.entityUuid)))
        val state = requireNotNull(database.syncOutboxDao().getState(change.entityType, change.entityUuid))
        require(state.deleted == (change.operation == "delete") && state.revision == change.revision &&
            state.payloadJson != null && state.payloadHash == syncPayloadHash(state.payloadJson) &&
            Json.parseToJsonElement(state.payloadJson).jsonObject == change.payload) { "COMMAND_FOLLOWUP_ACCEPTED_SNAPSHOT_CHANGED" }
        return hash
    }
    private fun requireAbsent(database: HabitDatabase, table: String, predicate: String, arguments: Array<out Any>) {
        require(!database.openHelper.writableDatabase.query("SELECT 1 FROM `$table` WHERE $predicate LIMIT 1", arguments)
            .use { it.moveToFirst() }) { "COMMAND_FOLLOWUP_DELETED_PROJECTION_CHANGED" }
    }
}
