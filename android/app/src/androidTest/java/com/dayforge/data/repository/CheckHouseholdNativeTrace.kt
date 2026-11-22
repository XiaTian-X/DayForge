package com.dayforge.data.repository

import android.database.sqlite.SQLiteTransactionListener
import android.os.SystemClock
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Observation only for the fixed CHECK/goal-continuation/metric journey. No SQL, pause, fault,
 * authority or receipt is added. SQL counters describe successful native calls; the test
 * independently proves their exact durable effects and binds every fact/request ID.
 */
internal class CheckHouseholdNativeTrace : SupportSQLiteOpenHelper.Factory {
    enum class Kind { CHECK, METRICS, PLAN, ACK }
    data class End(val kind: Kind, val sequence: Long, val nativeIdentity: Int,
        val beginAt: Long, val acquiredAt: Long, val endAt: Long,
        val factUuids: List<String>, val metricUuids: List<String>,
        val originRequests: List<String>, val acceptedRequests: List<String>)
    private class Tx(val sequence: Long, val native: SupportSQLiteDatabase, val beginAt: Long) {
        var acquiredAt = 0L
        val successful = arrayListOf(false)
        var rolledBackChild = false
        val factUuids = arrayListOf<String>(); val metricUuids = arrayListOf<String>()
        val originRequests = arrayListOf<String>(); val acceptedRequests = arrayListOf<String>()
        var forbiddenCommands = 0
    }
    private val sequence = AtomicLong()
    private val current = ThreadLocal<Tx?>()
    @Volatile private var armed = false
    val ends = CopyOnWriteArrayList<End>()
    val observationFailures = CopyOnWriteArrayList<String>()

