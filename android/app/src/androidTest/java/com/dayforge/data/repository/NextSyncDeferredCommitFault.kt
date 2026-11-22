package com.dayforge.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*

/** One fixed deferred FK fault below the real controlled factory. The ordinary builder,
 * onOpen, Root checks and transaction listeners all run unchanged. TEMP setup is opt-in for
 * one reopen only; other methods in the complete runtime class install no extra objects.
 * No raw handle or arbitrary SQL callback escapes this helper. */
internal class NextSyncDeferredCommitFault : SupportSQLiteOpenHelper.Factory {
    private val installNextOpen = AtomicBoolean()
    private val armed = AtomicBoolean()
    private val installations = AtomicInteger()
    private val updates = AtomicInteger()
    private val successes = AtomicInteger()
    private val endConstraints = AtomicInteger()
    private val originalFailure = AtomicReference<SQLiteConstraintException?>()
    private val attempt = ThreadLocal<SupportSQLiteDatabase?>()

    fun installOnNextOpen() {
        check(installations.get() == 0 && installNextOpen.compareAndSet(false, true))
    }

    fun arm() {
        assertEquals(1, installations.get())
        assertFalse(installNextOpen.get())
        check(updates.get() == 0 && armed.compareAndSet(false, true))
    }

    fun requireActualCommitConstraint(failure: Throwable) {
        assertEquals("The generated cursor UPDATE really ran", 1, updates.get())
        assertTrue("The original transaction reached native setTransactionSuccessful", successes.get() > 0)
        assertEquals("SQLite itself rejected the actual END", 1, endConstraints.get())
        val original = requireNotNull(originalFailure.get())
        assertTrue("The caller receives the original engine failure", generateSequence(failure) { it.cause }.any { it === original })
        assertFalse(armed.get())
    }

    fun requireNotReinstalled() = assertEquals(1, installations.get())

    private fun scalar(raw: SupportSQLiteDatabase, sql: String): Long = raw.query(sql).use { cursor ->
        check(cursor.moveToFirst() && cursor.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER)
        cursor.getLong(0).also { check(!cursor.moveToNext()) }
    }

    private fun install(raw: SupportSQLiteDatabase) {
        check(!raw.inTransaction())
        check(scalar(raw, "SELECT count(*) FROM main.sqlite_master WHERE type='table' AND name='next_sync_state'") == 1L)
        raw.execSQL("CREATE TEMP TABLE cursor_commit_gate(id INTEGER PRIMARY KEY, enabled INTEGER NOT NULL)")
        raw.execSQL("INSERT INTO temp.cursor_commit_gate VALUES(1,0)")
        raw.execSQL("CREATE TEMP TABLE cursor_commit_parent(id INTEGER PRIMARY KEY)")
        raw.execSQL("CREATE TEMP TABLE cursor_commit_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES cursor_commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        raw.execSQL("CREATE TEMP TRIGGER fail_cursor_commit AFTER UPDATE ON main.next_sync_state " +
            "WHEN (SELECT enabled FROM cursor_commit_gate WHERE id=1)=1 " +
            "BEGIN INSERT INTO cursor_commit_child VALUES(1,99); END")
        check(installations.incrementAndGet() == 1)
    }

    private fun <T> execute(raw: SupportSQLiteDatabase, action: () -> T): T {
        if (!armed.compareAndSet(true, false)) return action()
        check(raw.inTransaction() && attempt.get() == null)
        check(scalar(raw, "PRAGMA foreign_keys") == 1L)
        check(scalar(raw, "SELECT count(*) FROM temp.cursor_commit_child") == 0L)
        check(scalar(raw, "SELECT count(*) FROM temp.cursor_commit_parent") == 0L)
        check(scalar(raw, "SELECT cursor FROM main.next_sync_state WHERE id=1") == 20L)
        raw.execSQL("UPDATE temp.cursor_commit_gate SET enabled=1 WHERE id=1")
        check(scalar(raw, "SELECT changes()") == 1L)
        val result = action()
        check(raw.inTransaction())
        check(scalar(raw, "SELECT changes()") == 1L)
        check(scalar(raw, "SELECT cursor FROM main.next_sync_state WHERE id=1") == 21L)
        check(scalar(raw, "SELECT count(*) FROM temp.cursor_commit_child WHERE id=1 AND parent=99") == 1L)
        raw.query("PRAGMA temp.foreign_key_check(cursor_commit_child)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "cursor_commit_child" &&
                cursor.getLong(1) == 1L && cursor.getString(2) == "cursor_commit_parent" && !cursor.moveToNext())
        }
        attempt.set(raw)
        check(updates.incrementAndGet() == 1)
        return result
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
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        // Always wrap the exact generated statement, including cached-before-arm use.
                        if (sql != UPDATE_CURSOR) return statement
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() = execute(raw) { statement.execute() }
                            override fun executeInsert() = execute(raw) { statement.executeInsert() }
                            override fun executeUpdateDelete() = execute(raw) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String) {
                        if (sql == UPDATE_CURSOR) execute(raw) { raw.execSQL(sql) } else raw.execSQL(sql)
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        if (sql == UPDATE_CURSOR) execute(raw) { raw.execSQL(sql, bindArgs) } else raw.execSQL(sql, bindArgs)
                    }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        if (attempt.get() === raw) successes.incrementAndGet()
                    }
                    override fun endTransaction() {
                        if (attempt.get() !== raw) { raw.endTransaction(); return }
                        check(raw.inTransaction())
                        try {
                            raw.endTransaction()
                        } catch (failure: SQLiteConstraintException) {
                            // This is the engine's original COMMIT error, never a synthetic throw.
                            check(failure.message?.contains("FOREIGN KEY constraint failed") == true)
                            check(successes.get() > 0 && originalFailure.compareAndSet(null, failure))
                            endConstraints.incrementAndGet()
                            throw failure
                        } finally {
                            // Native physical END is not a commit/rollback certificate. Only the
                            // independent cold owner and complete main image decide persistence.
                            if (!raw.inTransaction()) attempt.remove()
                        }
                    }
                }
            }
            requireNotNull(wrapped)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) {
                parent.onOpen(wrap(db))
                if (installNextOpen.compareAndSet(true, false)) install(db)
            }
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }

    private companion object {
        const val UPDATE_CURSOR = "UPDATE OR ABORT `next_sync_state` SET `accountId` = ?,`serverInstanceId` = ?,`syncEpoch` = ?,`deviceId` = ?,`generation` = ?,`cursor` = ?,`bootstrapHash` = ?,`batchHash` = ?,`id` = ?,`challengeContract` = ? WHERE `id` = ?"
    }
}
