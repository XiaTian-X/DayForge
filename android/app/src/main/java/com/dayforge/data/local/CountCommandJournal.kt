package com.dayforge.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** Native metadata-only evidence for the primary command domain. The factory owns the raw
 * connection; ordinary SQL cannot install, mutate, or erase these objects. This does not infer
 * allowed writes. Every event must match a separately prepared exact mutation, while the
 * private store verifies complete expected native OLD/NEW contents under the same transaction.
 */
internal class CountCommandJournal(private val sql: SupportSQLiteDatabase) {
    sealed interface Boundary
    private data class Issued(val owner: CountCommandJournal, val tx: Any, val caller: Any?, val epoch: Long,
        val sequence: Long) : Boundary
    data class AnchorState(val storage: String, val generation: Long, val dirty: Long, val hash: String?)
    data class Expected(val table: String, val operation: String, val key: String,
        val controlBefore: AnchorState? = null, val controlAfter: AnchorState? = null) {
        init {
            require(table in KEYS && operation in setOf("INSERT", "UPDATE", "DELETE"))
            require(if (table == ROOT || table == CONTROL) key == "1" else key.length in 1..64)
            if (table == CONTROL) require(operation == "UPDATE" && controlBefore != null && controlAfter != null)
            else require(controlBefore == null && controlAfter == null)
        }
    }
    private val nonce = UUID.randomUUID().toString()
    private var installed = false
    private var dataVersion = -1L
    private var schemaVersion = -1L
    private var lastTx: Any? = null
    private val definitions = buildMap {
        put("count_command_journal_identity", "CREATE TEMP TABLE count_command_journal_identity(nonce TEXT NOT NULL)")
        put("count_command_journal_events", "CREATE TEMP TABLE count_command_journal_events(seq INTEGER PRIMARY KEY AUTOINCREMENT," +
            "tableName TEXT NOT NULL,operation TEXT NOT NULL,oldType TEXT NOT NULL,oldKey,newType TEXT NOT NULL,newKey," +
            anchorColumns.joinToString(",") + ")")
        for ((table, key) in KEYS.filterKeys { it != CONTROL }) for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            val old = if (operation == "INSERT") "'null',NULL" else cell("OLD", key)
            val fresh = if (operation == "DELETE") "'null',NULL" else cell("NEW", key)
            val name = "count_command_journal_${table}_$operation"
            put(name, "CREATE TEMP TRIGGER `$name` AFTER $operation ON main.`$table` BEGIN " +
                "INSERT INTO count_command_journal_events(tableName,operation,oldType,oldKey,newType,newKey) " +
                "VALUES('$table','$operation',$old,$fresh); END")
        }
        // Only explicitly naming the anchor column emits this event. Original business
        // generation/dirty triggers neither add metadata work nor disappear from domain proof.
        put("count_command_journal_control_anchor", "CREATE TEMP TRIGGER `count_command_journal_control_anchor` " +
            "AFTER UPDATE OF commandRootHash ON main.count_trust_control BEGIN " +
            "INSERT INTO count_command_journal_events(tableName,operation,oldType,oldKey,newType,newKey," +
            anchorColumns.joinToString(",") + ") VALUES('$CONTROL','UPDATE'," + cell("OLD", "id") + "," + cell("NEW", "id") + "," +
            listOf("OLD", "NEW").flatMap { side -> listOf("storageId", "generation", "dirty", "commandRootHash").map { key ->
                "typeof($side.`$key`),CASE WHEN typeof($side.`$key`) IN ('text','integer') AND " +
                    "length(CAST($side.`$key` AS BLOB))<=256 THEN $side.`$key` ELSE NULL END"
            } }.joinToString(",") + "); END")
    }

    fun install() {
        check(!installed && sql.inTransaction() && scalar("PRAGMA recursive_triggers") == 1L)
        definitions.values.take(2).forEach(sql::execSQL)
        sql.execSQL("INSERT INTO temp.count_command_journal_identity VALUES(?)", arrayOf(nonce))
        definitions.values.drop(2).forEach(sql::execSQL)
        dataVersion = scalar("PRAGMA main.data_version"); schemaVersion = scalar("PRAGMA main.schema_version")
        installed = true; checkConnection()
    }

    private fun checkConnection() {
        check(installed && sql.inTransaction()) { "COMMAND_JOURNAL_TRANSACTION_REQUIRED" }
        check(scalar("PRAGMA main.data_version") == dataVersion && scalar("PRAGMA main.schema_version") == schemaVersion &&
            scalar("PRAGMA recursive_triggers") == 1L) { "COMMAND_JOURNAL_CONNECTION_CHANGED" }
        sql.query("SELECT nonce FROM temp.count_command_journal_identity LIMIT 2").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING && it.getString(0) == nonce && !it.moveToNext())
        }
        val seen = hashSetOf<String>()
        sql.query("SELECT CASE WHEN length(CAST(name AS BLOB))<=256 THEN name END," +
            "CASE WHEN length(CAST(sql AS BLOB))<=8192 THEN sql END FROM temp.sqlite_master " +
            "WHERE substr(name,1,22)='count_command_journal_' ORDER BY name").use { c ->
            while (c.moveToNext()) {
                check(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getType(1) == Cursor.FIELD_TYPE_STRING)
                val name = c.getString(0)
                check(seen.add(name) && c.getString(1) == definitions[name]?.replaceFirst("CREATE TEMP ", "CREATE ")) {
                    "COMMAND_JOURNAL_SCHEMA_CHANGED"
                }
            }
        }
        check(seen == definitions.keys) { "COMMAND_JOURNAL_SCHEMA_CHANGED" }
    }

    /** The factory alone invokes this during its exact authorized whole-dataset reset.
     * TEMP DDL is transactional: known rollback restores this object's original native
     * nonce/schema, while known COMMIT discards the object. Never retarget an old boundary
     * to a new schema or nonce, and never reuse this object after unknown END.
     */
    fun dropForAuthorizedReset() {
        checkConnection()
        definitions.keys.toList().asReversed().forEach { name ->
            sql.execSQL(if (name in definitions.keys.take(2)) "DROP TABLE temp.`$name`" else "DROP TRIGGER temp.`$name`")
        }
    }

    fun capture(tx: Any, caller: Any?, epoch: Long): Boundary {
        checkConnection()
        // No old transaction can use this capability after its actual END. Retain the sequence
        // watermark but bound native TEMP rows to the current transaction; no persistent data GC.
        if (lastTx !== tx) { sql.execSQL("DELETE FROM temp.count_command_journal_events"); lastTx = tx }
        return Issued(this, tx, caller, epoch, sequence())
    }

    fun verify(boundary: Boundary, tx: Any, caller: Any?, epoch: Long, expected: List<Expected>) {
        checkConnection()
        val issued = boundary as? Issued ?: error("COMMAND_JOURNAL_FOREIGN_BOUNDARY")
        check(issued.owner === this && issued.tx === tx && issued.caller === caller && issued.epoch == epoch) {
            "COMMAND_JOURNAL_BOUNDARY_EXPIRED"
        }
        val end = sequence()
        check(end >= issued.sequence && end - issued.sequence == expected.size.toLong()) { "COMMAND_JOURNAL_EXTRA_OR_GAP" }
        var index = 0
        sql.query("SELECT seq,tableName,operation,oldType,oldKey,newType,newKey," + anchorColumns.joinToString(",") + " FROM temp.count_command_journal_events " +
            "WHERE seq>? ORDER BY seq LIMIT ?", arrayOf<Any>(issued.sequence, expected.size + 1)).use { c ->
            while (c.moveToNext()) {
                check(index < expected.size && c.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                    c.getLong(0) == Math.addExact(issued.sequence, index.toLong() + 1)) { "COMMAND_JOURNAL_EXTRA_OR_GAP" }
                val wanted = expected[index++]
                check((1..3).all { c.getType(it) == Cursor.FIELD_TYPE_STRING } && c.getType(5) == Cursor.FIELD_TYPE_STRING &&
                    c.getString(1) == wanted.table && c.getString(2) == wanted.operation) { "COMMAND_JOURNAL_UNEXPECTED_WRITE" }
                val keyType = if (wanted.table == ROOT || wanted.table == CONTROL) "integer" else "text"
                checkSide(c, 3, wanted.operation != "INSERT", keyType, wanted.key)
                checkSide(c, 5, wanted.operation != "DELETE", keyType, wanted.key)
                if (wanted.table == CONTROL) {
                    checkAnchor(c, 7, requireNotNull(wanted.controlBefore))
                    checkAnchor(c, 15, requireNotNull(wanted.controlAfter))
                } else check((7..22).all { c.getType(it) == Cursor.FIELD_TYPE_NULL }) { "COMMAND_JOURNAL_CONTROL_PADDING" }
            }
        }
        check(index == expected.size && sequence() == end) { "COMMAND_JOURNAL_EXTRA_OR_GAP" }
        checkConnection()
    }

    private fun checkAnchor(c: Cursor, first: Int, expected: AnchorState) {
        val values = listOf(expected.storage, expected.generation, expected.dirty, expected.hash)
        for ((index, value) in values.withIndex()) {
            val at = first + index * 2
            check(c.getType(at) == Cursor.FIELD_TYPE_STRING) { "COMMAND_JOURNAL_CONTROL_TYPE" }
            when (value) {
                null -> check(c.getString(at) == "null" && c.getType(at + 1) == Cursor.FIELD_TYPE_NULL)
                is String -> check(c.getString(at) == "text" && c.getType(at + 1) == Cursor.FIELD_TYPE_STRING && c.getString(at + 1) == value)
                is Long -> check(c.getString(at) == "integer" && c.getType(at + 1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(at + 1) == value)
                else -> error("COMMAND_JOURNAL_CONTROL_EXPECTED")
            }
        }
    }
    private fun checkSide(c: Cursor, type: Int, exists: Boolean, expectedType: String, key: String) {
        if (!exists) check(c.getString(type) == "null" && c.getType(type + 1) == Cursor.FIELD_TYPE_NULL)
        else check(c.getString(type) == expectedType && c.getType(type + 1) == Cursor.FIELD_TYPE_STRING && c.getString(type + 1) == key) {
            "COMMAND_JOURNAL_KEY_CHANGED"
        }
    }
    private fun sequence(): Long = sql.query("SELECT seq FROM temp.sqlite_sequence WHERE name='count_command_journal_events' LIMIT 2").use {
        if (!it.moveToFirst()) 0L else {
            check(it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            it.getLong(0).also { value -> check(value >= 0 && !it.moveToNext()) }
        }
    }
    private fun scalar(query: String): Long = sql.query(query).use { cursor ->
        check(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER)
        cursor.getLong(0).also { check(!cursor.moveToNext()) }
    }
    private fun cell(side: String, key: String): String = "typeof($side.`$key`),CASE WHEN typeof($side.`$key`) IN ('text','integer') " +
        "AND length(CAST($side.`$key` AS BLOB))<=256 THEN CAST($side.`$key` AS TEXT) ELSE NULL END"

    companion object {
        const val ROOT = "count_command_recovery_roots"
        const val CONTROL = "count_trust_control"
        private val anchorColumns = listOf("old", "new").flatMap { side ->
            listOf("Storage", "Generation", "Dirty", "Anchor").flatMap { field -> listOf("${side}${field}Type", "${side}${field}Value") }
        }
        val KEYS = mapOf("count_command_intents" to "commandId", "count_command_status" to "commandId",
            "count_command_results" to "commandId", "count_command_nodes" to "digest", ROOT to "id", CONTROL to "id")
    }
}
