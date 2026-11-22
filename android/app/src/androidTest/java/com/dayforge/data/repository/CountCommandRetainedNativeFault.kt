package com.dayforge.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteTransactionListener
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.HabitDatabaseProvider

/** Fixed native actors below the real production controlled helper. No callback or SQL
 * chosen by a test can become application authority. All BEGIN/execute paths are real. */
internal class CountCommandRetainedNativeFault : SupportSQLiteOpenHelper.Factory {
    enum class Mode { BEFORE_NODE, BEFORE_ROOT, BEFORE_ANCHOR, EXTRA_LEGACY, AFTER_END, DEFERRED_END }
    private class Tx { val marks = mutableListOf(false); var rolledBack = false }
    @Volatile private var mode: Mode? = null
    @Volatile private var targeted: Thread? = null
    @Volatile var hits = 0; private set
    @Volatile var actualCommit = false; private set
    @Volatile var actualRollback = false; private set
    @Volatile var actualDeferredFailure = false; private set
    fun arm(value: Mode) { check(mode == null && targeted == null); mode = value; hits = 0; actualCommit = false; actualRollback = false; actualDeferredFailure = false }
    fun build(context: Context) = HabitDatabaseProvider.build(context, nativeFactory = this)
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var identity: SupportSQLiteDatabase? = null
        var wrapped: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (identity !== raw) {
                identity = raw
                fun normal(sql: String) = sql.trim().lowercase(java.util.Locale.ROOT).replace("`", "").replace(Regex("\\s+"), " ")
                fun root(sql: String) = normal(sql).startsWith("update count_command_recovery_roots ")
                fun before(sql: String) {
                    val text = normal(sql)
                    val selected = when (mode) {
                        Mode.BEFORE_NODE -> text.startsWith("insert into count_command_nodes(")
                        Mode.BEFORE_ROOT -> root(sql)
                        Mode.BEFORE_ANCHOR -> text.startsWith("update count_trust_control set commandroothash=")
                        else -> false
                    }
                    if (selected) {
                        check(raw.inTransaction()); targeted = Thread.currentThread(); hits++; mode = null
                        error("FIXED_RETAINED_NATIVE_WRITE_FAILURE")
                    }
                }
                fun after(sql: String) {
                    if (root(sql) && mode in setOf(Mode.EXTRA_LEGACY, Mode.AFTER_END, Mode.DEFERRED_END)) {
                        check(raw.inTransaction())
                        check(raw.query("SELECT format FROM count_command_recovery_roots WHERE id=1").use { it.moveToFirst() && it.getInt(0) == 3 && !it.moveToNext() })
                        targeted = Thread.currentThread()
                        if (mode == Mode.EXTRA_LEGACY) {
                            raw.execSQL("UPDATE count_command_results SET resultJson=resultJson||'changed' WHERE commandId='legacy-native-id'")
                            check(raw.query("SELECT changes()").use { it.moveToFirst() && it.getInt(0) == 1 && !it.moveToNext() })
                            hits++; mode = null
                        }
                    }
                }
                wrapped = object : SupportSQLiteDatabase by raw {
                    private val transactions = ThreadLocal<Tx>()
                    private fun begin(action: () -> Unit) {
                        action()
                        val old = transactions.get()
                        if (old == null) transactions.set(Tx()) else old.marks.add(false)
                    }
                    override fun beginTransaction() = begin { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = begin { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun execSQL(sql: String) { before(sql); raw.execSQL(sql); after(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) { before(sql); raw.execSQL(sql, bindArgs); after(sql) }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() { before(sql); statement.execute(); after(sql) }
                            override fun executeInsert(): Long { before(sql); return statement.executeInsert().also { after(sql) } }
                            override fun executeUpdateDelete(): Int { before(sql); return statement.executeUpdateDelete().also { after(sql) } }
                        }
                    }
                    override fun setTransactionSuccessful() {
                        val tx = transactions.get()
                        if (tx != null && tx.marks.size == 1 && !tx.rolledBack && targeted === Thread.currentThread() && mode == Mode.DEFERRED_END) {
                            check(raw.query("PRAGMA foreign_keys").use { it.moveToFirst() && it.getInt(0) == 1 })
                            raw.execSQL("INSERT INTO temp.retained_fault_child(id,parent) VALUES(1,99)")
                            check(raw.query("PRAGMA temp.foreign_key_check(retained_fault_child)").use { it.moveToFirst() })
                            hits++
                        }
                        raw.setTransactionSuccessful()
                        tx?.let { it.marks[it.marks.lastIndex] = true }
                    }
                    override fun endTransaction() {
                        val tx = transactions.get()
                        if (tx == null) { raw.endTransaction(); return }
                        val outer = tx.marks.size == 1
                        val successful = tx.marks.last()
                        try {
                            try { raw.endTransaction() }
                            catch (failure: SQLiteConstraintException) {
                                if (outer && targeted === Thread.currentThread() && mode == Mode.DEFERRED_END) {
                                    check(successful && !tx.rolledBack && !raw.inTransaction() && failure.message.orEmpty().contains("FOREIGN KEY constraint failed"))
                                    actualDeferredFailure = true; actualRollback = true; mode = null; targeted = null
                                }
                                throw failure
                            }
                            if (outer && targeted === Thread.currentThread()) {
                                check(!raw.inTransaction())
                                actualCommit = successful && !tx.rolledBack
                                actualRollback = !actualCommit
                                if (mode == Mode.AFTER_END) {
                                    check(actualCommit); hits++; mode = null; targeted = null
                                    error("FIXED_RETAINED_END_RESULT_LOST")
                                }
                                check(mode != Mode.DEFERRED_END) { "FIXED_RETAINED_DEFERRED_NOT_REJECTED" }
                                targeted = null
                            }
                        } finally {
                            if (outer) transactions.remove() else { tx.marks.removeAt(tx.marks.lastIndex); if (!successful) tx.rolledBack = true }
                        }
                    }
                }
            }
            return requireNotNull(wrapped)
        }
        val original = configuration.callback
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(object : SupportSQLiteOpenHelper.Callback(original.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) {
                    original.onOpen(wrap(db)); check(!db.inTransaction())
                    db.execSQL("CREATE TEMP TABLE retained_fault_parent(id INTEGER PRIMARY KEY)")
                    db.execSQL("CREATE TEMP TABLE retained_fault_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES retained_fault_parent(id) DEFERRABLE INITIALLY DEFERRED)")
                }
                override fun onCorruption(db: SupportSQLiteDatabase) = original.onCorruption(wrap(db))
            }).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
