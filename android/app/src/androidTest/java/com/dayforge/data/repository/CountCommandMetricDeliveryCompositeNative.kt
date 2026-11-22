package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Test-only real native counts and fixed same-value status/outcome UPDATE faults.
 * Never exposes a raw connection, alters a production branch or fabricates END success. */
internal class CountCommandMetricDeliveryCompositeNative : SupportSQLiteOpenHelper.Factory {
    val cohort = ThreadLocal<Boolean>()
    private val extra = AtomicBoolean()
    private val prepareOutcome = AtomicReference<String?>()
    private val roots = AtomicInteger()
    private val anchors = AtomicInteger()
    private val statuses = AtomicInteger()
    private val nodes = AtomicInteger()
    private val extras = AtomicInteger()
    data class Counts(val roots: Int, val anchors: Int, val statuses: Int, val nodes: Int, val extras: Int)
    fun reset(extraStatusWrite: Boolean = false, prepareOutcomeId: String? = null) {
        check(!extraStatusWrite || prepareOutcomeId == null)
        roots.set(0); anchors.set(0); statuses.set(0); nodes.set(0); extras.set(0); extra.set(extraStatusWrite)
        prepareOutcome.set(prepareOutcomeId)
    }
    fun counts() = Counts(roots.get(), anchors.get(), statuses.get(), nodes.get(), extras.get())
    fun build(context: Context) = buildDiagnosticFaultDatabase(context, this)
    private fun normalized(sql: String) = sql.trim().replace("`", "").lowercase(Locale.ROOT)
    private fun after(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?>) {
        if (cohort.get() != true) return
        when {
            normalized(sql).startsWith("update count_command_recovery_roots set ") -> roots.incrementAndGet()
            normalized(sql).startsWith("insert into count_command_nodes") -> nodes.incrementAndGet()
            normalized(sql).startsWith("update count_command_status set ") -> {
                statuses.incrementAndGet()
                if (extra.compareAndSet(true, false)) {
                    check(raw.inTransaction())
                    val id = args.last() as String
                    raw.execSQL("UPDATE count_command_status SET revision=revision WHERE commandId=?", arrayOf(id))
                    raw.query("SELECT changes()").use { check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) }
                    extras.incrementAndGet()
                }
            }
        }
    }
    private fun afterRead(raw: SupportSQLiteDatabase, query: String, cursor: Cursor): Cursor {
        if (cohort.get() != true || prepareOutcome.get() == null || !normalized(query).startsWith(
                "select commandid,inputhash,factuuid,requestid,resultjson,state from count_command_results where commandid=?")) return cursor
        return object : CursorWrapper(cursor) {
            private var closed = false
            private var targetRead = false
            override fun moveToFirst(): Boolean = super.moveToFirst().also { found ->
                targetRead = found && getType(0) == Cursor.FIELD_TYPE_STRING && getString(0) == prepareOutcome.get()
            }
            override fun close() {
                super.close()
                if (closed || !targetRead) return
                closed = true
                val id = prepareOutcome.getAndSet(null) ?: return
                check(raw.inTransaction() && roots.get() == 0 && statuses.get() == 0)
                raw.execSQL("UPDATE count_command_results SET state=state WHERE commandId=?", arrayOf(id))
                raw.query("SELECT changes()").use { check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) }
                this@CountCommandMetricDeliveryCompositeNative.extras.incrementAndGet()
            }
        }
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null
        var wrapped: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native !== raw) {
                native = raw
                wrapped = object : SupportSQLiteDatabase by raw {
                    override fun query(query: String): Cursor = afterRead(raw, query, raw.query(query))
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = afterRead(raw, query, raw.query(query, bindArgs))
                    override fun query(query: SupportSQLiteQuery): Cursor = afterRead(raw, query.sql, raw.query(query))
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                        afterRead(raw, query.sql, raw.query(query, cancellationSignal))
                    override fun execSQL(sql: String) { raw.execSQL(sql); after(raw, sql, emptyArray()) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        raw.execSQL(sql, bindArgs); after(raw, sql, bindArgs)
                    }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        if (!normalized(sql).startsWith("update count_trust_control set commandroothash=")) return statement
                        return object : SupportSQLiteStatement by statement {
                            override fun executeUpdateDelete(): Int = statement.executeUpdateDelete().also {
                                if (cohort.get() == true) anchors.incrementAndGet()
                            }
                        }
                    }
                }
            }
            requireNotNull(wrapped)
        }
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = original.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
