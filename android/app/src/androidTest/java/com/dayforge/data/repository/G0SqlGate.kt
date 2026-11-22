package com.dayforge.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import androidx.room.withTransaction
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.HabitDatabase
import java.util.Locale
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Isolated G0 adapter. The trusted owner retains this capability; SQL clients receive only
 * wrapped interfaces. No runtime SQL registration, SQL prefix parser, reflection or delegate API.
 * Boot is one-way: after sealing, opening again cannot restore unrestricted initialization.
 */
internal class G0SqlGate(
    private val delegateFactory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) {
    enum class Phase { READ, INTENT, BUSINESS, FINAL }
    class Denied : IllegalStateException("G0_SQL_DENIED")
    private class Lease(val phase: Phase, val generation: Long, val thread: Thread) {
        var active = true
        var writesClosed = false
    }
    private val lease = ThreadLocal<Lease?>()
    @Volatile private var sealed = false
    @Volatile private var invalidated = false
    @Volatile private var generation = 0L
    private var sealedGeneration = -1L
    private var wrapped: Database? = null
    private val reads = mapOf(
        "SELECT digest,total,entries FROM g0_nodes WHERE level=? AND position=?" to "II",
        "SELECT seq,owner,value FROM g0_facts WHERE seq>=? AND seq<? ORDER BY seq" to "II",
        "SELECT coalesce(max(id),0) FROM g0_changes" to "",
        "SELECT id,entry FROM g0_changes WHERE id>? ORDER BY id" to "I",
        "SELECT nonce FROM temp.g0_proof_nonce" to "",
        "PRAGMA main.data_version" to "", "PRAGMA main.schema_version" to "", "PRAGMA temp.schema_version" to "",
        "SELECT count(*) FROM g0_intents WHERE command=?" to "S",
        "SELECT owner FROM g0_intents WHERE command=?" to "S",
        "SELECT count(*) FROM g0_results WHERE command=?" to "S",
        "SELECT count(*) FROM g0_outbox WHERE command=? AND seq=?" to "SI",
        "SELECT count(*) FROM g0_facts" to "", "SELECT count(*) FROM g0_nodes" to "",
        "SELECT count(*) FROM g0_outbox" to "", "SELECT count(*) FROM g0_results" to "",
        "SELECT name,sql FROM sqlite_master WHERE name IN ('g0_no_update','g0_capture_facts_UPDATE') ORDER BY name" to ""
    )
    private val writes = mapOf(
        "INSERT OR IGNORE INTO g0_intents VALUES(?,?)" to (Phase.INTENT to "SS"),
        "INSERT INTO g0_facts VALUES(?,?,1)" to (Phase.BUSINESS to "IS"),
        "INSERT INTO g0_outbox VALUES(?,?)" to (Phase.BUSINESS to "SI"),
        "INSERT INTO g0_results VALUES(?,?)" to (Phase.BUSINESS to "SI"),
        "UPDATE g0_nodes SET digest=?,total=?,entries=? WHERE level=? AND position=?" to (Phase.BUSINESS to "SIIII"),
        "INSERT INTO g0_nodes VALUES(?,?,?,?,?)" to (Phase.BUSINESS to "IISII"),
        // Probe Room invalidation without changing any legacy business value or outbox.
        "UPDATE metrics SET name=name WHERE id=?" to (Phase.BUSINESS to "I")
    )
    // TEMP maintenance is not business authority. Only these exact side-effect-free-to-main
    // templates are accepted without an owner; no table name or trigger body comes from callers.
    private val frameworkReads = setOf("PRAGMA query_only",
        "SELECT * FROM room_table_modification_log WHERE invalidated = 1")
    private val frameworkWrites = buildSet {
        add("UPDATE room_table_modification_log SET invalidated = 0 WHERE invalidated = 1")
        // Frozen mapping from the locked HabitDatabase_Impl invalidation tracker. The prototype
        // only subscribes to metrics; all other observer DDL remains denied until covered.
        val table = "metrics"
        val index = 4
        add("INSERT OR IGNORE INTO room_table_modification_log VALUES($index, 0)")
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            val trigger = "room_table_modification_trigger_${table}_$operation"
            add("CREATE TEMP TRIGGER IF NOT EXISTS `$trigger` AFTER $operation ON `$table` BEGIN UPDATE room_table_modification_log SET invalidated = 1 WHERE table_id = $index AND invalidated = 0; END")
            add("DROP TRIGGER IF EXISTS `$trigger`")
        }
    }

    fun seal() { check(!sealed && !invalidated); sealedGeneration = generation; sealed = true }
    fun invalidateConnection() { invalidated = true; generation++ }
    // Only the trusted owner receives Room/helper/factory and phase capabilities. SQL consumers
    // receive this narrower interface, never the Room helper (which owns TEMP maintenance).
    fun sqlClient(): SupportSQLiteDatabase = checkNotNull(wrapped).client()
    fun requireSealedConnection() { if (invalidated || !sealed || generation != sealedGeneration) throw Denied() }
    suspend fun <T> transaction(db: HabitDatabase, phase: Phase, block: suspend () -> T): T = db.withTransaction {
        check(lease.get() == null)
        val token = Lease(phase, generation, Thread.currentThread())
        try { withContext(lease.asContextElement(token)) { block() } }
        finally { token.active = false }
    }
    suspend fun <T> finalRead(block: suspend () -> T): T {
        val parent = active()
        check(parent.phase in setOf(Phase.BUSINESS, Phase.INTENT) && !parent.writesClosed)
        parent.writesClosed = true
        val token = Lease(Phase.FINAL, parent.generation, parent.thread)
        try { return withContext(lease.asContextElement(token)) { block() } }
        finally { token.active = false }
    }
    private fun active(): Lease {
        val token = lease.get() ?: throw Denied()
        if (!token.active || token.generation != generation || token.thread !== Thread.currentThread()) throw Denied()
        return token
    }
    private fun checkSql(sql: String, args: List<Any?>, query: Boolean, db: SupportSQLiteDatabase, framework: Boolean) {
        if (invalidated && !(framework && lease.get() == null && args.isEmpty() &&
            (if (query) sql in frameworkReads else sql in frameworkWrites))) throw Denied()
        if (!sealed) { if (!framework) throw Denied(); return }
        // These exact TEMP-only operations remain legal for the private Room owner during
        // invalidation cleanup after an uncertain COMMIT. They cannot renew any certificate.
        if (framework && lease.get() == null && args.isEmpty() &&
            (if (query) sql in frameworkReads else sql in frameworkWrites)) return
        requireSealedConnection()
        val token = active()
        if (!db.inTransaction()) throw Denied()
        if (!query && token.writesClosed) throw Denied()
        val types = if (query) reads[sql] else writes[sql]?.takeIf { it.first == token.phase }?.second
        if (types == null || args.size != types.length) throw Denied()
        args.zip(types.toList()).forEach { (arg, type) ->
            if (when (type) { 'I' -> arg !is Long && arg !is Int; 'S' -> arg !is String; else -> true }) throw Denied()
        }
    }
    private fun control() { if (sealed || invalidated) throw Denied() }
    private fun transactionControl() { if (sealed && lease.get() != null) throw Denied() }
    private fun cursor(value: Cursor, db: SupportSQLiteDatabase, framework: Boolean): Cursor {
        val owner = lease.get()
        fun checkCursor() {
            if (invalidated && !(framework && owner == null)) throw Denied()
            if (!sealed) return
            if (framework && owner == null) return
            if (owner == null || lease.get() !== owner || active() !== owner || !db.inTransaction()) throw Denied()
        }
        // Do not subclass CursorWrapper (getWrappedCursor) or return SQLiteCursor (getDatabase).
        return object : Cursor by value {
            override fun getCount(): Int { checkCursor(); return value.count }
            override fun getColumnCount(): Int { checkCursor(); return value.columnCount }
            override fun getColumnNames(): Array<String> { checkCursor(); return value.columnNames }
            override fun getColumnName(columnIndex: Int): String { checkCursor(); return value.getColumnName(columnIndex) }
            override fun isBeforeFirst(): Boolean { checkCursor(); return value.isBeforeFirst }
            override fun isAfterLast(): Boolean { checkCursor(); return value.isAfterLast }
            override fun isFirst(): Boolean { checkCursor(); return value.isFirst }
            override fun isLast(): Boolean { checkCursor(); return value.isLast }
            override fun copyStringToBuffer(columnIndex: Int, buffer: android.database.CharArrayBuffer) { checkCursor(); value.copyStringToBuffer(columnIndex, buffer) }
            override fun moveToPosition(position: Int): Boolean { checkCursor(); return value.moveToPosition(position) }
            override fun move(offset: Int) = moveToPosition(value.position + offset)
            override fun moveToFirst() = moveToPosition(0)
            override fun moveToLast() = moveToPosition(count - 1)
            override fun moveToNext() = moveToPosition(value.position + 1)
            override fun moveToPrevious() = moveToPosition(value.position - 1)
            override fun getString(columnIndex: Int): String? { checkCursor(); return value.getString(columnIndex) }
            override fun getBlob(columnIndex: Int): ByteArray? { checkCursor(); return value.getBlob(columnIndex) }
            override fun getLong(columnIndex: Int): Long { checkCursor(); return value.getLong(columnIndex) }
            override fun getInt(columnIndex: Int): Int { checkCursor(); return value.getInt(columnIndex) }
            override fun getShort(columnIndex: Int): Short { checkCursor(); return value.getShort(columnIndex) }
            override fun getFloat(columnIndex: Int): Float { checkCursor(); return value.getFloat(columnIndex) }
            override fun getDouble(columnIndex: Int): Double { checkCursor(); return value.getDouble(columnIndex) }
            override fun getType(columnIndex: Int): Int { checkCursor(); return value.getType(columnIndex) }
            override fun isNull(columnIndex: Int): Boolean { checkCursor(); return value.isNull(columnIndex) }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun requery(): Boolean = throw Denied()
        }
    }
    private fun wrap(raw: SupportSQLiteDatabase): Database = synchronized(this) {
        wrapped?.takeIf { it.same(raw) } ?: Database(raw).also { wrapped = it; generation++ }
    }
    val factory = SupportSQLiteOpenHelper.Factory { configuration ->
        val callback = configuration.callback
        val guardedCallback = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = callback.onCorruption(wrap(db))
        }
        val raw = delegateFactory.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(guardedCallback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        object : SupportSQLiteOpenHelper {
            override val databaseName get() = raw.databaseName
            override val writableDatabase get() = wrap(raw.writableDatabase)
            override val readableDatabase get() = wrap(raw.readableDatabase)
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) { control(); raw.setWriteAheadLoggingEnabled(enabled) }
            override fun close() { generation++; raw.close() }
        }
    }

    private class Bindings : SupportSQLiteProgram {
        private val values = sortedMapOf<Int, Any?>()
        override fun bindNull(index: Int) { values[index] = null }
        override fun bindLong(index: Int, value: Long) { values[index] = value }
        override fun bindDouble(index: Int, value: Double) { values[index] = value }
        override fun bindString(index: Int, value: String) { values[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { values[index] = value.copyOf() }
        override fun clearBindings() = values.clear()
        override fun close() = clearBindings()
        fun snapshot(): List<Any?> {
            if (values.keys.toList() != (1..values.size).toList()) throw Denied()
            return values.values.toList()
        }
    }
    private inner class Database(private val raw: SupportSQLiteDatabase, private val framework: Boolean = true) : SupportSQLiteDatabase by raw {
        fun client(): SupportSQLiteDatabase = Database(raw, false)
        private fun control() { if (!framework) throw Denied(); this@G0SqlGate.control() }
        private fun checkClientTransactionControl() {
            if (!framework) throw Denied()
            transactionControl()
        }
        fun same(other: SupportSQLiteDatabase) = other === raw
        override fun query(query: String): Cursor = query(SimpleSQLiteQuery(query))
        override fun query(query: String, bindArgs: Array<out Any?>): Cursor = query(SimpleSQLiteQuery(query, bindArgs))
        override fun query(query: SupportSQLiteQuery): Cursor = query(query, null)
        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
            val text = query.sql // Read caller-owned SQL once; never forward mutable callbacks.
            val bindings = Bindings().also(query::bindTo).snapshot()
            checkSql(text, bindings, true, raw, framework)
            val frozen = SimpleSQLiteQuery(text, bindings.toTypedArray())
            return cursor(if (cancellationSignal == null) raw.query(frozen) else raw.query(frozen, cancellationSignal), raw,
                framework && text in frameworkReads && bindings.isEmpty())
        }
        override fun execSQL(sql: String) = execSQL(sql, emptyArray())
        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
            val owned = bindArgs.map { if (it is ByteArray) it.copyOf() else it }
            checkSql(sql, owned, false, raw, framework); raw.execSQL(sql, owned.toTypedArray())
        }
        override fun compileStatement(sql: String): SupportSQLiteStatement {
            // Delay native compilation until execution. Even statements prepared before seal or
            // in another phase must pass the current execution check, with copied current binds.
            return object : SupportSQLiteStatement {
                private val bindings = Bindings()
                private var closed = false
                private fun <T> execute(query: Boolean, action: (SupportSQLiteStatement) -> T): T {
                    check(!closed)
                    val owned = bindings.snapshot(); checkSql(sql, owned, query, raw, framework)
                    return raw.compileStatement(sql).use { statement ->
                        SimpleSQLiteQuery.bind(statement, owned.toTypedArray()); action(statement)
                    }
                }
                override fun execute() { execute(false) { it.execute() } }
                override fun executeInsert() = execute(false) { it.executeInsert() }
                override fun executeUpdateDelete() = execute(false) { it.executeUpdateDelete() }
                override fun simpleQueryForLong() = execute(true) { it.simpleQueryForLong() }
                override fun simpleQueryForString() = execute(true) { it.simpleQueryForString() }
                override fun bindNull(index: Int) = bindings.bindNull(index)
                override fun bindLong(index: Int, value: Long) = bindings.bindLong(index, value)
                override fun bindDouble(index: Int, value: Double) = bindings.bindDouble(index, value)
                override fun bindString(index: Int, value: String) = bindings.bindString(index, value)
                override fun bindBlob(index: Int, value: ByteArray) = bindings.bindBlob(index, value)
                override fun clearBindings() = bindings.clearBindings()
                override fun close() { closed = true; bindings.close() }
            }
        }
        override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long { control(); return raw.insert(table, conflictAlgorithm, values) }
        override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int { control(); return raw.update(table, conflictAlgorithm, values, whereClause, whereArgs) }
        override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int { control(); return raw.delete(table, whereClause, whereArgs) }
        override fun beginTransaction() { checkClientTransactionControl(); raw.beginTransaction() }
        override fun beginTransactionNonExclusive() { checkClientTransactionControl(); raw.beginTransactionNonExclusive() }
        override fun beginTransactionReadOnly() { checkClientTransactionControl(); raw.beginTransactionReadOnly() }
        override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) { control(); raw.beginTransactionWithListener(transactionListener) }
        override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) { control(); raw.beginTransactionWithListenerNonExclusive(transactionListener) }
        override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) { control(); raw.beginTransactionWithListenerReadOnly(transactionListener) }
        override fun setTransactionSuccessful() { checkClientTransactionControl(); raw.setTransactionSuccessful() }
        override fun endTransaction() {
            checkClientTransactionControl()
            try { raw.endTransaction() } catch (failure: Throwable) {
                // An exception can follow a real COMMIT. Never retain a warm certificate when
                // the engine did not confirm the boundary; ordinary confirmed rollback is safe.
                generation++
                throw failure
            }
        }
        override fun yieldIfContendedSafely(): Boolean { control(); return raw.yieldIfContendedSafely() }
        override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean { control(); return raw.yieldIfContendedSafely(sleepAfterYieldDelayMillis) }
        override fun execPerConnectionSQL(sql: String, bindArgs: Array<out Any?>?) { control(); raw.execPerConnectionSQL(sql, bindArgs) }
        override var version: Int
            get() = raw.version
            set(value) { control(); raw.version = value }
        override fun setMaximumSize(numBytes: Long): Long { control(); return raw.setMaximumSize(numBytes) }
        override var pageSize: Long
            get() = raw.pageSize
            set(value) { control(); raw.pageSize = value }
        override fun setLocale(locale: Locale) { control(); raw.setLocale(locale) }
        override fun setMaxSqlCacheSize(cacheSize: Int) { control(); raw.setMaxSqlCacheSize(cacheSize) }
        override fun setForeignKeyConstraintsEnabled(enabled: Boolean) { control(); raw.setForeignKeyConstraintsEnabled(enabled) }
        override fun enableWriteAheadLogging(): Boolean { control(); return raw.enableWriteAheadLogging() }
        override fun disableWriteAheadLogging() { control(); raw.disableWriteAheadLogging() }
        override fun close() { if (!framework) throw Denied(); generation++; raw.close() }
    }
}
