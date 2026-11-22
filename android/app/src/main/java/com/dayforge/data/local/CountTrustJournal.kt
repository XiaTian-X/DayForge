package com.dayforge.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Collections
import java.util.UUID

/** Native primary-connection OLD/NEW evidence. It never decides whether a mutation is legal. */
internal class CountTrustJournal(private val sql: SupportSQLiteDatabase) {
    data class Cell(val type: Int, val value: Any?)
    data class Change(val sequence: Long, val table: String, val operation: String,
        val old: List<Cell>?, val new: List<Cell>?)
    sealed interface Boundary
    private data class Issued(val owner: CountTrustJournal, val sequence: Long,
        val transaction: Any, val caller: Any?, val epoch: Long) : Boundary
    private val nonce = UUID.randomUUID().toString()
    private val width = COLUMNS.values.maxOf { it.size }
    private var dataVersion = -1L
    private var mainVersion = -1L
    private var installed = false

    fun install() {
        check(!installed && sql.inTransaction()) { "COUNT_TRUST_JOURNAL_INSTALL_CONTEXT" }
        check(COLUMNS.keys == CountTrustSchema.BUSINESS_TABLES) { "COUNT_TRUST_JOURNAL_COVERAGE" }
        check(scalar("PRAGMA recursive_triggers") == 1L) { "COUNT_TRUST_REPLACE_CAPTURE_DISABLED" }
        val actual = sql.query("SELECT name FROM main.sqlite_master WHERE type='table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val optionalStatistics = if (sql.version == CountCommandV20Schema.VERSION) {
            CountCommandPrimarySchema.requireStatisticsSchema(sql)
            ApprovedBusinessSchema.optionalStatistics
        } else emptySet()
        check(actual - optionalStatistics == COLUMNS.keys + CountCommandV20Schema.localTables(sql.version) +
            setOf("android_metadata", "room_master_table", "sqlite_sequence")) { "COUNT_TRUST_JOURNAL_COVERAGE" }
        COLUMNS.forEach { (table, fields) ->
            val found = sql.query("PRAGMA main.table_info(`$table`)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            check(found.size == fields.size && found.toSet() == fields.toSet()) { "COUNT_TRUST_JOURNAL_COLUMNS_CHANGED" }
        }
        sql.execSQL("CREATE TEMP TABLE count_trust_journal_identity(nonce TEXT NOT NULL)")
        sql.execSQL("INSERT INTO temp.count_trust_journal_identity VALUES(?)", arrayOf(nonce))
        sql.execSQL("CREATE TEMP TABLE count_trust_journal_events(seq INTEGER PRIMARY KEY AUTOINCREMENT,tableName TEXT NOT NULL,operation TEXT NOT NULL)")
        sql.execSQL("CREATE TEMP TABLE count_trust_journal_cells(seq INTEGER NOT NULL,side INTEGER NOT NULL,${(0 until width).joinToString(",") { "c$it" }},sourceTable TEXT NOT NULL,PRIMARY KEY(seq,side))")
        COLUMNS.forEach { (table, fields) ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                val sides = when (operation) { "INSERT" -> listOf("NEW"); "DELETE" -> listOf("OLD"); else -> listOf("OLD", "NEW") }
                val payload = sides.joinToString(" ") { side ->
                    val values = fields.map { "$side.`$it`" } + List(width - fields.size) { "NULL" } + "'$table'"
                    "INSERT INTO count_trust_journal_cells VALUES((SELECT max(seq) FROM count_trust_journal_events),${if (side == "OLD") 0 else 1},${values.joinToString(",")});"
                }
                sql.execSQL("CREATE TEMP TRIGGER `count_trust_journal_${table}_$operation` AFTER $operation ON main.`$table` BEGIN INSERT INTO count_trust_journal_events(tableName,operation) VALUES('$table','$operation'); $payload END")
            }
        }
        dataVersion = scalar("PRAGMA main.data_version")
        mainVersion = scalar("PRAGMA main.schema_version")
        installed = true
    }

    private fun checkIdentity() {
        check(installed && sql.inTransaction()) { "COUNT_TRUST_JOURNAL_TRANSACTION_REQUIRED" }
        check(sql.query("SELECT nonce FROM temp.count_trust_journal_identity").use {
            it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING && it.getString(0) == nonce && !it.moveToNext()
        }) { "COUNT_TRUST_CONNECTION_CHANGED" }
    }

    fun checkConnection() {
        checkIdentity()
        check(scalar("PRAGMA main.data_version") == dataVersion) { "COUNT_TRUST_EXTERNAL_CHANGE" }
        check(scalar("PRAGMA main.schema_version") == mainVersion) { "COUNT_TRUST_SCHEMA_CHANGED" }
        check(scalar("PRAGMA recursive_triggers") == 1L) { "COUNT_TRUST_REPLACE_CAPTURE_DISABLED" }
    }

