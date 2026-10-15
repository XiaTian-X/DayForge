package com.dayforge.domain.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.PersonalDataDisplayMemory
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.CountActionIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local interaction admission only. Writers must still authenticate the original claim. */
@Singleton
class CountActionGate internal constructor(
    tokens: TokenManager,
    private val clock: () -> Long,
    private val later: (Long, () -> Unit) -> Unit
) {
    @Inject constructor(tokens: TokenManager) : this(tokens, SystemClock::elapsedRealtime,
        { delay, work -> Handler(Looper.getMainLooper()).postDelayed({ work() }, delay) })

    internal data class Key(val session: LocalDataSession, val uuid: String)
    internal class Lease internal constructor(internal val key: Key, internal val id: Long,
        internal val until: Long) {
        internal var running = true
        internal var invalidated = false
    }
    private val monitor = Any()
    private val entries = mutableMapOf<Key, Lease>()
    private var blocked = false
    private val mutable = MutableStateFlow<Set<Long>>(emptySet())
    val busyHabitIds = mutable.asStateFlow()
    private fun publish() { mutable.value = entries.values.filterNot { it.invalidated }.mapTo(mutableSetOf()) { it.id } }
    private val listener = object : PersonalDataDisplayMemory.Listener {
        override fun transition(blocked: Boolean) = synchronized(monitor) {
            this@CountActionGate.blocked = blocked
            // Invalidate presentation immediately, but a same-session credential refresh must
            // not reopen a still-running command. Other accounts have different keys and proceed.
            entries.values.forEach { it.invalidated = true }
            publish()
        }
    }
    init { tokens.registerPersonalDisplay(listener) }

    internal fun tryAcquire(identity: CountActionIdentity): Lease? =
        tryAcquire(identity.session, identity.habitUuid, identity.habitId)

    /** Publication hint only, never used to authorize a write. */
    internal fun isCurrent(identity: CountActionIdentity): Boolean = synchronized(monitor) {
        !blocked && entries[Key(identity.session, identity.habitUuid)]?.let { it.running && !it.invalidated } == true
    }

    internal fun tryAcquire(session: LocalDataSession, uuid: String, id: Long): Lease? = synchronized(monitor) {
        val now = clock()
        entries.entries.removeAll { !it.value.running && it.value.until <= now }
        val key = Key(session, uuid)
        if (blocked || entries.containsKey(key)) { publish(); return null }
        Lease(key, id, now + 300L).also { entries[key] = it; publish() }
    }

    internal fun release(lease: Lease): Unit = synchronized(monitor) {
        if (entries[lease.key] !== lease) return
        lease.running = false
        val remaining = lease.until - clock()
        if (remaining <= 0) { entries.remove(lease.key); publish() }
        else later(remaining) { release(lease) }
    }

    /** Acquire before dispatch; completion handler also covers an already cancelled scope. */
    internal fun launch(scope: CoroutineScope, identity: CountActionIdentity, block: suspend () -> Unit): Job? {
        val lease = tryAcquire(identity) ?: return null
        return try {
            scope.launch(start = CoroutineStart.LAZY) { block() }.also { job ->
                job.invokeOnCompletion { release(lease) }
                job.start()
            }
        } catch (error: Throwable) { release(lease); throw error }
    }
}
