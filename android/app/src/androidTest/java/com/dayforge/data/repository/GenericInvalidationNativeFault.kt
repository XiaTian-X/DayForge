package com.dayforge.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.*

/** Two fixed native fault points only: real INVALIDATED Status UPDATE, and its successful
 * outer END. No arbitrary SQL or callback is exposed, and each delegated write runs once. */
internal class GenericInvalidationNativeFault : SupportSQLiteOpenHelper.Factory {
    private enum class Fault { CANCEL, LOSE_END }
    @Volatile private var armed: Fault? = null
    @Volatile private var command: String? = null
    @Volatile private var cancelled: Job? = null
    @Volatile private var writing: Thread? = null
    @Volatile private var successful = false
    @Volatile var statusWrites = 0; private set
    @Volatile var cancelSignals = 0; private set
    @Volatile private var lostEnds = 0
    private val lost = IllegalStateException("COMMAND_INVALIDATION_TEST_COMMITTED_END_LOST")
    fun cancelAfterStatus(id: String, job: Job) { check(armed == null && statusWrites == 0); command = id; cancelled = job; armed = Fault.CANCEL }
    fun loseCommittedEnd(id: String) { check(armed == null && statusWrites == 0); command = id; armed = Fault.LOSE_END }
    fun requireActualLostEnd(error: Throwable) {
        assertEquals(1, statusWrites); assertEquals(1, lostEnds); assertTrue(successful)
        assertTrue(generateSequence(error) { it.cause }.any { it === lost })
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null; var wrapped: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native !== raw) {
                native = raw
                wrapped = object : SupportSQLiteDatabase by raw {
                    private fun after(sql: String, bindings: Array<out Any?>) {
                        val fault = armed ?: return
                        val normalized = sql.lowercase(Locale.ROOT).replace("`", "").replace("\"", "")
                        if (!normalized.startsWith("update count_command_status set ") || bindings.getOrNull(2) != "INVALIDATED" ||
                            bindings.lastOrNull() != command || statusWrites != 0) return
                        check(raw.inTransaction())
                        raw.query("SELECT changes()").use { check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) }
                        statusWrites++
                        when (fault) {
                            Fault.CANCEL -> {
                                armed = null; val job = requireNotNull(cancelled); cancelled = null
                                check(job.isActive); cancelSignals++; job.cancel(CancellationException("COMMAND_INVALIDATION_TEST_CANCELLED_AT_STATUS"))
                            }
                            Fault.LOSE_END -> writing = Thread.currentThread()
                        }
                    }
                    override fun execSQL(sql: String) { raw.execSQL(sql); after(sql, emptyArray()) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) { raw.execSQL(sql, bindArgs); after(sql, bindArgs) }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        if (writing === Thread.currentThread()) successful = true
                    }
                    override fun endTransaction() {
                        raw.endTransaction()
                        if (armed == Fault.LOSE_END && writing === Thread.currentThread() && successful && !raw.inTransaction()) {
                            raw.query("SELECT execution FROM main.count_command_status WHERE commandId=?", arrayOf(requireNotNull(command))).use {
                                check(it.moveToFirst() && it.getString(0) == "INVALIDATED" && !it.moveToNext())
                            }
                            armed = null; writing = null; lostEnds++; throw lost
                        }
                    }
                }
            }
            requireNotNull(wrapped)
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
