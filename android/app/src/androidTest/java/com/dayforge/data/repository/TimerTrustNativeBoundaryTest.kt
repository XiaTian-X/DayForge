package com.dayforge.data.repository

import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native throw occurs after this actor's actual business END, never in Room's unrelated
 * invalidation transaction. Its fixed MAIN witness cannot be satisfied by a TEMP/WAL view.
 */
@RunWith(AndroidJUnit4::class)
class TimerTrustNativeBoundaryTest : NextCoreRequestFixture() {
    private val native = TimerEndLoss()
    init { storage.isolatedBuilder = { context -> buildDiagnosticFaultDatabase(context, native) } }

    @Test(timeout = 150_000) fun lostActualCommittedEndReturnsNoStartAndReopenAuditsSamePersistentSession() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        register()
        TimerHistoryReader(db, tokens, sessions).readProjection(timerHabit)
        val before = requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase))
        val w = NextTimerWriter(db, tokens, sessions); val ticket = w.capture(timerHabit.id)
        native.arm(id(80), id(81))
        var returned: Long? = null
        val failure = rejected {
            returned = w.write(timerHabit.id, ticket) { w.applyTransition(TimerTransition.Start(
                TimeLogEntity(habitId = timerHabit.id, uuid = id(80), startTime = millis, date = millis, endTime = null,
                    durationSeconds = 0, timerNextCommandSequence = 2, timerControlGeneration = 1,
                    timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(81), sessionUuid = id(80), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(80), sequence = 1, startedAt = millis))) }
        }
        native.requireLost(failure); assertNull(returned)
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        storage.reopen()
        val log = requireNotNull(db.timeLogDao().getActiveTimeLog())
        assertEquals(id(80), log.uuid); assertEquals(2, log.timerNextCommandSequence)
        val original = TimerHistoryReader(db, tokens, sessions).read(timerHabit)
        assertEquals(log, original.activeLog); assertEquals(1, original.logs.size)
        val shown = NextTimerWriter(db, tokens, sessions).statusDisplaySnapshot(timerHabit)
        assertEquals(id(80), requireNotNull(shown).authority.sessionUuid)
        assertEquals(1, count("timelogs")); assertEquals(1, count("timer_segments")); assertEquals(1, count("timer_command_outbox"))
        assertEquals(1, count("next_request_origins")); assertEquals(0, count("timelog_day_allocations"))
        assertTrue(requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase)).revision > before.revision)
        rejected { NextTimerWriter(db, tokens, sessions).requireAction(timerHabit.id, ticket) }
        assertEquals(log, db.timeLogDao().getActiveTimeLog()); assertEquals(1, count("timer_command_outbox"))
    }

    private class TimerEndLoss : SupportSQLiteOpenHelper.Factory {
        private var session: String? = null; private var command: String? = null
        @Volatile private var actor: Thread? = null
        @Volatile private var successful = false
        @Volatile private var completed = false
        private val lost = IllegalStateException("TIMER_TEST_ACTUAL_COMMITTED_END_LOST")
        fun arm(session: String, command: String) { check(this.session == null); this.session = session; this.command = command }
        fun requireLost(error: Throwable) {
            assertTrue(successful); assertTrue(completed)
            assertTrue(generateSequence(error) { it.cause }.any { it === lost })
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            check(configuration.context.packageName == "com.dayforge.testbed")
            var original: SupportSQLiteDatabase? = null; var adapted: SupportSQLiteDatabase? = null
            val monitor = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (original !== raw) {
                    original = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        fun after(sql: String) {
                            if (command == null || actor != null || completed) return
                            val normalized = sql.lowercase().replace("`", "").replace("\"", "")
                            if (!normalized.startsWith("insert") || "into next_request_origins" !in normalized) return
                            val present = raw.query("SELECT 1 FROM main.next_request_origins WHERE kind='timer_command' AND requestId=?", arrayOf(command))
                                .use { it.moveToFirst() }
                            if (present) { check(raw.inTransaction()); actor = Thread.currentThread() }
                        }
                        override fun execSQL(sql: String) { raw.execSQL(sql); after(sql) }
                        override fun execSQL(sql: String, bindArgs: Array<out Any?>) { raw.execSQL(sql, bindArgs); after(sql) }
                        override fun compileStatement(sql: String): SupportSQLiteStatement {
                            val statement = raw.compileStatement(sql)
                            return object : SupportSQLiteStatement by statement {
                                override fun executeInsert(): Long = statement.executeInsert().also { after(sql) }
                                override fun execute() { statement.execute(); after(sql) }
                                override fun executeUpdateDelete(): Int = statement.executeUpdateDelete().also { after(sql) }
                            }
                        }
                        override fun setTransactionSuccessful() {
                            raw.setTransactionSuccessful(); if (actor === Thread.currentThread()) successful = true
                        }
                        override fun endTransaction() {
                            raw.endTransaction()
                            if (actor === Thread.currentThread() && successful && !completed && !raw.inTransaction()) {
                                fun count(table: String, where: String, value: String?): Long = raw.query(
                                    "SELECT COUNT(*) FROM main.$table WHERE $where", arrayOf(value)).use { check(it.moveToFirst()); it.getLong(0) }
                                check(count("timelogs", "uuid=? AND endTime IS NULL AND timerNextCommandSequence=2", session) == 1L)
                                check(count("timer_segments", "sessionUuid=? AND sequence=1 AND endedAt IS NULL", session) == 1L)
                                check(count("timer_command_outbox", "commandId=? AND sequence=1", command) == 1L)
                                check(count("next_request_origins", "requestId=? AND kind='timer_command'", command) == 1L)
                                raw.query("SELECT r.generation,c.generation,c.dirty FROM main.count_trust_root r JOIN main.count_trust_control c ON c.id=r.id").use {
                                    check(it.moveToFirst() && it.getLong(0) == it.getLong(1) && it.getLong(2) == 0L && !it.moveToNext())
                                }
                                completed = true; actor = null; throw lost
                            }
                        }
                    }
                }
                requireNotNull(adapted)
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
}