    fun capture(transaction: Any, caller: Any?, epoch: Long): Boundary {
        checkConnection()
        return Issued(this, sequence(), transaction, caller, epoch)
    }

    /** A source-only hint, never the complete business journal or authority to sign a root.
     * Implementations remain private, bound to the original owner/transaction/Job/epoch.
     */
    sealed interface SourceWindow { val totalChanges: Long }
    private data class SourceHeader(val sequence: Long)
    private data class IssuedSourceWindow(val journal: CountTrustJournal, val boundary: Boundary,
        val endSequence: Long, override val totalChanges: Long, val headers: List<SourceHeader>) : SourceWindow
    private data class SourceMetadata(val end: Long, val count: Long, val sources: Long)

    private fun sourceBoundary(boundary: Boundary, transaction: Any, caller: Any?, epoch: Long): Issued {
        checkConnection()
        val issued = boundary as? Issued ?: error("COUNT_TRUST_FOREIGN_BOUNDARY")
        check(issued.owner === this && issued.transaction === transaction && issued.caller === caller &&
            issued.epoch == epoch) { "COUNT_TRUST_BOUNDARY_EXPIRED" }
        return issued
    }

    private fun sourceSequence(): Long = sql.query(
        "SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events' LIMIT 2"
    ).use { cursor ->
        if (!cursor.moveToFirst()) 0L else {
            check(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER) { "COUNT_TRUST_JOURNAL_INVALID" }
            cursor.getLong(0).also { check(it >= 0 && !cursor.moveToNext()) { "COUNT_TRUST_JOURNAL_INVALID" } }
        }
    }

    /** All header validity and sequence completeness are scalar; no OLD/NEW value or unbounded
     * header String enters a CursorWindow. The SQL identifiers below come only from fixed COLUMNS.
     */
    private fun sourceMetadata(issued: Issued): SourceMetadata {
        val end = sourceSequence()
        check(end >= issued.sequence) { "COUNT_TRUST_JOURNAL_GAP" }
        val validTables = COLUMNS.keys.joinToString(",") { "'$it'" }
        // Ordered CASE arms keep hostile large TEXT/BLOB headers behind their scalar
        // type/length checks even if the SQL engine reorders ordinary boolean terms.
        val invalid = "CASE WHEN typeof(seq)<>'integer' THEN 1 WHEN typeof(tableName)<>'text' THEN 1 " +
            "WHEN length(CAST(tableName AS BLOB))>128 THEN 1 WHEN tableName NOT IN ($validTables) THEN 1 " +
            "WHEN typeof(operation)<>'text' THEN 1 WHEN length(CAST(operation AS BLOB))>12 THEN 1 " +
            "WHEN operation NOT IN ('INSERT','UPDATE','DELETE') THEN 1 ELSE 0 END"
        val source = "CASE WHEN typeof(tableName)<>'text' THEN 0 WHEN length(CAST(tableName AS BLOB))>128 THEN 0 " +
            "WHEN tableName IN ('sync_outbox','timer_command_outbox') THEN 1 ELSE 0 END"
        return sql.query("SELECT COUNT(*),MIN(seq),MAX(seq),COALESCE(SUM($invalid),0),COALESCE(SUM($source),0) " +
            "FROM temp.count_trust_journal_events WHERE seq>?", arrayOf(issued.sequence)).use { cursor ->
            check(cursor.moveToFirst() && listOf(0, 3, 4).all { cursor.getType(it) == Cursor.FIELD_TYPE_INTEGER }) {
                "COUNT_TRUST_JOURNAL_INVALID"
            }
            val count = cursor.getLong(0); val sources = cursor.getLong(4)
            check(count >= 0 && sources in 0..count && cursor.getLong(3) == 0L) { "COUNT_TRUST_JOURNAL_INVALID" }
            check(count == Math.subtractExact(end, issued.sequence)) { "COUNT_TRUST_JOURNAL_GAP" }
            if (count == 0L) check(cursor.isNull(1) && cursor.isNull(2)) { "COUNT_TRUST_JOURNAL_GAP" }
            else check(cursor.getType(1) == Cursor.FIELD_TYPE_INTEGER && cursor.getType(2) == Cursor.FIELD_TYPE_INTEGER &&
                cursor.getLong(1) == Math.addExact(issued.sequence, 1L) && cursor.getLong(2) == end) { "COUNT_TRUST_JOURNAL_GAP" }
            check(!cursor.moveToNext() && sourceSequence() == end) { "COUNT_TRUST_JOURNAL_CHANGED" }
            SourceMetadata(end, count, sources)
        }
    }

    /** Complete journal-header proof for the fixed challenge inputs only. It never accepts
     * caller-supplied table names and never substitutes for the original OLD/NEW effects audit.
     */
    sealed interface ChallengeWindow { val totalChanges: Long }
    private data class IssuedChallengeWindow(override val totalChanges: Long) : ChallengeWindow

