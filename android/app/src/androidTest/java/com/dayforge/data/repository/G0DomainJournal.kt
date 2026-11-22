package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

/** Private primary-connection journal. Native OLD/NEW cells retain SQLite types; no JSON/quote
 * conversion or net-state comparison. Every main-domain table is covered, including cascades.
 * A journal is evidence of mutations, never permission to accept them.
 */
internal class G0DomainJournal(private val sql: SupportSQLiteDatabase, private val compact: Boolean = true,
    countCertificateScope:Boolean = false,countBlockScope:Boolean = false,countRootScope:Boolean = false) {
    data class Cell(val type: Int, val value: Any?)
    data class Change(val sequence: Long, val table: String, val operation: String,
        val old: List<Cell>?, val new: List<Cell>?)
    private var nonce = ""
    private var dataVersion = -1L
    private var mainVersion = -1L
    private var installed = false
    private var consumed = 0L
    private val domainColumns = G0DomainSqlCatalog.columns +
        (if(countCertificateScope)G0CountCertificateSql.tables else emptyMap()) +
        (if(countBlockScope)G0CountBlockSql.tables else emptyMap()) +
        (if(countRootScope)G0CountRootSql.tables else emptyMap())

    private lateinit var columns: Map<String, List<String>>
    private var width = 0
    private fun <T> timed(name:String, body:()->T):T = G0PhaseTrace.current.get().let { trace -> if(trace==null) body() else trace.call("journal_$name",body) }
    private fun scalar(text: String): Long = sql.query(text).use { check(it.moveToFirst()); it.getLong(0) }
    private fun schema(): List<List<String?>> = trustedSchema(sql)
    suspend fun install(expected: List<List<String?>>) {
        check(!installed && sql.inTransaction())
        check(timed("schema") { schema() } == expected) { "G0_DOMAIN_SCHEMA_NOT_TRUSTED" }
        check(scalar("PRAGMA recursive_triggers") == 1L) { "G0_DOMAIN_REPLACE_CAPTURE_REQUIRED" }
        val witnessColumns = G0ProductionWitnessColumns.read(sql, expected)
        check(domainColumns.keys.intersect(witnessColumns.keys).isEmpty()) { "G0_DOMAIN_WITNESS_OVERLAP" }
        columns = domainColumns + witnessColumns
        width = columns.values.maxOf { it.size }
        val actualTables = timed("tables") { sql.query("SELECT name FROM main.sqlite_master WHERE type='table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        }
        check(actualTables == columns.keys + setOf("android_metadata", "room_master_table", "sqlite_sequence")) { "G0_DOMAIN_TABLE_COVERAGE" }
        sql.execSQL("CREATE TEMP TABLE g0_domain_identity(nonce TEXT NOT NULL)")
        nonce = UUID.randomUUID().toString()
        sql.execSQL("INSERT INTO temp.g0_domain_identity VALUES(?)", arrayOf(nonce))
        sql.execSQL("CREATE TEMP TABLE g0_domain_events(seq INTEGER PRIMARY KEY AUTOINCREMENT,tableName TEXT NOT NULL,operation TEXT NOT NULL)")
        if(compact) timed("payload_ddl") { sql.execSQL("CREATE TEMP TABLE g0_domain_cells(seq INTEGER NOT NULL,side INTEGER NOT NULL,${(0 until width).joinToString(",") { "c$it" }},sourceTable TEXT NOT NULL,PRIMARY KEY(seq,side))") }
        for ((table, fields) in columns) {
            currentCoroutineContext().ensureActive()
            val actual = timed("columns") { sql.query("PRAGMA main.table_info(`$table`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } } }
            check(actual == fields) { "G0_DOMAIN_COLUMNS_CHANGED" }
            // No affinity on payload columns: TEXT, REAL, INTEGER, BLOB and NULL stay distinct.
            val payloadTable=if(compact) "g0_domain_cells" else "g0_domain_$table"
            if(!compact) timed("payload_ddl") { sql.execSQL("CREATE TEMP TABLE `$payloadTable`(seq INTEGER NOT NULL,side INTEGER NOT NULL,${fields.indices.joinToString(",") { "c$it" }},PRIMARY KEY(seq,side))") }
            for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                val trigger = timed("sql_build") {
                val sides = when (operation) { "INSERT" -> listOf("NEW"); "DELETE" -> listOf("OLD"); else -> listOf("OLD", "NEW") }
                val payload = sides.joinToString(" ") { side ->
                    val values=fields.map { "$side.`$it`" } + if(compact) List(width-fields.size) { "NULL" } + "'$table'" else emptyList()
                    "INSERT INTO `$payloadTable` VALUES((SELECT max(seq) FROM g0_domain_events),${if (side == "OLD") 0 else 1},${values.joinToString(",")});"
                }
                "CREATE TEMP TRIGGER `g0_domain_${table}_$operation` AFTER $operation ON main.`$table` BEGIN INSERT INTO g0_domain_events(tableName,operation) VALUES('$table','$operation'); $payload END"
                }
                timed("trigger_ddl") { sql.execSQL(trigger) }
            }
        }
        dataVersion = scalar("PRAGMA main.data_version")
        mainVersion = scalar("PRAGMA main.schema_version")
        installed = true
    }
    fun checkConnection() {
        check(installed && sql.inTransaction())
        check(sql.query("SELECT nonce FROM temp.g0_domain_identity").use { it.moveToFirst() && it.getString(0) == nonce && !it.moveToNext() }) { "G0_DOMAIN_CONNECTION_CHANGED" }
        check(scalar("PRAGMA main.data_version") == dataVersion) { "G0_DOMAIN_EXTERNAL_CHANGE" }
        check(scalar("PRAGMA main.schema_version") == mainVersion) { "G0_DOMAIN_SCHEMA_CHANGED" }
        check(scalar("PRAGMA recursive_triggers") == 1L) { "G0_DOMAIN_CAPTURE_DISABLED" }
    }
    /** Synchronous empty-change witness for an adopted read-only Room transaction boundary.
     * Same pending header predicate as pending(); no row payload can be admitted here.
     */
    fun requireNoPendingChanges() {
        checkConnection()
        sql.query("SELECT seq FROM temp.g0_domain_events WHERE seq>? LIMIT 1", arrayOf(consumed)).use {
            check(!it.moveToFirst()) { "G0_DOMAIN_UNADMITTED_CHANGE" }
        }
    }
    suspend fun pending(): List<Change> {
        checkConnection()
        val headers = sql.query("SELECT seq,tableName,operation FROM temp.g0_domain_events WHERE seq>? ORDER BY seq", arrayOf(consumed)).use { c ->
            buildList { while (c.moveToNext()) { currentCoroutineContext().ensureActive(); add(Triple(c.getLong(0), c.getString(1), c.getString(2))) } }
        }
        return headers.mapIndexed { index, (seq, table, operation) ->
            currentCoroutineContext().ensureActive()
            check(seq == consumed + index + 1 && table in columns && operation in setOf("INSERT", "UPDATE", "DELETE")) { "G0_DOMAIN_JOURNAL_GAP" }
            val payloadTable=if(compact) "g0_domain_cells" else "g0_domain_$table"
            val count=columns.getValue(table).size
            val sides = sql.query("SELECT * FROM temp.`$payloadTable` WHERE seq=? ORDER BY side", arrayOf(seq)).use { c ->
                check(c.columnCount==2+(if(compact) width+1 else count)) { "G0_DOMAIN_PAYLOAD_LAYOUT" }
                buildMap { while (c.moveToNext()) {
                    val side = c.getInt(1)
                    check(side !in keys)
                    if(compact) check(c.getType(2+width)==Cursor.FIELD_TYPE_STRING && c.getString(2+width)==table) { "G0_DOMAIN_PAYLOAD_SOURCE" }
                    for(i in 2+count until 2+(if(compact) width else count)) check(c.isNull(i)) { "G0_DOMAIN_PAYLOAD_PADDING" }
                    put(side, (2 until 2+count).map { i -> Cell(c.getType(i), when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> java.lang.Double.doubleToRawLongBits(c.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                        Cursor.FIELD_TYPE_STRING -> c.getString(i)
                        else -> error("G0_DOMAIN_CELL_TYPE")
                    }) })
                } }
            }
            check(sides.keys == when (operation) { "INSERT" -> setOf(1); "DELETE" -> setOf(0); else -> setOf(0, 1) }) { "G0_DOMAIN_JOURNAL_INCOMPLETE" }
            Change(seq, table, operation, sides[0], sides[1])
        }
    }
    /** Only after the outer COMMIT is confirmed. No caller-supplied sequence is accepted. */
    fun committed(changes: List<Change>) {
        check(!sql.inTransaction())
        changes.lastOrNull()?.let { consumed = it.sequence }
    }
    companion object {
        fun trustedSchema(sql: SupportSQLiteDatabase): List<List<String?>> = listOf("main", "temp").flatMap { namespace ->
            sql.query("SELECT type,name,tbl_name,sql FROM $namespace.sqlite_master ORDER BY type,name").use { c ->
                buildList { while (c.moveToNext()) add(listOf(namespace) + (0..3).map { if (c.isNull(it)) null else c.getString(it) }) }
            }
        }
    }
}
