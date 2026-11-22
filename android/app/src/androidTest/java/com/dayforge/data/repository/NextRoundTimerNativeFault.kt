package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*

/** Fixed TEMP faults beneath the unchanged controlled factory. Only the three original
 * late-fault tests opt in. No main schema change, raw handle, arbitrary SQL or callback is
 * exposed; ordinary onOpen/primary MAC/source/transaction guards run unchanged. */
internal class NextRoundTimerNativeFault : SupportSQLiteOpenHelper.Factory {
    enum class Mode { CREATION_ORIGIN, IGNORE_BIRTH, TIMER_VALUE, METADATA_HASH, CURSOR, DEFERRED_COMMIT }
    private data class Selection(val mode: Mode, val creationId: String?)
    private val next = AtomicReference<Selection?>()
    private val installed = AtomicReference<Selection?>()
    private val armed = AtomicBoolean()
    private val hits = AtomicInteger()
    private val ends = AtomicInteger()
    private val successes = AtomicInteger()
    private val constraints = AtomicInteger()
    private val originalConstraint = AtomicReference<SQLiteConstraintException?>()
    private val endObservationFailure = AtomicReference<Throwable?>()
    private val attempt = ThreadLocal<SupportSQLiteDatabase?>()

    fun installOnNextOpen(mode: Mode, creationId: String? = null) {
        check(!armed.get() && next.get() == null)
        require((mode == Mode.CREATION_ORIGIN) == (creationId != null))
        creationId?.let { require(it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) }
        hits.set(0); ends.set(0); successes.set(0); constraints.set(0); originalConstraint.set(null)
        endObservationFailure.set(null)
        installed.set(null)
        check(next.compareAndSet(null, Selection(mode, creationId)))
    }

    fun arm() {
        requireNotNull(installed.get()); check(next.get() == null && hits.get() == 0)
        check(armed.compareAndSet(false, true))
    }

    fun requireActualFaultAndEnd(failure: Throwable) {
        assertEquals("Original generated INSERT and fixed TEMP trigger really executed", 1, hits.get())
        endObservationFailure.get()?.let { throw AssertionError("Original native END/frame observation failed", it) }
        assertEquals("Original outer endTransaction returned or threw and released its Android transaction frame", 1, ends.get())
        assertFalse(armed.get())
        if (requireNotNull(installed.get()).mode == Mode.DEFERRED_COMMIT) {
            assertTrue("Original writer reached native setTransactionSuccessful", successes.get() > 0)
            assertEquals(1, constraints.get())
            val original = requireNotNull(originalConstraint.get())
            assertTrue("Caller receives the same engine constraint failure", generateSequence(failure) { it.cause }.any { it === original })
        } else {
            assertEquals(0, constraints.get())
        }
    }