    fun unchangedChallengeWindow(boundary: Boundary, transaction: Any, caller: Any?, epoch: Long): ChallengeWindow? {
        val issued = sourceBoundary(boundary, transaction, caller, epoch)
        // Validate EVERY event's sequence/type/table/operation before selecting four tables.
        // Damaged evidence throws; a real write (even same-value or later reverted) is a miss.
        val metadata = sourceMetadata(issued)
        val touched = sql.query("SELECT COUNT(*) FROM temp.count_trust_journal_events WHERE seq>? AND tableName IN " +
            "('next_sync_state','next_challenge_state','next_challenge_rounds','next_challenge_births')",
            arrayOf(issued.sequence)).use { cursor ->
            check(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER) { "COUNT_TRUST_JOURNAL_INVALID" }
            cursor.getLong(0).also { count ->
                check(count in 0..metadata.count && !cursor.moveToNext()) { "COUNT_TRUST_JOURNAL_INVALID" }
            }
        }
        check(sourceSequence() == metadata.end) { "COUNT_TRUST_JOURNAL_CHANGED" }
        return if (touched == 0L) IssuedChallengeWindow(metadata.count) else null
    }

    fun hasPendingChanges(boundary: Boundary, transaction: Any, caller: Any?, epoch: Long): Boolean =
        sourceMetadata(sourceBoundary(boundary, transaction, caller, epoch)).count != 0L

