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
class ReplicaPresentationController @Inject constructor(tokens: TokenManager,
    private val repository: ReplicaPresentationRepository) : AutoCloseable {
    private val monitor = Any()
    private var version = 0L
    private var blocked = false
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
    init {
        tokens.registerReplicaPresentation(cache)
        scope.launch {
            merge(repository.changes, reload.receiveAsFlow()).collectLatest {
                val stamp = synchronized(monitor) { if (blocked || closed) null else version } ?: return@collectLatest
                val check = try { repository.inspect() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    ReplicaPresentationCheck(ReplicaPresentationProblem.DAMAGED_STATE)
                }
                synchronized(monitor) {
                    if (!closed && !blocked && stamp == version) {
                        if (check.problem != null) {
                            if (mutable.value is ReplicaPresentationState.Open) version++
                            mutable.value = ReplicaPresentationState.Pending(check)
                        }
                        else if (mutable.value !is ReplicaPresentationState.Open) {
                            // Captures this exact issued permit; stale collectors/cancel/ABA cannot revive it.
                            lateinit var permit: ReplicaDisplayPermit
                            permit = ReplicaDisplayPermit(stamp) { synchronized(monitor) {
                                !closed && !blocked && version == stamp &&
                                    (mutable.value as? ReplicaPresentationState.Open)?.permit === permit
                            } }
                            mutable.value = ReplicaPresentationState.Open(permit)
                        }
                    }
                }
            }
        }
    }
    /** Read-only recheck, not a network retry or a recovery command. */
    fun retry() { reload.trySend(Unit) }
    override fun close() {
        synchronized(monitor) { closed = true; version++; mutable.value = ReplicaPresentationState.Checking }
        scope.cancel(); reload.close()
    }
    internal suspend fun awaitClosed() { scope.coroutineContext[Job]?.join() }
}
