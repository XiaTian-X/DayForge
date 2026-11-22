package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed real-native phase observation/fault. Reuses the original journey trace to classify
 * actual successful outer END; neither a callback nor this evidence issues a receipt. */
internal class MetricSubmissionPhaseNativeBoundary : SupportSQLiteOpenHelper.Factory {
    enum class Mode { PAUSE_T2_BEFORE_STATUS, LOSE_METRIC_END, LOSE_T2_END }
    data class Cell(val type: Int, val value: String?)
    data class Rows(val columns: List<String>, val rows: List<List<Cell>>) {
        fun text(row: List<Cell>, name: String): String {
            val cell = row[columns.indexOf(name).also { check(it >= 0) }]
            check(cell.type == Cursor.FIELD_TYPE_STRING)
            return requireNotNull(cell.value)
        }
    }
    data class Image(val tables: Map<String, Rows>)
    val trace = CountJourneyNativeTrace()
    private val mode = AtomicReference<Mode?>()
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val metricDml = ThreadLocal<Boolean>()
    private val pausedMetricEnd = AtomicBoolean()
    @Volatile var image: Image? = null
        private set
    @Volatile var failure: IllegalStateException? = null
        private set
    fun arm(value: Mode) { check(mode.compareAndSet(null, value)); trace.arm() }
    fun awaitBoundary(): Boolean = entered.await(45, TimeUnit.SECONDS)
    fun release() { released.countDown() }

    private fun capture(raw: SupportSQLiteDatabase): Image = Image(listOf(
        "count_command_intents", "count_command_status", "count_command_results", "metric_logs"
    ).associateWith { table ->
        raw.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            val columns = cursor.columnNames.toList()
            val rows = buildList {
                while (cursor.moveToNext()) {
                    check(size < 64) { "PHASE_TEST_UNEXPECTED_NATIVE_INVENTORY" }
                    add(columns.indices.map { column ->
                        val type = cursor.getType(column)
                        Cell(type, when (type) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column).toString()
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits().toString()
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                            else -> error("PHASE_TEST_UNEXPECTED_NATIVE_TYPE")
                        })
                    })
                }
            }
            Rows(columns, rows)
        }
    })

    private fun before(raw: SupportSQLiteDatabase, sql: String) {
        val normalized = sql.trim().lowercase(java.util.Locale.ROOT).replace("`", "").replace("\"", "")
        if (!trace.metricsCommitted.isCompleted || !normalized.startsWith("update count_command_status set ") ||
            !mode.compareAndSet(Mode.PAUSE_T2_BEFORE_STATUS, null)) return
        check(raw.inTransaction())
        // Actual T1 already ended; the first T2 status DML has not run. Fixed SELECTs
        // only observe that same native transaction and do not enter the public Room API.
        image = capture(raw)
        entered.countDown()
        check(released.await(45, TimeUnit.SECONDS)) { "PHASE_TEST_T2_BARRIER_TIMEOUT" }
    }
    private fun executed(sql: String) {
        val normalized = sql.trim().lowercase(java.util.Locale.ROOT).replace("`", "").replace("\"", "")
        if (Regex("^insert(?:\\s+or\\s+\\w+)?\\s+into\\s+(?:main\\.)?metric_logs(?:\\s|\\()").containsMatchIn(normalized))
            metricDml.set(true)
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var source: SupportSQLiteDatabase? = null
        var wrapped: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (source !== raw) {
                source = raw
                wrapped = object : SupportSQLiteDatabase by raw {
                    override fun execSQL(sql: String) { before(raw, sql); raw.execSQL(sql); executed(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        before(raw, sql); raw.execSQL(sql, bindArgs); executed(sql)
                    }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() { before(raw, sql); statement.execute(); executed(sql) }
                            override fun executeInsert(): Long { before(raw, sql); return statement.executeInsert().also { executed(sql) } }
                            override fun executeUpdateDelete(): Int { before(raw, sql); return statement.executeUpdateDelete().also { executed(sql) } }
                        }
                    }
                    override fun endTransaction() {
                        if (mode.get() == Mode.LOSE_METRIC_END && metricDml.get() == true &&
                            pausedMetricEnd.compareAndSet(false, true)) {
                            check(raw.inTransaction()); entered.countDown()
                            check(released.await(45, TimeUnit.SECONDS)) { "PHASE_TEST_T1_BARRIER_TIMEOUT" }
                        }
                        val count = trace.ends.size
                        raw.endTransaction() // The original trace records only after actual native END.
                        if (!raw.inTransaction()) metricDml.remove()
                        if (trace.ends.size == count) return
                        val end = trace.ends.last()
                        val expected = when (end.kind) {
                            CountJourneyNativeTrace.Kind.METRIC_T1 -> Mode.LOSE_METRIC_END
                            CountJourneyNativeTrace.Kind.T2 -> Mode.LOSE_T2_END
                            else -> null
                        }
                        if (expected == null || !mode.compareAndSet(expected, null)) return
                        check(!raw.inTransaction())
                        image = capture(raw)
                        val error = IllegalStateException("PHASE_TEST_${end.kind}_END_OUTCOME_LOST")
                        check(failure == null); failure = error
                        entered.countDown()
                        throw error
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
        val helper = trace.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
