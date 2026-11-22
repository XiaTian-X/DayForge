package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.CountDayEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import java.util.Collections
import kotlinx.serialization.json.*

/** Owned native row values and exact primary identities. No DTO coercion is used for equality. */
internal class CountTrustRows(private val sql: SupportSQLiteDatabase) {
    data class Table(val name: String, val columns: List<String>, val primary: List<String>)
    class Row(val table: Table, cells: List<CountTrustJournal.Cell>) {
        val cells: List<CountTrustJournal.Cell> = Collections.unmodifiableList(cells.map { cell ->
            if (cell.type == Cursor.FIELD_TYPE_BLOB) cell.copy(value = Collections.unmodifiableList((cell.value as List<*>).map { it as Byte })) else cell
        })
        init { require(table.columns.size == cells.size) }
        val value: JsonObject by lazy(LazyThreadSafetyMode.NONE) { buildJsonObject {
            table.columns.zip(cells).forEach { (name, cell) -> put(name, encode(cell)) }
        } }
        val identity: String by lazy(LazyThreadSafetyMode.NONE) { JsonArray(table.primary.map { encode(cell(it)) }).toString() }
        val name: String get() = rowName(table.name, identity)
        fun cell(name: String) = cells[table.columns.indexOf(name).also { require(it >= 0) }]
        fun text(name: String): String = cell(name).let { require(it.type == Cursor.FIELD_TYPE_STRING); it.value as String }
        fun long(name: String): Long = cell(name).let { require(it.type == Cursor.FIELD_TYPE_INTEGER); it.value as Long }
        fun int(name: String): Int = long(name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
        fun bool(name: String): Boolean = long(name).also { require(it in 0..1) } == 1L
        fun nullableText(name: String): String? = if (cell(name).type == Cursor.FIELD_TYPE_NULL) null else text(name)
        fun nullableLong(name: String): Long? = if (cell(name).type == Cursor.FIELD_TYPE_NULL) null else long(name)
        fun completion(): CompletionEntity {
            require(table.name == "completions")
            return CompletionEntity(id = long("id"), habitId = long("habitId"), date = long("date"), value = int("value"),
                actualCompletedAt = nullableLong("actualCompletedAt"), uuid = text("uuid"), habitUuid = nullableText("habitUuid"),
                createdAt = long("createdAt"), recordedTimezone = text("recordedTimezone"), recordedLocalDate = text("recordedLocalDate"),
                timeMetadataSource = text("timeMetadataSource"), oneTimeAction = nullableText("oneTimeAction"),
                oneTimeExpectedVersion = nullableLong("oneTimeExpectedVersion")?.also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }?.toInt(),
                oneTimeExpectedHeadEventUuid = nullableText("oneTimeExpectedHeadEventUuid"), oneTimeRevertsEventUuid = nullableText("oneTimeRevertsEventUuid"))
        }
        fun day(): CountDayEntity {
            require(table.name == "count_days")
            return CountDayEntity(long("habitId"), text("habitUuid"), text("localDate"), int("targetValue"), bool("isCountdown"),
                text("firstEventUuid"), nullableText("originRequestId"), nullableText("originHash"), nullableText("planPredecessorId"),
                nullableText("planPredecessorOriginHash"), nullableText("planPredecessorDependencyHash"),
                nullableLong("planQueueWatermark"), nullableText("capturedDeviceId"), text("originKind"))
        }
        fun shadow(): SyncEntityStateEntity {
            require(table.name == "sync_entity_state")
            return SyncEntityStateEntity(text("entityType"), text("entityUuid"), long("revision"), bool("deleted"),
                nullableText("payloadJson"), nullableText("payloadHash"), long("updatedAt"))
        }
    }

    private val tables = hashMapOf<String, Table>()
    fun table(name: String): Table = tables.getOrPut(name) {
        require(name in CountTrustSchema.BUSINESS_TABLES)
        val fields = sql.query("PRAGMA table_info(`$name`)").use { c -> buildList {
            while (c.moveToNext()) add(c.getString(1) to c.getInt(5))
        } }
        require(fields.isNotEmpty() && fields.map { it.first }.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) })
        val primary = fields.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }
        require(primary.isNotEmpty())
        Table(name, Collections.unmodifiableList(fields.map { it.first }), Collections.unmodifiableList(primary))
    }

    fun decode(name: String, value: JsonObject): Row {
        val table = table(name); require(value.keys == table.columns.toSet())
        return Row(table, table.columns.map { decodeCell(value.getValue(it)) })
    }

    fun read(name: String, where: String = "1", args: Array<out Any?> = emptyArray()): List<Row> {
        val table = table(name)
        return sql.query("SELECT * FROM `$name` WHERE $where ORDER BY ${table.primary.joinToString(",") { "`$it`" }}", args).use { c ->
            require(c.columnNames.toList() == table.columns)
            buildList { while (c.moveToNext()) add(Row(table, table.columns.indices.map { capture(c, it) })) }
        }
    }

    fun byIdentity(name: String, identity: String): Row? {
        val table = table(name)
        val key = Json.parseToJsonElement(identity).jsonArray.map(::decodeCell)
        require(key.size == table.primary.size)
        val args = key.map { cell -> when (cell.type) {
            Cursor.FIELD_TYPE_FLOAT -> Double.fromBits(cell.value as Long)
            Cursor.FIELD_TYPE_BLOB -> (cell.value as List<*>).map { it as Byte }.toByteArray()
            else -> cell.value
        } }.toTypedArray()
        return read(name, table.primary.joinToString(" AND ") { "`$it` IS ?" }, args).singleOrNull()
    }

    companion object {
        fun rowName(table: String, identity: String) = "row:$table:$identity"
        fun encode(cell: CountTrustJournal.Cell): JsonElement = buildJsonArray {
            add(cell.type)
            add(when (cell.type) {
                Cursor.FIELD_TYPE_NULL -> JsonNull
                Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cell.value as Long)
                Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cell.value as String)
                Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(android.util.Base64.encodeToString(
                    (cell.value as List<*>).map { it as Byte }.toByteArray(), android.util.Base64.NO_WRAP))
                else -> error("COUNT_TRUST_CELL_TYPE")
            })
        }
        fun decodeCell(value: JsonElement): CountTrustJournal.Cell {
            val pair = value.jsonArray; require(pair.size == 2)
            val type = pair[0].jsonPrimitive.int
            val body = when (type) {
                Cursor.FIELD_TYPE_NULL -> { require(pair[1] == JsonNull); null }
                Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT -> pair[1].jsonPrimitive.also { require(!it.isString) }.long
                Cursor.FIELD_TYPE_STRING -> pair[1].jsonPrimitive.also { require(it.isString) }.content
                Cursor.FIELD_TYPE_BLOB -> android.util.Base64.decode(pair[1].jsonPrimitive.also { require(it.isString) }.content, android.util.Base64.NO_WRAP).toList()
                else -> error("COUNT_TRUST_CELL_TYPE")
            }
            return CountTrustJournal.Cell(type, body)
        }
        private fun capture(c: Cursor, index: Int) = CountTrustJournal.Cell(c.getType(index), when (c.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toRawBits()
            Cursor.FIELD_TYPE_STRING -> c.getString(index)
            Cursor.FIELD_TYPE_BLOB -> Collections.unmodifiableList(c.getBlob(index).toList())
            else -> error("COUNT_TRUST_CELL_TYPE")
        })
    }
}