    private fun scalar(raw: SupportSQLiteDatabase, sql: String): Long = raw.query(sql).use { c ->
        check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
        c.getLong(0).also { check(!c.moveToNext()) }
    }
    private fun text(raw: SupportSQLiteDatabase, sql: String): String = raw.query(sql).use { c ->
        check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_STRING)
        c.getString(0).also { check(!c.moveToNext()) }
    }

    private fun install(raw: SupportSQLiteDatabase, selection: Selection) {
        check(!raw.inTransaction())
        check(scalar(raw, "SELECT count(*) FROM main.sqlite_master WHERE type='table' AND name IN ('next_request_origins','next_challenge_births','next_acceptances')") == 3L)
        raw.execSQL("CREATE TEMP TABLE round_timer_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL)")
        raw.execSQL("INSERT INTO temp.round_timer_gate VALUES(1,0)")
        raw.execSQL("CREATE TEMP TABLE round_timer_hits(id INTEGER PRIMARY KEY,identity TEXT NOT NULL)")
        val gate = "(SELECT enabled FROM round_timer_gate WHERE id=1)=1"
        val mode = selection.mode
        val body = when (mode) {
            Mode.CREATION_ORIGIN -> "AFTER INSERT ON main.next_request_origins WHEN $gate AND NEW.requestId='$PAUSE' BEGIN " +
                "UPDATE next_request_origins SET intentJson=replace(intentJson,'$DEVICE','$OTHER_DEVICE') WHERE requestId='${selection.creationId}'; " +
                "INSERT INTO round_timer_hits VALUES(1,NEW.requestId); END"
            Mode.IGNORE_BIRTH -> "BEFORE INSERT ON main.next_challenge_births WHEN $gate BEGIN " +
                "INSERT INTO round_timer_hits VALUES(1,NEW.entityUuid); SELECT RAISE(IGNORE); END"
            Mode.TIMER_VALUE -> "AFTER INSERT ON main.next_challenge_births WHEN $gate BEGIN " +
                "UPDATE timelogs SET durationSeconds=77; INSERT INTO round_timer_hits VALUES(1,NEW.entityUuid); END"
            Mode.METADATA_HASH -> "AFTER INSERT ON main.next_acceptances WHEN $gate BEGIN " +
                "UPDATE next_challenge_state SET metadataHash='bad'; INSERT INTO round_timer_hits VALUES(1,NEW.requestId); END"
            Mode.CURSOR -> "AFTER INSERT ON main.next_acceptances WHEN $gate BEGIN " +
                "UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1; " +
                "UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1; " +
                "INSERT INTO round_timer_hits VALUES(1,NEW.requestId); END"
            Mode.DEFERRED_COMMIT -> {
                raw.execSQL("CREATE TEMP TABLE round_timer_parent(id INTEGER PRIMARY KEY)")
                raw.execSQL("CREATE TEMP TABLE round_timer_guard(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES round_timer_parent(id) DEFERRABLE INITIALLY DEFERRED)")
                "AFTER INSERT ON main.next_acceptances WHEN $gate BEGIN " +
                    "INSERT INTO round_timer_guard VALUES(1,99); INSERT INTO round_timer_hits VALUES(1,NEW.requestId); END"
            }
        }
        raw.execSQL("CREATE TEMP TRIGGER round_timer_fault $body")
        installed.set(selection)
    }

    private fun matches(sql: String, mode: Mode): Boolean = sql == when (mode) {
        Mode.CREATION_ORIGIN -> INSERT_ORIGIN
        Mode.IGNORE_BIRTH, Mode.TIMER_VALUE -> INSERT_BIRTH
        Mode.METADATA_HASH, Mode.CURSOR, Mode.DEFERRED_COMMIT -> INSERT_ACCEPTANCE
    }

    private fun <T> execute(raw: SupportSQLiteDatabase, sql: String, action: () -> T): T {
        val selection = installed.get() ?: return action()
        if (!matches(sql, selection.mode) || !armed.compareAndSet(true, false)) return action()
        check(raw.inTransaction() && attempt.get() == null)
        check(scalar(raw, "PRAGMA foreign_keys") == 1L)
        check(scalar(raw, "SELECT count(*) FROM temp.round_timer_hits") == 0L)
        val priorOrigin = if (selection.mode == Mode.CREATION_ORIGIN)
            text(raw, "SELECT intentJson FROM main.next_request_origins WHERE requestId='${selection.creationId}'") else null
        if (priorOrigin != null) check(priorOrigin.contains(DEVICE))
        val syncGeneration = if (selection.mode == Mode.CURSOR)
            scalar(raw, "SELECT generation FROM main.next_sync_state WHERE id=1") else null
        val roundGeneration = if (selection.mode == Mode.CURSOR)
            scalar(raw, "SELECT generation FROM main.next_challenge_state WHERE id=1") else null
        if (selection.mode == Mode.CURSOR) {
            check(scalar(raw, "SELECT cursor FROM main.next_sync_state WHERE id=1") == 20L)
            check(scalar(raw, "SELECT cursor FROM main.next_challenge_state WHERE id=1") == 20L)
        }
        raw.execSQL("UPDATE temp.round_timer_gate SET enabled=1 WHERE id=1")
        check(scalar(raw, "SELECT changes()") == 1L)
        val result = action() // The original generated INSERT executes exactly once.
        check(raw.inTransaction())
        check(scalar(raw, "SELECT changes()") == if (selection.mode == Mode.IGNORE_BIRTH) 0L else 1L)
        val expectedIdentity = when (selection.mode) {
            Mode.CREATION_ORIGIN -> PAUSE
            Mode.IGNORE_BIRTH, Mode.TIMER_VALUE -> SESSION
            else -> START
        }
        check(text(raw, "SELECT identity FROM temp.round_timer_hits WHERE id=1") == expectedIdentity)
        when (selection.mode) {
            Mode.CREATION_ORIGIN -> {
                check(text(raw, "SELECT intentJson FROM main.next_request_origins WHERE requestId='${selection.creationId}'") ==
                    requireNotNull(priorOrigin).replace(DEVICE, OTHER_DEVICE))
                check(scalar(raw, "SELECT count(*) FROM main.next_request_origins WHERE requestId='$PAUSE'") == 1L)
            }
            Mode.IGNORE_BIRTH -> check(scalar(raw, "SELECT count(*) FROM main.next_challenge_births WHERE entityUuid='$SESSION'") == 0L)
            Mode.TIMER_VALUE -> {
                check(scalar(raw, "SELECT count(*) FROM main.next_challenge_births WHERE entityUuid='$SESSION'") == 1L)
                check(scalar(raw, "SELECT durationSeconds FROM main.timelogs WHERE uuid='$SESSION'") == 77L)
            }
            Mode.METADATA_HASH -> check(text(raw, "SELECT metadataHash FROM main.next_challenge_state WHERE id=1") == "bad")
            Mode.CURSOR -> {
                check(scalar(raw, "SELECT cursor FROM main.next_sync_state WHERE id=1") == 21L)
                check(scalar(raw, "SELECT cursor FROM main.next_challenge_state WHERE id=1") == 21L)
                check(scalar(raw, "SELECT generation FROM main.next_sync_state WHERE id=1") == requireNotNull(syncGeneration) + 1L)
                check(scalar(raw, "SELECT generation FROM main.next_challenge_state WHERE id=1") == requireNotNull(roundGeneration) + 1L)
            }
            Mode.DEFERRED_COMMIT -> {
                check(scalar(raw, "SELECT count(*) FROM temp.round_timer_guard WHERE id=1 AND parent=99") == 1L)
                check(scalar(raw, "SELECT count(*) FROM temp.round_timer_parent") == 0L)
                raw.query("PRAGMA temp.foreign_key_check(round_timer_guard)").use { c ->
                    check(c.moveToFirst() && c.getString(0) == "round_timer_guard" && c.getLong(1) == 1L &&
                        c.getString(2) == "round_timer_parent" && !c.moveToNext())
                }
            }
        }
        if (selection.mode in setOf(Mode.METADATA_HASH, Mode.CURSOR, Mode.DEFERRED_COMMIT))
            check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE requestId='$START'") == 1L)
        attempt.set(raw); check(hits.incrementAndGet() == 1)
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
                        if (sql !in setOf(INSERT_ORIGIN, INSERT_BIRTH, INSERT_ACCEPTANCE)) return statement
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() = execute(raw, sql) { statement.execute() }
                            override fun executeInsert() = execute(raw, sql) { statement.executeInsert() }
                            override fun executeUpdateDelete() = execute(raw, sql) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String) = execute(raw, sql) { raw.execSQL(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = execute(raw, sql) { raw.execSQL(sql, bindArgs) }
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
                            check(requireNotNull(installed.get()).mode == Mode.DEFERRED_COMMIT)
                            check(failure.message?.contains("FOREIGN KEY constraint failed") == true && successes.get() > 0)
                            check(originalConstraint.compareAndSet(null, failure))
                            constraints.incrementAndGet()
                            throw failure // Same actual SQLite END failure, never constructed by the spy.
                        } finally {
                            // Count the original endTransaction/frame observation independently.
                            // Android can release its session/connection even when deferred COMMIT
                            // fails; a later bare SELECT may use another WAL connection. Neither
                            // !inTransaction nor this counter proves engine rollback. Do not query
                            // TEMP or try another BEGIN/ROLLBACK on this unknown owner. The test's
                            // independent cold owner and complete main image decide persistence.
                            // A failed frame observation never replaces the original engine error.
                            try {
                                if (!raw.inTransaction()) {
                                    check(ends.incrementAndGet() == 1)
                                    attempt.remove()
                                }
                            } catch (observation: Throwable) {
                                endObservationFailure.compareAndSet(null, observation)
                            }
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
                next.getAndSet(null)?.let { install(db, it) }
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
        const val DEVICE = "aa310000-0000-4000-8000-000000000004"
        const val OTHER_DEVICE = "aa310000-0000-4000-8000-000000000009"
        const val SESSION = "aa310000-0000-4000-8000-000000000014"
        const val START = "aa310000-0000-4000-8000-000000000015"
        const val PAUSE = "aa310000-0000-4000-8000-000000000016"
        const val INSERT_ORIGIN = "INSERT OR ABORT INTO `next_request_origins` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`sourceHash`,`intentJson`) VALUES (?,?,?,?,?,?,?,?,?)"
        const val INSERT_BIRTH = "INSERT OR ABORT INTO `next_challenge_births` (`entityType`,`entityUuid`,`roundUuid`) VALUES (?,?,?)"
        const val INSERT_ACCEPTANCE = "INSERT OR ABORT INTO `next_acceptances` (`kind`,`requestId`,`originHash`,`transmissionHash`,`resultHash`,`resultJson`) VALUES (?,?,?,?,?,?)"
    }
}
