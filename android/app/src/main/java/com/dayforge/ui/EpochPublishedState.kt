package com.dayforge.ui

import com.dayforge.data.local.DatabaseEpochPublication
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseRuntimeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Retains an emission's original epoch through the final replay-cache assignment. The
 * source may be shared by several screen fields; this adapter never starts another DB read
 * or obtains a fresh current epoch for old data. A value may itself be null: that original
 * clear has the same lifetime check as an original non-null result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> epochPublishedState(scope: CoroutineScope, host: DatabaseRuntimeHost,
    started: SharingStarted, initial: T, source: Flow<DatabaseEpochValue<T>>,
    initialized: (Boolean) -> Unit = {}): StateFlow<T> =
    EpochPublishedState(scope, host, started, initial, source, initialized)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
private class EpochPublishedState<T>(scope: CoroutineScope, host: DatabaseRuntimeHost,
    started: SharingStarted, private val initial: T, source: Flow<DatabaseEpochValue<T>>,
    private val initialized: (Boolean) -> Unit,
    private val state: MutableStateFlow<T> = MutableStateFlow(initial)) : StateFlow<T> by state {
    private val monitor = Any()
    private var origin: DatabaseEpochPublication? = null
    private fun clearLocked() { origin = null; state.value = initial; initialized(false) }
    private val listener = object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            // Host callbacks do not hold its publication lock. Do not reverse the lock order
            // by asking a publication about the Host while holding this local monitor.
            val before = synchronized(monitor) { origin }
            val obsolete = blocked || before?.isCurrentInMemory() != true
            if (obsolete) synchronized(monitor) { if (origin === before) clearLocked() }
        }
    }
    private val registration = host.observeMemory(listener)
    init {
        scope.launch {
            started.command(state.subscriptionCount).collectLatest { command ->
                when (command) {
                    SharingCommand.START -> source.collect { emission ->
                        emission.publication.publish {
                            synchronized(monitor) {
                                origin = emission.publication
                                state.value = emission.value
                                initialized(true)
                            }
                        }
                    }
                    SharingCommand.STOP -> Unit
                    SharingCommand.STOP_AND_RESET_REPLAY_CACHE -> synchronized(monitor) { clearLocked() }
                }
            }
        }
    }
}
