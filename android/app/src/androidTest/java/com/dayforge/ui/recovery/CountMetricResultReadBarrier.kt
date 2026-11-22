package com.dayforge.ui.recovery

import android.database.Cursor
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fixed native read-END barrier for the exact original parent status. No DML, arbitrary SQL,
 * raw-handle escape, producer callback or continuation interception. */
internal class CountMetricResultReadBarrier : SupportSQLiteOpenHelper.Factory {
    @Volatile private var commandId: String? = null
    @Volatile private var reading: Thread? = null
    @Volatile private var successful = false
    @Volatile var ends = 0; private set
    private val reached = CountDownLatch(1)
    private val released = CountDownLatch(1)
    fun arm(id: String) { check(commandId == null && ends == 0); commandId = id }
    fun awaitReadEnd(): Boolean = reached.await(45, TimeUnit.SECONDS)
    fun release() { released.countDown() }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        var current: SupportSQLiteDatabase? = null; var wrapper: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (current !== raw) {
                current = raw
                wrapper = object : SupportSQLiteDatabase by raw {
                    private fun observe(query: SupportSQLiteQuery) {
                        val expected = commandId ?: return
                        if (reading != null || !raw.inTransaction()) return
                        val sql = query.sql.lowercase(Locale.ROOT).replace("`", "").replace("\"", "").replace(Regex("\\s+"), " ")
                        if (!sql.startsWith("select commandid,inputhash,revision,execution,delivery,") ||
                            !sql.endsWith("from count_command_status where commandid=? limit 2")) return
                        var same = false
                        query.bindTo(object : SupportSQLiteProgram {
                            override fun bindString(index: Int, value: String) { check(index == 1); same = value == expected }
                            override fun bindNull(index: Int) { error("UNEXPECTED_STATUS_BIND_TYPE") }
                            override fun bindLong(index: Int, value: Long) { error("UNEXPECTED_STATUS_BIND_TYPE") }
                            override fun bindDouble(index: Int, value: Double) { error("UNEXPECTED_STATUS_BIND_TYPE") }
                            override fun bindBlob(index: Int, value: ByteArray) { error("UNEXPECTED_STATUS_BIND_TYPE") }
                            override fun clearBindings() { same = false }
                            override fun close() = Unit
                        })
                        if (same) reading = Thread.currentThread()
                    }
                    override fun query(query: SupportSQLiteQuery): Cursor = raw.query(query).also { observe(query) }
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                        raw.query(query, cancellationSignal).also { observe(query) }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        if (reading === Thread.currentThread()) successful = true
                    }
                    override fun endTransaction() {
                        raw.endTransaction()
                        if (commandId != null && successful && reading === Thread.currentThread() && !raw.inTransaction()) {
                            commandId = null; reading = null; successful = false; ends++
                            reached.countDown()
                            check(released.await(45, TimeUnit.SECONDS)) { "COUNT_METRIC_RESULT_READ_BARRIER_TIMEOUT" }
                        }
                    }
                }
            }
            return requireNotNull(wrapper)
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
}
