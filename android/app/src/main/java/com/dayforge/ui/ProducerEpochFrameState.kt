package com.dayforge.ui

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

/** Preserve the exact original producer wrapper, including nullable clear frames. The
 * producer's final guard supplies its account/request/epoch check under its own lock order;
 * this adapter must not take the Host publication lock before invoking that guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> producerEpochFrameState(scope: CoroutineScope, host: DatabaseRuntimeHost,
    started: SharingStarted, source: Flow<DatabaseEpochValue<T>?>,
    publish: (DatabaseEpochValue<T>?, () -> Unit) -> Boolean): StateFlow<DatabaseEpochValue<T>?> =
    ProducerEpochFrameState(scope, host, started, source, publish)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
private class ProducerEpochFrameState<T>(scope: CoroutineScope, host: DatabaseRuntimeHost,
    started: SharingStarted, source: Flow<DatabaseEpochValue<T>?>,
    publish: (DatabaseEpochValue<T>?, () -> Unit) -> Boolean,
    private val state: MutableStateFlow<DatabaseEpochValue<T>?> = MutableStateFlow(null)
) : StateFlow<DatabaseEpochValue<T>?> by state {
    private val monitor = Any()
    private val listener = object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            val before = synchronized(monitor) { state.value }
            val obsolete = blocked || before?.publication?.isCurrentInMemory() != true
            if (obsolete) synchronized(monitor) { if (state.value === before) state.value = null }
        }
    }
    private val registration = host.observeMemory(listener)
    init {
        scope.launch {
            started.command(state.subscriptionCount).collectLatest { command ->
                when (command) {
                    SharingCommand.START -> source.collect { frame ->
                        publish(frame) { synchronized(monitor) { state.value = frame } }
                    }
                    SharingCommand.STOP -> Unit
                    SharingCommand.STOP_AND_RESET_REPLAY_CACHE -> synchronized(monitor) { state.value = null }
                }
            }
        }
    }
}
