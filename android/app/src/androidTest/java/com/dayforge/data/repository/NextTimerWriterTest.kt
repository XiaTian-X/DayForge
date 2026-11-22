package com.dayforge.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabaseProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.domain.model.TimerActionAuthority
import com.dayforge.domain.service.TimerService
import com.dayforge.domain.service.TimerServiceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.dayforge.domain.service.ActiveTimerStateProvider
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.FailureChecker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextTimerWriterTest : NextCoreRequestFixture() {
    private val originFault = TimerOriginFault()
    init { storage.isolatedBuilder = { HabitDatabaseProvider.build(it, nativeFactory = originFault) } }

    private fun writer() = NextTimerWriter(db, tokens, sessions)

    private suspend fun startWith(ticket: TimerActionAuthority?) = writer().write(timerHabit.id, ticket) {
        db.timeLogDao().insertSyncedTimer(
            TimeLogEntity(habitId = timerHabit.id, uuid = id(20), startTime = millis, date = millis, endTime = null, durationSeconds = 0,
                timerNextCommandSequence = 2, timerControlGeneration = 1, timerLastCommandAt = millis,
                timerTimezone = "Asia/Shanghai"),
            TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start",
                occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
            TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
    }

    @Test fun staleStartConfigurationAndMissingTicketCannotCreatePartialSession() = runBlocking<Unit> {
        val ticket = writer().capture(timerHabit.id)
        producer().write(local()) { db.habitDao().update(timerHabit.copy(targetValue = 2)) }
        assertNull(writer().capture(timerHabit.id, timerHabit))
        val stats = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao()),
            db.completionDao(), db.timeLogDao(), timerWriter = writer())
        assertNull(stats.calculate(timerHabit, emptyList(), emptyList()).timerAuthority)
        val structure = db.syncOutboxDao().getAll()
        rejected { startWith(ticket) }
        rejected { startWith(null) }
        assertNull(db.timeLogDao().getActiveTimeLog())
        assertEquals(0, count("timer_segments")); assertEquals(0, count("timer_command_outbox"))
        assertEquals(structure, db.syncOutboxDao().getAll())
        startWith(writer().capture(timerHabit.id))
        assertEquals(120, writer().policy(timerHabit.id, id(20))!!.targetSeconds)
        producer().write(local()) { db.habitDao().update(db.habitDao().getHabitById(timerHabit.id)!!.copy(targetValue = 1)) }
        assertEquals(1, db.habitDao().getHabitById(timerHabit.id)!!.targetValue)
        assertEquals(120, writer().policy(timerHabit.id, id(20))!!.targetSeconds)
    }

    @Test fun staleAuthenticationDeviceAndKnownPermissionsRejectOriginalAction() = runBlocking<Unit> {
        register()
        val ticket = requireNotNull(writer().capture(timerHabit.id))
        tokens.saveDeviceRegistration(id(5), caps, true, 2)
        rejected { startWith(ticket) }
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write", "facts.append"), true, 3)
        rejected { startWith(writer().capture(timerHabit.id)) }
        tokens.saveDeviceRegistration(id(4), caps, true, 4)
        tokens.saveLoginSession("synthetic-next", "synthetic-refresh", "member", id(1), false)
        rejected { startWith(ticket) }
        assertNull(db.timeLogDao().getActiveTimeLog())
        assertEquals(0, count("timer_segments")); assertEquals(0, count("next_request_origins"))
    }

    @Test fun cancellationAndOriginInsertFailureRollbackStateSegmentsAndCommandTogether() = runBlocking<Unit> {
        val ticket = writer().capture(timerHabit.id)
        try {
            writer().write(timerHabit.id, ticket) {
                db.timeLogDao().insert(TimeLogEntity(habitId = timerHabit.id, startTime = millis, date = millis, endTime = null, durationSeconds = 0))
                throw CancellationException("cancel before commit")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertNull(db.timeLogDao().getActiveTimeLog())
        assertEquals(0, count("timelogs"))
        originFault.arm()
        val failure = rejected { startWith(ticket) }
        originFault.requireRolledBack(failure)
        assertNull(db.timeLogDao().getActiveTimeLog()); assertEquals(0, count("timer_segments"))
        assertEquals(0, count("timer_command_outbox")); assertEquals(0, count("next_request_origins"))
        assertEquals(0, count("timelogs"))
        originFault.removeAndExpectRetry()
        startWith(ticket)
        originFault.requireRetryCommitted()
        assertEquals(1, count("next_request_origins"))
        val started = requireNotNull(db.timeLogDao().getActiveTimeLog())
        assertEquals(id(20), started.uuid)
        assertNull(started.endTime)
        assertEquals(listOf(id(21)), db.timeLogDao().getPendingTimerCommands().map { it.commandId })
        assertEquals(1, db.timeLogDao().getTimerSegments(id(20)).size)
        assertEquals(id(21), requireNotNull(db.nextRequestDao().origin(NEXT_TIMER, id(21))).requestId)
    }

    @Test fun originalSessionActionDoesNotRetargetReplacementSessionOrConfirmation() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val old = requireNotNull(writer().capture(timerHabit.id))
        val intent = TimerServiceController.commandIntent(app, TimerService.ACTION_DISCARD, timerHabit.id, 1, authority = old)
        assertEquals(old, TimerActionAuthority.read(intent))
        writer().write(timerHabit.id, old) { db.timeLogDao().deleteTimerAndQueue(requireNotNull(db.timeLogDao().getActiveTimeLog()),
            TimerCommandEntity(commandId = id(22), sessionUuid = id(20), sequence = 2, commandType = "cancel",
                occurredAt = millis + 1, expectedControlGeneration = 1)) }
        start(session = 30, command = 31)
        val current = requireNotNull(db.timeLogDao().getActiveTimeLog())
        val queued = db.timeLogDao().getPendingTimerCommands()
        rejected { writer().requireAction(timerHabit.id, TimerActionAuthority.read(intent)) }
        assertEquals(current, db.timeLogDao().getActiveTimeLog()); assertEquals(queued, db.timeLogDao().getPendingTimerCommands())
        assertNotEquals(intent.data, TimerServiceController.commandIntent(app, TimerService.ACTION_DISCARD,
            timerHabit.id, 1, authority = writer().capture(timerHabit.id)).data)
    }

    @Test fun corruptOriginalSourceIsNotReplacedByCurrentConfigOnColdRead() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val source = db.timeLogDao().getPendingTimerCommands().single()
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=occurredAt+1 WHERE commandId=?", arrayOf(id(21)))
        storage.reopen()
        rejected { writer().policy(timerHabit.id, id(20)) }
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=? WHERE commandId=?", arrayOf<Any>(source.occurredAt, id(21)))
        assertEquals(60, writer().policy(timerHabit.id, id(20))!!.targetSeconds)
    }

    @Test fun completionFollowUpCannotPromptForPauseOrChangedAuthentication() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val ticket = requireNotNull(writer().capture(timerHabit.id))
        var published = false
        val pausedWait = async(start = CoroutineStart.UNDISPATCHED) {
            writer().afterCompletion(timerHabit.id, ticket) { published = true }
        }
        delay(50)
        assertFalse(pausedWait.isCompleted); assertFalse(published)
        writer().write(timerHabit.id, ticket) {
            val log = requireNotNull(db.timeLogDao().getActiveTimeLog())
            db.timeLogDao().updatePauseAndQueue(log.id, true, millis + 1, 0, 3, millis + 1, 1, null, null,
                TimerCommandEntity(commandId = id(22), sessionUuid = id(20), sequence = 2, commandType = "pause",
                    occurredAt = millis + 1, expectedControlGeneration = 1, activeElapsedMillis = 1))
        }
        pausedWait.await()
        assertFalse(published)
        // The already-changed sequence returns without prompting; the account change is rejected even on that path.
        tokens.saveLoginSession("synthetic-new", "synthetic-refresh", "member", id(1), false)
        rejected { writer().afterCompletion(timerHabit.id, ticket) { published = true } }
        assertFalse(published)
        assertNull(db.timeLogDao().getActiveTimeLog()!!.endTime)
    }

    @Test fun unrelatedRetainedOriginDoesNotOverrideOrLimitCurrentSessionProof() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val birth = requireNotNull(db.nextRequestDao().origin(NEXT_TIMER, id(21)))
        db.nextRequestDao().insertOrigin(birth.copy(requestId = id(99), queueId = 99,
            intentJson = "unrelated retained proof for ${id(98)}", sourceHash = "unrelated"))
        assertEquals(60, writer().policy(timerHabit.id, id(20))!!.targetSeconds)
        assertEquals(2, count("next_request_origins"))
    }

    @Test fun validTicketCannotCommitCommandsForAnotherSessionOrSequence() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val ticket = requireNotNull(writer().capture(timerHabit.id))
        val original = requireNotNull(db.timeLogDao().getActiveTimeLog())
        val queued = db.timeLogDao().getPendingTimerCommands()
        val segments = db.timeLogDao().getTimerSegments(original.uuid)
        for ((uuid, sequence) in listOf(id(99) to 2, original.uuid to 3)) {
            rejected {
                writer().write(timerHabit.id, ticket) {
                    db.timeLogDao().updatePauseAndQueue(original.id, true, millis + 1, 0, 3, millis + 1, 1, null, null,
                        TimerCommandEntity(commandId = id(22), sessionUuid = uuid, sequence = sequence,
                            commandType = "pause", occurredAt = millis + 1, expectedControlGeneration = 1,
                            activeElapsedMillis = 1))
                }
            }
            assertEquals(original, db.timeLogDao().getActiveTimeLog())
            assertEquals(queued, db.timeLogDao().getPendingTimerCommands())
            assertEquals(segments, db.timeLogDao().getTimerSegments(original.uuid))
            assertEquals(1, count("next_request_origins"))
        }
    }

    @Test fun hiddenDisplayStopsWithoutStoppingPersistedTimerAndReopensWithFreshProof() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val state = ActiveTimerStateProvider(db.timeLogDao(), habits(), app, writer()).observe(scope)
        val first = withTimeout(5_000) { state.filterNotNull().first() }
        val before = requireNotNull(db.timeLogDao().getActiveTimeLog())
        val commands = db.timeLogDao().getPendingTimerCommands()
        withTimeout(5_000) {
            while (state.value != null) kotlinx.coroutines.delay(20)
        }
        assertEquals(before, db.timeLogDao().getActiveTimeLog())
        assertEquals(commands, db.timeLogDao().getPendingTimerCommands())
        val reopened = withTimeout(5_000) { state.filterNotNull().first() }
        assertEquals(first.habitId, reopened.habitId)
        assertEquals(first.targetMinutes, reopened.targetMinutes)
        assertNotSame(first.authority, reopened.authority)
        assertEquals(before.uuid, reopened.authority!!.sessionUuid)
        assertEquals(before, db.timeLogDao().getActiveTimeLog())
        assertEquals(commands, db.timeLogDao().getPendingTimerCommands())
    }

    @Test fun displayFlowSurvivesAccountTransitionWithoutAdoptingOldTimerOrNewTarget() = runBlocking<Unit> {
        startWith(writer().capture(timerHabit.id))
        val state = ActiveTimerStateProvider(db.timeLogDao(), habits(), app, writer()).observe(scope)
        val initial = withTimeout(5_000) { state.filterNotNull().first() }
        assertEquals(1, initial.targetMinutes)
        val before = requireNotNull(db.timeLogDao().getActiveTimeLog())
        // Delay between authentication replacement and local-store cleanup must not crash the consumer.
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
        withTimeout(5_000) { state.first { it == null } }
        assertEquals(before, db.timeLogDao().getActiveTimeLog())
        tokens.saveLoginSession("synthetic-return", "synthetic-refresh", "member", id(1), false)
        val resumed = withTimeout(5_000) { state.filterNotNull().first() }
        assertEquals(1, resumed.targetMinutes)
        assertEquals(before.uuid, resumed.authority!!.sessionUuid)
        assertNotEquals(initial.authority!!.authenticationGeneration, resumed.authority!!.authenticationGeneration)
    }

    /** This one test's fixed fault lives below the controlled factory. No MAIN DDL, caller SQL,
     * callback or native handle is exposed; other methods never arm it and execute unchanged. */
    private class TimerOriginFault : SupportSQLiteOpenHelper.Factory {
        private companion object {
            const val REQUEST = "aa310000-0000-4000-8000-000000000015"
            const val SESSION = "aa310000-0000-4000-8000-000000000014"
            const val ORIGIN = "INSERT OR ABORT INTO `next_request_origins` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`sourceHash`,`intentJson`) VALUES (?,?,?,?,?,?,?,?,?)"
        }
        private class Attempt(val native: SupportSQLiteDatabase) {
            val invalid = AtomicBoolean()
            val phase = AtomicInteger(1) // 1: original fault; 2: same-ticket retry; 0: actor claimed.
            val actors = AtomicInteger()
            val returned = AtomicInteger()
            val marks = AtomicInteger()
            val ends = AtomicInteger()
            val retryActors = AtomicInteger()
            val retryReturned = AtomicInteger()
            val retryMarks = AtomicInteger()
            val retryEnds = AtomicInteger()
            val original = AtomicReference<SQLiteConstraintException?>()
            val observationFailure = AtomicReference<Throwable?>()
            @Volatile var originalEffect = false
            @Volatile var rollbackEqual = false
            @Volatile var retryCommitted = false
            @Volatile var reported = false
        }
        private data class Frame(val attempt: Attempt, val retry: Boolean)
        private val selected = AtomicReference<Attempt?>()
        private val writer = ThreadLocal<Frame?>()
        @Volatile private var opened: SupportSQLiteDatabase? = null

        private fun activeFrame(): Frame? {
            val frame = writer.get() ?: return null
            if (frame.attempt.invalid.get()) { writer.remove(); return null }
            return frame
        }
        private fun invalidate(raw: SupportSQLiteDatabase?) {
            selected.get()?.takeIf { it.native === raw }?.invalid?.set(true)
        }
        private fun observe(a: Attempt, block: () -> Unit) {
            try { block() } catch (error: Throwable) { a.observationFailure.compareAndSet(null, error) }
        }
        private fun counts(raw: SupportSQLiteDatabase): List<Long> = raw.query(
            "SELECT (SELECT count(*) FROM main.timelogs),(SELECT count(*) FROM main.timer_segments)," +
                "(SELECT count(*) FROM main.timer_command_outbox),(SELECT count(*) FROM main.next_request_origins)"
        ).use { c -> check(c.moveToFirst()); List(4) { c.getLong(it) }.also { check(!c.moveToNext()) } }

        fun arm() {
            val raw = requireNotNull(opened)
            check(selected.get() == null && !raw.inTransaction())
            check(counts(raw) == listOf(0L, 0L, 0L, 0L))
            // SQLite executes this fixed TEMP DDL on its writer connection. The later exact
            // original DAO exception, not this installation, proves that connection saw it.
            raw.execSQL("CREATE TEMP TRIGGER reject_timer_origin BEFORE INSERT ON main.next_request_origins " +
                "WHEN NEW.kind='$NEXT_TIMER' AND NEW.requestId='$REQUEST' " +
                "BEGIN SELECT RAISE(ABORT, 'origin fault'); END")
            check(counts(raw) == listOf(0L, 0L, 0L, 0L))
            selected.set(Attempt(raw))
        }
        fun requireRolledBack(failure: Throwable) {
            val a = requireNotNull(selected.get())
            assertFalse(a.invalid.get()); assertSame(a.native, opened)
            assertEquals(0, a.phase.get())
            assertEquals(1, a.actors.get())
            assertEquals(0, a.returned.get())
            assertTrue("Original timer, segment and command existed before the origin actor", a.originalEffect)
            assertTrue(failure is SQLiteConstraintException)
            assertSame("The real origin DAO exception propagates unchanged", a.original.get(), failure)
            assertTrue(failure.message.orEmpty().contains("origin fault"))
            assertEquals(0, a.marks.get()); assertEquals(1, a.ends.get())
            assertNull("Observation must not replace the native outcome", a.observationFailure.get())
            assertTrue("Actual outer END returned and all four tables rolled back", a.rollbackEqual)
            assertFalse(a.native.inTransaction())
            assertEquals(listOf(0L, 0L, 0L, 0L), counts(a.native))
            a.reported = true
        }
        fun removeAndExpectRetry() {
            val a = requireNotNull(selected.get())
            check(a.reported && !a.invalid.get() && a.native === opened && !a.native.inTransaction())
            a.native.execSQL("DROP TRIGGER temp.reject_timer_origin")
            a.native.query("SELECT count(*) FROM temp.sqlite_master WHERE name='reject_timer_origin'").use {
                check(it.moveToFirst() && it.getLong(0) == 0L && !it.moveToNext())
            }
            check(a.phase.compareAndSet(0, 2))
        }
        fun requireRetryCommitted() {
            val a = requireNotNull(selected.get())
            assertTrue(a.reported); assertFalse(a.invalid.get()); assertSame(a.native, opened)
            assertEquals(0, a.phase.get()); assertEquals(1, a.retryActors.get()); assertEquals(1, a.retryReturned.get())
            assertTrue(a.retryMarks.get() > 0); assertEquals(1, a.retryEnds.get()); assertTrue(a.retryCommitted)
            assertNull(a.observationFailure.get()); assertFalse(a.native.inTransaction())
            assertEquals(listOf(1L, 1L, 1L, 1L), counts(a.native))
        }
        private fun originalEffect(raw: SupportSQLiteDatabase, queueId: Long): Boolean = raw.query(
            "SELECT count(*) FROM main.timer_command_outbox q " +
                "JOIN main.timelogs t ON t.uuid=q.sessionUuid JOIN main.habits h ON h.id=t.habitId " +
                "JOIN main.timer_segments s ON s.sessionUuid=t.uuid " +
                "WHERE q.id=? AND q.commandId=? AND q.sessionUuid=? AND q.sequence=1 AND q.commandType='start' " +
                "AND q.expectedControlGeneration=0 AND q.activityUuid=h.uuid AND q.timezone='Asia/Shanghai' " +
                "AND h.uuid='aa310000-0000-4000-8000-00000000000c' AND t.endTime IS NULL " +
                "AND t.durationSeconds=0 AND t.timerNextCommandSequence=2 AND t.timerControlGeneration=1 " +
                "AND t.timerLastCommandAt=t.startTime AND t.timerTimezone=q.timezone AND t.date=t.startTime " +
                "AND s.sequence=1 AND s.startedAt=t.startTime AND s.endedAt IS NULL AND q.occurredAt=t.startTime",
            arrayOf<Any?>(queueId, REQUEST, SESSION)
        ).use { c -> check(c.moveToFirst()); (c.getLong(0) == 1L).also { check(!c.moveToNext()) } }

        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            check(configuration.context.packageName == "com.dayforge.testbed")
            var native: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            val monitor = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (native !== raw) {
                    native = raw; opened = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun close() { invalidate(raw); raw.close() }
                        override fun compileStatement(sql: String): SupportSQLiteStatement {
                            val statement = raw.compileStatement(sql)
                            if (sql != ORIGIN) return statement
                            return object : SupportSQLiteStatement by statement {
                                private val keys = hashMapOf<Int, Any?>()
                                override fun clearBindings() { keys.clear(); statement.clearBindings() }
                                override fun bindNull(index: Int) { if (index in 1..5) keys[index] = null; statement.bindNull(index) }
                                override fun bindLong(index: Int, value: Long) { if (index in 1..5) keys[index] = value; statement.bindLong(index, value) }
                                override fun bindString(index: Int, value: String) { if (index in 1..5) keys[index] = value; statement.bindString(index, value) }
                                override fun bindDouble(index: Int, value: Double) { if (index in 1..5) keys[index] = value; statement.bindDouble(index, value) }
                                override fun bindBlob(index: Int, value: ByteArray) { if (index in 1..5) keys[index] = Unit; statement.bindBlob(index, value) }
                                private fun <T> executeOriginal(action: () -> T): T {
                                    val a = selected.get()
                                    if (a == null || a.invalid.get() || keys[1] != NEXT_TIMER || keys[2] != REQUEST)
                                        return action()
                                    val phase = a.phase.get()
                                    if (phase !in 1..2 || !a.phase.compareAndSet(phase, 0)) return action()
                                    val retry = phase == 2
                                    observe(a) {
                                        check(a.native === raw && raw === opened && raw.inTransaction() && activeFrame() == null)
                                        check(keys[4] == 5L && keys[5] == "aa310000-0000-4000-8000-000000000001")
                                        check(counts(raw) == listOf(1L, 1L, 1L, 0L))
                                        check(originalEffect(raw, requireNotNull(keys[3] as? Long)))
                                        if (!retry) a.originalEffect = true
                                    }
                                    writer.set(Frame(a, retry))
                                    (if (retry) a.retryActors else a.actors).incrementAndGet()
                                    try {
                                        return action().also {
                                            (if (retry) a.retryReturned else a.returned).incrementAndGet()
                                        }
                                    } catch (failure: SQLiteConstraintException) {
                                        if (!retry) a.original.compareAndSet(null, failure)
                                        throw failure
                                    }
                                }
                                override fun execute() = executeOriginal { statement.execute() }
                                override fun executeInsert(): Long = executeOriginal { statement.executeInsert() }
                                override fun executeUpdateDelete(): Int = executeOriginal { statement.executeUpdateDelete() }
                            }
                        }
                        override fun setTransactionSuccessful() {
                            raw.setTransactionSuccessful()
                            activeFrame()?.takeIf { it.attempt.native === raw }?.let {
                                (if (it.retry) it.attempt.retryMarks else it.attempt.marks).incrementAndGet()
                            }
                        }
                        override fun endTransaction() {
                            val frame = activeFrame()?.takeIf { it.attempt.native === raw }
                            var returned = false
                            try { raw.endTransaction(); returned = true }
                            finally { if (frame != null) observe(frame.attempt) {
                                if (!raw.inTransaction()) {
                                    try {
                                        val a = frame.attempt
                                        (if (frame.retry) a.retryEnds else a.ends).incrementAndGet()
                                        check(returned)
                                        if (frame.retry) a.retryCommitted = counts(raw) == listOf(1L, 1L, 1L, 1L)
                                        else a.rollbackEqual = counts(raw) == listOf(0L, 0L, 0L, 0L)
                                    } finally { writer.remove() }
                                }
                            } }
                        }
                    }
                }
                requireNotNull(adapted)
            }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by helper {
                override fun close() { invalidate(native); helper.close() }
                override val writableDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase get() = wrap(helper.readableDatabase)
            }
        }
    }
}
