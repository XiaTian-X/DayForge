package com.dayforge.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.*

/** Fixed actual native C boundary only. No SQL mutation, arbitrary callbacks or raw handle.
 * Arms after the real root UPDATE, observes the real successful outer END, and retains the
 * same exception identity. Cohort prevents Room background work consuming the fault. */
internal class CountObsoleteFollowUpNativeBoundary : SupportSQLiteOpenHelper.Factory {
    val cohort = ThreadLocal<Boolean>()
    val rootWrites = AtomicInteger()
    val cancelled = AtomicInteger()
    private val job = AtomicReference<Job?>()
    private val lose = AtomicBoolean()
    private val pause = AtomicBoolean()
    private val pauseThread = ThreadLocal<SupportSQLiteDatabase?>()
    private var reached = java.util.concurrent.CountDownLatch(1)
    private var released = java.util.concurrent.CountDownLatch(1)
    val pausedEnds = AtomicInteger()
    fun pauseAfterRootEnd() { check(!lose.get() && job.get() == null && pause.compareAndSet(false, true)) }
    fun awaitClosingEnd(): Boolean = reached.await(45, java.util.concurrent.TimeUnit.SECONDS)
    fun release() { released.countDown() }
    private val ending = ThreadLocal<SupportSQLiteDatabase?>()
    private val successes = AtomicInteger()
    private val losses = AtomicInteger()
    private val failure = AtomicReference<IllegalStateException?>()
    fun cancelAfterRoot(value: Job) { check(!lose.get() && job.compareAndSet(null, value)) }
    fun loseAfterRootEnd() { check(job.get() == null && lose.compareAndSet(false, true)) }
    private fun afterRoot(raw: SupportSQLiteDatabase, sql: String) {
        if ((cohort.get() != true && !pause.get()) || !sql.startsWith("UPDATE count_command_recovery_roots SET ")) return
        check(raw.inTransaction())
        raw.query("SELECT changes()").use { check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) }
        rootWrites.incrementAndGet()
        job.getAndSet(null)?.let { check(it.isActive); cancelled.incrementAndGet(); it.cancel(CancellationException("C cancelled after real root write")) }
        if (lose.compareAndSet(true, false)) { check(ending.get() == null); ending.set(raw) }
        if (pause.compareAndSet(true, false)) { check(pauseThread.get() == null); pauseThread.set(raw) }
    }
    fun requireLostEnd(error: Throwable) {
        assertTrue(rootWrites.get() >= 1); assertTrue(successes.get() > 0); assertEquals(1, losses.get())
        assertTrue(generateSequence(error) { it.cause }.any { it === failure.get() })
        assertEquals("COMMAND_OBSOLETE_FOLLOWUP_TEST_END_OUTCOME_LOST", requireNotNull(failure.get()).message)
        assertFalse(lose.get())
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
                    override fun execSQL(sql: String) { raw.execSQL(sql); afterRoot(raw, sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) { raw.execSQL(sql, bindArgs); afterRoot(raw, sql) }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        if (ending.get() === raw || pauseThread.get() === raw) successes.incrementAndGet()
                    }
                    override fun endTransaction() {
                        if (pauseThread.get() === raw) {
                            check(raw.inTransaction()); raw.endTransaction(); check(!raw.inTransaction()); pauseThread.remove()
                            check(successes.get() > 0); check(pausedEnds.incrementAndGet() == 1); reached.countDown()
                            check(released.await(45, java.util.concurrent.TimeUnit.SECONDS)) { "COMMAND_FOLLOWUP_END_BARRIER_TIMEOUT" }
                            return
                        }
                        if (ending.get() !== raw) { raw.endTransaction(); return }
                        check(raw.inTransaction())
                        raw.endTransaction()
                        check(!raw.inTransaction())
                        ending.remove()
                        check(successes.get() > 0)
                        check(losses.incrementAndGet() == 1)
                        val error = IllegalStateException("COMMAND_OBSOLETE_FOLLOWUP_TEST_END_OUTCOME_LOST")
                        check(failure.compareAndSet(null, error))
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
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
