package com.dayforge.data.repository

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** Fixed native cases below the unchanged controlled factory. Room 2.8 executes statements
 * through execute(); all three entry points are calibrated, not just executeInsert. */
internal class IndependentMetricNativeFault : SupportSQLiteOpenHelper.Factory {
    enum class Mode { BEFORE_T0_INSERT, AFTER_T0_END, BEFORE_VALUE_EXECUTE, AFTER_VALUE_END, AFTER_UPGRADE_END, EXTRA_VALUE_NOOP, DEFERRED_VALUE_END, DEFERRED_T2_END, AFTER_T2_END }
    private class NativeTx {
        val successful = mutableListOf(false)
        var childRolledBack = false
    }
    @Volatile private var mode: Mode? = null
    @Volatile private var endThread: Thread? = null
    @Volatile var hits = 0; private set
    @Volatile var actualEnd = false; private set
    @Volatile var executeEntries = 0; private set
    @Volatile var deferredEndFailures = 0; private set
    @Volatile var deferredFailure: android.database.sqlite.SQLiteConstraintException? = null; private set
    @Volatile var stagedOutcomeObserved = false; private set
    @Volatile var deliveryObserved = false; private set
    @Volatile private var deferredThread: Thread? = null
    fun arm(value: Mode) {
        check(mode == null && endThread == null)
        mode = value; hits = 0; actualEnd = false; executeEntries = 0
        deferredEndFailures = 0; deferredFailure = null; stagedOutcomeObserved = false; deliveryObserved = false; deferredThread = null
    }
    fun build(context: Context) = buildDiagnosticFaultDatabase(context, this)
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var identity: SupportSQLiteDatabase? = null
        var adapted: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (identity !== raw) {
                identity = raw
                fun normalized(sql: String) = sql.trim().lowercase(java.util.Locale.ROOT).replace(96.toChar().toString(), "")
                    .replace(Regex("\\s+"), " ")
                fun intent(sql: String) = normalized(sql).startsWith("insert into count_command_intents ")
                fun value(sql: String) = normalized(sql).let { it.startsWith("insert or replace into metric_logs ") || it.startsWith("insert into metric_logs ") }
                fun before(sql: String) {
                    if (mode == Mode.BEFORE_T0_INSERT && intent(sql)) {
                        check(raw.inTransaction()); mode = null; hits++
                        error("FIXED_METRIC_T0_BEFORE_INSERT")
                    }
                    if (mode == Mode.BEFORE_VALUE_EXECUTE && value(sql)) {
                        check(raw.inTransaction()); mode = null; hits++
                        error("FIXED_METRIC_BEFORE_VALUE")
                    }
                }
                fun after(sql: String) {
                    if (mode == Mode.DEFERRED_VALUE_END && value(sql)) deferredThread = Thread.currentThread()
                    if (mode in setOf(Mode.DEFERRED_T2_END, Mode.AFTER_T2_END) && normalized(sql).startsWith("update count_command_status ")) {
                        deliveryObserved = raw.query("SELECT 1 FROM count_command_status WHERE delivery='DELIVERED' LIMIT 1").use { it.moveToFirst() }
                        if (deliveryObserved) {
                            if (mode == Mode.AFTER_T2_END) endThread = Thread.currentThread()
                            else deferredThread = Thread.currentThread()
                        }
                    }
                    if (mode == Mode.EXTRA_VALUE_NOOP && value(sql)) {
                        check(raw.inTransaction()); mode = null; hits++
                        raw.execSQL("UPDATE metric_logs SET note=note WHERE id=(SELECT MAX(id) FROM metric_logs)")
                    } else if (mode == Mode.AFTER_VALUE_END && value(sql) || mode == Mode.AFTER_T0_END && intent(sql)) {
                        check(raw.inTransaction()); endThread = Thread.currentThread()
                    } else if (mode == Mode.AFTER_UPGRADE_END && normalized(sql).startsWith("update count_command_recovery_roots ")) {
                        val upgraded = raw.query("SELECT format FROM count_command_recovery_roots WHERE id=1").use {
                            check(it.moveToFirst()); it.getInt(0) == 2
                        }
                        if (upgraded) endThread = Thread.currentThread()
                    }
                }
                adapted = object : SupportSQLiteDatabase by raw {
                    private val transaction = ThreadLocal<NativeTx>()
                    private fun begin(body: () -> Unit) {
                        body()
                        val current = transaction.get()
                        if (current == null) transaction.set(NativeTx()) else current.successful.add(false)
                    }
                    override fun beginTransaction() = begin { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = begin { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: android.database.sqlite.SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: android.database.sqlite.SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: android.database.sqlite.SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() {
                                if (value(sql)) executeEntries++
                                before(sql); statement.execute(); after(sql)
                            }
                            override fun executeInsert(): Long { before(sql); return statement.executeInsert().also { after(sql) } }
                            override fun executeUpdateDelete(): Int { before(sql); return statement.executeUpdateDelete().also { after(sql) } }
                        }
                    }
                    override fun execSQL(sql: String) { before(sql); raw.execSQL(sql); after(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) { before(sql); raw.execSQL(sql, bindArgs); after(sql) }
                    override fun setTransactionSuccessful() {
                        val tx = transaction.get()
                        // Room DAO helpers may mark nested transactions successful before
                        // command Outcome/footer exist. Only the real outer success call,
                        // after the production controller's complete footer, may inject.
                        if (tx != null && tx.successful.size == 1 && !tx.childRolledBack &&
                            deferredThread === Thread.currentThread() && mode in setOf(Mode.DEFERRED_VALUE_END, Mode.DEFERRED_T2_END)) {
                            check(raw.inTransaction())
                            check(raw.query("PRAGMA foreign_keys").use { it.moveToFirst() && it.getInt(0) == 1 })
                            stagedOutcomeObserved = raw.query("SELECT 1 FROM count_command_results WHERE state='APPLIED' LIMIT 1").use { it.moveToFirst() }
                            check(stagedOutcomeObserved)
                            if (mode == Mode.DEFERRED_T2_END) check(deliveryObserved)
                            raw.execSQL("INSERT INTO temp.independent_metric_fault_child(id,parent) VALUES(1,99)")
                            check(raw.query("PRAGMA temp.foreign_key_check(independent_metric_fault_child)").use { it.moveToFirst() })
                            hits++; mode = null
                        }
                        raw.setTransactionSuccessful()
                        tx?.let { it.successful[it.successful.lastIndex] = true }
                    }
                    override fun endTransaction() {
                        val tx = transaction.get()
                        if (tx == null) { raw.endTransaction(); return }
                        val outer = tx.successful.size == 1
                        val successful = tx.successful.last()
                        try {
                        try { raw.endTransaction() }
                        catch (failure: android.database.sqlite.SQLiteConstraintException) {
                            if (deferredThread === Thread.currentThread() && outer) {
                                check(successful && !tx.childRolledBack)
                                check(failure.message.orEmpty().contains("FOREIGN KEY constraint failed"))
                                check(deferredFailure == null); deferredFailure = failure
                                deferredEndFailures++; check(!raw.inTransaction()); deferredThread = null
                            }
                            throw failure
                        }
                        if (deferredThread === Thread.currentThread() && outer && !raw.inTransaction()) {
                            deferredThread = null
                            error("FIXED_DEFERRED_END_WAS_NOT_REJECTED")
                        }
                        if (endThread === Thread.currentThread() && outer && !raw.inTransaction()) {
                            actualEnd = true; hits++; endThread = null; mode = null
                            error("FIXED_INDEPENDENT_METRIC_END_RESULT_LOST")
                        }
                        } finally {
                            if (outer) transaction.remove()
                            else {
                                tx.successful.removeAt(tx.successful.lastIndex)
                                if (!successful) tx.childRolledBack = true
                            }
                        }
                    }
                }
            }
            return requireNotNull(adapted)
        }
        val callback = configuration.callback
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(object : SupportSQLiteOpenHelper.Callback(callback.version) {
                    override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(wrap(db))
                    override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(wrap(db))
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(wrap(db), oldVersion, newVersion)
                    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(wrap(db), oldVersion, newVersion)
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        callback.onOpen(wrap(db))
                        check(!db.inTransaction())
                        db.execSQL("CREATE TEMP TABLE independent_metric_fault_parent(id INTEGER PRIMARY KEY)")
                        db.execSQL("CREATE TEMP TABLE independent_metric_fault_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES independent_metric_fault_parent(id) DEFERRABLE INITIALLY DEFERRED)")
                    }
                    override fun onCorruption(db: SupportSQLiteDatabase) = callback.onCorruption(wrap(db))
                }).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
