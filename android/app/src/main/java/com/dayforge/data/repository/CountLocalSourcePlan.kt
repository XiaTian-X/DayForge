package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.isContractUuid

/** Optional source contract for one already-active recurring COUNT +1. This freezes the
 * actual repository input before day/fact insertion. It grants no domain or SQL authority;
 * the original operation/day/round/rate/causal validators still own those contracts.
 */
internal class CountLocalSourcePlan private constructor(
    private val fact: CompletionEntity,
    private val watermark: Long
) {
    /** Compare every native fact cell before a Room DTO could coerce a damaged type. An
     * unsupported callback falls back to the unchanged original producer and its rejection.
     */
    fun matchesNativeFact(id: Long, actual: CountTrustRows.Row?): Boolean {
        val row = actual ?: return false
        if (id <= 0 || row.table.name != "completions") return false
        val values = factValues(id)
        if (row.table.columns.toSet() != values.keys) return false
        val expected = CountTrustRows.Row(row.table, row.table.columns.map { column -> when (val value = values.getValue(column)) {
            null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
            is Long -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
            is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
            else -> error("COUNT_SOURCE_FACT_CELL")
        } })
        return row.value == expected.value
    }

    private fun factValues(id: Long): Map<String, Any?> = linkedMapOf(
            "id" to id, "habitId" to fact.habitId, "date" to fact.date, "value" to fact.value.toLong(),
            "actualCompletedAt" to fact.actualCompletedAt, "uuid" to fact.uuid, "habitUuid" to fact.habitUuid,
            "createdAt" to fact.createdAt, "recordedTimezone" to fact.recordedTimezone,
            "recordedLocalDate" to fact.recordedLocalDate, "timeMetadataSource" to fact.timeMetadataSource,
            "oneTimeAction" to fact.oneTimeAction, "oneTimeExpectedVersion" to fact.oneTimeExpectedVersion?.toLong(),
            "oneTimeExpectedHeadEventUuid" to fact.oneTimeExpectedHeadEventUuid,
            "oneTimeRevertsEventUuid" to fact.oneTimeRevertsEventUuid)

    /** Same complete frozen fact comparison as matchesNativeFact, but SQL returns only bounded
     * identities and a boolean. No damaged arbitrary fact body is materialized for this hint.
     */
    fun verifiedStoredFact(sql: SupportSQLiteDatabase, id: Long): CompletionEntity? {
        if (id <= 0) return null
        val ids = sql.query("SELECT id FROM main.completions WHERE uuid=? ORDER BY id LIMIT 2", arrayOf(fact.uuid)).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                if (cursor.getType(0) != Cursor.FIELD_TYPE_INTEGER) return null
                add(cursor.getLong(0))
            } }
        }
        if (ids != listOf(id)) return null
        val values = factValues(id)
        val names = CountTrustJournal.COLUMNS.getValue("completions")
        check(names.toSet() == values.keys && names.size == values.size) { "COUNT_SOURCE_FACT_LAYOUT" }
        val length = names.joinToString("+") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
        val expectedLength = values.values.joinToString("+") { "COALESCE(length(CAST(? AS BLOB)),0)" }
        val lengthArgs = (values.values + id).toTypedArray()
        val sameLength = sql.query("SELECT $length,$expectedLength FROM main.completions WHERE id=? LIMIT 2", lengthArgs).use { cursor ->
            cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getType(1) == Cursor.FIELD_TYPE_INTEGER &&
                cursor.getLong(0) == cursor.getLong(1) && !cursor.moveToNext()
        }
        if (!sameLength) return null
        val args = arrayListOf<Any>()
        val equal = values.map { (name, value) -> when (value) {
            null -> "`$name` IS NULL"
            is Long -> { args += value; "typeof(`$name`)='integer' AND `$name` IS ?" }
            is String -> { args += value; "typeof(`$name`)='text' AND CAST(`$name` AS BLOB)=CAST(? AS BLOB)" }
            else -> error("COUNT_SOURCE_FACT_CELL")
        } }.joinToString(" AND ")
        args += id
        val matched = sql.query("SELECT CASE WHEN $equal THEN 1 ELSE 0 END FROM main.completions WHERE id=? LIMIT 2",
            args.toTypedArray()).use { cursor ->
            cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) == 1L && !cursor.moveToNext()
        }
        return fact.copy(id = id).takeIf { matched }
    }

    /** The original trigger allocates exactly ID, operationId and second-resolution createdAt.
     * A different legitimate callback shape is an optional miss, not a new business rejection.
     */
    fun bind(id: Long, actualFact: CompletionEntity?, actualSource: SyncOutboxEntity?): SyncOutboxEntity? {
        if (id <= 0 || actualFact != fact.copy(id = id) || actualSource == null) return null
        if (actualSource.id <= watermark || !isContractUuid(actualSource.operationId) ||
            actualSource.createdAt % 1000L != 0L) return null
        val expected = SyncOutboxEntity(
            id = actualSource.id, operationId = actualSource.operationId, recordType = "completion",
            entityUuid = fact.uuid, wireEntityUuid = fact.uuid, action = "upsert",
            referenceUuid = requireNotNull(fact.habitUuid), payloadJson = null,
            baseRevision = null, basePayloadJson = null, attemptedAt = null, attemptCount = 0,
            lastError = null, errorCode = null, deadLetteredAt = null, createdAt = actualSource.createdAt
        )
        return expected.takeIf { actualSource == it }
    }

    val uuid: String get() = fact.uuid

    companion object {
        fun freeze(habit: HabitEntity, fact: CompletionEntity, watermark: Long): CountLocalSourcePlan? {
            if (!habit.isActive || habit.habitType != HabitType.COUNTING || habit.appearance == null ||
                habit.completionPolicy != "recurring" || fact.id != 0L || fact.value != 1 ||
                fact.habitId != habit.id || fact.habitUuid != habit.uuid || !isContractUuid(fact.uuid) ||
                fact.oneTimeAction != null || fact.oneTimeExpectedVersion != null ||
                fact.oneTimeExpectedHeadEventUuid != null || fact.oneTimeRevertsEventUuid != null) return null
            require(watermark >= 0)
            return CountLocalSourcePlan(fact.copy(), watermark)
        }
    }
}
