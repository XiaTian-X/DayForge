package com.dayforge.data.local

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.*

/** Exact stage assertions for the original damaged-old-file cases. These predicates neither
 * inject a failure nor accept an arbitrary open error. Preserve the original cause on failure. */
internal fun requireMissingTableMigrationFailure(error: Throwable?, table: String) {
    require(table in setOf("one_time_transmissions", "completion_metric_prompts"))
    val causes = generateSequence(error) { it.cause }.toList()
    val original = causes.filterIsInstance<SQLiteException>().singleOrNull {
        it.message.orEmpty().contains("no such table: main.$table") &&
            it.message.orEmpty().contains("CREATE TRIGGER `count_trust_dirty_${table}_insert`")
    }
    if (original == null || original.stackTrace.none {
            it.className == "com.dayforge.data.local.CountTrustSchema" && it.methodName == "install"
        }) migrationStageFailure("Expected original missing-table SQLite failure during COUNT dirty-trigger installation", error)
}

internal fun requireFinalRoomMigrationValidationFailure(error: Throwable?, table: String, missingColumn: String) {
    require(table in setOf("one_time_transmissions", "completion_metric_prompts"))
    val original = generateSequence(error) { it.cause }.filterIsInstance<IllegalStateException>().singleOrNull {
        it.message.orEmpty().startsWith("Migration didn't properly handle: $table(") &&
            it.message.orEmpty().contains("Expected:") && it.message.orEmpty().contains("Found:") &&
            it.message.orEmpty().contains("unavailable_$missingColumn")
    }
    if (original == null || original.stackTrace.none {
            it.className == "androidx.room.BaseRoomConnectionManager" && it.methodName == "onMigrate"
        }) migrationStageFailure("Expected actual Room final TableInfo validation after all migration edges", error)
}

private fun migrationStageFailure(expected: String, original: Throwable?): Nothing {
    val causes = generateSequence(original) { it.cause }.take(8)
        .joinToString(" -> ") { "${it.javaClass.name}: ${it.message.orEmpty().take(1600)}" }
    throw AssertionError("$expected; actual=$causes").also { if (original != null) it.initCause(original) }
}

internal fun migrationValidationImage(sql: SupportSQLiteDatabase): JsonObject =
    migrationValidationImage(sql.version) { sql.query(it) }

internal fun migrationValidationImage(sql: SQLiteDatabase): JsonObject =
    migrationValidationImage(sql.version) { sql.rawQuery(it, null) }

/** One complete main image: schema/rootpages, old user_version, rowids, every native type and
 * cell. No TEMP name resolution, excluded schema object, filtered row, or checksum shortcut. */
private fun migrationValidationImage(version: Int, query: (String) -> Cursor): JsonObject {
    fun row(c: Cursor): JsonObject = buildJsonObject {
        for (i in 0 until c.columnCount) put(c.getColumnName(i), buildJsonObject {
            put("sqlite_type", c.getType(i))
            put("value", when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JsonNull
                Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i).toRawBits().toString(16))
                Cursor.FIELD_TYPE_STRING -> JsonPrimitive(c.getString(i))
                Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(c.getBlob(i).joinToString("") { "%02x".format(it.toInt() and 255) })
                else -> error("Unexpected SQLite native cell")
            })
        })
    }
    val schema = query("SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
        buildJsonArray { while (c.moveToNext()) add(row(c)) }
    }
    val names = query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }
    return buildJsonObject {
        put("user_version", version); put("schema", schema)
        put("tables", buildJsonObject { for (table in names) {
            require(table.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
            // DDL can leave Android's cached SELECT-* column labels from the previous
            // shape. Capture every current column and select it explicitly; this also
            // changes the prepared SQL when a column is renamed, without dropping a cell.
            val columns = query("PRAGMA main.table_info(`$table`)").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(1)) }
            }
            check(columns.isNotEmpty() && columns.size == columns.toSet().size)
            fun quoted(value: String) = "\"${value.replace("\"", "\"\"")}\""
            val projection = columns.joinToString(",") { "${quoted(table)}.${quoted(it)}" }
            put(table, query("SELECT rowid AS __recovery_rowid,$projection FROM main.`$table` ORDER BY rowid").use { c ->
                buildJsonArray { while (c.moveToNext()) add(row(c)) }
            })
        } })
    }
}
