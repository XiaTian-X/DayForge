package com.dayforge.ui

import com.dayforge.data.local.DatabaseCreationInput
import com.dayforge.data.local.DatabaseEpochGraph
import com.dayforge.data.local.DatabaseRuntimeHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A creation VM binds once to the original form input. Revocation clears source-derived
 * fields synchronously; late callbacks/Flow emissions cannot repopulate them or rebind. */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
internal class CreationState<T>(
    private val host: DatabaseRuntimeHost?,
    initial: T,
    private val expired: () -> T,
    private val state: MutableStateFlow<T> = MutableStateFlow(initial)
) : StateFlow<T> by state {
    private val monitor = Any()
    private var input: DatabaseCreationInput? = null
    private val registration = host?.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            val original = synchronized(monitor) { input }
            if (original != null && (blocked || !original.publication.isCurrentInMemory())) {
                synchronized(monitor) { if (input === original) state.value = expired() }
            }
        }
    })
    override var value: T
        get() = state.value
        set(value) {
            if (host == null) state.value = value
            else synchronized(monitor) { input }?.publication?.publish { state.value = value }
        }
    fun asStateFlow(): StateFlow<T> = this
    fun bind(original: DatabaseCreationInput) {
        val owner = checkNotNull(host) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        check(original.publication.belongsTo(owner)) { "DATABASE_EPOCH_INPUT_REQUIRED" }
        check(original.publication.publish {
            synchronized(monitor) {
                check(input == null || input === original) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                input = original
            }
        }) { "DATABASE_EPOCH_INPUT_EXPIRED" }
    }
    fun isCurrent(): Boolean = host == null || synchronized(monitor) { input }?.publication?.isCurrentInMemory() == true
    suspend fun <R> withGraph(work: DatabaseRuntimeHost.Work = DatabaseRuntimeHost.Work.COMMAND,
        block: suspend (DatabaseEpochGraph?) -> R): R = if (host == null) block(null)
        else host.withSourceGraph(synchronized(monitor) { input }?.publication, work) { block(it) }
}
