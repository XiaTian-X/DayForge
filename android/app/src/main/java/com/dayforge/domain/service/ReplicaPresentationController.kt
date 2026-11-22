package com.dayforge.domain.service

import com.dayforge.data.local.ReplicaPresentationMemory
import com.dayforge.data.local.TokenManager
import com.dayforge.data.repository.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal sealed interface ReplicaPresentationState {
    data object Checking : ReplicaPresentationState
    class Open(val permit: ReplicaDisplayPermit) : ReplicaPresentationState
    data class Pending(val check: ReplicaPresentationCheck) : ReplicaPresentationState
}

/** Ephemeral draw/lifetime guard only, never a writer/recovery activation ticket. */
internal class ReplicaDisplayPermit internal constructor(val lifetime: Long, private val live: () -> Boolean) {
    fun canRender(): Boolean = live()
}

@Singleton
class ReplicaPresentationController private constructor(tokens: TokenManager,
    private val fixed: ReplicaPresentationRepository?, private val host: com.dayforge.data.local.DatabaseRuntimeHost?) : AutoCloseable {
    constructor(tokens: TokenManager, repository: ReplicaPresentationRepository) : this(tokens, repository, null)
    @Inject internal constructor(tokens: TokenManager, host: com.dayforge.data.local.DatabaseRuntimeHost) : this(tokens, null, host)
    private val changes get() = host?.controlHints { it.replicaPresentationRepository.changes } ?: requireNotNull(fixed).changes
    private suspend fun inspect() = if (host == null) Triple(requireNotNull(fixed).inspect(), null, null)
        else host.controlSnapshot {
            it.replicaPresentationRepository.inspect() to host.currentPublication()
        }.let { Triple(it.value.first, it.publication, it.value.second) }
    private val monitor = Any()
    private var version = 0L
    private var blocked = false
    private var graphBlocked = false
    private var closed = false
    private val reload = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow<ReplicaPresentationState>(ReplicaPresentationState.Checking)
    internal val state = mutable.asStateFlow()
    private val cache = object : ReplicaPresentationMemory.Cache {
        override fun transition(blocked: Boolean) = synchronized(monitor) {
            version++; this@ReplicaPresentationController.blocked = blocked
            mutable.value = ReplicaPresentationState.Checking
            reload.trySend(Unit)
            Unit
        }
    }
    private val graphListener = object : com.dayforge.data.local.DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) = synchronized(monitor) {
            version++; graphBlocked = blocked
            mutable.value = ReplicaPresentationState.Checking
            reload.trySend(Unit)
            Unit
        }
    }
    private val graphRegistration = host?.observeMemory(graphListener)
    init {
        tokens.registerReplicaPresentation(cache)
        scope.launch {
            merge(changes, reload.receiveAsFlow()).collectLatest {
                val stamp = synchronized(monitor) { if (blocked || graphBlocked || closed) null else version } ?: return@collectLatest
                val (check, epoch, businessEpoch) = try { inspect() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Triple(ReplicaPresentationCheck(ReplicaPresentationProblem.DAMAGED_STATE), null, null)
                }
                publishCheck(stamp, check, epoch, businessEpoch)
            }
        }
    }
    private fun publishCheck(stamp: Long, check: ReplicaPresentationCheck,
        epoch: com.dayforge.data.local.DatabaseEpochPublication?,
        businessEpoch: com.dayforge.data.local.DatabaseEpochPublication?) {
        fun assign(): Boolean = synchronized(monitor) {
            if (closed || blocked || graphBlocked || stamp != version) false
            else {
                fun update() {
                    if (check.problem != null) {
                        if (mutable.value is ReplicaPresentationState.Open) version++
                        mutable.value = ReplicaPresentationState.Pending(check)
                    } else if (businessEpoch?.isCurrentInMemory() == false) {
                        if (mutable.value is ReplicaPresentationState.Open) version++
                        mutable.value = ReplicaPresentationState.Checking
                    } else if (mutable.value !is ReplicaPresentationState.Open) {
                        lateinit var permit: ReplicaDisplayPermit
                        permit = ReplicaDisplayPermit(stamp) { canRender(permit, stamp, epoch, businessEpoch) }
                        mutable.value = ReplicaPresentationState.Open(permit)
                    }
                }
                if (epoch == null) { update(); true } else epoch.publish(::update)
            }
        }
        if (epoch == null) assign() else epoch.withSourceFence(::assign)
    }
    private fun canRender(permit: ReplicaDisplayPermit, stamp: Long,
        epoch: com.dayforge.data.local.DatabaseEpochPublication?,
        businessEpoch: com.dayforge.data.local.DatabaseEpochPublication?): Boolean {
        fun current(): Boolean = synchronized(monitor) {
            !closed && !blocked && !graphBlocked && version == stamp &&
                (mutable.value as? ReplicaPresentationState.Open)?.permit === permit && epoch?.isCurrentInMemory() != false &&
                businessEpoch?.isCurrentInMemory() != false
        }
        return if (epoch == null) current() else epoch.withSourceFence(::current)
    }

    /** Read-only recheck, not a network retry or a recovery command. */
    fun retry() { reload.trySend(Unit) }
    override fun close() {
        synchronized(monitor) { closed = true; version++; mutable.value = ReplicaPresentationState.Checking }
        scope.cancel(); reload.close()
    }
    internal suspend fun awaitClosed() { scope.coroutineContext[Job]?.join() }
}
