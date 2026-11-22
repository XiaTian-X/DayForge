package com.dayforge.data.repository

import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement

/** Fixed test-only observations below the real controlled factory. No Room mock, caller SQL,
 * stored payload, credential, native handle or arbitrary action is exposed by this helper. */
internal class MetricT1NativeFixture(private val delegate: SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
    enum class Fault { NONE, FIRST_DAO_UPDATED_AT, AFTER_TYPED_METRIC_UPDATED_AT }
    enum class InsertEntry { STATEMENT_EXECUTE, STATEMENT_EXECUTE_INSERT, STATEMENT_EXECUTE_UPDATE_DELETE,
        EXEC_SQL, EXEC_SQL_ARGS }
    data class Sample(val dao: Int, val typed: Int, val ends: Int, val faultTouched: Boolean, val metricInsertAttempts: Int,
        val daoBeforeFirstMetricInsertAttempt: Int, val daoAfterFirstMetricInsertAttempt: Int,
        val metricInsertEntries: Map<InsertEntry, Int>)
    private val monitor = Any()
    private var selected = 0L
    @Volatile private var armed = false
    @Volatile private var thread: Thread? = null
    private var fault = Fault.NONE
    private var dao = 0
    private var daoBeforeFirstMetricInsertAttempt = 0
    private var daoAfterFirstMetricInsertAttempt = 0
    private var typed = 0
    private var touched = false
    private var metricInsertAttempts = 0
    private val metricInsertEntries = IntArray(InsertEntry.entries.size)
    private val metricInsertSql = Regex("^\\s*INSERT(?:\\s+OR\\s+(?:ABORT|FAIL|IGNORE|REPLACE|ROLLBACK))?\\s+INTO\\s+(?:main\\.)?(?:`metric_logs`|metric_logs)(?=\\s|\\()",
        RegexOption.IGNORE_CASE)
    private var completed: Sample? = null
    fun arm(id: Long, inject: Fault = Fault.NONE) = synchronized(monitor) {
        check(!armed && thread == null && completed == null); require(id > 0)
        selected = id; fault = inject; dao = 0; typed = 0; touched = false; metricInsertAttempts = 0
        daoBeforeFirstMetricInsertAttempt = 0; daoAfterFirstMetricInsertAttempt = 0
        metricInsertEntries.fill(0); armed = true
    }
    fun finished(): Sample = synchronized(monitor) {
        check(!armed && thread == null)
        requireNotNull(completed).also { completed = null }
    }
    private fun metricInsertAttempt(entry: InsertEntry) {
        if (!armed || thread !== Thread.currentThread()) return
        synchronized(monitor) {
            if (armed && thread === Thread.currentThread()) {
                metricInsertAttempts++
                metricInsertEntries[entry.ordinal]++
            }
        }
    }
    /** Fixed enum counters only. No statement SQL, bind arguments or payload survives. */
    private fun insertEntrySnapshot(): Map<InsertEntry, Int> = java.util.Collections.unmodifiableMap(
        InsertEntry.entries.associateWith { metricInsertEntries[it.ordinal] })
    private val spaces = Regex("\\s+")
    private val equalsSpacing = Regex("\\s*=\\s*")
    private fun normalized(sql: String) = sql.trim().lowercase(java.util.Locale.ROOT).replace("`", "")
        .replace(spaces, " ").replace(equalsSpacing, "=")
    private fun argument(query: SupportSQLiteQuery): Any? {
        var first: Any? = null
        query.bindTo(object : SupportSQLiteProgram {
            override fun bindNull(index: Int) { if (index == 1) first = null }
            override fun bindLong(index: Int, value: Long) { if (index == 1) first = value }
            override fun bindDouble(index: Int, value: Double) { if (index == 1) first = value }
            override fun bindString(index: Int, value: String) { if (index == 1) first = value }
            override fun bindBlob(index: Int, value: ByteArray) = Unit
            override fun clearBindings() { first = null }
            override fun close() = Unit
        })
        return first
    }
    private fun read(raw: SupportSQLiteDatabase, sql: String, argument: () -> Any?, query: () -> Cursor): Cursor {
        if (!armed || thread != null && thread !== Thread.currentThread()) return query()
        val kind = normalized(sql)
        val forge = synchronized(monitor) {
            if (armed && thread == null && kind == "select * from metrics where id=? limit 1" && argument() == selected) {
                check(raw.inTransaction()); thread = Thread.currentThread()
            }
            if (thread !== Thread.currentThread()) false else {
                if (kind == "select * from metrics where id=? limit 1") {
                    dao++
                    // Original source mapping reads its actual parent after the metric INSERTs,
                    // still BEFORE outer END. Keep that separate from the two pre-INSERT reads.
                    if (metricInsertAttempts == 0) daoBeforeFirstMetricInsertAttempt++
                    else daoAfterFirstMetricInsertAttempt++
                }
                if (kind == "select * from metrics where uuid=? order by id") {
                    typed++
                }
                fault == Fault.FIRST_DAO_UPDATED_AT && dao == 1 && kind == "select * from metrics where id=? limit 1"
            }
        }
        val cursor = query()
        val damageOnClose = synchronized(monitor) { thread === Thread.currentThread() &&
            kind == "select * from metrics where uuid=? order by id" &&
            fault == Fault.AFTER_TYPED_METRIC_UPDATED_AT && !touched }
        if (damageOnClose) return object : CursorWrapper(cursor) {
            override fun close() {
                super.close() // The typed row has already been copied out of this real cursor.
                synchronized(monitor) {
                    if (!touched) {
                        raw.execSQL("UPDATE main.metrics SET updatedAt=updatedAt+1 WHERE id=?", arrayOf(selected))
                        touched = true
                    }
                }
            }
        }
        return if (!forge) cursor else object : CursorWrapper(cursor) {
            override fun getLong(columnIndex: Int): Long = super.getLong(columnIndex).let { value ->
                if (super.getColumnName(columnIndex) != "updatedAt") value else {
                    synchronized(monitor) { touched = true }
                    Math.addExact(value, 1L)
                }
            }
        }
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        val wrappers = Any()
        var original: SupportSQLiteDatabase? = null
        var adapted: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(wrappers) {
            if (original !== raw) {
                original = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    override fun endTransaction() {
                        if (!armed || thread !== Thread.currentThread()) { raw.endTransaction(); return }
                        try { raw.endTransaction() }
                        catch (original: Throwable) {
                            // A thrown actual END has unknown outcome. Preserve the original
                            // exception and an explicit non-success observation; never retry END.
                            synchronized(monitor) {
                                if (thread === Thread.currentThread()) {
                                    completed = Sample(dao, typed, 0, touched, metricInsertAttempts,
                                        daoBeforeFirstMetricInsertAttempt, daoAfterFirstMetricInsertAttempt, insertEntrySnapshot())
                                    thread = null; armed = false
                                }
                            }
                            throw original
                        }
                        if (!raw.inTransaction()) synchronized(monitor) {
                            if (thread === Thread.currentThread()) {
                                completed = Sample(dao, typed, 1, touched, metricInsertAttempts,
                                    daoBeforeFirstMetricInsertAttempt, daoAfterFirstMetricInsertAttempt, insertEntrySnapshot())
                                thread = null; armed = false
                            }
                        }
                    }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        // Prepared Room statements may predate arm(); retain the fixed INSERT
                        // wrapper without retaining any SQL/bind/payload in the observation.
                        if (!metricInsertSql.containsMatchIn(sql)) return statement
                        return object : SupportSQLiteStatement by statement {
                            // Room 2.8.5's EntityInsertAdapter calls SQLiteStatement.step();
                            // framework 2.6.2's compatibility bridge delegates to execute().
                            override fun execute() {
                                metricInsertAttempt(InsertEntry.STATEMENT_EXECUTE)
                                statement.execute()
                            }
                            override fun executeInsert(): Long {
                                metricInsertAttempt(InsertEntry.STATEMENT_EXECUTE_INSERT)
                                return statement.executeInsert()
                            }
                            override fun executeUpdateDelete(): Int {
                                metricInsertAttempt(InsertEntry.STATEMENT_EXECUTE_UPDATE_DELETE)
                                return statement.executeUpdateDelete()
                            }
                        }
                    }
                    override fun execSQL(sql: String) {
                        if (armed && thread === Thread.currentThread() && metricInsertSql.containsMatchIn(sql))
                            metricInsertAttempt(InsertEntry.EXEC_SQL)
                        raw.execSQL(sql)
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        if (armed && thread === Thread.currentThread() && metricInsertSql.containsMatchIn(sql))
                            metricInsertAttempt(InsertEntry.EXEC_SQL_ARGS)
                        raw.execSQL(sql, bindArgs)
                    }
                    override fun query(query: String): Cursor {
                        if (!armed) return raw.query(query)
                        return read(raw, query, { null }) { raw.query(query) }
                    }
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                        if (!armed) return raw.query(query, bindArgs)
                        return read(raw, query, { bindArgs.firstOrNull() }) { raw.query(query, bindArgs) }
                    }
                    override fun query(query: SupportSQLiteQuery): Cursor {
                        if (!armed) return raw.query(query)
                        return read(raw, query.sql, { argument(query) }) { raw.query(query) }
                    }
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                        if (!armed) return raw.query(query, cancellationSignal)
                        return read(raw, query.sql, { argument(query) }) { raw.query(query, cancellationSignal) }
                    }
                }
            }
            requireNotNull(adapted)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = delegate.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
