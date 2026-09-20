package com.dayforge.data.local.dao

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class TimerTransitionDaoTest {
    @get:org.junit.Rule val storage = com.dayforge.data.local.PhysicalDatabaseRule()
    private lateinit var database: HabitDatabase
    private lateinit var dao: TimeLogDao
    private var habitId: Long = 0

    @Before
    fun setUp() = runBlocking {
        database = storage.database
        dao = database.timeLogDao()
        habitId = database.habitDao().insert(
            HabitEntity(
                name = "Timer transition",
                habitType = HabitType.TIMER,
                iconResId = 0,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pause_resume_and_stop_persist_one_ordered_command_history() = runBlocking {
        val session = "timer-session"
        val logId = insertStartedTimer(session)

        dao.updatePauseAndQueue(
            id = logId,
            isPaused = true,
            pausedAt = 20_000,
            accumulatedPauseMillis = 0,
            nextSequence = 3,
            commandAt = 20_000,
            activeElapsedMillis = 10_000,
            elapsedRealtimeAnchor = null,
            bootCount = 1,
            command = command(session, 2, "pause", 20_000, 10_000)
        )
        dao.updatePauseAndQueue(
            id = logId,
            isPaused = false,
            pausedAt = null,
            accumulatedPauseMillis = 5_000,
            nextSequence = 4,
            commandAt = 25_000,
            activeElapsedMillis = 10_000,
            elapsedRealtimeAnchor = 30_000,
            bootCount = 1,
            command = command(session, 3, "resume", 25_000),
            resumedSegment = TimerSegmentEntity(
                sessionUuid = session,
                sequence = 3,
                startedAt = 25_000
            )
        )
        dao.finishTimerAndQueue(
            id = logId,
            endTime = 45_000,
            durationSeconds = 30,
            accumulatedPauseMillis = 5_000,
            nextSequence = 5,
            activeElapsedMillis = 30_000,
            command = command(session, 4, "stop", 45_000, 30_000),
            wasPaused = false
        )

        reopen()
        val completed = dao.getById(logId)
        assertNotNull(completed)
        assertEquals(45_000L, completed?.endTime)
        assertFalse(completed?.isPaused == true)
        assertNull(dao.getActiveTimeLog())
        assertEquals(
            listOf("start", "pause", "resume", "stop"),
            dao.getPendingTimerCommands().map { it.commandType }
        )
        assertEquals(
            listOf(20_000L, 45_000L),
            dao.getTimerSegments(session).map { it.endedAt }
        )
    }

    @Test
    fun stale_stop_rolls_back_its_outbox_command() = runBlocking {
        val session = "stale-stop-session"
        val logId = insertStartedTimer(session)
        dao.finishTimerAndQueue(
            id = logId,
            endTime = 20_000,
            durationSeconds = 10,
            accumulatedPauseMillis = 0,
            nextSequence = 3,
            activeElapsedMillis = 10_000,
            command = command(session, 2, "stop", 20_000, 10_000),
            wasPaused = false
        )

        val failure = runCatching {
            dao.finishTimerAndQueue(
                id = logId,
                endTime = 21_000,
                durationSeconds = 11,
                accumulatedPauseMillis = 0,
                nextSequence = 4,
                activeElapsedMillis = 11_000,
                command = command(session, 3, "stop", 21_000, 11_000),
                wasPaused = false
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is IllegalStateException)
        assertEquals("Active timer changed before stop transition", failure?.message)
        reopen()
        assertEquals(listOf(1, 2), dao.getPendingTimerCommands().map { it.sequence })
        assertEquals(20_000L, dao.getById(logId)?.endTime)
    }

    @Test
    fun commandInsertFailureRollsBackTimerAndSegmentOnDisk() = runBlocking {
        val session = "failed-stop"
        val id = insertStartedTimer(session)
        val original = dao.getById(id)
        val segments = dao.getTimerSegments(session)
        val commands = dao.getPendingTimerCommands()
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_test_stop BEFORE INSERT ON timer_command_outbox
            WHEN NEW.commandType = 'stop'
            BEGIN SELECT RAISE(ABORT, 'test stop insert failure'); END
        """.trimIndent())
        val error = runCatching {
            dao.finishTimerAndQueue(id, 20000, 10, 0, 3, 10000,
                command(session, 2, "stop", 20000, 10000), false)
        }.exceptionOrNull()
        assertTrue(error is android.database.sqlite.SQLiteConstraintException)
        assertTrue(error?.message.orEmpty().contains("test stop insert failure"))
        reopen()
        assertEquals(original, dao.getById(id))
        assertEquals(segments, dao.getTimerSegments(session))
        assertEquals(commands, dao.getPendingTimerCommands())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_test_stop")
        dao.finishTimerAndQueue(id, 20000, 10, 0, 3, 10000,
            command(session, 2, "stop", 20000, 10000), false)
        reopen()
        assertEquals(20000L, dao.getById(id)?.endTime)
        assertEquals(listOf("start", "stop"), dao.getPendingTimerCommands().map { it.commandType })
        assertEquals(listOf(20000L), dao.getTimerSegments(session).map { it.endedAt })
    }

    private fun reopen() {
        database = storage.reopen()
        dao = database.timeLogDao()
    }

    private suspend fun insertStartedTimer(session: String): Long = dao.insertSyncedTimer(
        TimeLogEntity(
            habitId = habitId,
            startTime = 10_000,
            endTime = null,
            durationSeconds = 0,
            timerNextCommandSequence = 2,
            timerControlGeneration = 1,
            timerLastCommandAt = 10_000,
            timerTimezone = "Asia/Shanghai",
            timerElapsedRealtimeAnchor = 10_000,
            timerBootCount = 1,
            date = 0,
            uuid = session
        ),
        command(session, 1, "start", 10_000),
        TimerSegmentEntity(
            sessionUuid = session,
            sequence = 1,
            startedAt = 10_000
        )
    )

    private fun command(
        session: String,
        sequence: Int,
        type: String,
        occurredAt: Long,
        activeElapsedMillis: Long? = null
    ) = TimerCommandEntity(
        commandId = "$session-$sequence",
        sessionUuid = session,
        sequence = sequence,
        commandType = type,
        occurredAt = occurredAt,
        expectedControlGeneration = if (sequence == 1) 0 else 1,
        activeElapsedMillis = activeElapsedMillis
    )
}
