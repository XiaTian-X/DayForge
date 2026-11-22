package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.DatabaseEpochPublication
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.repository.HabitRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The stable coordinator retains prompt state; repositories come from the complete pinned graph. */
class HabitDeletionCoordinator private constructor(
    private val context: Context,
    private val fixedRepository: HabitRepository?,
    private val databases: DatabaseRuntimeHost?,
    private val stableTokens: com.dayforge.data.local.TokenManager?
) {
    constructor(context: Context, habitRepository: HabitRepository) : this(context, habitRepository, null, null)
    @Inject internal constructor(@ApplicationContext context: Context, databases: DatabaseRuntimeHost,
        tokens: com.dayforge.data.local.TokenManager) : this(context, null, databases, tokens)
    private val habitRepository get() = fixedRepository ?: requireNotNull(databases).boundGraph().habitRepository
    private val presentationMonitor = Any()
    private var blocked = false
    private var accountGeneration = 0L
    private val _pendingDeletion = MutableStateFlow<PendingHabitDeletion?>(null)
    val pendingDeletion: StateFlow<PendingHabitDeletion?> = _pendingDeletion.asStateFlow()
    private val _pendingDeletionFrames = MutableStateFlow<DatabaseEpochValue<PendingHabitDeletion?>?>(null)
    internal val pendingDeletionFrames = _pendingDeletionFrames.asStateFlow()
    private val registration = databases?.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            if (blocked) synchronized(presentationMonitor) { _pendingDeletion.value = null; _pendingDeletionFrames.value = null }
        }
    })

    private val accountInvalidator = object : com.dayforge.data.local.PersonalDataDisplayMemory.Listener {
        override fun transition(blocked: Boolean) = synchronized(presentationMonitor) {
            this@HabitDeletionCoordinator.blocked = blocked
            accountGeneration = Math.addExact(accountGeneration, 1L)
            _pendingDeletion.value = null; _pendingDeletionFrames.value = null
        }
    }
    init { if (databases != null) requireNotNull(stableTokens).registerPersonalDisplay(accountInvalidator) }

    internal fun publishPendingDeletion(frame: DatabaseEpochValue<PendingHabitDeletion?>?, assignment: () -> Unit): Boolean {
        fun assign() = synchronized(presentationMonitor) {
            if (_pendingDeletionFrames.value !== frame || frame?.value != null && blocked) false
            else if (frame == null) { assignment(); true } else frame.publication.publish(assignment)
        }
        return if (frame == null) assign() else frame.publication.withSourceFence(::assign)
    }

    private fun publish(value: PendingHabitDeletion?, publication: DatabaseEpochPublication?,
        expectedGeneration: Long? = null) = synchronized(presentationMonitor) {
        if (databases == null) _pendingDeletion.value = value
        else if (value == null || !blocked && expectedGeneration == accountGeneration) {
            _pendingDeletion.value = value
            _pendingDeletionFrames.value = if (value == null) null else DatabaseEpochValue(value, requireNotNull(publication))
        }
    }

    /** Host callers already pin the source frame with withSourceGraph; no current-epoch lookup
     * outside a graph operation can bless an old numeric-ID selection. */
    suspend fun requestDeletion(habit: HabitEntity) {
        val publication = databases?.currentPublication()
        val generation = synchronized(presentationMonitor) { accountGeneration }
        val snapshot = if (habit.appearance != null) habitRepository.getHabitForEditing(habit.id)
            else com.dayforge.data.repository.ObjectEditSnapshot(habit, null)
        val authority = snapshot.authority
        check(snapshot.value == habit) { "对象已变更，请刷新后再删除" }
        val children = habitRepository.getHabitChildren(habit.uuid)
        if (children.isEmpty()) habitRepository.deleteHabit(habit, context, authority = authority)
        else publish(PendingHabitDeletion(habit, children.size, authority), publication, generation)
    }

    /** Capture the actual rendered frame, not the latest pending value after coroutine dispatch. */
    internal fun captureDeletion(frame: DatabaseEpochValue<PendingHabitDeletion?>, withChildren: Boolean): suspend () -> Unit {
        val expected = requireNotNull(frame.value) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        val host = requireNotNull(databases)
        return {
            host.withSourceGraph(frame.publication) {
                synchronized(presentationMonitor) {
                    check(!blocked && _pendingDeletionFrames.value === frame && _pendingDeletion.value === expected) {
                        "DATABASE_EPOCH_SELECTION_CHANGED"
                    }
                }
                delete(expected, withChildren)
                // A different request cannot be cleared by this operation's later completion.
                synchronized(presentationMonitor) {
                    if (_pendingDeletionFrames.value === frame) publish(null, frame.publication)
                }
            }
        }
    }

    private suspend fun delete(pending: PendingHabitDeletion, withChildren: Boolean) {
        if (withChildren) habitRepository.deleteHabitWithChildren(pending.habit, context, pending.authority)
        else habitRepository.deleteHabitOrphanChildren(pending.habit, context, pending.authority)
    }

    /** Original direct API is retained for fixed-graph callers, never a Host source bypass. */
    suspend fun deleteWithChildren() {
        check(databases == null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        val pending = _pendingDeletion.value ?: return
        delete(pending, true); _pendingDeletion.value = null
    }
    suspend fun deleteKeepingChildren() {
        check(databases == null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        val pending = _pendingDeletion.value ?: return
        delete(pending, false); _pendingDeletion.value = null
    }
    fun dismissDeletion() {
        check(databases == null) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        _pendingDeletion.value = null
    }
    internal fun dismissDeletion(frame: DatabaseEpochValue<PendingHabitDeletion?>) {
        requireNotNull(databases)
        synchronized(presentationMonitor) {
            if (_pendingDeletionFrames.value !== frame) return
            _pendingDeletion.value = null
            _pendingDeletionFrames.value = null
        }
    }
}

data class PendingHabitDeletion(
    val habit: HabitEntity,
    val childCount: Int,
    val authority: com.dayforge.data.repository.ObjectEditAuthority? = null
)
