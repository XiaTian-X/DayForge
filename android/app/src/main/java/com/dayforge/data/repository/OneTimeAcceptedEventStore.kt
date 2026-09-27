package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.NextSyncBootstrapResponse
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.validateTaskResultBinding
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.OneTimeTransmissionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.OneTimeProjection
import com.dayforge.domain.model.OneTimeTransitionException
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.model.mergeOneTimeProjection
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.ZoneId
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString

internal data class OneTimeSyncContext(val session: LocalDataSession, val deviceId: String)
internal data class PreparedOneTimeSubmission(val context: OneTimeSyncContext, val operation: SyncV2Operation)

/** V5 fact/result persistence. Not an HTTP client and never handles rejection as success. */
internal class OneTimeAcceptedEventStore(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val local: OneTimeLocalIntentStore,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val outbox = database.syncOutboxDao()
    private val facts = database.completionDao()
    private val habits = database.habitDao()
    private val receipts = database.completionFollowUpDao()
    private val json = Json { encodeDefaults = true }

    suspend fun context(): OneTimeSyncContext = sessions.exclusive { access() }

    /** Only the causal head is sendable, even if a pull has already seen its fact. */
    suspend fun prepare(activityUuid: String): PreparedOneTimeSubmission? = sessions.exclusive {
        val context = access(write = true)
        database.withTransaction {
            local.readInTransaction(activityUuid, context.session)
            val row = outbox.getActivityIntents(activityUuid).firstOrNull() ?: return@withTransaction null
            if (row.deadLetteredAt != null) throw OneTimeLocalException(OneTimeLocalException.Reason.PENDING_REJECTED)
            val operation = operation(row)
            requireReceipt(operation, activityUuid)
            val binding = receipts.transmission(operation.operationId)
            if (binding == null) {
                // No guessing the first device for an old/partially restored attempted operation.
                require(row.attemptedAt == null && row.attemptCount == 0)
                receipts.insertTransmission(OneTimeTransmissionEntity(operation.operationId,
                    context.session.authentication.userId, requireNotNull(context.session.serverInstanceId),
                    requireNotNull(context.session.syncEpoch), context.deviceId, json.encodeToString(operation)))
            } else {
                validateTransmission(binding, context, operation, sending = true)
                require(binding.rejectionJson == null && row.attemptedAt != null && row.attemptCount > 0)
            }
            outbox.markPrepared(row.id, row.wireEntityUuid, row.action, requireNotNull(row.payloadJson),
                row.baseRevision, row.basePayloadJson, now())
            PreparedOneTimeSubmission(context, operation)
        }
    }

    suspend fun acknowledge(prepared: PreparedOneTimeSubmission, result: NextSyncOperationResult): OneTimeLocalSnapshot = sessions.exclusive {
        access(prepared.context)
        require(result.status in setOf("applied", "already_applied"))
        validateTaskResultBinding(prepared.operation, result)
        require(result.errorCode == null && result.oneTimeConflict == null && result.baseEntity == null && result.localEntity == null &&
            result.conflictingFields.isEmpty() && result.conflictKind == null)
        val fact = OneTimeServerFact(requireNotNull(result.entity), requireNotNull(result.revision))
        fact.requireOriginal(prepared.operation, prepared.context.deviceId)
        database.withTransaction {
            val activity = fact.proof.activityUuid
            local.readInTransaction(activity, prepared.context.session)
            requireReceipt(prepared.operation, activity)
            val binding = requireNotNull(receipts.transmission(prepared.operation.operationId))
            validateTransmission(binding, prepared.context, prepared.operation, sending = true)
            require(binding.rejectionJson == null)
            val row = outbox.getByOperationId(prepared.operation.operationId)
            if (row == null) {
                // A local receipt alone does NOT prove server success. An immutable shadow must agree.
                val shadow = outbox.getState("activity_event", fact.proof.publicId)
                require(shadow != null && !shadow.deleted && shadow.revision == fact.revision &&
                    shadow.payloadJson?.let { Json.parseToJsonElement(it) } == fact.payload)
                return@withTransaction local.readInTransaction(activity, prepared.context.session)
            }
            require(operation(row) == prepared.operation && row.attemptedAt != null && row.deadLetteredAt == null)
            require(outbox.getActivityIntents(activity).firstOrNull()?.operationId == row.operationId)
            merge(fact, prepared.context)
            outbox.deleteById(row.id)
            local.readInTransaction(activity, prepared.context.session)
        }
    }

    /** Only an explicit, bound per-operation result quarantines a causal suffix. */
    suspend fun reject(prepared: PreparedOneTimeSubmission, result: NextSyncOperationResult): OneTimeLocalSnapshot = sessions.exclusive {
        access(prepared.context)
        validateTaskResultBinding(prepared.operation, result)
        require(result.status in setOf("conflict", "rejected") && !result.errorCode.isNullOrBlank())
        require(result.entity == null && result.revision == null && result.baseEntity == null && result.localEntity == null &&
            result.conflictingFields.isEmpty() && result.conflictKind == null)
        val activity = prepared.operation.payload.getValue("activity_uuid").let {
            require(it is kotlinx.serialization.json.JsonPrimitive && it.isString)
            it.content
        }
        database.withTransaction {
            local.readInTransaction(activity, prepared.context.session)
            requireReceipt(prepared.operation, activity)
            val binding = requireNotNull(receipts.transmission(prepared.operation.operationId))
            validateTransmission(binding, prepared.context, prepared.operation, sending = true)
            val row = requireNotNull(outbox.getByOperationId(prepared.operation.operationId))
            require(operation(row) == prepared.operation && row.attemptedAt != null && row.attemptCount > 0)
            require(outbox.getActivityIntents(activity).firstOrNull()?.operationId == row.operationId)
            // An accepted immutable fact and a rejected result for the same intent cannot coexist.
            require(outbox.getState("activity_event", row.entityUuid) == null)
            val serialized = json.encodeToString(result)
            if (binding.rejectionJson != null) {
                require(Json.parseToJsonElement(binding.rejectionJson) == Json.parseToJsonElement(serialized) &&
                    row.deadLetteredAt != null && row.errorCode == result.errorCode)
            } else {
                require(row.deadLetteredAt == null)
                check(receipts.recordRejection(row.operationId, serialized) == 1)
                outbox.markDeadLetter(row.id, result.errorCode, result.message ?: requireNotNull(result.errorCode), now())
            }
            // Do not fabricate the conflict head as a fact or alter any frozen successor/metric.
            local.readInTransaction(activity, prepared.context.session)
        }
    }

    /** A page is atomic; pulling a matching fact never consumes an unknown-result operation. */
    suspend fun apply(context: OneTimeSyncContext, changes: List<SyncV2Change>) = sessions.exclusive {
        access(context)
        database.withTransaction {
            val activities = linkedSetOf<String>()
            changes.forEach { change ->
                require(change.entityType == "activity_event" && change.operation == "upsert" && change.sequence >= 0)
                val fact = OneTimeServerFact(change.payload, change.revision)
                require(change.entityUuid == fact.proof.publicId)
                if (activities.add(fact.proof.activityUuid)) local.readInTransaction(fact.proof.activityUuid, context.session)
                merge(fact, context)
            }
            activities.forEach { local.readInTransaction(it, context.session) }
        }
    }

    /**
     * Merge complete once histories for already validated/initialized parent structures.
     * This is not account replacement: no structural deletion, cursor write or operation acknowledgement.
     */
    suspend fun restoreHistories(context: OneTimeSyncContext, response: NextSyncBootstrapResponse): List<OneTimeLocalSnapshot> = sessions.exclusive {
        access(context)
        // Re-run the complete-history envelope checks, including after caller-owned list mutation.
        val snapshot = response.copy(changes = response.changes.toList(), oneTimeCheckpoints = response.oneTimeCheckpoints.toList())
        require(snapshot.nextCursor >= 0 && Instant.parse(snapshot.serverTime).atZone(ZoneId.of("UTC")).year in 1..9999)
        val checkpoints = snapshot.oneTimeCheckpoints.associateBy { it.activityUuid }
        val groups = snapshot.changes.filter {
            it.entityType == "activity_event" && it.payload["one_time"] != null && it.payload["one_time"] != JsonNull
        }.map { change ->
            OneTimeServerFact(change.payload, change.revision).also {
                require(it.proof.publicId == change.entityUuid && it.proof.activityUuid in checkpoints)
            }
        }.groupBy { it.proof.activityUuid }
        snapshot.changes.filter { it.entityType == "plan_node" && it.entityUuid in checkpoints }.forEach {
            require(it.payload["public_id"] == JsonPrimitive(it.entityUuid))
        }
        database.withTransaction {
            val parents = checkpoints.mapValues { (activity, checkpoint) ->
                val before = local.readInTransaction(activity, context.session)
                if (before.confirmed.version > checkpoint.state.version) {
                    throw OneTimeLocalException(OneTimeLocalException.Reason.HISTORY_BEHIND_LOCAL)
                }
                val incoming = groups[activity].orEmpty()
                if (before.confirmed.version > 0) {
                    val atKnownVersion = incoming.single { it.proof.oneTimeStateAfter.version == before.confirmed.version }
                    require(atKnownVersion.proof.oneTimeStateAfter == before.confirmed)
                }
                val parent = requireNotNull(habits.getHabitByUuid(activity))
                val incomingIds = incoming.map { it.proof.publicId }.toSet()
                // Previously accepted slots cannot disappear from a purported complete history.
                facts.getByHabitOnce(parent.id).forEach { known ->
                    outbox.getState("activity_event", known.uuid)?.let { shadow ->
                        require(!shadow.deleted && known.uuid in incomingIds)
                    }
                }
                parent to before
            }
            withoutLegacyOutbox {
                checkpoints.forEach { (activity, checkpoint) ->
                    val (parent, before) = parents.getValue(activity)
                    groups[activity].orEmpty().forEach { persistFact(it, context, parent) }
                    if (checkpoint.state != before.confirmed) check(habits.updateForSync(parent.copy(
                        oneTimeConfirmedVersion = checkpoint.state.version,
                        oneTimeConfirmedHeadEventUuid = checkpoint.state.headEventUuid,
                        oneTimeConfirmedCompletionEventUuid = checkpoint.state.completionEventUuid)) == 1)
                }
            }
            checkpoints.map { (activity, checkpoint) ->
                local.readInTransaction(activity, context.session).also { require(it.confirmed == checkpoint.state) }
            }
        }
    }

    private suspend fun access(expected: OneTimeSyncContext? = null, write: Boolean = false): OneTimeSyncContext {
        val access = tokens.localFactAccess() ?: throw OneTimeLocalException(OneTimeLocalException.Reason.STALE_SESSION)
        val device = tokens.syncDeviceId.first()
        if (access.session.serverInstanceId == null || access.session.syncEpoch == null || device == null || !isContractUuid(device)) {
            throw OneTimeLocalException(OneTimeLocalException.Reason.STALE_SESSION)
        }
        val context = OneTimeSyncContext(access.session, device)
        if (expected != null && expected != context) throw OneTimeLocalException(OneTimeLocalException.Reason.STALE_SESSION)
        if (write && !access.canAppend) throw OneTimeLocalException(OneTimeLocalException.Reason.FACTS_DENIED)
        return context
    }

    private fun operation(row: SyncOutboxEntity): SyncV2Operation {
        require(row.recordType == "one_time_completion" && row.entityUuid == row.wireEntityUuid &&
            row.action == "upsert" && row.baseRevision == null && row.basePayloadJson == null)
        return SyncV2Operation(row.operationId, "activity_event", row.wireEntityUuid, row.action,
            payload = Json.parseToJsonElement(requireNotNull(row.payloadJson)).jsonObject)
    }

    private suspend fun requireReceipt(operation: SyncV2Operation, activityUuid: String) {
        val receipt = requireNotNull(receipts.submission(operation.operationId))
        require(receipt.entityType == "activity_event" && receipt.entityUuid == operation.entityUuid &&
            receipt.referenceUuid == activityUuid && Json.parseToJsonElement(receipt.payloadJson) == operation.payload)
    }

    private fun validateTransmission(binding: OneTimeTransmissionEntity, context: OneTimeSyncContext,
        operation: SyncV2Operation, sending: Boolean) {
        require(binding.operationId == operation.operationId && isContractUuid(binding.deviceId) &&
            Json.parseToJsonElement(binding.operationJson) == json.encodeToJsonElement(operation))
        if (binding.accountId != context.session.authentication.userId ||
            binding.serverInstanceId != context.session.serverInstanceId || binding.syncEpoch != context.session.syncEpoch ||
            (sending && binding.deviceId != context.deviceId)
        ) throw OneTimeLocalException(OneTimeLocalException.Reason.TRANSMISSION_CONTEXT_CHANGED)
    }

    private suspend fun merge(fact: OneTimeServerFact, context: OneTimeSyncContext) {
        val proof = fact.proof
        val before = local.readInTransaction(proof.activityUuid, context.session)
        val after = mergeOneTimeProjection(OneTimeProjection(proof.activityUuid, before.confirmed),
            OneTimeProjection(proof.activityUuid, proof.oneTimeStateAfter))
        val habit = requireNotNull(habits.getHabitByUuid(proof.activityUuid))
        // A late, lower-version snapshot may fill a history gap, but cannot fork an already
        // authoritative slot/edge. Unconfirmed local alternatives are deliberately excluded.
        val incomingVersion = proof.oneTime.expectedVersion.toLong()
        facts.getByHabitOnce(habit.id).filter {
            it.uuid != proof.publicId && requireNotNull(it.oneTimeExpectedVersion).toLong() in (incomingVersion - 1)..(incomingVersion + 1)
        }.forEach { known ->
            val knownShadow = outbox.getState("activity_event", known.uuid)
            if (knownShadow != null && !knownShadow.deleted) {
                val version = requireNotNull(known.oneTimeExpectedVersion).toLong()
                if (version == incomingVersion ||
                    (version + 1 == incomingVersion && known.uuid != proof.oneTime.expectedHeadEventUuid) ||
                    (incomingVersion + 1 == version && known.oneTimeExpectedHeadEventUuid != proof.publicId)
                ) throw OneTimeTransitionException("TASK_STATE_DIVERGED")
            }
        }
        withoutLegacyOutbox {
            persistFact(fact, context, habit)
            if (after.state != before.confirmed) check(habits.updateForSync(habit.copy(
                oneTimeConfirmedVersion = after.state.version, oneTimeConfirmedHeadEventUuid = after.state.headEventUuid,
                oneTimeConfirmedCompletionEventUuid = after.state.completionEventUuid)) == 1)
        }
    }

    /** Shared immutable body/source validation; callers validate either the full chain or incremental edges. */
    private suspend fun persistFact(fact: OneTimeServerFact, context: OneTimeSyncContext, habit: HabitEntity) {
        val proof = fact.proof
        require(proof.activityUuid == habit.uuid)
        val shadow = outbox.getState("activity_event", proof.publicId)
        if (shadow != null) require(!shadow.deleted && shadow.revision == fact.revision &&
            shadow.payloadJson?.let { Json.parseToJsonElement(it) } == fact.payload)
        val receipt = receipts.submissionForEntity("activity_event", proof.publicId)
        if (receipt != null) {
            val original = SyncV2Operation(receipt.operationId, "activity_event", receipt.entityUuid,
                "upsert", payload = Json.parseToJsonElement(receipt.payloadJson).jsonObject)
            val binding = requireNotNull(receipts.transmission(receipt.operationId))
            validateTransmission(binding, context, original, sending = false)
            require(binding.rejectionJson == null)
            fact.requireOriginal(original, binding.deviceId)
        }
        val existing = facts.getCompletionByUuid(proof.publicId)
        val incoming = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = proof.publicId,
            date = fact.occurredAt.atZone(ZoneId.of(fact.timezone)).toLocalDate().atStartOfDay(ZoneId.of(fact.timezone)).toInstant().toEpochMilli(),
            actualCompletedAt = fact.occurredAt.toEpochMilli(), value = if (proof.oneTime.action == "complete") 1 else 0,
            recordedTimezone = fact.timezone, recordedLocalDate = fact.localDate, createdAt = fact.createdAt.toEpochMilli(),
            timeMetadataSource = "server", oneTimeAction = proof.oneTime.action, oneTimeExpectedVersion = proof.oneTime.expectedVersion,
            oneTimeExpectedHeadEventUuid = proof.oneTime.expectedHeadEventUuid, oneTimeRevertsEventUuid = proof.oneTime.revertsEventUuid)
        if (existing != null) require(existing.copy(id = 0, createdAt = incoming.createdAt,
                timeMetadataSource = "server", date = incoming.date) == incoming)
        if (existing == null) check(facts.insertForSync(incoming) > 0)
        if (shadow == null) outbox.upsertState(SyncEntityStateEntity("activity_event", proof.publicId,
            fact.revision, payloadJson = fact.payload.toString(), payloadHash = syncPayloadHash(fact.payload.toString()), updatedAt = now()))
    }

    private suspend fun withoutLegacyOutbox(block: suspend () -> Unit) {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        require(sql.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use { it.moveToFirst() && it.getInt(0) == 0 })
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        block()
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
    }
}
