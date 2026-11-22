package com.dayforge.data.repository

import android.database.sqlite.SQLiteTransactionListener
import android.os.SystemClock
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/** Observation only, below the unchanged production controlled factory. No SQL is added,
 * no transaction is paused and no trace is an authority/receipt. Terminal original readers
 * independently bind each recorded native phase to the one synthetic parent/child group. */
internal class CountJourneyNativeTrace : SupportSQLiteOpenHelper.Factory {
    enum class Kind { T0, COUNT_T1, METRIC_T1, T2, ACK }
    data class End(val sequence: Long, val nativeIdentity: Int, val kind: Kind, val beginAt: Long, val acquiredAt: Long,
        val endAt: Long, val facts: Int, val metrics: Int, val outcomes: Int, val statusUpdates: Int,
        val acceptances: Int, val acceptedRequests: List<String>)
    private class Tx(val sequence: Long, val native: SupportSQLiteDatabase, val beginAt: Long) {
        var acquiredAt = 0L
        val successful = arrayListOf(false)
        var rolledBackChild = false
        var intents = 0; var facts = 0; var metrics = 0; var outcomes = 0; var statuses = 0; var acceptances = 0
        val acceptedRequests = arrayListOf<String>()
    }
    private val sequence = AtomicLong()
    private val current = ThreadLocal<Tx?>()
    @Volatile private var armed = false
    val ends = CopyOnWriteArrayList<End>()
    val observationFailures = CopyOnWriteArrayList<String>()
    val countCommitted = CompletableDeferred<End>()
    val metricsCommitted = CompletableDeferred<End>()
    val delivered = CompletableDeferred<End>()
    fun arm() { check(!armed && ends.isEmpty()); armed = true }
    private fun completeOnce(signal: CompletableDeferred<End>, end: End, failure: String) {
        if (!signal.complete(end)) observationFailures.addIfAbsent(failure)
    }
    private fun executed(sql: String, acceptedRequest: String? = null) {
        if (!armed) return
        val tx = current.get() ?: return
        val text = sql.trim().lowercase(java.util.Locale.ROOT).replace("`", "").replace("\"", "")
        if (UPDATE.find(text)?.groupValues?.get(1) == "count_command_status") tx.statuses++
        when (INSERT.find(text)?.groupValues?.get(1)) {
            "count_command_intents" -> tx.intents++
            "completions" -> tx.facts++
            "metric_logs" -> tx.metrics++
            "count_command_results" -> tx.outcomes++
            "next_acceptances" -> {
                tx.acceptances++
                if (acceptedRequest == null) observationFailures.addIfAbsent("missing_acceptance_request")
                else tx.acceptedRequests += acceptedRequest
            }
        }
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var original: SupportSQLiteDatabase? = null; var wrapper: SupportSQLiteDatabase? = null
        val lock = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(lock) {
            if (original !== raw) {
                original = raw
                wrapper = object : SupportSQLiteDatabase by raw {
                    private fun begin(block: () -> Unit) {
                        val prior = current.get()
                        if (!armed && prior == null) { block(); return }
                        val tx = prior ?: Tx(sequence.incrementAndGet(), raw, SystemClock.elapsedRealtimeNanos()).also(current::set)
                        check(tx.native === raw) { "Foreign native transaction entered trace" }
                        if (prior != null) tx.successful.add(false)
                        try {
                            block()
                            if (prior == null) tx.acquiredAt = SystemClock.elapsedRealtimeNanos()
                        } catch (error: Throwable) {
                            if (prior == null) current.remove() else tx.successful.removeAt(tx.successful.lastIndex)
                            throw error
                        }
                    }
                    override fun beginTransaction() = begin { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = begin { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun execSQL(sql: String) { raw.execSQL(sql); executed(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        raw.execSQL(sql, bindArgs); executed(sql, bindArgs.getOrNull(1) as? String)
                    }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val acceptance = sql.contains("next_acceptances", ignoreCase = true)
                        var requestId: String? = null
                        return object : SupportSQLiteStatement by statement {
                            override fun bindString(index: Int, value: String) {
                                statement.bindString(index, value)
                                if (acceptance && index == 2) requestId = value
                            }
                            override fun clearBindings() { statement.clearBindings(); requestId = null }
                            override fun execute() { statement.execute(); executed(sql, requestId) }
                            override fun executeInsert(): Long = statement.executeInsert().also { executed(sql, requestId) }
                            override fun executeUpdateDelete(): Int = statement.executeUpdateDelete().also { executed(sql, requestId) }
                        }
                    }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        current.get()?.let { it.successful[it.successful.lastIndex] = true }
                    }
                    override fun endTransaction() {
                        val tx = current.get()
                        if (tx == null) { raw.endTransaction(); return }
                        val outer = tx.successful.size == 1; val successful = tx.successful.last()
                        try {
                            raw.endTransaction() // Exceptions propagate and produce no successful End.
                            if (outer && successful && !tx.rolledBackChild && !raw.inTransaction()) {
                                val kind = when {
                                    tx.facts == 1 && tx.metrics == 0 && tx.outcomes == 1 -> Kind.COUNT_T1
                                    tx.facts == 0 && tx.metrics == 2 && tx.outcomes == 1 -> Kind.METRIC_T1
                                    tx.intents == 1 && tx.facts == 0 && tx.metrics == 0 && tx.outcomes == 0 -> Kind.T0
                                    tx.intents == 0 && tx.facts == 0 && tx.metrics == 0 && tx.outcomes == 0 && tx.statuses >= 2 -> Kind.T2
                                    tx.acceptances > 0 -> Kind.ACK
                                    else -> null
                                }
                                if (kind != null) {
                                    val end = End(tx.sequence, System.identityHashCode(raw), kind, tx.beginAt, tx.acquiredAt, SystemClock.elapsedRealtimeNanos(),
                                        tx.facts, tx.metrics, tx.outcomes, tx.statuses, tx.acceptances, tx.acceptedRequests.toList())
                                    if (ends.size < 128) ends.add(end) else observationFailures.addIfAbsent("trace_capacity")
                                    when (kind) {
                                        Kind.COUNT_T1 -> completeOnce(countCommitted, end, "second_count_t1")
                                        Kind.METRIC_T1 -> completeOnce(metricsCommitted, end, "second_metric_t1")
                                        Kind.T2 -> completeOnce(delivered, end, "second_delivery_t2")
                                        else -> Unit
                                    }
                                }
                            }
                        } finally {
                            if (outer) current.remove() else {
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
    companion object {
        private val UPDATE = Regex("^update\\s+(?:main\\.)?([a-z_]+)\\s+set\\s")
        private val INSERT = Regex("^insert(?:\\s+or\\s+\\w+)?\\s+into\\s+(?:main\\.)?([a-z_]+)(?:\\s|\\()")
    }
}