    fun sourceWindow(boundary: Boundary, transaction: Any, caller: Any?, epoch: Long,
        expectedSources: Int): SourceWindow? {
        require(expectedSources >= 0)
        val issued = sourceBoundary(boundary, transaction, caller, epoch)
        val metadata = sourceMetadata(issued)
        // Optional shape, not a new product limit: original sources() still owns capacity/refusal.
        if (expectedSources > 10000 || metadata.sources != expectedSources.toLong()) return null
        val headers = sql.query("SELECT seq,tableName,operation FROM temp.count_trust_journal_events " +
            "WHERE seq>? AND tableName IN ('sync_outbox','timer_command_outbox') ORDER BY seq LIMIT ?",
            arrayOf<Any>(issued.sequence, expectedSources + 1)).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                check(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getType(1) == Cursor.FIELD_TYPE_STRING &&
                    cursor.getType(2) == Cursor.FIELD_TYPE_STRING) { "COUNT_TRUST_JOURNAL_INVALID" }
                // Header validity was checked above before materializing these fixed small values.
                if (cursor.getString(1) != "sync_outbox" || cursor.getString(2) != "INSERT") return null
                add(SourceHeader(cursor.getLong(0)))
            }
        } }
        check(headers.size == expectedSources && sourceSequence() == metadata.end) { "COUNT_TRUST_JOURNAL_CHANGED" }
        return IssuedSourceWindow(this, boundary, metadata.end, metadata.count, immutable(headers))
    }

    /** Compare only the independently expected source INSERT cells, entirely as bounded SQL
     * scalars. Never materialize a full journal cell row before the original source preflight.
     * Non-source domain cells remain covered by the ORIGINAL final pending()/domain audit.
     */
    fun verifySourceWindow(window: SourceWindow, expectedRows: List<List<Cell>>,
        transaction: Any, caller: Any?, epoch: Long): Boolean {
        val issued = window as? IssuedSourceWindow ?: error("COUNT_TRUST_FOREIGN_SOURCE_WINDOW")
        check(issued.journal === this) { "COUNT_TRUST_FOREIGN_SOURCE_WINDOW" }
        val boundary = sourceBoundary(issued.boundary, transaction, caller, epoch)
        val metadata = sourceMetadata(boundary)
        check(metadata.end >= issued.endSequence) { "COUNT_TRUST_JOURNAL_GAP" }
        if (metadata.end > issued.endSequence) return false // Same live transaction, newer writes: original fallback.
        check(metadata.count == issued.totalChanges && expectedRows.size == issued.headers.size) { "COUNT_TRUST_JOURNAL_CHANGED" }
        val count = COLUMNS.getValue("sync_outbox").size
        val length = (0 until count).joinToString("+") { "COALESCE(length(CAST(c$it AS BLOB)),0)" }
        val padding = (count until width).joinToString(" AND ") { "c$it IS NULL" }.ifEmpty { "1" }
        for ((header, expected) in issued.headers.zip(expectedRows)) {
            check(expected.size == count) { "COUNT_TRUST_SOURCE_EXPECTED_LAYOUT" }
            val lengthBindings = arrayListOf<Any?>()
            val bindings = arrayListOf<Any>()
            val equal = expected.mapIndexed { index, cell -> when (cell.type) {
                Cursor.FIELD_TYPE_NULL -> { check(cell.value == null); lengthBindings += null; "c$index IS NULL" }
                Cursor.FIELD_TYPE_INTEGER -> {
                    val value = cell.value as Long
                    lengthBindings += value
                    bindings += value; "typeof(c$index)='integer' AND c$index IS ?"
                }
                Cursor.FIELD_TYPE_STRING -> {
                    val value = cell.value as String
                    lengthBindings += value
                    bindings += value; "typeof(c$index)='text' AND CAST(c$index AS BLOB)=CAST(? AS BLOB)"
                }
                else -> error("COUNT_TRUST_SOURCE_EXPECTED_TYPE")
            } }.joinToString(" AND ")
            // Calculate expected bytes from the independent bound values in the same SQLite
            // encoding as the actual row. Do not assume all valid databases are UTF-8.
            val expectedLength = expected.joinToString("+") { "COALESCE(length(CAST(? AS BLOB)),0)" }
            lengthBindings += header.sequence
            // Exact total bytes are a consistency witness, not a new user size limit.
            // The original main-rowHash preflight has already run. Check bytes BEFORE equality.
            sql.query("SELECT COUNT(*),COALESCE(SUM(CASE WHEN typeof(side)<>'integer' THEN 1 WHEN side<>1 THEN 1 " +
                "WHEN typeof(sourceTable)<>'text' THEN 1 WHEN length(CAST(sourceTable AS BLOB))>22 THEN 1 " +
                "WHEN sourceTable<>'sync_outbox' THEN 1 WHEN $padding THEN 0 ELSE 1 END),0)," +
                "COALESCE(MAX($length),0),$expectedLength FROM temp.count_trust_journal_cells WHERE seq=?", lengthBindings.toTypedArray()).use { cursor ->
                check(cursor.moveToFirst() && (0..3).all { cursor.getType(it) == Cursor.FIELD_TYPE_INTEGER } &&
                    cursor.getLong(0) == 1L && cursor.getLong(1) == 0L) { "COUNT_TRUST_JOURNAL_INCOMPLETE" }
                check(cursor.getLong(2) == cursor.getLong(3)) { "COUNT_TRUST_SOURCE_JOURNAL_CHANGED" }
                check(!cursor.moveToNext()) { "COUNT_TRUST_JOURNAL_INVALID" }
            }
            bindings += header.sequence
            sql.query("SELECT CASE WHEN $equal THEN 1 ELSE 0 END FROM temp.count_trust_journal_cells WHERE seq=? AND side=1",
                bindings.toTypedArray()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                    cursor.getLong(0) == 1L && !cursor.moveToNext()) { "COUNT_TRUST_SOURCE_JOURNAL_CHANGED" }
            }
        }
        val finalEnd = sourceSequence()
        check(finalEnd >= issued.endSequence) { "COUNT_TRUST_JOURNAL_GAP" }
        return finalEnd == issued.endSequence
    }

    /** Fixed COUNT-only read projection. These values carry no write/signing capability. */
    data class CountAddEffect(val table: String, val old: List<Cell>?, val new: List<Cell>)

    /** Complete actual evidence for the closed four/six-effect COUNT transaction. Unexpected
     * effects return null to its caller, which MUST reject the selected plan and roll back.
     * Structural corruption/lifetime failures throw. No arbitrary journal payload is loaded:
     * each returned native cell is projected only if its entire row exactly equals expected.
     */
    fun readCountAddEffects(boundary: Boundary, expected: List<CountAddEffect>,
        transaction: Any, caller: Any?, epoch: Long): List<Change>? {
        require(expected.size == 4 || expected.size == 6)
        val allowed = setOf("completions", "sync_outbox", "habits", "next_request_origins", "count_days")
        require(expected.all { it.table in allowed && it.new.size == COLUMNS.getValue(it.table).size &&
            (it.old == null || it.old.size == it.new.size) })
        val issued = sourceBoundary(boundary, transaction, caller, epoch)
        val metadata = sourceMetadata(issued)
        if (metadata.count != expected.size.toLong()) return null
        val headers = sql.query("SELECT CASE WHEN typeof(seq)='integer' THEN seq ELSE NULL END," +
            "CASE WHEN typeof(tableName)='text' AND length(CAST(tableName AS BLOB))<=128 THEN tableName ELSE NULL END," +
            "CASE WHEN typeof(operation)='text' AND length(CAST(operation AS BLOB))<=12 THEN operation ELSE NULL END " +
            "FROM temp.count_trust_journal_events WHERE seq>? ORDER BY seq LIMIT ?",
            arrayOf<Any>(issued.sequence, expected.size + 1)).use { c -> buildList {
            while (c.moveToNext()) {
                check(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getType(1) == Cursor.FIELD_TYPE_STRING &&
                    c.getType(2) == Cursor.FIELD_TYPE_STRING) { "COUNT_TRUST_JOURNAL_INVALID" }
                add(Triple(c.getLong(0), c.getString(1), c.getString(2)))
            }
        } }
        check(headers.size == expected.size && sourceSequence() == metadata.end) { "COUNT_TRUST_JOURNAL_CHANGED" }
        headers.forEachIndexed { index, header ->
            check(header.first == Math.addExact(issued.sequence, index + 1L)) { "COUNT_TRUST_JOURNAL_GAP" }
        }
        val pair = if (expected.size == 6) 1 else 0
        require(expected[pair].table == "completions" && expected[pair + 1].table == "sync_outbox" &&
            expected[pair].old == null && expected[pair + 1].old == null)
        fun shape(header: Triple<Long, String, String>, effect: CountAddEffect) =
            header.second == effect.table && header.third == if (effect.old == null) "INSERT" else "UPDATE"
        val selected = when {
            headers.indices.all { shape(headers[it], expected[it]) } -> expected
            headers.indices.all { index -> shape(headers[index], expected[when (index) {
                pair -> pair + 1; pair + 1 -> pair; else -> index
            }]) } -> expected.toMutableList().also { it[pair] = expected[pair + 1]; it[pair + 1] = expected[pair] }
            else -> return null
        }
        val result = ArrayList<Change>(expected.size)
        for ((header, effect) in headers.zip(selected)) {
            val sides = if (effect.old == null) mapOf(1 to effect.new) else mapOf(0 to effect.old, 1 to effect.new)
            val count = COLUMNS.getValue(effect.table).size
            val padding = (count until width).joinToString(" AND ") { "c$it IS NULL" }.ifEmpty { "1" }
            val sideValues = sides.keys.joinToString(",")
            val invalid = "CASE WHEN typeof(side)<>'integer' THEN 1 WHEN side NOT IN ($sideValues) THEN 1 " +
                "WHEN typeof(sourceTable)<>'text' THEN 1 WHEN length(CAST(sourceTable AS BLOB))>128 THEN 1 " +
                "WHEN sourceTable<>'${effect.table}' THEN 1 WHEN $padding THEN 0 ELSE 1 END"
            sql.query("SELECT COUNT(*),COALESCE(SUM($invalid),0) FROM temp.count_trust_journal_cells WHERE seq=?",
                arrayOf(header.first)).use { c ->
                check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getType(1) == Cursor.FIELD_TYPE_INTEGER &&
                    c.getLong(0) == sides.size.toLong() && c.getLong(1) == 0L && !c.moveToNext()) {
                    "COUNT_TRUST_JOURNAL_INCOMPLETE"
                }
            }
            val actual = linkedMapOf<Int, List<Cell>>()
            for ((side, cells) in sides) {
                val expectedCells = requireNotNull(cells)
                val binds = arrayListOf<Any?>()
                val actualLength = (0 until count).joinToString("+") { "COALESCE(length(CAST(c$it AS BLOB)),0)" }
                val expectedLength = expectedCells.joinToString("+") { cell ->
                    binds += cell.value
                    "COALESCE(length(CAST(? AS BLOB)),0)"
                }
                val equals = expectedCells.mapIndexed { index, cell -> when (cell.type) {
                    Cursor.FIELD_TYPE_NULL -> { check(cell.value == null); "c$index IS NULL" }
                    Cursor.FIELD_TYPE_INTEGER -> {
                        binds += cell.value as Long; "typeof(c$index)='integer' AND c$index IS ?"
                    }
                    Cursor.FIELD_TYPE_STRING -> {
                        binds += cell.value as String
                        "typeof(c$index)='text' AND CAST(c$index AS BLOB)=CAST(? AS BLOB)"
                    }
                    else -> error("COUNT_ADD_EXPECTED_CELL_TYPE")
                } }.joinToString(" AND ")
                val metadataMatch = "typeof(side)='integer' AND side=$side AND typeof(sourceTable)='text' " +
                    "AND length(CAST(sourceTable AS BLOB))<=128 AND sourceTable='${effect.table}' AND $padding"
                // The CASE is in the SAME statement as native materialization. Even a fault
                // injected after a previous scalar query cannot push a huge wrong value into
                // CursorWindow. Parameterized CAST compares the database's actual encoding.
                val projected = (0 until count).joinToString(",") { "CASE WHEN matched=1 THEN c$it ELSE NULL END" }
                binds += header.first; binds += side
                val row = sql.query("WITH checked AS (SELECT *,CASE WHEN $metadataMatch THEN CASE WHEN $actualLength=$expectedLength " +
                    "THEN CASE WHEN $equals THEN 1 ELSE 0 END ELSE 0 END ELSE 0 END AS matched " +
                    "FROM temp.count_trust_journal_cells WHERE seq=? AND side=?) SELECT matched,$projected FROM checked",
                    binds.toTypedArray()).use { c ->
                    check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER) { "COUNT_TRUST_JOURNAL_CHANGED" }
                    if (c.getLong(0) != 1L) return null
                    val value = immutable((1..count).map { cell(c, it) })
                    check(!c.moveToNext()) { "COUNT_TRUST_JOURNAL_CHANGED" }
                    value
                }
                actual[side] = row
                check(sourceSequence() == metadata.end) { "COUNT_TRUST_JOURNAL_CHANGED" }
            }
            result += Change(header.first, header.second, header.third, actual[0], actual[1])
        }
        check(sourceSequence() == metadata.end) { "COUNT_TRUST_JOURNAL_CHANGED" }
        return immutable(result)
    }

    fun pending(boundary: Boundary, transaction: Any, caller: Any?, epoch: Long): List<Change> {
        checkConnection()
        val issued = boundary as? Issued ?: error("COUNT_TRUST_FOREIGN_BOUNDARY")
        check(issued.owner === this && issued.transaction === transaction && issued.caller === caller &&
            issued.epoch == epoch) { "COUNT_TRUST_BOUNDARY_EXPIRED" }
        val headers = sql.query("SELECT seq,tableName,operation FROM temp.count_trust_journal_events WHERE seq>? ORDER BY seq",
            arrayOf(issued.sequence)).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                check(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getType(1) == Cursor.FIELD_TYPE_STRING &&
                    cursor.getType(2) == Cursor.FIELD_TYPE_STRING) { "COUNT_TRUST_JOURNAL_INVALID" }
                add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
            }
        } }
        val result = headers.mapIndexed { index, (seq, table, operation) ->
            check(seq == issued.sequence + index + 1 && table in COLUMNS &&
                operation in setOf("INSERT", "UPDATE", "DELETE")) { "COUNT_TRUST_JOURNAL_GAP" }
            val count = COLUMNS.getValue(table).size
            val sides = sql.query("SELECT * FROM temp.count_trust_journal_cells WHERE seq=? ORDER BY side", arrayOf(seq)).use { cursor ->
                check(cursor.columnCount == 3 + width) { "COUNT_TRUST_JOURNAL_LAYOUT" }
                buildMap<Int, List<Cell>> {
                    while (cursor.moveToNext()) {
                        check(cursor.getType(1) == Cursor.FIELD_TYPE_INTEGER)
                        val side = cursor.getInt(1)
                        check(side !in keys && cursor.getType(2 + width) == Cursor.FIELD_TYPE_STRING &&
                            cursor.getString(2 + width) == table) { "COUNT_TRUST_JOURNAL_SOURCE" }
                        for (column in 2 + count until 2 + width) check(cursor.isNull(column)) { "COUNT_TRUST_JOURNAL_PADDING" }
                        put(side, immutable((2 until 2 + count).map { column -> cell(cursor, column) }))
                    }
                }
            }
            check(sides.keys == when (operation) { "INSERT" -> setOf(1); "DELETE" -> setOf(0); else -> setOf(0, 1) }) {
                "COUNT_TRUST_JOURNAL_INCOMPLETE"
            }
            Change(seq, table, operation, sides[0], sides[1])
        }
        check((result.lastOrNull()?.sequence ?: issued.sequence) == sequence()) { "COUNT_TRUST_JOURNAL_GAP" }
        return immutable(result)
    }

    /** Exact authorized whole-dataset replacement only. Ordinary optional retirement must
     * never call this or reset continuity. Transactional DROP restores this same old object
     * on known ROLLBACK; the owning factory forgets it only after actual known COMMIT.
     */
    fun dropForAuthorizedReset() {
        checkConnection()
        val definitions = linkedMapOf(
            "count_trust_journal_identity" to "CREATE TEMP TABLE count_trust_journal_identity(nonce TEXT NOT NULL)",
            "count_trust_journal_events" to "CREATE TEMP TABLE count_trust_journal_events(seq INTEGER PRIMARY KEY AUTOINCREMENT,tableName TEXT NOT NULL,operation TEXT NOT NULL)",
            "count_trust_journal_cells" to "CREATE TEMP TABLE count_trust_journal_cells(seq INTEGER NOT NULL,side INTEGER NOT NULL,${(0 until width).joinToString(",") { "c$it" }},sourceTable TEXT NOT NULL,PRIMARY KEY(seq,side))"
        )
        COLUMNS.forEach { (table, fields) ->
            for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                val sides = when (operation) { "INSERT" -> listOf("NEW"); "DELETE" -> listOf("OLD"); else -> listOf("OLD", "NEW") }
                val payload = sides.joinToString(" ") { side ->
                    val values = fields.map { "$side.`$it`" } + List(width - fields.size) { "NULL" } + "'$table'"
                    "INSERT INTO count_trust_journal_cells VALUES((SELECT max(seq) FROM count_trust_journal_events),${if (side == "OLD") 0 else 1},${values.joinToString(",")});"
                }
                val name = "count_trust_journal_${table}_$operation"
                definitions[name] = "CREATE TEMP TRIGGER `$name` AFTER $operation ON main.`$table` BEGIN INSERT INTO count_trust_journal_events(tableName,operation) VALUES('$table','$operation'); $payload END"
            }
        }
        val maximumDefinitionBytes = definitions.values.maxOf { it.toByteArray(Charsets.UTF_8).size.toLong() } * 2L
        val seen = hashSetOf<String>()
        sql.query("SELECT CASE WHEN length(CAST(name AS BLOB))<=512 THEN name END," +
            "CASE WHEN length(CAST(sql AS BLOB))<=? THEN sql END FROM temp.sqlite_master " +
            "WHERE substr(name,1,20)='count_trust_journal_' ORDER BY name", arrayOf(maximumDefinitionBytes)).use { c ->
            while (c.moveToNext()) {
                check(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getType(1) == Cursor.FIELD_TYPE_STRING)
                val name = c.getString(0)
                check(seen.add(name) && c.getString(1) == definitions[name]?.replaceFirst("CREATE TEMP ", "CREATE ")) {
                    "COUNT_TRUST_RESET_JOURNAL_SCHEMA_CHANGED"
                }
            }
        }
        check(seen == definitions.keys) { "COUNT_TRUST_RESET_JOURNAL_SCHEMA_CHANGED" }
        definitions.keys.drop(3).asReversed().forEach { sql.execSQL("DROP TRIGGER temp.`$it`") }
        definitions.keys.take(3).asReversed().forEach { sql.execSQL("DROP TABLE temp.`$it`") }
    }

    /** Called privately at the next outer BEGIN; no cross-transaction boundary survives. */
    fun trim() {
        checkConnection()
        clearRows()
    }

    /** Cleanup only after qualification is permanently retired. The same private connection
     * and nonce are mandatory, but a recovery may legitimately replace the main schema.
     * Never resets sqlite_sequence or provides evidence for a new boundary.
     */
    fun trimRetired() {
        checkIdentity()
        clearRows()
    }

    private fun clearRows() {
        sql.execSQL("DELETE FROM temp.count_trust_journal_cells")
        sql.execSQL("DELETE FROM temp.count_trust_journal_events")
    }

    private fun sequence(): Long = scalar("SELECT COALESCE((SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events'),0)")
    private fun scalar(statement: String): Long = sql.query(statement).use {
        check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
        it.getLong(0).also { _ -> check(!it.moveToNext()) }
    }
    private fun cell(cursor: Cursor, column: Int): Cell {
        val type = cursor.getType(column)
        return Cell(type, when (type) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
            Cursor.FIELD_TYPE_FLOAT -> java.lang.Double.doubleToRawLongBits(cursor.getDouble(column))
            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
            Cursor.FIELD_TYPE_BLOB -> immutable(cursor.getBlob(column).toList())
            else -> error("COUNT_TRUST_JOURNAL_CELL_TYPE")
        })
    }
    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

    companion object {
        internal val COLUMNS: Map<String, List<String>> = Collections.unmodifiableMap(mapOf(
        "habits" to listOf("id", "name", "description", "habitType", "iconResId", "colorHex", "schedule", "targetValue", "isCountdown", "isActive", "uuid", "parentHabitId", "targetCycles", "failMode", "goalSuccess", "activityRate", "activityRateUpdatedAt", "bestTime", "createdAt", "updatedAt", "completionPolicy", "oneTimeConfirmedVersion", "oneTimeConfirmedHeadEventUuid", "oneTimeConfirmedCompletionEventUuid", "appearance", "planMetadata"),
        "completions" to listOf("id", "habitId", "date", "value", "actualCompletedAt", "uuid", "habitUuid", "createdAt", "recordedTimezone", "recordedLocalDate", "timeMetadataSource", "oneTimeAction", "oneTimeExpectedVersion", "oneTimeExpectedHeadEventUuid", "oneTimeRevertsEventUuid"),
        "count_days" to listOf("habitId", "habitUuid", "localDate", "targetValue", "isCountdown", "firstEventUuid", "originRequestId", "originHash", "planPredecessorId", "planPredecessorOriginHash", "planPredecessorDependencyHash", "planQueueWatermark", "capturedDeviceId", "originKind"),
        "timelogs" to listOf("id", "habitId", "startTime", "endTime", "durationSeconds", "isPaused", "pausedAt", "accumulatedPauseMillis", "timerNextCommandSequence", "timerControlGeneration", "timerLastCommandAt", "timerTimezone", "timerActiveElapsedMillis", "timerElapsedRealtimeAnchor", "timerBootCount", "date", "uuid", "createdAt", "updatedAt"),
        "metrics" to listOf("id", "name", "description", "unit", "decimalPlaces", "aggregationType", "targetDirection", "targetValue", "targetValueUpper", "iconResId", "colorHex", "isActive", "uuid", "createdAt", "updatedAt", "appearance"),
        "metric_logs" to listOf("id", "metricId", "date", "value", "unit", "note", "uuid", "createdAt", "updatedAt", "recordedTimezone", "recordedLocalDate", "timeMetadataSource"),
        "habit_metric_links" to listOf("id", "habitId", "habitUuid", "metricId", "metricUuid", "coefficient", "showInHabitDetail", "promptOnComplete", "isActive", "uuid", "createdAt", "updatedAt"),
        "sync_outbox" to listOf("id", "operationId", "recordType", "entityUuid", "wireEntityUuid", "action", "referenceUuid", "payloadJson", "baseRevision", "basePayloadJson", "attemptedAt", "attemptCount", "lastError", "errorCode", "deadLetteredAt", "createdAt"),
        "sync_entity_state" to listOf("entityType", "entityUuid", "revision", "deleted", "payloadJson", "payloadHash", "updatedAt"),
        "sync_conflicts" to listOf("id", "operationId", "recordType", "localEntityUuid", "wireEntityUuid", "entityType", "action", "referenceUuid", "baseRevision", "serverRevision", "basePayloadJson", "localPayloadJson", "serverPayloadJson", "conflictingFieldsJson", "conflictKind", "errorCode", "message", "status", "resolution", "createdAt", "resolvedAt"),
        "sync_control" to listOf("id", "suppressOutbox"),
        "timer_command_outbox" to listOf("id", "commandId", "sessionUuid", "sequence", "commandType", "occurredAt", "expectedControlGeneration", "expectedRevision", "activityUuid", "timezone", "activeElapsedMillis", "attemptCount", "lastError", "errorCode", "deadLetteredAt", "createdAt"),
        "timer_segments" to listOf("id", "sessionUuid", "sequence", "startedAt", "endedAt"),
        "timelog_day_allocations" to listOf("sessionUuid", "habitId", "localDate", "localDateEpoch", "timezone", "durationMillis"),
        "local_fact_submissions" to listOf("operationId", "entityType", "entityUuid", "referenceUuid", "payloadJson"),
        "completion_metric_prompts" to listOf("eventUuid", "activityUuid", "state", "revision", "entriesJson", "recordedAtMillis", "timezone"),
        "one_time_transmissions" to listOf("operationId", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "operationJson", "rejectionJson"),
        "next_recovery_state" to listOf("accountId", "serverInstanceId", "syncEpoch", "deviceId", "generation", "phase", "minimumCursor", "candidateCursor", "snapshotHash", "id"),
        "next_request_origins" to listOf("kind", "requestId", "queueId", "protocol", "accountId", "serverInstanceId", "syncEpoch", "sourceHash", "intentJson"),
        "next_transmissions" to listOf("kind", "requestId", "queueId", "protocol", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "wireHash", "wireBytes"),
        "next_acceptances" to listOf("kind", "requestId", "originHash", "transmissionHash", "resultHash", "resultJson"),
        "next_structural_dependencies" to listOf("operationId", "logicalOrder", "originHash", "predecessorId", "predecessorOriginHash", "predecessorDependencyHash", "capturedDeviceId", "kind"),
        "next_structural_supersessions" to listOf("originalId", "replacementId", "originalQueueId", "replacementQueueId", "originalOriginHash", "originalDependencyHash", "originalSourceHash", "sourceSnapshotJson", "sourceSnapshotHash", "predecessorAcceptedRequestId", "predecessorAcceptanceHash", "replacementOriginHash", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "kind"),
        "next_sync_state" to listOf("accountId", "serverInstanceId", "syncEpoch", "deviceId", "generation", "cursor", "bootstrapHash", "batchHash", "id", "challengeContract"),
        "next_rejections" to listOf("kind", "requestId", "originHash", "transmissionHash", "resultHash", "resultJson"),
        "next_challenge_rounds" to listOf("roundUuid", "activityUuid", "generation", "accountId", "serverInstanceId", "syncEpoch", "sourceDeviceUuid", "operationUuid", "recordJson", "recordHash"),
        "next_challenge_births" to listOf("entityType", "entityUuid", "roundUuid"),
        "next_challenge_state" to listOf("accountId", "serverInstanceId", "syncEpoch", "deviceId", "generation", "cursor", "batchHash", "metadataHash", "id"),
        "next_restart_materializations" to listOf("operationId", "kind", "originHash", "frontierHash", "operationJson", "operationHash"),
        "next_restart_plan_proofs" to listOf("operationId", "originHash", "transmissionHash", "revision", "logSequence", "deviceId", "planJson", "planHash"),
        "next_config_imports" to listOf("importId", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "archiveHash", "identitiesHash", "mode", "state", "receiptHash"),
        "next_config_import_payloads" to listOf("importId", "payloadKind", "part", "bytes"),
        "next_recovery_audits" to listOf("recoveryId", "accountId", "serverInstanceId", "syncEpoch", "deviceId", "rootKind", "rootRequestId", "graphHash", "checkpointHash", "manifestHash", "selectedCount"),
        "next_abandoned_requests" to listOf("kind", "requestId", "recoveryId", "disposition", "sourceHash", "sourceSnapshotHash", "originHash", "transmissionHash", "rejectionHash"),
        "next_recovery_audit_payloads" to listOf("recoveryId", "payloadKey", "part", "bytes"),
        "next_replica_fence" to listOf("recoveryId", "accountId", "serverInstanceId", "oldEpoch", "newEpoch", "authenticationGeneration", "deviceId", "capabilityRevision", "sourceHash", "mode", "id")
    ).mapValues { Collections.unmodifiableList(ArrayList(it.value)) })
    }
}
