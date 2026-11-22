package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.CountDayEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.isContractUuid
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Pure ordinary COUNT ACK transformation. Inputs are semantic values, NOT certificates.
 * The trusted caller must freeze them and prove their complete scoped membership/absence,
 * raw SQLite types, global uniqueness/shadow/revert health, original request authority and
 * actual transaction effects. Nothing here signs, stores, authorizes or claims that a supplied
 * List is complete. The original full merger remains the independent collection oracle.
 */
internal object CountAckFactRules {
    /** Existing accepted count policies for this day, excluding the incoming event identity.
     * Reverted events still contribute. The trusted directory may provide aggregate policy keys;
     * it must also prove the first member if a previously absent day needs materialization.
     */
    data class DayInput(val existing: CountDayEntity?, val otherAcceptedPolicies: Set<CountDayPolicy?>,
        val firstOtherAcceptedEventUuid: String?)

    /** One step in the complete ordinary-revert target path. A non-revert is a terminal step.
     * One-time revert events are terminal here, matching the original ordinary undo graph.
     */
    data class RevertStep(val uuid: String, val activityUuid: String, val nextOrdinaryTarget: String?)
    data class RevertInput(val existingRevertForTarget: String?, val targetPath: List<RevertStep>,
        val absentPathTerminal: String?, val targetCompletion: CompletionEntity?,
        val targetTimer: TimeLogEntity?, val targetShadow: SyncEntityStateEntity?,
        val targetPending: List<SyncOutboxEntity>, val targetTimerCommands: List<TimerCommandEntity>)

    data class Input(val change: SyncV2Change, val operation: SyncV2Operation, val deviceId: String,
        val acceptedQueueId: Long, val shadowUpdatedAt: Long, val habit: HabitEntity, val completion: CompletionEntity?,
        val timer: TimeLogEntity?, val shadow: SyncEntityStateEntity?,
        val entityPending: List<SyncOutboxEntity>, val entityConflicts: List<SyncConflictEntity>,
        val isReverted: Boolean, val day: DayInput? = null, val revert: RevertInput? = null)

    /** Values only, with no deferred callback/SQL handle. A null field means no such effect. */
    data class Plan(val completionUpsert: CompletionEntity?, val completionDelete: CompletionEntity?,
        val timerDelete: TimeLogEntity?, val shadowUpsert: SyncEntityStateEntity?, val dayInsert: CountDayEntity?)

    /** Unsupported legal profiles use the original merger; they are never product rejections. */
    fun supports(habit: HabitEntity?, operation: SyncV2Operation, change: SyncV2Change): Boolean =
        habit != null && (habit.habitType == HabitType.COUNTING || habit.habitType == HabitType.CHECK_IN &&
            change.payload["event_type"] == JsonPrimitive("check_in")) && habit.appearance != null && habit.completionPolicy == "recurring" &&
            operation.entityType == "activity_event" && operation.action == "upsert" &&
            change.entityType == "activity_event" && change.operation == "upsert" &&
            change.payload["event_type"] in eventTypes &&
            change.payload["one_time"].let { it == null || it == JsonNull }

