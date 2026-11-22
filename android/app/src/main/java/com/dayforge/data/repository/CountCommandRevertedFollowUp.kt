package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** A SELECT-only, transaction-local witness for one formal original COUNT and its exact
 * immutable revert. Ordinary undo need not own a durable command. No absence, parsed JSON,
 * accepted birth alone, or stored outcome is permission to end the parent's follow-up. */
internal object CountCommandRevertedFollowUp {
    private const val MAX_ORIGINS = 10_000
    private data class Source(val origin: NextRequestOriginEntity, val round: NextRoundOperationIntent,
        val hash: String, val accepted: Boolean, val shadowHash: String?)

    suspend fun read(database: HabitDatabase, tokens: TokenManager, sessions: AccountSessionCoordinator,
        intent: CountCommandRecords.Intent, outcome: CountCommandOutcome, access: LocalCoreWriteAccess,
        habit: HabitEntity, native: CountTrustRows.Row, frozen: CountCommandEvidence.Record,
        rounds: NextRoundWriteScope?): String? {
        check(database.inTransaction())
        val scope = rounds ?: return null
        val sync = scope.access
        require(sync.session == access.session && sync.deviceId == access.capturedDeviceId &&
            tokens.localSyncAccess() == sync) { "COMMAND_FOLLOWUP_AUTHORITY_CHANGED" }
        val activity = habit.uuid
        val target = requireNotNull(outcome.primaryFactUuid)
        val captured = intent.body().getValue("input").jsonObject.getValue("round") as? JsonObject ?: return null
        if (captured["profile"] != JsonPrimitive("rounds/v1")) return null
        require(captured.keys == setOf("profile", "head", "initial", "restart"))
        val head = captured.getValue("head").takeUnless { it == JsonNull }?.let {
            Json.decodeFromJsonElement<ChallengeRoundHead>(it)
        } ?: return null
        require(head.activityUuid == activity)
        if (!scope.completionRounds.contains(activity, head) || activity in scope.pendingInitials ||
            activity in scope.pendingRestarts) return null

        // The caller's bound old-id read was absent. Audit the UUID independently: a moved
        // original, duplicate, or replacement cannot be turned into a legal undo by absence.
        val sql = database.openHelper.writableDatabase
        sql.query("SELECT id,uuid FROM main.completions WHERE id=? OR uuid=? LIMIT 2",
            arrayOf<Any>(frozen.number("id"), target)).use { require(!it.moveToFirst()) { "COMMAND_FOLLOWUP_ORIGINAL_FACT_CHANGED" } }

        // Explicit recovery only: full bounded origin inventory, never a LIKE/JSON spelling
        // prefilter or LIMIT 1. Overflow preserves pending work without truncating uniqueness.
        val originIds = ids(database, "SELECT requestId FROM next_request_origins WHERE kind=? ORDER BY requestId LIMIT 10001",
            arrayOf(NEXT_OPERATION)) ?: return null
        val acceptedIds = ids(database, "SELECT requestId FROM next_acceptances WHERE kind=? ORDER BY requestId LIMIT 10001",
            arrayOf(NEXT_OPERATION)) ?: return null
        require(originIds.containsAll(acceptedIds)) { "COMMAND_FOLLOWUP_REQUEST_ORPHAN" }
        val targetSources = ids(database,
            "SELECT operationId FROM sync_outbox WHERE recordType='completion' AND action='delete' AND entityUuid=? ORDER BY operationId LIMIT 10001",
            arrayOf(target)) ?: return null
        require(originIds.containsAll(targetSources)) { "COMMAND_FOLLOWUP_REQUEST_ORPHAN" }
        val matches = arrayListOf<String>()
        for (id in originIds) {
            currentCoroutineContext().ensureActive()
            requireNotNull(NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
            val row = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, id))
            // Restart proposals share this table and have their own strict discriminator.
            val proposal = restartProposal(row.intentJson)
            if (proposal != null) {
                require(proposal.operationId == id)
                require(id !in targetSources) { "COMMAND_FOLLOWUP_REVERT_SOURCE_CHANGED" }
                continue
            }
            val operation = decodeNextOperationIntent(row.intentJson)
            require(operation.operationId == id)
            // An explicit target declaration is relevant even when another event field is
            // damaged. In particular, an accepted source has no live queue to expose this
            // contradiction; filtering by a valid event shape first would hide it.
            val declaresTarget = operation.payload["reverts_event_uuid"] == JsonPrimitive(target)
            require(id !in targetSources || declaresTarget) { "COMMAND_FOLLOWUP_REVERT_SOURCE_CHANGED" }
            if (declaresTarget) {
                require(operation.entityType == "activity_event" && operation.action == "upsert" &&
                    operation.payload["event_type"] == JsonPrimitive("revert") && operation.baseRevision == null &&
                    isContractUuid(operation.entityUuid) && operation.entityUuid != target) {
                    "COMMAND_FOLLOWUP_REVERT_SOURCE_CHANGED"
                }
                requireRevertPayload(operation.payload, activity, target)
                matches += id
            }
        }
        if (matches.size != 1) return null
        val summary = outcome.origins.single { it.entityUuid == target }
        val original = source(database, sync, summary.requestId, habit) ?: return null
        require(NextRequestSql.immutableOriginHash(original.origin) == summary.immutableOriginHash &&
            original.round.operation.entityUuid == target && original.round.operation.entityType == "activity_event" &&
            original.round.operation.action == "upsert" && original.round.context.head == head) {
            "COMMAND_FOLLOWUP_ORIGINAL_ROUND_CHANGED"
        }
        val reverted = source(database, sync, matches.single(), habit) ?: return null
        val operation = reverted.round.operation
        require(operation.entityUuid != target && operation.payload["activity_uuid"] == JsonPrimitive(activity) &&
            reverted.round.context.head == head) { "COMMAND_FOLLOWUP_REVERT_TARGET_CHANGED" }
        requireRevertPayload(operation.payload, activity, target)
        val birth = scope.completionRounds.birth(target)
        if (original.accepted) require(birth == head) { "COMMAND_FOLLOWUP_ORIGINAL_BIRTH_CHANGED" }
        else require(birth == null) { "COMMAND_FOLLOWUP_ORIGINAL_BIRTH_CHANGED" }
        if (reverted.accepted) require(scope.completionRounds.birth(operation.entityUuid) == head) {
            "COMMAND_FOLLOWUP_REVERT_BIRTH_CHANGED"
        } else require(scope.completionRounds.birth(operation.entityUuid) == null) { "COMMAND_FOLLOWUP_REVERT_BIRTH_CHANGED" }
        if (!reverted.accepted) {
            val queue = requireNotNull(database.syncOutboxDao().getById(reverted.origin.queueId))
            require(queue.recordType == "completion" && queue.action == "delete" && queue.entityUuid == target &&
                queue.wireEntityUuid == target && queue.referenceUuid == activity && queue.createdAt % 1000L == 0L &&
                operation.payload["occurred_at"] == JsonPrimitive(Instant.ofEpochMilli(queue.createdAt).toString())) {
                "COMMAND_FOLLOWUP_REVERT_SOURCE_CHANGED"
            }
        }
        // Every retained fact/day/birth is still audited by the original domain reader.
        // The original immutable business fields are checked without equating ACK created_at
        // normalization with the old native insertion row or inventing a deleted projection.
        val day = LocalDate.parse(frozen.text("recordedLocalDate"))
        val history = RecurringCompletionEvidenceReader(database, tokens, sessions).recoveryInTransaction(habit, day)
        require(history.completions.none { it.uuid == target || it.id == frozen.number("id") }) {
            "COMMAND_FOLLOWUP_ORIGINAL_FACT_CHANGED"
        }
        CountFactEvidenceRules.verifyPendingFields(activity, Math.toIntExact(frozen.number("value")),
            frozen.nullableNumber("actualCompletedAt"), frozen.number("date"), frozen.text("recordedLocalDate"),
            frozen.text("recordedTimezone"), history.policies[day], original.round.operation.payload)
        require(tokens.localCoreWriteAccess() == access && tokens.localSyncAccess() == sync) {
            "COMMAND_FOLLOWUP_AUTHORITY_CHANGED"
        }
        return CountCommandRecords.hash("reverted-followup-domain", buildJsonObject {
            put("habit", native.value); put("target", target); put("original_id", frozen.number("id"))
            put("original_proof", original.hash); put("revert_proof", reverted.hash)
            put("original_shadow", original.shadowHash?.let(::JsonPrimitive) ?: JsonNull)
            put("revert_shadow", reverted.shadowHash?.let(::JsonPrimitive) ?: JsonNull)
            put("metadata", Json.parseToJsonElement(scope.metadataJson))
        }.toString())
    }

    private suspend fun ids(database: HabitDatabase, query: String, args: Array<out Any>): Set<String>? {
        val values = linkedSetOf<String>()
        database.openHelper.writableDatabase.query(query, args).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (values.size == MAX_ORIGINS) return null
                require(cursor.getType(0) == Cursor.FIELD_TYPE_STRING)
                val id = cursor.getString(0)
                require(isContractUuid(id) && values.add(id)) { "COMMAND_FOLLOWUP_REQUEST_DUPLICATE" }
            }
        }
        return values
    }

    private suspend fun source(database: HabitDatabase, access: LocalSyncAccess, id: String, habit: HabitEntity): Source? {
        val args = arrayOf<Any>(NEXT_OPERATION, id)
        val hash = requireNotNull(NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?", args))
        val row = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, id))
        val round = roundOperationIntent(row.intentJson) ?: return null
        require(row.kind == NEXT_OPERATION && row.requestId == id && row.protocol == 5 && row.queueId > 0 &&
            row.accountId == access.session.authentication.userId && row.serverInstanceId == access.session.serverInstanceId &&
            row.syncEpoch == access.session.syncEpoch && round.capturedDeviceId == access.deviceId &&
            round.operation.entityType == "activity_event" && round.operation.action == "upsert" &&
            round.operation.payload["activity_uuid"] == JsonPrimitive(habit.uuid)) { "COMMAND_FOLLOWUP_REVERT_SCOPE_CHANGED" }
        val shadowHash = NextRequestSql.rowHash(database, "sync_entity_state", "entityType=? AND entityUuid=?",
            arrayOf("activity_event", round.operation.entityUuid))
        val queued = NextRequestSql.rowHash(database, "sync_outbox", "id=?", arrayOf(row.queueId))
        if (queued != null) {
            val proof = NextOriginalRequestProofStore(database).pending(access, NEXT_OPERATION, id)
            require(proof.origin == row && proof.originHash == hash)
            if (proof.state != NextRecoveryRequestState.NEVER_SENT || shadowHash != null) return null
            NextRestartBindingStore(database).requireReady(access, row)
            return Source(row, round, proof.evidenceHash, false, null)
        }
        if (NextRequestSql.rowHash(database, "next_acceptances", "kind=? AND requestId=?", args) == null) return null
        require(NextRequestSql.rowHash(database, "next_rejections", "kind=? AND requestId=?", args) == null &&
            NextRequestSql.rowHash(database, "one_time_transmissions", "operationId=?", arrayOf(id)) == null) {
            "COMMAND_FOLLOWUP_CONTRADICTORY_RESULT"
        }
        val accepted = NextAcceptedFactProofStore(database).read(access, id)
        require(accepted.first == round.operation)
        requireNotNull(shadowHash) { "COMMAND_FOLLOWUP_ACCEPTED_SNAPSHOT_MISSING" }
        val state = requireNotNull(database.syncOutboxDao().getState("activity_event", round.operation.entityUuid))
        require(!state.deleted && state.revision > 0 && state.payloadJson != null &&
            state.payloadHash == syncPayloadHash(state.payloadJson)) { "COMMAND_FOLLOWUP_ACCEPTED_SNAPSHOT_CHANGED" }
        val payload = Json.parseToJsonElement(state.payloadJson).jsonObject
        val change = SyncV2Change(0, "activity_event", state.entityUuid, "upsert", state.revision, payload,
            payload.getValue("updated_at").jsonPrimitive.content)
        NextCommonFactMapper.validateOrdinaryFactSnapshot(change)
        NextCommonFactProof.requireOriginalPayload(round.operation.payload, change, round.capturedDeviceId)
        if (payload["event_type"] == JsonPrimitive("revert")) NextCommonFactMapper.revert(change, habit)
        else NextCommonFactMapper.completion(change, habit)
        return Source(row, round, accepted.second, true, shadowHash)
    }

    /** Exact original ordinary DELETE producer payload, not a general event validator. */
    private fun requireRevertPayload(payload: JsonObject, activity: String, target: String) {
        val at = CountCommandRecords.text(payload, "occurred_at")
        val zone = CountCommandRecords.text(payload, "timezone")
        val instant = Instant.parse(at)
        require(payload == buildJsonObject {
            put("activity_uuid", activity); put("event_type", "revert"); put("reverts_event_uuid", target)
            put("occurred_at", instant.toString()); put("timezone", ZoneId.of(zone).id)
            put("local_date", instant.atZone(ZoneId.of(zone)).toLocalDate().toString()); put("source_type", "app")
        }) { "COMMAND_FOLLOWUP_REVERT_PAYLOAD_CHANGED" }
    }
}
