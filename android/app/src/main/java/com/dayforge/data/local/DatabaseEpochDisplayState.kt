package com.dayforge.data.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** State retained by a screen, with an epoch check at the actual replay-cache assignment.
 * A delayed old Flow value cannot be published after replacement. Initial is the caller's
 * existing loading value, never a fabricated verified empty business result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> DatabaseRuntimeHost.displayState(
    scope: CoroutineScope,
    started: SharingStarted,
    initial: T,
    onInitialized: (Boolean) -> Unit = {},
    source: (DatabaseEpochGraph) -> Flow<T>
): StateFlow<T> = EpochDisplayState(this, scope, started, initial, onInitialized, source)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
private class EpochDisplayState<T>(
    host: DatabaseRuntimeHost,
    scope: CoroutineScope,
    started: SharingStarted,
    private val initial: T,
    private val initialized: (Boolean) -> Unit,
    source: (DatabaseEpochGraph) -> Flow<T>,
    private val state: MutableStateFlow<T> = MutableStateFlow(initial)
) : StateFlow<T> by state {
    private val listener = object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            if (blocked) { state.value = initial; initialized(false) }
        }
    }
    // Strong ownership is local to this screen state, while the host keeps only a weak entry.
    private val registration = host.observeMemory(listener)
    init {
        scope.launch {
            started.command(state.subscriptionCount).collectLatest { command ->
                when (command) {
                    SharingCommand.START -> host.observe(source).collect { emission ->
                        emission.publication.publish { state.value = emission.value; initialized(true) }
                    }
                    SharingCommand.STOP -> Unit
                    SharingCommand.STOP_AND_RESET_REPLAY_CACHE -> state.value = initial
                }
            }
        }
    }
}
