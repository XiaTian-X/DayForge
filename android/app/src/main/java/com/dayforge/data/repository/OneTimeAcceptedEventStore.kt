package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.validateTaskResultBinding
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.domain.model.OneTimeProjection
import com.dayforge.domain.model.OneTimeTransitionException
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.model.mergeOneTimeProjection
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal data class OneTimeSyncContext(val session: LocalDataSession, val deviceId: String)
internal data class PreparedOneTimeSubmission(val context: OneTimeSyncContext, val operation: SyncV2Operation)

/** V5 accepted-fact persistence. Not an HTTP client and never handles rejection as success. */
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

    private suspend fun merge(fact: OneTimeServerFact, context: OneTimeSyncContext) {
        val proof = fact.proof
        val before = local.readInTransaction(proof.activityUuid, context.session)
        val after = mergeOneTimeProjection(OneTimeProjection(proof.activityUuid, before.confirmed),
            OneTimeProjection(proof.activityUuid, proof.oneTimeStateAfter))
        require(outbox.getState("activity_event", proof.publicId)?.deleted != true)
        val shadow = outbox.getState("activity_event", proof.publicId)
        if (shadow != null) require(shadow.revision == fact.revision &&
            shadow.payloadJson?.let { Json.parseToJsonElement(it) } == fact.payload)
        val receipt = receipts.submissionForEntity("activity_event", proof.publicId)
        if (receipt != null) fact.requireOriginal(SyncV2Operation(receipt.operationId, "activity_event", receipt.entityUuid,
            "upsert", payload = Json.parseToJsonElement(receipt.payloadJson).jsonObject), context.deviceId)
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
        val existing = facts.getCompletionByUuid(proof.publicId)
        val incoming = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = proof.publicId,
            date = fact.occurredAt.atZone(ZoneId.of(fact.timezone)).toLocalDate().atStartOfDay(ZoneId.of(fact.timezone)).toInstant().toEpochMilli(),
            actualCompletedAt = fact.occurredAt.toEpochMilli(), value = if (proof.oneTime.action == "complete") 1 else 0,
            recordedTimezone = fact.timezone, recordedLocalDate = fact.localDate, createdAt = fact.createdAt.toEpochMilli(),
            timeMetadataSource = "server", oneTimeAction = proof.oneTime.action, oneTimeExpectedVersion = proof.oneTime.expectedVersion,
            oneTimeExpectedHeadEventUuid = proof.oneTime.expectedHeadEventUuid, oneTimeRevertsEventUuid = proof.oneTime.revertsEventUuid)
        if (existing != null) require(existing.copy(id = 0, createdAt = incoming.createdAt,
                timeMetadataSource = "server", date = incoming.date) == incoming)
        val sql = database.openHelper.writableDatabase
        require(sql.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use { it.moveToFirst() && it.getInt(0) == 0 })
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        if (existing == null) facts.insertForSync(incoming)
        if (after.state != before.confirmed) check(habits.updateForSync(habit.copy(
            oneTimeConfirmedVersion = after.state.version, oneTimeConfirmedHeadEventUuid = after.state.headEventUuid,
            oneTimeConfirmedCompletionEventUuid = after.state.completionEventUuid)) == 1)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        if (shadow == null) outbox.upsertState(SyncEntityStateEntity("activity_event", proof.publicId,
            fact.revision, payloadJson = fact.payload.toString(), payloadHash = syncPayloadHash(fact.payload.toString()), updatedAt = now()))
    }
}
