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

/** Observation only for the fixed two-COUNT/one-metric-group journey. No SQL, pause, fault,
 * authority or receipt is added. SQL counters describe successful native calls; the test
 * independently proves their exact durable effects and binds every command/request ID.
 */
internal class CountBacklogNativeTrace : SupportSQLiteOpenHelper.Factory {
    enum class Kind { T0, COUNT_T1, METRIC_T1, T2, ACK }
    data class End(
        val kind: Kind, val sequence: Long, val nativeIdentity: Int,
        val beginAt: Long, val acquiredAt: Long, val endAt: Long,
        val facts: Int, val metrics: Int,
        val intentIds: List<String>, val outcomeIds: List<String>,
        val statusIds: List<String>, val acceptedRequests: List<String>
    )
    private class Tx(val sequence: Long, val native: SupportSQLiteDatabase, val beginAt: Long) {
        var acquiredAt = 0L
        val successful = arrayListOf(false)
        var rolledBackChild = false
        var facts = 0; var metrics = 0; var intents = 0; var outcomes = 0; var statuses = 0; var acceptances = 0
        val intentIds = arrayListOf<String>()
        val outcomeIds = arrayListOf<String>()
        val statusIds = arrayListOf<String>()
        val acceptedRequests = arrayListOf<String>()
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
    private fun executed(sql: String, first: Any?, second: Any?, last: Any?) {
        if (!armed) return
        val tx = current.get() ?: return
        val text = sql.trim().lowercase(Locale.ROOT).replace("`", "").replace("\"", "")
        if (UPDATE.find(text)?.groupValues?.get(1) == "count_command_status") {
            tx.statuses++; id(tx.statusIds, last, "status")
        }
        when (INSERT.find(text)?.groupValues?.get(1)) {
            "completions" -> tx.facts++
            "metric_logs" -> tx.metrics++
            "count_command_intents" -> { tx.intents++; id(tx.intentIds, first, "intent") }
            "count_command_results" -> { tx.outcomes++; id(tx.outcomeIds, first, "outcome") }
            "next_acceptances" -> { tx.acceptances++; id(tx.acceptedRequests, second, "acceptance") }
        }
    }
    private fun completed(tx: Tx, endAt: Long) {
        val kind = when {
            tx.acceptances > 0 && tx.intents == 0 && tx.outcomes == 0 -> Kind.ACK
            tx.acceptances == 0 && tx.facts == 1 && tx.metrics == 0 && tx.outcomes == 1 -> Kind.COUNT_T1
            tx.acceptances == 0 && tx.facts == 0 && tx.metrics == 2 && tx.outcomes == 1 -> Kind.METRIC_T1
            tx.acceptances == 0 && tx.intents == 1 && tx.facts == 0 && tx.metrics == 0 && tx.outcomes == 0 -> Kind.T0
            tx.acceptances == 0 && tx.intents == 0 && tx.facts == 0 && tx.metrics == 0 && tx.outcomes == 0 && tx.statuses > 0 -> Kind.T2
            else -> null
        }
        if (kind == null) {
            if (tx.facts + tx.metrics + tx.intents + tx.outcomes + tx.statuses + tx.acceptances > 0)
                failure("unclassified_domain_transaction")
            return
        }
        if (tx.intentIds.size != tx.intents || tx.outcomeIds.size != tx.outcomes ||
            tx.statusIds.size != tx.statuses || tx.acceptedRequests.size != tx.acceptances)
            failure("incomplete_statement_identity")
        val end = End(kind, tx.sequence, System.identityHashCode(tx.native), tx.beginAt, tx.acquiredAt, endAt,
            tx.facts, tx.metrics, tx.intentIds.toList(), tx.outcomeIds.toList(), tx.statusIds.toList(), tx.acceptedRequests.toList())
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
                        observe { executed(sql, null, null, null) }
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        raw.execSQL(sql, bindArgs)
                        observe { executed(sql, bindArgs.getOrNull(0), bindArgs.getOrNull(1), bindArgs.lastOrNull()) }
                    }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val text = sql.trim().lowercase(Locale.ROOT).replace("`", "").replace("\"", "")
                        val inserted = INSERT.find(text)?.groupValues?.get(1)
                        val needsFirst = inserted == "count_command_intents" || inserted == "count_command_results"
                        val needsSecond = inserted == "next_acceptances"
                        val needsLast = UPDATE.find(text)?.groupValues?.get(1) == "count_command_status"
                        // Only the identity positions are retained; no payload/BLOB/token copy.
                        var first: String? = null; var second: String? = null; var last: String? = null; var lastIndex = 0
                        fun bound(index: Int, value: String?) {
                            if (needsFirst && index == 1) first = value
                            if (needsSecond && index == 2) second = value
                            if (needsLast && index >= lastIndex) { lastIndex = index; last = value }
                        }
                        fun observed() = observe { executed(sql, first, second, last) }
                        return object : SupportSQLiteStatement by statement {
                            override fun bindNull(index: Int) { statement.bindNull(index); bound(index, null) }
                            override fun bindLong(index: Int, value: Long) { statement.bindLong(index, value); bound(index, null) }
                            override fun bindDouble(index: Int, value: Double) { statement.bindDouble(index, value); bound(index, null) }
                            override fun bindString(index: Int, value: String) { statement.bindString(index, value); bound(index, value) }
                            override fun bindBlob(index: Int, value: ByteArray) { statement.bindBlob(index, value); bound(index, null) }
                            override fun clearBindings() {
                                statement.clearBindings(); first = null; second = null; last = null; lastIndex = 0
                            }
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
