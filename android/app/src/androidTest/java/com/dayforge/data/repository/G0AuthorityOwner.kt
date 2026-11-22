package com.dayforge.data.repository

import androidx.datastore.core.DataStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Isolated authority ordering capability, not authentication or a production account runtime.
 * All writes to its private DataStore must pass through wrap(). No credential data is cached.
 * A permit covers a SINGLE actual final/engine end, and is released by the transaction owner.
 * Unknown persistence poisons admission until a new owner has independently recovered state.
 */
internal class G0AuthorityOwner {
    sealed interface Lease
    private data class Ticket(val owner: G0AuthorityOwner, val revision: Long) : Lease
    private data class State(val revision: Long = 0, val transition: Boolean = false,
        val quarantined: Boolean = false, val permits: Int = 0,
        val drained: CompletableDeferred<Unit>? = null)
    class Denied : IllegalStateException("G0_AUTHORITY_REVOKED")
    private val state = AtomicReference(State())
    // Memory-only revocation callbacks. No SQL, credentials, waiting or reentry into DataStore.
    private val reservationListeners=java.util.concurrent.CopyOnWriteArrayList<()->Unit>()
    fun observeReservations(clear:()->Unit):AutoCloseable {
        reservationListeners+=clear
        val now=state.get()
        if(now.transition || now.quarantined) clear()
        return AutoCloseable { reservationListeners.remove(clear) }
    }
    // Only credential mutations use this mutex. Final never waits for it or a DataStore writer.
    private val mutations = Mutex()
    var onTransitionForTest: suspend () -> Unit = {}
    var afterPersistenceForTest: suspend () -> Unit = {}

    fun lease(): Lease {
        val now = state.get()
        if (now.transition || now.quarantined) throw Denied()
        return Ticket(this, now.revision)
    }

    fun checkLease(lease: Lease) {
        val ticket = lease as? Ticket ?: throw Denied()
        val now = state.get()
        if (ticket.owner !== this || ticket.revision != now.revision || now.transition || now.quarantined) throw Denied()
    }

    fun finalPermit(lease: Lease): AutoCloseable {
        val ticket = lease as? Ticket ?: throw Denied()
        while (true) {
            val before = state.get()
            if (ticket.owner !== this || ticket.revision != before.revision || before.transition || before.quarantined) throw Denied()
            if (state.compareAndSet(before, before.copy(permits = Math.addExact(before.permits, 1)))) break
        }
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                while (true) {
                    val before = state.get()
                    check(before.permits > 0)
                    val after = before.copy(permits = before.permits - 1)
                    if (state.compareAndSet(before, after)) {
                        if (after.permits == 0) after.drained?.complete(Unit)
                        break
                    }
                }
            }
        }
    }

    fun <T> wrap(delegate: DataStore<T>): DataStore<T> = object : DataStore<T> {
        override val data get() = delegate.data
        override suspend fun updateData(transform: suspend (t: T) -> T): T = mutations.withLock {
            val caller = currentCoroutineContext()
            caller.ensureActive()
            // Reservation happens BEFORE entering the real DataStore actor. Never wait for a
            // final permit from inside transform: final itself needs a fresh persisted snapshot.
            withContext(NonCancellable) {
                val drained = CompletableDeferred<Unit>()
                while (true) {
                    val before = state.get()
                    if (before.transition || before.quarantined) throw Denied()
                    val after = before.copy(revision = Math.addExact(before.revision, 1),
                        transition = true, drained = drained)
                    if (state.compareAndSet(before, after)) {
                        if (after.permits == 0) drained.complete(Unit)
                        break
                    }
                }
                try {
                    reservationListeners.forEach { it() }
                    onTransitionForTest()
                    drained.await()
                    caller.ensureActive()
                    val result = delegate.updateData(transform)
                    afterPersistenceForTest()
                    // The actual persistence completed. A caller cancellation after this point
                    // can cancel delivery, but cannot pretend the credentials rolled back.
                    val before = state.get()
                    check(before.transition && before.permits == 0)
                    check(state.compareAndSet(before, before.copy(transition = false, drained = null)))
                    result
                } catch (failure: Throwable) {
                    while (true) {
                        val before = state.get()
                        if (state.compareAndSet(before, before.copy(quarantined = true))) break
                    }
                    throw failure
                }
            }
        }
    }
}
