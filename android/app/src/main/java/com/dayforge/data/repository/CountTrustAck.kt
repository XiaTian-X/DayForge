package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.contractLongOrNull
import com.dayforge.domain.model.isContractUuid
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Plain recurring COUNT's scoped ACK adapter. Sources must authenticate complete membership
 * AND compare every selected native row to the root. Empty where means authenticated identities
 * read individually; it never means SELECT all history. The caller owns fresh authority, the
 * unchanged global-health certificate, transaction, original request binder and final journal.
 */
internal class CountTrustAck(private val database: HabitDatabase, private val sources: Sources,
    private val eligible: (HabitEntity) -> Boolean) {
    // Keep the original Sources SAM constructor/trailing-lambda call contract for COUNT callers.
    constructor(database: HabitDatabase, sources: Sources) : this(database, sources,
        { it.habitType == com.dayforge.data.model.HabitType.COUNTING })
    fun interface Sources {
        suspend fun rows(index: String, table: String, where: String, args: Array<out Any?>): List<CountTrustRows.Row>
    }
    data class Effect(val table: String, val before: CountTrustRows.Row?, val after: CountTrustRows.Row?)
    class Prepared internal constructor(val plan: CountAckFactRules.Plan,
        internal val completion: CountTrustRows.Row?, internal val shadow: CountTrustRows.Row?,
        internal val control: CountTrustRows.Row)
    private val sql get() = database.openHelper.writableDatabase
    private val native get() = CountTrustRows(sql)

    private suspend fun rows(kind: String, table: String, vararg parts: String,
        where: String = "", args: Array<out Any?> = emptyArray()) =
        sources.rows(CountTrustMembership.key(kind, *parts), table, where, args)

    suspend fun prepare(change: SyncV2Change, operation: SyncV2Operation, deviceId: String,
        acceptedQueueId: Long, shadowUpdatedAt: Long): Prepared? {
        check(database.inTransaction())
        if (operation.entityType != "activity_event" || operation.action != "upsert" ||
            change.entityType != "activity_event" || change.operation != "upsert" ||
            change.payload["event_type"] !in quantityTypes ||
            change.payload["one_time"].let { it != null && it != JsonNull }) return null
        val activity = change.payload.string("activity_uuid")
        val habitRows = rows("habit.uuid", "habits", activity, where = "uuid=?", args = arrayOf(activity))
        if (habitRows.size != 1) return null
        val habit = habitRows.single().habit()
        if (!CountAckFactRules.supports(habit, operation, change) || !eligible(habit)) return null
        val id = change.entityUuid
        val completions = rows("completion.uuid", "completions", id, where = "uuid=?", args = arrayOf(id))
        require(completions.size <= 1)
        val timers = rows("timer.uuid", "timelogs", id, where = "uuid=?", args = arrayOf(id))
        // The pure merger would reject any timer collision; no timer decoding or broad read is needed.
        require(timers.isEmpty())
        val shadows = rows("shadow.entity", "sync_entity_state", "activity_event", id,
            where = "entityType=? AND entityUuid=?", args = arrayOf("activity_event", id))
        require(shadows.size <= 1)
        val pending = (rows("outbox.entity", "sync_outbox", "activity_event", id) +
            rows("outbox.wire", "sync_outbox", "activity_event", id)).distinctBy { it.name }.map { it.outbox() }
        val conflicts = (rows("conflict.entity", "sync_conflicts", "activity_event", id) +
            rows("conflict.wire", "sync_conflicts", "activity_event", id)).distinctBy { it.name }
            .filter { it.text("status") == "unresolved" }.map { it.conflict() }
        val incomingReverts = rows("shadow.revertTarget", "sync_entity_state", id).filter { row ->
            val state = row.shadow()
            !state.deleted && body(state).let { it["event_type"] == JsonPrimitive("revert") &&
                it["one_time"].let { marker -> marker == null || marker == JsonNull } &&
                it["reverts_event_uuid"] == JsonPrimitive(id) }
        }
        // A formerly absent target can acquire its first accepted shadow in this ACK.
        // Global health of the OLD graph does not prove these newly completed inbound
        // edges belong to this activity. Check the complete target membership now.
        require(incomingReverts.size <= 1)
        incomingReverts.forEach { require(body(it.shadow()).string("activity_uuid") == activity) }
        val reverted = incomingReverts.isNotEmpty()
        val day = if (change.payload["event_type"] in countTypes) {
            val date = change.payload.string("local_date")
            val days = rows("day.date", "count_days", activity, date,
                where = "habitId=? AND localDate=?", args = arrayOf<Any>(habit.id, date))
            require(days.size <= 1)
            val other = rows("shadow.date", "sync_entity_state", activity, date).map { it.shadow() }
                .filter { !it.deleted && it.entityUuid != id }.map { it.entityUuid to body(it) }
                .filter { (_, payload) -> payload["event_type"] in countTypes }
            CountAckFactRules.DayInput(days.singleOrNull()?.day(),
                other.map { NextCommonFactMapper.countPolicy(it.second) }.toSet(), other.firstOrNull()?.first)
        } else null
        val control = rows("control.id", "sync_control", "1", where = "id=?", args = arrayOf(1)).single()
        require(control.int("id") == 1 && control.int("suppressOutbox") == 0)
        val plan = CountAckFactRules.prepare(CountAckFactRules.Input(change, operation, deviceId,
            acceptedQueueId, shadowUpdatedAt, habit, completions.singleOrNull()?.completion(), null,
            shadows.singleOrNull()?.shadow(), pending, conflicts, reverted, day))
        require(plan.completionDelete == null && plan.timerDelete == null)
        return Prepared(plan, completions.singleOrNull(), shadows.singleOrNull(), control)
    }

    /** Expected effects are constructed from the pure plan, never inferred from the SQL journal.
     * The caller must compare ordered native effects (including no-op/extra trigger writes), then
     * add its separately proven outbox deletion/receipt effects before advancing the root.
     */
    suspend fun apply(prepared: Prepared): List<Effect> {
        check(database.inTransaction())
        val effects = mutableListOf<Effect>()
        val plan = prepared.plan
        plan.dayInsert?.let { day ->
            val expected = dayRow(day)
            database.countDayDao().insert(day)
            effects += Effect("count_days", null, expected)
        }
        val busy = replace(prepared.control, mapOf("suppressOutbox" to 1))
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        effects += Effect("sync_control", prepared.control, busy)
        plan.completionUpsert?.let { fact ->
            val saved = database.completionDao().upsert(fact)
            check(saved > 0 && (fact.id == 0L || fact.id == saved))
            effects += Effect("completions", prepared.completion, completionRow(fact.copy(id = saved)))
        }
        plan.shadowUpsert?.let { shadow ->
            // Existing non-deleted shadow is either exactly known (no write) or a rejection.
            check(prepared.shadow == null)
            val expected = shadowRow(shadow)
            insert(expected)
            effects += Effect("sync_entity_state", null, expected)
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        effects += Effect("sync_control", busy, prepared.control)
        for (effect in effects) {
            val expected = effect.after ?: continue
            // Control has two intentional transitions; only its final state remains on disk.
            if (effect.table == "sync_control" && expected !== prepared.control) continue
            check(native.byIdentity(effect.table, expected.identity)?.value == expected.value) { "COUNT_ACK_EFFECT_CHANGED" }
        }
        return Collections.unmodifiableList(effects)
    }

    private fun insert(row: CountTrustRows.Row) {
        val fields = row.table.columns
        sql.execSQL("INSERT INTO `${row.table.name}` (${fields.joinToString(",") { "`$it`" }}) VALUES(${fields.joinToString(",") { "?" }})",
            row.cells.map { it.value }.toTypedArray())
    }
    private fun row(table: String, values: Map<String, Any?>): CountTrustRows.Row {
        val layout = native.table(table)
        require(values.keys == layout.columns.toSet())
        return CountTrustRows.Row(layout, layout.columns.map { cell(values.getValue(it)) })
    }
    private fun replace(row: CountTrustRows.Row, values: Map<String, Any?>) = CountTrustRows.Row(row.table,
        row.table.columns.map { if (it in values) cell(values[it]) else row.cell(it) })
    private fun completionRow(value: CompletionEntity) = row("completions", mapOf(
        "id" to value.id, "habitId" to value.habitId, "date" to value.date, "value" to value.value,
        "actualCompletedAt" to value.actualCompletedAt, "uuid" to value.uuid, "habitUuid" to value.habitUuid,
        "createdAt" to value.createdAt, "recordedTimezone" to value.recordedTimezone,
        "recordedLocalDate" to value.recordedLocalDate, "timeMetadataSource" to value.timeMetadataSource,
        "oneTimeAction" to value.oneTimeAction, "oneTimeExpectedVersion" to value.oneTimeExpectedVersion,
        "oneTimeExpectedHeadEventUuid" to value.oneTimeExpectedHeadEventUuid, "oneTimeRevertsEventUuid" to value.oneTimeRevertsEventUuid))
    private fun dayRow(value: CountDayEntity) = row("count_days", mapOf(
        "habitId" to value.habitId, "habitUuid" to value.habitUuid, "localDate" to value.localDate,
        "targetValue" to value.targetValue, "isCountdown" to value.isCountdown, "firstEventUuid" to value.firstEventUuid,
        "originRequestId" to value.originRequestId, "originHash" to value.originHash,
        "planPredecessorId" to value.planPredecessorId, "planPredecessorOriginHash" to value.planPredecessorOriginHash,
        "planPredecessorDependencyHash" to value.planPredecessorDependencyHash, "planQueueWatermark" to value.planQueueWatermark,
        "capturedDeviceId" to value.capturedDeviceId, "originKind" to value.originKind))
    private fun shadowRow(value: SyncEntityStateEntity) = row("sync_entity_state", mapOf(
        "entityType" to value.entityType, "entityUuid" to value.entityUuid, "revision" to value.revision,
        "deleted" to value.deleted, "payloadJson" to value.payloadJson, "payloadHash" to value.payloadHash, "updatedAt" to value.updatedAt))

    companion object {
        private val countTypes = setOf(JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))
        private val quantityTypes = countTypes + JsonPrimitive("check_in")

        /** Bootstrap-only read-only closure of the original common merger's global rejections.
         * False is a legal state needing original day materialization, not a product rejection.
         * The kernel must not issue its reusable global-health certificate in that case.
         */
        suspend fun globalCommonHealth(database: HabitDatabase): Boolean {
            check(database.inTransaction())
            val sql = database.openHelper.writableDatabase
            for (table in listOf("completions", "metric_logs", "habit_metric_links")) {
                if (sql.query("SELECT 1 FROM $table GROUP BY uuid HAVING COUNT(*)>1 LIMIT 1").use { it.moveToFirst() })
                    throw NextFactMergeException(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
            }
            if (sql.query("SELECT 1 FROM completions c JOIN timelogs t ON c.uuid=t.uuid LIMIT 1").use { it.moveToFirst() })
                throw NextFactMergeException(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
            val ids = sql.query("SELECT entityUuid FROM sync_entity_state WHERE entityType='activity_event' LIMIT 10001").use { cursor ->
                buildList { while (cursor.moveToNext()) {
                    require(cursor.getType(0) == Cursor.FIELD_TYPE_STRING)
                    add(cursor.getString(0))
                } }
            }
            require(ids.size <= 10000)
            val allStates = ids.map { id ->
                requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf("activity_event", id)))
                requireNotNull(database.syncOutboxDao().getState("activity_event", id)).also { state ->
                    require(state.entityType == "activity_event" && state.entityUuid == id && state.revision > 0 && isContractUuid(id))
                    val payload = requireNotNull(state.payloadJson)
                    require(state.payloadHash == syncPayloadHash(payload))
                }
            }
            val events = allStates.filter { !it.deleted }.associate { state ->
                currentCoroutineContext().ensureActive()
                val payload = body(state)
                require(state.revision > 0 && payload["public_id"] == JsonPrimitive(state.entityUuid) &&
                    contractLongOrNull(payload["revision"]) == state.revision && isContractUuid(payload.string("activity_uuid")))
                require(payload.string("event_type") in setOf("check_in", "count_delta", "count_snapshot", "duration_session", "revert"))
                if (payload["event_type"] == JsonPrimitive("revert")) require(isContractUuid(payload.string("reverts_event_uuid")))
                state.entityUuid to payload
            }
            val undo = events.filterValues { it["event_type"] == JsonPrimitive("revert") &&
                it["one_time"].let { marker -> marker == null || marker == JsonNull } }
                .mapValues { it.value.string("reverts_event_uuid") }
            require(undo.values.toSet().size == undo.size)
            val validated = hashSetOf<String>()
            for (event in undo.keys) {
                currentCoroutineContext().ensureActive()
                val seen = hashSetOf<String>(); var cursor: String? = event
                while (cursor != null && cursor !in validated) {
                    val current = cursor
                    require(seen.add(current))
                    val target = undo[current]
                    if (target != null) events[target]?.let { require(it.string("activity_uuid") == events.getValue(current).string("activity_uuid")) }
                    cursor = target
                }
                validated += seen
            }
            val habits = database.habitDao().getAllHabitsOnce().associateBy { it.uuid }
            var materializationRequired = false
            val groups = events.filterValues { it["event_type"] in countTypes }.entries.groupBy {
                it.value.string("activity_uuid") to it.value.string("local_date") }
            for ((key, facts) in groups) {
                currentCoroutineContext().ensureActive()
                val habit = habits[key.first] ?: continue
                val policies = facts.map { NextCommonFactMapper.countPolicy(it.value) }.distinct()
                require(policies.size == 1) { "COUNT_DAY_POLICY_CONFLICT" }
                val policy = policies.single()
                val existing = NextCountDayStore(database).read(habit, key.second, allowUnbound = true)
                if (policy == null) require(existing == null) { "COUNT_DAY_POLICY_UNKNOWN" }
                else if (existing == null) materializationRequired = true
                else {
                    require(existing.policy == policy) { "COUNT_DAY_POLICY_CONFLICT" }
                    // Original restore verifies provenance again after the writes.
                    require(NextCountDayStore(database).read(habit, key.second) == existing)
                }
            }
            return !materializationRequired
        }

        private fun cell(value: Any?) = when (value) {
            null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
            is Boolean -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, if (value) 1L else 0L)
            is Int -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value.toLong())
            is Long -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
            is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
            else -> error("COUNT_ACK_UNSUPPORTED_CELL")
        }
        private fun body(state: SyncEntityStateEntity) = Json.parseToJsonElement(requireNotNull(state.payloadJson)).jsonObject
        private fun JsonObject.string(key: String) = getValue(key).let { require(it is JsonPrimitive && it.isString); it.content }
        private fun CountTrustRows.Row.habit(): HabitEntity {
            val codec = HabitTypeConverter()
            fun nullableInt(name: String) = nullableLong(name)?.also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }?.toInt()
            return HabitEntity(id = long("id"), name = text("name"), description = text("description"),
                habitType = codec.toHabitType(text("habitType")), iconResId = int("iconResId"), colorHex = text("colorHex"),
                schedule = codec.toSchedule(text("schedule")), targetValue = int("targetValue"), isCountdown = bool("isCountdown"),
                isActive = bool("isActive"), uuid = text("uuid"), parentHabitId = nullableText("parentHabitId"),
                targetCycles = nullableInt("targetCycles"), failMode = codec.toFailMode(text("failMode")),
                goalSuccess = if (cell("goalSuccess").type == Cursor.FIELD_TYPE_NULL) null else bool("goalSuccess"),
                activityRate = int("activityRate"), activityRateUpdatedAt = long("activityRateUpdatedAt"),
                bestTime = nullableLong("bestTime"), createdAt = long("createdAt"), updatedAt = long("updatedAt"),
                completionPolicy = nullableText("completionPolicy"), oneTimeConfirmedVersion = nullableInt("oneTimeConfirmedVersion"),
                oneTimeConfirmedHeadEventUuid = nullableText("oneTimeConfirmedHeadEventUuid"),
                oneTimeConfirmedCompletionEventUuid = nullableText("oneTimeConfirmedCompletionEventUuid"),
                appearance = codec.toAppearance(nullableText("appearance")), planMetadata = codec.toPlanMetadata(nullableText("planMetadata")))
        }
        private fun CountTrustRows.Row.outbox() = SyncOutboxEntity(long("id"), text("operationId"), text("recordType"),
            text("entityUuid"), text("wireEntityUuid"), text("action"), nullableText("referenceUuid"), nullableText("payloadJson"),
            nullableLong("baseRevision"), nullableText("basePayloadJson"), nullableLong("attemptedAt"), int("attemptCount"),
            nullableText("lastError"), nullableText("errorCode"), nullableLong("deadLetteredAt"), long("createdAt"))
        private fun CountTrustRows.Row.conflict() = SyncConflictEntity(long("id"), text("operationId"), text("recordType"),
            text("localEntityUuid"), text("wireEntityUuid"), text("entityType"), text("action"), nullableText("referenceUuid"),
            nullableLong("baseRevision"), long("serverRevision"), nullableText("basePayloadJson"), text("localPayloadJson"),
            text("serverPayloadJson"), text("conflictingFieldsJson"), nullableText("conflictKind"), nullableText("errorCode"),
            nullableText("message"), text("status"), nullableText("resolution"), long("createdAt"), nullableLong("resolvedAt"))
    }
}
