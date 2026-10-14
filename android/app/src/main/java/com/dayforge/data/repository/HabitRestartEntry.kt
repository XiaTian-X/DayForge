package com.dayforge.data.repository

import com.dayforge.data.local.entity.HabitEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Public repository boundary; internal challenge wire/proof types never reach UI consumers. */
@Singleton
class HabitRestartEntry @Inject internal constructor(private val restart: NextChallengeRestartRepository) {
    internal suspend fun commit(habit: HabitEntity, authority: ObjectEditAuthority) {
        restart.restart(habit, authority)
    }
}

/** Memory-only confirmation bound to the displayed plan/account/round, never a saved credential.
 * Repeated taps share one local commit; cancellation cannot mint a fresh round on retry.
 */
class HabitReactivationRequest internal constructor(
    val habit: HabitEntity,
    internal val authority: ObjectEditAuthority?,
    internal val legacyAccess: com.dayforge.data.local.LocalCoreWriteAccess? = null
) {
    val preservesHistory: Boolean get() = authority?.rounds != null
    private val mutex = Mutex()
    private var committed = false
    internal suspend fun commit(action: suspend () -> Unit): Boolean = mutex.withLock {
        if (committed) false else {
            action()
            committed = true
            true
        }
    }
}
