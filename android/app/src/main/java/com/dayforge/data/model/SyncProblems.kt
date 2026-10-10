package com.dayforge.data.model

import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimerCommandEntity

/** One account-bound snapshot. Next rows deliberately have no legacy mutation handles. */
internal sealed interface SyncProblems {
    data object Checking : SyncProblems
    data object Unavailable : SyncProblems
    data class Legacy(val session: LocalDataSession?, val changes: List<SyncOutboxEntity>,
        val conflicts: List<SyncConflictEntity>, val timers: List<TimerCommandEntity>) : SyncProblems
    data class Next(val items: List<NextSyncProblem>) : SyncProblems

    val count: Int get() = when (this) {
        Checking -> 0
        Unavailable -> 1 // An unreadable snapshot must not look like an empty, healthy queue.
        is Legacy -> changes.size + conflicts.size + timers.size
        is Next -> items.size
    }
}

internal data class NextSyncProblem(val kind: String, val requestId: String, val entityType: String,
    val entityUuid: String, val code: String, val fields: List<String> = emptyList())
