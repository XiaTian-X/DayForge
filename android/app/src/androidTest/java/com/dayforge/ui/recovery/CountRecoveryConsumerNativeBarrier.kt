package com.dayforge.ui.recovery

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One fixed observation barrier below the controlled factory. No caller SQL/row injection,
 * callbacks or raw handles. Stops only after the real outer END containing a real Outcome INSERT. */
internal class CountRecoveryConsumerNativeBarrier : SupportSQLiteOpenHelper.Factory {
    @Volatile private var armed = false
    @Volatile private var outcomeThread: Thread? = null
    @Volatile var committedOutcomeInserts = 0; private set
    private val reached = CountDownLatch(1)
    private val released = CountDownLatch(1)
    fun arm() { check(!armed && committedOutcomeInserts == 0); armed = true }
    fun awaitCommitted(): Boolean = reached.await(45, TimeUnit.SECONDS)
    fun release() { released.countDown() }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        var current: SupportSQLiteDatabase? = null; var wrapper: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (current !== raw) {
                current = raw
                wrapper = object : SupportSQLiteDatabase by raw {
                    private fun after(sql: String) {
                        if (!armed) return
                        val normalized = sql.lowercase(Locale.ROOT).replace("`", "").replace("\"", "")
                        if (normalized.startsWith("insert into count_command_results ")) {
                            check(raw.inTransaction())
                            raw.query("SELECT changes()").use { check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) }
                            check(outcomeThread == null); outcomeThread = Thread.currentThread()
                        }
                    }
                    override fun execSQL(sql: String) { raw.execSQL(sql); after(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) { raw.execSQL(sql, bindArgs); after(sql) }
                    override fun endTransaction() {
                        raw.endTransaction()
                        if (armed && outcomeThread === Thread.currentThread() && !raw.inTransaction()) {
                            // Verify the actual committed native image, not a merely attempted INSERT.
                            raw.query("SELECT count(*) FROM main.count_command_results").use {
                                check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext())
                            }
                            armed = false; outcomeThread = null; committedOutcomeInserts++
                            reached.countDown()
                            check(released.await(45, TimeUnit.SECONDS)) { "COUNT_RECOVERY_CONSUMER_BARRIER_TIMEOUT" }
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
