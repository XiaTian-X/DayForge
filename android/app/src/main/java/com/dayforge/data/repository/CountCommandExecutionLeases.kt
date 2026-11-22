package com.dayforge.data.repository

import com.dayforge.data.local.CountTrustRuntime
import java.util.WeakHashMap
import kotlinx.coroutines.Job

/** Process-local exclusion for a particular command, NOT authorization or durable fate.
 * A new process relies on primary/native recovery, not this empty map. Different command
 * IDs remain independent; this is not a new persistent single-command-per-habit gate.
 */
internal object CountCommandExecutionLeases {
    private val owners = WeakHashMap<CountTrustRuntime, MutableMap<String, Lease>>()

    class Lease private constructor(private val owner: CountTrustRuntime,
        private val commandId: String, val caller: Job) {
        private var closed = false
        private var completion: kotlinx.coroutines.DisposableHandle? = null
        private fun attach(handle: kotlinx.coroutines.DisposableHandle) = synchronized(owners) {
            if (closed) handle.dispose() else completion = handle
        }
        fun requireCaller(job: Job?) = synchronized(owners) {
            check(!closed && job === caller && caller.isActive && owners[owner]?.get(commandId) === this) {
                "COMMAND_EXECUTION_LEASE_EXPIRED"
            }
        }
        fun close() = synchronized(owners) {
            if (!closed) {
                closed = true
                completion?.dispose(); completion = null
                if (owners[owner]?.get(commandId) === this) {
                    owners[owner]?.remove(commandId)
                    if (owners[owner]?.isEmpty() == true) owners.remove(owner)
                }
            }
        }
        companion object {
            internal fun acquire(owner: CountTrustRuntime, commandId: String, caller: Job): Lease? = synchronized(owners) {
                val commands = owners.getOrPut(owner) { linkedMapOf() }
                if (commands.containsKey(commandId)) null else Lease(owner, commandId, caller).also { lease ->
                    commands[commandId] = lease
                    // Completion (not merely cancellation requested) means an old native T1
                    // has finished unwinding. A cancelled but still-running transaction keeps
                    // its lease until the private producer finally releases it.
                    lease.attach(caller.invokeOnCompletion { lease.close() })
                }
            }
        }
    }

    fun acquire(owner: CountTrustRuntime, commandId: String, caller: Job): Lease? = Lease.acquire(owner, commandId, caller)
}
