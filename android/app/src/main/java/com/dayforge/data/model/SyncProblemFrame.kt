package com.dayforge.data.model

import com.dayforge.data.local.DatabaseEpochPublication
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.repository.NextRecoveryKey
import java.util.Collections

/** Original complete read result and its original graph lifetime, never a credential cache. */
internal class SyncProblemFrame internal constructor(value: SyncProblems,
    internal val business: DatabaseEpochPublication, internal val control: DatabaseEpochPublication) {
    val value: SyncProblems = when (value) {
        is SyncProblems.Legacy -> value.copy(changes = Collections.unmodifiableList(value.changes.toList()),
            conflicts = Collections.unmodifiableList(value.conflicts.toList()), timers = Collections.unmodifiableList(value.timers.toList()))
        is SyncProblems.Next -> value.copy(items = Collections.unmodifiableList(value.items.map {
            it.copy(fields = Collections.unmodifiableList(it.fields.toList()))
        }))
        else -> value
    }
    internal val publication get() = if (value is SyncProblems.Legacy) business else control
    fun isCurrent(): Boolean = publication.isCurrentInMemory()
    fun publish(block: () -> Unit): Boolean = publication.publish(block)
    fun withSourceFence(block: () -> Boolean): Boolean = publication.withSourceFence(block)
    private fun legacy(): SyncProblems.Legacy = requireNotNull(value as? SyncProblems.Legacy) { "SYNC_PROBLEM_INPUT_CHANGED" }
    fun change(row: SyncOutboxEntity): SyncProblemChange {
        val original = legacy()
        check(original.changes.any { it === row }) { "SYNC_PROBLEM_INPUT_CHANGED" }
        return SyncProblemChange(requireNotNull(original.session), row)
    }
    fun conflict(row: SyncConflictEntity): SyncProblemConflict {
        val original = legacy()
        check(original.conflicts.any { it === row }) { "SYNC_PROBLEM_INPUT_CHANGED" }
        return SyncProblemConflict(requireNotNull(original.session), row)
    }
    fun timer(row: TimerCommandEntity): SyncProblemTimer {
        val original = legacy()
        check(original.timers.any { it === row }) { "SYNC_PROBLEM_INPUT_CHANGED" }
        return SyncProblemTimer(requireNotNull(original.session), row)
    }
    fun requireRecovery(key: NextRecoveryKey) {
        check((value as? SyncProblems.Next)?.items?.any { it.kind == key.kind && it.requestId == key.requestId } == true) {
            "SYNC_PROBLEM_INPUT_CHANGED"
        }
    }
}
internal class SyncProblemChange internal constructor(val session: LocalDataSession, val row: SyncOutboxEntity)
internal class SyncProblemConflict internal constructor(val session: LocalDataSession, val row: SyncConflictEntity)
internal class SyncProblemTimer internal constructor(val session: LocalDataSession, val row: TimerCommandEntity)
