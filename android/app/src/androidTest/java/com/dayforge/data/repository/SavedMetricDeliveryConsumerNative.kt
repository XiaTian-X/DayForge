package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteTransactionListener
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*

/** One fixed native ABORT for the first metric-delivery status update after actual metric T1.
 * Never accepts SQL/callbacks or exports a database. Diagnostics explicitly pin the writer.
 */
internal class SavedMetricDeliveryConsumerNative : SupportSQLiteOpenHelper.Factory {
    private data class Cell(val type: Int, val value: String?)
    private data class Rows(val columns: List<String>, val rows: List<List<Cell>>) {
        fun text(row: List<Cell>, name: String): String {
            val cell = row[columns.indexOf(name).also { check(it >= 0) }]
            check(cell.type == Cursor.FIELD_TYPE_STRING); return requireNotNull(cell.value)
        }
    }
    private data class Image(val version: Int, val schemaVersion: Long, val schema: Rows, val tables: Map<String, Rows>)
    private class Ready(val parent: CountCommandReference, val child: String, val native: SupportSQLiteDatabase)
    private class Frame(val native: SupportSQLiteDatabase, val before: Image?) {
        var depth = 1; var outerMarks = 0; var ends = 0
        var metricInserts = 0; var outcomeInserts = 0
        val successful = arrayListOf(false); var rolledBackChild = false
    }
    private class Hit(val ready: Ready, val frame: Frame, val target: String) {
        var error: Throwable? = null; var rollback = false
    }
    val trace = CountJourneyNativeTrace()
    val failed = CompletableDeferred<Unit>()
    private val expected = AtomicReference<CountCommandReference?>()
    private val ready = AtomicReference<Ready?>()
    private val target = AtomicReference<Hit?>()
    private val observationFailure = AtomicReference<Throwable?>()
    private val frames = ThreadLocal<Frame?>()
    @Volatile private var opened: SupportSQLiteDatabase? = null

