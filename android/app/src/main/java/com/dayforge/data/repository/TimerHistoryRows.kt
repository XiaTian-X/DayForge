package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.data.local.entity.TimeLogDayAllocationEntity

/** SELECT hints within the reader's audited transaction, never a history/authority cache.
 * A bounded result must be complete; overflow falls back to the original per-session query.
 */
internal class TimerHistoryRows private constructor(
    private val sessions: Set<String>,
    private val segments: Map<String, List<TimerSegmentEntity>>?,
    private val allocations: Map<String, List<TimeLogDayAllocationEntity>>?
) {
    suspend fun segments(database: HabitDatabase, session: String): List<TimerSegmentEntity> {
        check(database.inTransaction() && session in sessions)
        return segments?.get(session).orEmpty().takeIf { segments != null }
            ?: database.timeLogDao().getTimerSegments(session)
    }

    suspend fun allocations(database: HabitDatabase, session: String): List<TimeLogDayAllocationEntity> {
        check(database.inTransaction() && session in sessions)
        return allocations?.get(session).orEmpty().takeIf { allocations != null }
            ?: database.timeLogDao().getDayAllocations(session)
    }

    companion object {
        suspend fun read(database: HabitDatabase, sessions: List<String>): TimerHistoryRows {
            check(database.inTransaction() && sessions.size in 1..128 && sessions.distinct().size == sessions.size)
            val dao = database.timeLogDao()
            val segments = dao.getHistorySegmentHints(sessions)
            val allocations = dao.getHistoryAllocationHints(sessions)
            return TimerHistoryRows(sessions.toSet(),
                segments.takeIf { it.size <= 256 }?.groupBy { it.sessionUuid },
                allocations.takeIf { it.size <= 256 }?.groupBy { it.sessionUuid })
        }
    }
}
