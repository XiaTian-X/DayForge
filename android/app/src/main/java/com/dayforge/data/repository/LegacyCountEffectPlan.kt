package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.domain.model.isContractUuid
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

/** Pure conditional description of the original v4 local SQL effects, not a proof issuer.
 * A private primary participant must freeze the OLD rows before business DML, bind the narrow
 * original clock/SQLite/trigger allocations, then compare the COMPLETE shared native journal.
 * No expected value below is learned from journal events or a post-write final row.
 *
 * The legacy command adapter must supply the original captured values. This does not create
 * a v5 origin/revert: v4 source folding / first-send wire allocation remains the original sender.
 */
internal class LegacyCountEffectPlan private constructor(
    nativeHabit: CountTrustRows.Row,
    private val action: Action,
    private val completionLayout: CountTrustRows.Table,
    private val sourceLayout: CountTrustRows.Table,
    private val sourceWatermark: Long
) {
    private val oldHabit = freeze(nativeHabit)
    private val habitId = oldHabit.long("id")
    private val habitUuid = oldHabit.text("uuid")
    val requiresActivation: Boolean = action is Action.Add && !oldHabit.bool("isActive")

    sealed interface Action {
        /** These are the original input coordinates, not an extra early clock capture. */
        data class Add(val uuid: String, val occurredAt: Instant, val zone: ZoneId, val quantity: Int) : Action
        /** The selected native target is immutable; no "latest" selection occurs on replay. */
        class Delete internal constructor(target: CountTrustRows.Row) : Action { val before = freeze(target) }
    }
    data class SourceAllocation(val id: Long, val operationId: String, val createdAt: Long)
    data class RateAllocation(val value: Int, val updateAt: Long)

    /** The private original executor captures this scalar where updateIsActive's default
     * argument originally ran. It does NOT use the T0 timestamp or an observed NEW row.
     */
    fun activationBeforeWrite(updatedAt: Long): CountTrustRows.Row {
        check(requiresActivation)
        return replace(oldHabit, mapOf("isActive" to integer(1), "updatedAt" to integer(updatedAt)))
    }

    /** Called at the original CompletionEntity construction position, after any reactivation.
     * UUID belongs to the complete durable input; createdAt remains this original T1 clock.
     */
    fun completionAtOriginalConstruction(createdAt: Long): CompletionEntity {
        val add = action as? Action.Add ?: error("COMMAND_LEGACY_NOT_AN_ADD")
        return CompletionEntity(habitId = habitId,
            date = add.occurredAt.atZone(add.zone).toLocalDate().atStartOfDay(add.zone).toInstant().toEpochMilli(),
            value = add.quantity, actualCompletedAt = add.occurredAt.toEpochMilli(), uuid = add.uuid,
            habitUuid = habitUuid, createdAt = createdAt, recordedTimezone = add.zone.id)
    }

    /** IDs are read only from the preselected new source interval, never by enumerating the
     * actual journal and interpreting whatever it happens to contain as the expected plan.
     * The original private rate calculation supplies value and its separate DAO update time.
     */
    fun bind(completion: CompletionEntity?, insertedId: Long?, activationAt: Long?,
        sources: List<SourceAllocation>, rate: RateAllocation): Bound {
        check((activationAt != null) == requiresActivation)
        require(sources.size == if (requiresActivation) 2 else 1)
        require(sources.map { it.id }.distinct().size == sources.size &&
            sources.map { it.operationId }.distinct().size == sources.size)
        var watermark = sourceWatermark
        sources.forEach { allocation ->
            require(allocation.id > watermark && isContractUuid(allocation.operationId) && allocation.createdAt % 1000L == 0L)
            watermark = allocation.id
        }
        val groups = arrayListOf<List<CountTrustAck.Effect>>()
        var habit = oldHabit
        var sourceOffset = 0
        if (requiresActivation) {
            val activated = activationBeforeWrite(requireNotNull(activationAt))
            groups += listOf(CountTrustAck.Effect("habits", habit, activated),
                CountTrustAck.Effect("sync_outbox", null, source(sources[sourceOffset++], "habit", habitUuid, "upsert", habit.text("habitType"))))
            habit = activated
        }
        val factEffect: CountTrustAck.Effect
        val factUuid: String
        val sourceAction: String
        val sourceReference: String
        when (val intended = action) {
            is Action.Add -> {
                val constructed = requireNotNull(completion)
                check(constructed == completionAtOriginalConstruction(constructed.createdAt)) { "COMMAND_LEGACY_FACT_INPUT_CHANGED" }
                val actualId = requireNotNull(insertedId)
                require(constructed.id == 0L && actualId > 0L)
                val actual = factRow(constructed.copy(id = actualId))
                factEffect = CountTrustAck.Effect("completions", null, actual)
                factUuid = intended.uuid; sourceAction = "upsert"; sourceReference = habitUuid
            }
            is Action.Delete -> {
                require(completion == null && insertedId == null && activationAt == null)
                factEffect = CountTrustAck.Effect("completions", intended.before, null)
                factUuid = intended.before.text("uuid"); sourceAction = "delete"
                sourceReference = intended.before.nullableText("habitUuid") ?: habitUuid
            }
        }
        groups += listOf(factEffect, CountTrustAck.Effect("sync_outbox", null,
            source(sources[sourceOffset], "completion", factUuid, sourceAction, sourceReference)))
        // This is required even when both fields already equal the result. Omitting this
        // no-op UPDATE cannot be compensated by an unrelated write with the same generation.
        val rated = replace(habit, mapOf("activityRate" to integer(rate.value.toLong()), "activityRateUpdatedAt" to integer(rate.updateAt)))
        groups += listOf(CountTrustAck.Effect("habits", habit, rated))
        return Bound(groups)
    }

    class Bound internal constructor(groups: List<List<CountTrustAck.Effect>>) {
        private val groups = Collections.unmodifiableList(groups.map { Collections.unmodifiableList(it.toList()) })
        val effects: List<CountTrustAck.Effect> = Collections.unmodifiableList(groups.flatten())
        val finalRows: Map<String, CountTrustRows.Row?> = Collections.unmodifiableMap(linkedMapOf<String, CountTrustRows.Row?>().also { rows ->
            effects.forEach { effect -> rows[(effect.after ?: requireNotNull(effect.before)).name] = effect.after }
        })

        fun verifyActual(changes: List<CountTrustJournal.Change>) {
            check(changes.size == effects.size) { "COMMAND_LEGACY_UNEXPECTED_EFFECT_COUNT" }
            var offset = 0
            for (group in groups) {
                val actual = changes.subList(offset, offset + group.size)
                val direct = actual.indices.all { matches(actual[it], group[it]) }
                val triggerFirst = group.size == 2 && matches(actual[0], group[1]) && matches(actual[1], group[0])
                check(direct || triggerFirst) { "COMMAND_LEGACY_UNEXPECTED_OLD_NEW_EFFECT" }
                offset += group.size
            }
        }

        /** Native terminal reads are an additional barrier, never the expected-effect source.
         * Caller must query every exact primary identity, including the deleted target's
         * strict absence, under the same lifetime and compare all native types and fields.
         */
        fun verifyFinal(actual: Map<String, CountTrustRows.Row?>) {
            check(actual.keys == finalRows.keys) { "COMMAND_LEGACY_FINAL_COVERAGE" }
            finalRows.forEach { (key, expected) ->
                val found = actual.getValue(key)
                check(found?.table == expected?.table && found?.cells == expected?.cells) { "COMMAND_LEGACY_FINAL_CHANGED" }
            }
        }

        private fun matches(actual: CountTrustJournal.Change, expected: CountTrustAck.Effect): Boolean {
            if (actual.table != expected.table) return false
            val operation = when { expected.before == null -> "INSERT"; expected.after == null -> "DELETE"; else -> "UPDATE" }
            if (actual.operation != operation) return false
            val fields = CountTrustJournal.COLUMNS.getValue(expected.table)
            return actual.old == expected.before?.let { fields.map(it::cell) } && actual.new == expected.after?.let { fields.map(it::cell) }
        }
    }

    private fun factRow(v: CompletionEntity) = row(completionLayout, mapOf(
        "id" to v.id, "habitId" to v.habitId, "date" to v.date, "value" to v.value,
        "actualCompletedAt" to v.actualCompletedAt, "uuid" to v.uuid, "habitUuid" to v.habitUuid,
        "createdAt" to v.createdAt, "recordedTimezone" to v.recordedTimezone, "recordedLocalDate" to v.recordedLocalDate,
        "timeMetadataSource" to v.timeMetadataSource, "oneTimeAction" to v.oneTimeAction,
        "oneTimeExpectedVersion" to v.oneTimeExpectedVersion, "oneTimeExpectedHeadEventUuid" to v.oneTimeExpectedHeadEventUuid,
        "oneTimeRevertsEventUuid" to v.oneTimeRevertsEventUuid))

    private fun source(v: SourceAllocation, type: String, uuid: String, action: String, reference: String) = row(sourceLayout, mapOf(
        "id" to v.id, "operationId" to v.operationId, "recordType" to type, "entityUuid" to uuid, "wireEntityUuid" to uuid,
        "action" to action, "referenceUuid" to reference, "payloadJson" to null, "baseRevision" to null,
        "basePayloadJson" to null, "attemptedAt" to null, "attemptCount" to 0, "lastError" to null,
        "errorCode" to null, "deadLetteredAt" to null, "createdAt" to v.createdAt))

    companion object {
        fun add(habit: CountTrustRows.Row, intent: Action.Add, completionLayout: CountTrustRows.Table,
            sourceLayout: CountTrustRows.Table, watermark: Long): LegacyCountEffectPlan {
            require(isContractUuid(intent.uuid))
            return create(habit, intent, completionLayout, sourceLayout, watermark)
        }
        fun delete(habit: CountTrustRows.Row, target: CountTrustRows.Row,
            sourceLayout: CountTrustRows.Table, watermark: Long): LegacyCountEffectPlan {
            require(target.table.name == "completions" && target.long("habitId") == habit.long("id") && target.nullableText("oneTimeAction") == null)
            // Preserve the actual trigger's COALESCE, including its nullable OLD field.
            // Widget's original stricter owner predicate remains in the Widget executor;
            // this common pure plan cannot silently add it to the ordinary repository path.
            return create(habit, Action.Delete(target), target.table, sourceLayout, watermark)
        }
        private fun create(habit: CountTrustRows.Row, action: Action, completions: CountTrustRows.Table,
            sources: CountTrustRows.Table, watermark: Long): LegacyCountEffectPlan {
            require(habit.table.name == "habits" && habit.text("habitType") == "COUNTING" && habit.nullableText("appearance") == null &&
                habit.nullableText("completionPolicy") != "one_and_done" && watermark >= 0)
            require(completions.name == "completions" && sources.name == "sync_outbox")
            return LegacyCountEffectPlan(habit, action, freeze(completions), freeze(sources), watermark)
        }
        private fun freeze(table: CountTrustRows.Table) = table.copy(columns = Collections.unmodifiableList(table.columns.toList()),
            primary = Collections.unmodifiableList(table.primary.toList()))
        private fun freeze(row: CountTrustRows.Row) = CountTrustRows.Row(freeze(row.table), row.cells)
        private fun replace(row: CountTrustRows.Row, values: Map<String, CountTrustJournal.Cell>): CountTrustRows.Row {
            require(row.table.columns.containsAll(values.keys))
            return CountTrustRows.Row(row.table, row.table.columns.map { values[it] ?: row.cell(it) })
        }
        private fun integer(value: Long) = CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
        private fun row(layout: CountTrustRows.Table, values: Map<String, Any?>): CountTrustRows.Row {
            check(layout.columns.toSet() == values.keys) { "COMMAND_LEGACY_LAYOUT_CHANGED" }
            return CountTrustRows.Row(layout, layout.columns.map { name -> when (val value = values.getValue(name)) {
                null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
                is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
                is Long -> integer(value)
                is Int -> integer(value.toLong())
                else -> error("COMMAND_LEGACY_EXPECTED_NATIVE_TYPE")
            } })
        }
    }
}
