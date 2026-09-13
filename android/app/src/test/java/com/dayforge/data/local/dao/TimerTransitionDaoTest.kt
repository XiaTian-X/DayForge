package com.dayforge.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TimerTransitionDaoTest {
    private lateinit var database: HabitDatabase
    private lateinit var dao: TimeLogDao
    private var habitId: Long = 0

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HabitDatabase::class.java).build()
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
    fun `pause resume and stop persist one ordered command history`() = runTest {
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
    fun `stale stop rolls back its outbox command`() = runTest {
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
        assertEquals(listOf(1, 2), dao.getPendingTimerCommands().map { it.sequence })
        assertEquals(20_000L, dao.getById(logId)?.endTime)
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
