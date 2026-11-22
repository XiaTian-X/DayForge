package com.dayforge.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*

/** Fixed TEMP RAISE(IGNORE) fault below the real controlled factory, for one round-page
 * test only. No main DDL, Root repair, synthetic exception, alternate writer or raw handle.
 * Other methods in the complete class do not install these TEMP objects. */
internal class NextRoundCursorIgnoreFault : SupportSQLiteOpenHelper.Factory {
    private val installNextOpen = AtomicBoolean()
    private val armed = AtomicBoolean()
    private val installations = AtomicInteger()
    private val ignoredUpdates = AtomicInteger()
    private val actualEnds = AtomicInteger()
    private val attempt = ThreadLocal<SupportSQLiteDatabase?>()

    fun installOnNextOpen() {
        check(installations.get() == 0 && installNextOpen.compareAndSet(false, true))
    }

    fun arm() {
        assertEquals(1, installations.get()); assertFalse(installNextOpen.get())
        check(ignoredUpdates.get() == 0 && armed.compareAndSet(false, true))
    }

    fun requireActualIgnoreAndEnd(failure: Throwable) {
        assertEquals("One original cursor UPDATE reached SQLite and was ignored", 1, ignoredUpdates.get())
        assertEquals("The failed page's outer native END returned before the oracle", 1, actualEnds.get())
        assertTrue("Original commitState rejects its actual zero-row UPDATE", failure is IllegalStateException)
        assertEquals("Check failed.", failure.message)
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
        raw.execSQL("CREATE TEMP TABLE round_cursor_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL)")
        raw.execSQL("INSERT INTO temp.round_cursor_gate VALUES(1,0)")
        raw.execSQL("CREATE TEMP TABLE round_cursor_hits(id INTEGER PRIMARY KEY,oldCursor INTEGER,oldGeneration INTEGER,newCursor INTEGER,newGeneration INTEGER)")
        raw.execSQL("CREATE TEMP TRIGGER round_cursor_ignore BEFORE UPDATE ON main.next_sync_state " +
            "WHEN (SELECT enabled FROM round_cursor_gate WHERE id=1)=1 BEGIN " +
            "INSERT INTO round_cursor_hits VALUES(1,OLD.cursor,OLD.generation,NEW.cursor,NEW.generation); " +
            "SELECT RAISE(IGNORE); END")
        check(installations.incrementAndGet() == 1)
    }

    private fun <T> executeCursor(raw: SupportSQLiteDatabase, action: () -> T): T {
        if (!armed.compareAndSet(true, false)) return action()
        check(raw.inTransaction() && attempt.get() == null)
        // These are the actual same-writer committed upload receipts, before page commit.
        check(scalar(raw, "SELECT count(*) FROM main.sync_outbox") == 0L)
        check(scalar(raw, "SELECT count(*) FROM main.next_acceptances") == 2L)
        check(scalar(raw, "SELECT cursor FROM main.next_sync_state WHERE id=1") == 20L)
        check(scalar(raw, "SELECT count(*) FROM temp.round_cursor_hits") == 0L)
        raw.execSQL("UPDATE temp.round_cursor_gate SET enabled=1 WHERE id=1")
        check(scalar(raw, "SELECT changes()") == 1L)
        val result = action() // Exactly one real statement execution; SQLite supplies IGNORE.
        check(raw.inTransaction())
        check(scalar(raw, "SELECT changes()") == 0L)
        check(scalar(raw, "SELECT cursor FROM main.next_sync_state WHERE id=1") == 20L)
        raw.query("SELECT oldCursor,oldGeneration,newCursor,newGeneration FROM temp.round_cursor_hits WHERE id=1").use { cursor ->
            check(cursor.moveToFirst() && (0..3).all { cursor.getType(it) == android.database.Cursor.FIELD_TYPE_INTEGER })
            check(cursor.getLong(0) == 20L && cursor.getLong(2) == 22L &&
                cursor.getLong(3) == cursor.getLong(1) + 1L && !cursor.moveToNext())
        }
        if (result is Int) check(result == 0)
        attempt.set(raw)
        check(ignoredUpdates.incrementAndGet() == 1)
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
                        if (sql != UPDATE_CURSOR) return statement
                        // Compile-time wrapping includes statements cached before arming.
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() = executeCursor(raw) { statement.execute() }
                            override fun executeInsert() = executeCursor(raw) { statement.executeInsert() }
                            override fun executeUpdateDelete() = executeCursor(raw) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String) {
                        if (sql == UPDATE_CURSOR) executeCursor(raw) { raw.execSQL(sql) } else raw.execSQL(sql)
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        if (sql == UPDATE_CURSOR) executeCursor(raw) { raw.execSQL(sql, bindArgs) } else raw.execSQL(sql, bindArgs)
                    }
                    override fun endTransaction() {
                        val selected = attempt.get() === raw
                        raw.endTransaction()
                        if (selected && !raw.inTransaction()) {
                            check(actualEnds.incrementAndGet() == 1)
                            attempt.remove()
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