    fun expectParent(parent: CountCommandReference) {
        check(expected.compareAndSet(null, parent)); trace.arm()
    }
    private fun observe(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            observationFailure.compareAndSet(null, failure); failed.completeExceptionally(failure)
        }
    }
    private fun rows(raw: SupportSQLiteDatabase, query: String): Rows = raw.query(query).use { c ->
        val columns = c.columnNames.toList()
        Rows(columns, buildList {
            while (c.moveToNext()) {
                check(size < 20_000)
                add(columns.indices.map { index ->
                    val type = c.getType(index)
                    Cell(type, when (type) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index).toString()
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toRawBits().toString()
                        Cursor.FIELD_TYPE_STRING -> c.getString(index)
                        Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(c.getBlob(index), Base64.NO_WRAP)
                        else -> error("Unexpected native cell")
                    })
                })
            }
        })
    }
    private fun scalar(raw: SupportSQLiteDatabase, sql: String): Long = raw.query(sql).use {
        check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
        it.getLong(0).also { _ -> check(!it.moveToNext()) }
    }
    private fun image(raw: SupportSQLiteDatabase): Image {
        check(raw.inTransaction())
        val schema = rows(raw, "SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")
        val names = schema.rows.filter { schema.text(it, "type") == "table" }.map { schema.text(it, "name") }
        val tables = names.sorted().associateWith { name ->
            check(name.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*")))
            rows(raw, "SELECT rowid AS __native_rowid__,* FROM main.`$name` ORDER BY rowid")
        }
        return Image(raw.version, scalar(raw, "PRAGMA main.schema_version"), schema, tables)
    }
    /** Raw IMMEDIATE transaction; it never goes through business frame counters. */
    private fun <T> writerDiagnostic(raw: SupportSQLiteDatabase, block: () -> T): T {
        check(!raw.inTransaction())
        raw.beginTransactionNonExclusive()
        var error: Throwable? = null
        try {
            val value = block(); raw.setTransactionSuccessful(); return value
        } catch (failure: Throwable) { error = failure; throw failure }
        finally {
            try { raw.endTransaction() } catch (end: Throwable) {
                if (error == null) throw end else error.addSuppressed(end)
            }
        }
    }
    private fun install(raw: SupportSQLiteDatabase) = writerDiagnostic(raw) {
        raw.execSQL("CREATE TEMP TABLE saved_metric_delivery_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL,parentId TEXT,childId TEXT)")
        raw.execSQL("INSERT INTO saved_metric_delivery_gate VALUES(1,0,NULL,NULL)")
        raw.execSQL("CREATE TEMP TRIGGER saved_metric_delivery_abort BEFORE UPDATE ON main.count_command_status " +
            "WHEN (SELECT enabled FROM saved_metric_delivery_gate WHERE id=1)=1 AND " +
            "OLD.commandId IN ((SELECT parentId FROM saved_metric_delivery_gate WHERE id=1),(SELECT childId FROM saved_metric_delivery_gate WHERE id=1)) " +
            "BEGIN SELECT RAISE(ABORT,'SAVED_METRIC_DELIVERY_TEST_ABORT'); END")
        check(scalar(raw, "SELECT enabled FROM temp.saved_metric_delivery_gate WHERE id=1") == 0L)
    }
    private fun executed(sql: String) {
        if (expected.get() == null) return
        val frame = frames.get() ?: return
        val normalized = sql.trim().lowercase(java.util.Locale.ROOT).replace("`", "").replace("\"", "")
        when (Regex("^insert(?:\\s+or\\s+\\w+)?\\s+into\\s+(?:main\\.)?([a-z_]+)(?:\\s|\\()").find(normalized)?.groupValues?.get(1)) {
            "metric_logs" -> frame.metricInserts++
            "count_command_results" -> frame.outcomeInserts++
        }
    }
    private fun afterMetricEnd(raw: SupportSQLiteDatabase) {
        val parent = expected.get() ?: return
        check(trace.metricsCommitted.isCompleted && !raw.inTransaction())
        val child = writerDiagnostic(raw) {
            val all = image(raw)
            val statuses = all.tables.getValue("count_command_status")
            val row = statuses.rows.single { statuses.text(it, "commandId") == parent.commandId }
            fun decode(value: List<Cell>): CountCommandRecords.Status {
                val bytes = statuses.text(value, "stateJson").toByteArray(Charsets.UTF_8)
                return CountCommandRecords.Status.decode(bytes, bytes.size) { }
            }
            val state = decode(row)
            check(state.inputHash == parent.inputHash && state.execution == CountCommandRecords.Execution.APPLIED && state.pending)
            val child = requireNotNull(state.child)
            val childState = decode(statuses.rows.single { statuses.text(it, "commandId") == child })
            check(childState.parent == parent.commandId && childState.execution == CountCommandRecords.Execution.APPLIED && childState.pending)
            check(all.tables.getValue("metric_logs").rows.size == 2)
            check(scalar(raw, "SELECT enabled FROM temp.saved_metric_delivery_gate WHERE id=1") == 0L)
            child
        }
        check(ready.compareAndSet(null, Ready(parent, child, raw)))
    }
    private fun beforeStatus(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?>): Hit? {
        val possible = ready.get() ?: return null
        val normalized = sql.trim().lowercase(java.util.Locale.ROOT).replace("`", "")
        if (!normalized.startsWith("update count_command_status set ")) return null
        val id = args.lastOrNull() as? String ?: return null
        if (id != possible.parent.commandId && id != possible.child) return null
        val frame = requireNotNull(frames.get())
        check(raw === opened && raw === possible.native && frame.native === raw && raw.inTransaction())
        // The image was captured immediately after this exact outer BEGIN, before its caller's DML.
        requireNotNull(frame.before)
        check(ready.compareAndSet(possible, null))
        val hit = Hit(possible, frame, id)
        check(target.compareAndSet(null, hit))
        check(scalar(raw, "SELECT enabled FROM temp.saved_metric_delivery_gate WHERE id=1") == 0L)
        raw.execSQL("UPDATE temp.saved_metric_delivery_gate SET enabled=1,parentId=?,childId=? WHERE id=1",
            arrayOf(possible.parent.commandId, possible.child))
        return hit
    }
    fun requireHealthy() {
        observationFailure.get()?.let { throw AssertionError("Native observation failed", it) }
    }
    fun requireRollback(parent: CountCommandReference, child: CountCommandReference) {
        requireHealthy()
        val hit = requireNotNull(target.get())
        assertEquals(parent, hit.ready.parent); assertEquals(child.commandId, hit.ready.child)
        assertTrue(hit.target in setOf(parent.commandId, child.commandId))
        assertTrue(hit.error is SQLiteConstraintException)
        assertTrue(requireNotNull(hit.error).message.orEmpty().contains("SAVED_METRIC_DELIVERY_TEST_ABORT"))
        assertEquals(0, hit.frame.outerMarks); assertEquals(1, hit.frame.ends); assertTrue(hit.rollback)
        assertTrue(trace.metricsCommitted.isCompleted); assertFalse(trace.delivered.isCompleted)
        assertEquals(1, trace.ends.count { it.kind == CountJourneyNativeTrace.Kind.METRIC_T1 })
        assertTrue(trace.observationFailures.isEmpty())
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null; var wrapped: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native === raw) return@synchronized requireNotNull(wrapped)
            native = raw; opened = raw
            object : SupportSQLiteDatabase by raw {
                private fun begin(action: () -> Unit) {
                    val outer = !raw.inTransaction()
                    action() // The original BEGIN/listener always returns/throws unchanged.
                    observe {
                        if (outer) frames.set(Frame(raw, if (ready.get() != null) image(raw) else null))
                        else frames.get()?.let { it.depth++; it.successful.add(false) }
                    }
                }
                override fun beginTransaction() = begin { raw.beginTransaction() }
                override fun beginTransactionNonExclusive() = begin { raw.beginTransactionNonExclusive() }
                override fun beginTransactionReadOnly() = begin { raw.beginTransactionReadOnly() }
                override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListener(transactionListener) }
                override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) = begin { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                override fun compileStatement(sql: String): SupportSQLiteStatement {
                    val statement = raw.compileStatement(sql)
                    return object : SupportSQLiteStatement by statement {
                        override fun execute() { statement.execute(); observe { executed(sql) } }
                        override fun executeInsert(): Long = statement.executeInsert().also { observe { executed(sql) } }
                        override fun executeUpdateDelete(): Int = statement.executeUpdateDelete().also { observe { executed(sql) } }
                    }
                }
                override fun execSQL(sql: String) { raw.execSQL(sql); observe { executed(sql) } }
                override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                    var hit: Hit? = null
                    observe { hit = beforeStatus(raw, sql, bindArgs) }
                    try { raw.execSQL(sql, bindArgs); observe { executed(sql) } } catch (original: Throwable) {
                        hit?.error = original
                        throw original // Same actual SQLite exception, never hand-manufactured.
                    }
                }
                override fun setTransactionSuccessful() {
                    raw.setTransactionSuccessful()
                    observe { frames.get()?.let {
                        it.successful[it.successful.lastIndex] = true
                        if (it.depth == 1) it.outerMarks++
                    } }
                }
                override fun endTransaction() {
                    val frame = frames.get()
                    try { raw.endTransaction() } catch (original: Throwable) {
                        frames.remove(); failed.completeExceptionally(original); throw original
                    }
                    observe {
                        if (frame != null && --frame.depth == 0) {
                            frames.remove()
                            val hit = target.get()
                            if (hit != null && hit.frame === frame) {
                                check(!raw.inTransaction()); frame.ends++
                                check(hit.error is SQLiteConstraintException && frame.outerMarks == 0)
                                val after = writerDiagnostic(raw) {
                                    check(scalar(raw, "SELECT enabled FROM temp.saved_metric_delivery_gate WHERE id=1") == 0L)
                                    image(raw)
                                }
                                check(requireNotNull(frame.before) == after) { "Actual T2 rollback changed MAIN" }
                                hit.rollback = true; failed.complete(Unit)
                            }
                            if (frame.metricInserts == 2 && frame.outcomeInserts == 1 &&
                                frame.outerMarks == 1 && !frame.rolledBackChild) {
                                check(!raw.inTransaction())
                                afterMetricEnd(raw) // Only this exact frame's successful native END can issue readiness.
                            }
                        } else if (frame != null) {
                            if (!frame.successful.removeAt(frame.successful.lastIndex)) frame.rolledBackChild = true
                        }
                    }
                }
            }.also { wrapped = it }
        }
        val parent = configuration.callback
        val callbacks = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) { parent.onOpen(wrap(db)); install(db) }
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = trace.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callbacks).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