    fun arm() { check(!armed && ends.isEmpty()); armed = true }
    private fun failure(reason: String) {
        synchronized(observationFailures) {
            if (observationFailures.size < 64) observationFailures.addIfAbsent(reason)
        }
    }
    private fun observe(block: () -> Unit) {
        try { block() } catch (error: Throwable) { failure("observation_${error.javaClass.simpleName}") }
    }
    private fun id(destination: MutableList<String>, value: Any?, label: String) {
        if (value !is String || value.isEmpty() || value.length > 160) {
            failure("missing_${label}_id"); return
        }
        if (destination.size < 32) destination += value else failure("${label}_id_capacity")
    }
    /** Only fixed generated INSERT columns are inspected. This observer never executes SQL. */
    private fun identityPosition(sql: String, table: String): Int? {
        val key = when (table) {
            "completions", "metric_logs" -> "uuid"
            "next_request_origins", "next_acceptances" -> "requestid"
            else -> return null
        }
        val normalized = sql.trim().lowercase(Locale.ROOT).replace("`", "").replace("\"", "")
        val end = normalized.indexOf(')'); val start = normalized.indexOf('(')
        if (start < 0 || end <= start) { if (armed) failure("fixed_insert_columns_missing"); return null }
        val fields = normalized.substring(start + 1, end).split(',').map(String::trim)
        if (fields.count { it == key } != 1) { if (armed) failure("fixed_identity_column_missing"); return null }
        return fields.indexOf(key) + 1
    }
    private fun executed(sql: String, identity: Any?) {
        if (!armed) return
        val tx = current.get() ?: return
        val text = sql.trim().lowercase(Locale.ROOT).replace("`", "").replace("\"", "")
        if (UPDATE.find(text)?.groupValues?.get(1) == "count_command_status") tx.forbiddenCommands++
        when (INSERT.find(text)?.groupValues?.get(1)) {
            "completions" -> id(tx.factUuids, identity, "fact")
            "metric_logs" -> id(tx.metricUuids, identity, "metric")
            "next_request_origins" -> id(tx.originRequests, identity, "origin")
            "next_acceptances" -> id(tx.acceptedRequests, identity, "acceptance")
            "count_command_intents", "count_command_results" -> tx.forbiddenCommands++
        }
    }
    private fun completed(tx: Tx, endAt: Long) {
        if (tx.forbiddenCommands != 0) failure("CHECK_borrowed_typed_command")
        val kind = when {
            tx.acceptedRequests.isNotEmpty() && tx.originRequests.isEmpty() -> Kind.ACK
            tx.acceptedRequests.isEmpty() && tx.factUuids.size == 1 && tx.metricUuids.isEmpty() && tx.originRequests.size == 1 -> Kind.CHECK
            tx.acceptedRequests.isEmpty() && tx.factUuids.isEmpty() && tx.metricUuids.size == 2 && tx.originRequests.size == 2 -> Kind.METRICS
            tx.acceptedRequests.isEmpty() && tx.factUuids.isEmpty() && tx.metricUuids.isEmpty() && tx.originRequests.size == 1 -> Kind.PLAN
            else -> null
        }
        if (kind == null) {
            if (tx.factUuids.size + tx.metricUuids.size + tx.originRequests.size + tx.acceptedRequests.size > 0)
                failure("unclassified_domain_transaction")
            return
        }
        val end = End(kind, tx.sequence, System.identityHashCode(tx.native), tx.beginAt, tx.acquiredAt, endAt,
            tx.factUuids.toList(), tx.metricUuids.toList(), tx.originRequests.toList(), tx.acceptedRequests.toList())
        synchronized(ends) { if (ends.size < 128) ends.add(end) else failure("trace_capacity") }
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var original: SupportSQLiteDatabase? = null
        var wrapper: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (original !== raw) {
                original = raw
                wrapper = object : SupportSQLiteDatabase by raw {
                    private fun begin(block: () -> Unit) {
                        val prior = current.get()
                        if (!armed && prior == null) { block(); return }
                        val tx = prior ?: Tx(sequence.incrementAndGet(), raw, SystemClock.elapsedRealtimeNanos()).also(current::set)
                        observe {
                            if (tx.native !== raw) failure("foreign_native_transaction")
                            if (prior != null) tx.successful.add(false)
                        }
                        try {
                            block()
                            if (prior == null) tx.acquiredAt = SystemClock.elapsedRealtimeNanos()
                        } catch (original: Throwable) {
                            if (prior == null) current.remove()
                            else observe { tx.successful.removeAt(tx.successful.lastIndex) }
                            throw original
                        }
                    }
                    override fun beginTransaction() = begin { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = begin { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun execSQL(sql: String) {
                        raw.execSQL(sql)
                        observe { executed(sql, null) }
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        raw.execSQL(sql, bindArgs)
                        observe {
                            val text = sql.trim().lowercase(Locale.ROOT).replace("`", "").replace("\"", "")
                            val table = INSERT.find(text)?.groupValues?.get(1)
                            val index = table?.let { identityPosition(sql, it) }
                            executed(sql, index?.let { bindArgs.getOrNull(it - 1) })
                        }
                    }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val text = sql.trim().lowercase(Locale.ROOT).replace("`", "").replace("\"", "")
                        val table = INSERT.find(text)?.groupValues?.get(1)
                        val position = table?.let { identityPosition(sql, it) }
                        var identity: String? = null
                        fun bound(index: Int, value: String?) { if (index == position) identity = value }
                        fun observed() = observe { executed(sql, identity) }
                        return object : SupportSQLiteStatement by statement {
                            override fun bindNull(index: Int) { statement.bindNull(index); bound(index, null) }
                            override fun bindLong(index: Int, value: Long) { statement.bindLong(index, value); bound(index, null) }
                            override fun bindDouble(index: Int, value: Double) { statement.bindDouble(index, value); bound(index, null) }
                            override fun bindString(index: Int, value: String) { statement.bindString(index, value); bound(index, value) }
                            override fun bindBlob(index: Int, value: ByteArray) { statement.bindBlob(index, value); bound(index, null) }
                            override fun clearBindings() { statement.clearBindings(); identity = null }
                            override fun execute() { statement.execute(); observed() }
                            override fun executeInsert(): Long = statement.executeInsert().also { observed() }
                            override fun executeUpdateDelete(): Int = statement.executeUpdateDelete().also { observed() }
                        }
                    }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        observe { current.get()?.let { it.successful[it.successful.lastIndex] = true } }
                    }
                    override fun endTransaction() {
                        val tx = current.get()
                        if (tx == null) { raw.endTransaction(); return }
                        val outer = tx.successful.size == 1
                        val successful = tx.successful.last()
                        try {
                            raw.endTransaction()
                            val endedAt = SystemClock.elapsedRealtimeNanos()
                            observe {
                                if (outer && successful && !tx.rolledBackChild && !raw.inTransaction()) completed(tx, endedAt)
                            }
                        } finally {
                            if (outer) current.remove()
                            else observe {
                                tx.successful.removeAt(tx.successful.lastIndex)
                                if (!successful) tx.rolledBackChild = true
                            }
                        }
                    }
                }
            }
            requireNotNull(wrapper)
        }
        val parent = configuration.callback
        val callbacks = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callbacks).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }

    private companion object {
        val UPDATE = Regex("^update\\s+(?:main\\.)?([a-z_]+)\\s+set\\s")
        val INSERT = Regex("^insert(?:\\s+or\\s+\\w+)?\\s+into\\s+(?:main\\.)?([a-z_]+)(?:\\s|\\()")
    }
}