    fun prepare(input: Input): Plan {
        val change = input.change
        val operation = input.operation
        val habit = input.habit
        require(supports(habit, operation, change)) { "COUNT_ACK_RULES_UNSUPPORTED" }
        require(change.sequence == 0L && input.acceptedQueueId > 0 && operation.entityUuid == change.entityUuid &&
            operation.baseRevision in listOf(null, 0L))
        val payload = change.payload
        val id = change.entityUuid
        require(payload.text("activity_uuid") == habit.uuid)
        // The outer request/result binder remains mandatory. This repeats the original common
        // fact store's own snapshot validation rather than accepting an already mapped DTO.
        NextCommonFactMapper.validateOrdinaryFactSnapshot(change)

        val isRevert = payload["event_type"] == JsonPrimitive("revert")
        val dayInsert = if (payload["event_type"] in countTypes) prepareDay(input) else null
        if (isRevert) checkRevertGraph(change, requireNotNull(input.revert))

        val old = input.shadow
        if (old?.deleted == true) fail(NextFactMergeException.Reason.ENTITY_DELETED)
        val known = old != null && old.revision == change.revision && body(old) == payload
        if (old != null) {
            require(old.entityType == "activity_event" && old.entityUuid == id && old.revision > 0 &&
                old.payloadJson != null && old.payloadHash == syncPayloadHash(old.payloadJson))
            if (!known) fail(NextFactMergeException.Reason.FACT_DIVERGED)
            require(Instant.parse(body(old).text("created_at")) == Instant.parse(payload.text("created_at")))
        }
        val local = input.entityPending.filter { it.recordType == "completion" &&
            (it.entityUuid == id || it.wireEntityUuid == id) }
        if (input.entityConflicts.any { it.entityType == "activity_event" &&
                (it.wireEntityUuid == id || it.localEntityUuid == id) })
            fail(NextFactMergeException.Reason.LOCAL_FACT_PENDING)
        val deletedLocally = local.any { it.entityUuid == id && (it.action == "delete" || it.wireEntityUuid != id) }
        NextCommonFactProof.requireOriginalPayload(operation.payload, change, input.deviceId)

        var upsert: CompletionEntity? = null
        var delete: CompletionEntity? = null
        var deleteTimer: TimeLogEntity? = null
        if (isRevert) {
            val target = NextCommonFactMapper.revert(change, habit)
            require(input.completion == null && input.timer == null)
            val proof = requireNotNull(input.revert)
            val targetCompletion = proof.targetCompletion
            val targetTimer = proof.targetTimer
            if (targetCompletion != null && (targetCompletion.habitId != habit.id || targetCompletion.oneTimeAction != null) ||
                targetTimer != null && targetTimer.habitId != habit.id)
                fail(NextFactMergeException.Reason.TARGET_MISMATCH)
            require(targetCompletion == null || targetCompletion.uuid == target)
            require(targetTimer == null || targetTimer.uuid == target)
            proof.targetShadow?.let { require(body(it).text("activity_uuid") == habit.uuid) }
            val protected = proof.targetPending.any { it.id != input.acceptedQueueId &&
                it.recordType == "completion" && it.entityUuid == target }
            if (protected && (targetCompletion != null || targetTimer != null))
                fail(NextFactMergeException.Reason.LOCAL_FACT_PENDING)
            if (proof.targetTimerCommands.any { it.sessionUuid == target })
                fail(NextFactMergeException.Reason.LOCAL_TIMER_PENDING)
            delete = targetCompletion
            deleteTimer = targetTimer
        } else {
            require(input.timer == null)
            val mapped = NextCommonFactMapper.completion(change, habit, input.completion)
            if (input.completion != null && input.completion.copy(createdAt = mapped.createdAt) != mapped)
                fail(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
            if (!input.isReverted && !deletedLocally) upsert = mapped
        }
        val shadow = if (known) null else SyncEntityStateEntity("activity_event", id, change.revision,
            payloadJson = payload.toString(), payloadHash = syncPayloadHash(payload.toString()),
            updatedAt = input.shadowUpdatedAt)
        return Plan(upsert, delete, deleteTimer, shadow, dayInsert)
    }

    private fun prepareDay(input: Input): CountDayEntity? {
        val payload = input.change.payload
        val date = payload.text("local_date")
        val day = requireNotNull(input.day) { "COUNT_ACK_DAY_INPUT_REQUIRED" }
        val policy = NextCommonFactMapper.countPolicy(payload)
        require((day.otherAcceptedPolicies + policy).size == 1) { "COUNT_DAY_POLICY_CONFLICT" }
        val old = day.existing
        if (old != null) require(old.habitId == input.habit.id && old.habitUuid == input.habit.uuid && old.localDate == date)
        if (policy == null) {
            require(old == null) { "COUNT_DAY_POLICY_UNKNOWN" }
            return null
        }
        if (old != null) {
            require(old.policy == policy) { "COUNT_DAY_POLICY_CONFLICT" }
            return null
        }
        val first = if (day.otherAcceptedPolicies.isEmpty()) input.change.entityUuid
            else requireNotNull(day.firstOtherAcceptedEventUuid)
        require(isContractUuid(first))
        return CountDayEntity(input.habit.id, input.habit.uuid, date, policy.targetValue, policy.isCountdown, first)
    }

    private fun checkRevertGraph(change: SyncV2Change, input: RevertInput) {
        val id = change.entityUuid
        val target = change.payload.text("reverts_event_uuid")
        require(input.existingRevertForTarget == null || input.existingRevertForTarget == id)
        val seen = hashSetOf(id)
        var cursor: String? = target
        for (step in input.targetPath) {
            require(cursor != null && step.uuid == cursor && seen.add(step.uuid))
            require(isContractUuid(step.uuid) && isContractUuid(step.activityUuid) &&
                (step.nextOrdinaryTarget == null || isContractUuid(step.nextOrdinaryTarget)))
            require(step.activityUuid == change.payload.text("activity_uuid"))
            cursor = step.nextOrdinaryTarget
        }
        require(cursor == null && input.absentPathTerminal == null ||
            cursor != null && input.absentPathTerminal == cursor && seen.add(cursor))
    }

    private fun body(state: SyncEntityStateEntity) = Json.parseToJsonElement(requireNotNull(state.payloadJson)).jsonObject
    private fun JsonObject.text(key: String): String = getValue(key).let {
        require(it is JsonPrimitive && it.isString); it.content
    }
    private fun fail(reason: NextFactMergeException.Reason): Nothing = throw NextFactMergeException(reason)
    private val countTypes = setOf(JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))
    private val eventTypes = countTypes + JsonPrimitive("check_in") + JsonPrimitive("revert")
}
