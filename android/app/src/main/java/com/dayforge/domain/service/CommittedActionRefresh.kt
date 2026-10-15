package com.dayforge.domain.service

import com.dayforge.data.repository.HabitReadSnapshotChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

/** A fact was committed. Never suggest replaying its write when a follow-up cannot be read. */
internal class CommittedActionRefreshFailed(cause: Exception) : Exception("COMMITTED_ACTION_REFRESH_FAILED", cause)

internal suspend fun <T> afterCommittedAction(block: suspend () -> T): T = try {
    block()
} catch (error: Exception) {
    if (error is CancellationException || error is CommittedActionRefreshFailed) throw error
    throw CommittedActionRefreshFailed(error)
}

/** Read-only retry; authentication, identity changes and corrupt evidence fail immediately. */
internal suspend fun <T> readCurrentHabitSnapshot(block: suspend () -> T): T {
    var failures = 0
    while (true) {
        try { return block() }
        catch (error: HabitReadSnapshotChanged) {
            if (failures++ >= 3) throw error
            yield()
        }
    }
}
