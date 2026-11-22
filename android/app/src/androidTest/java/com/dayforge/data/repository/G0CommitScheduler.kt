package com.dayforge.data.repository

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Test-only joint admission. No authentication, SQL permission or certificate is implied.
 * No coroutine is resumed, and no user code/IO runs, inside the metadata monitor.
 * Resource ownership ends only when the structured operation (including Room end) unwinds.
 * Framework work outside this owner is NOT claimed to participate in admission.
 */
internal class G0CommitScheduler(private val clock: () -> Long = SystemClock::elapsedRealtimeNanos) {
    enum class Kind { WRITE, ACK, READ, AUTH, MAINTENANCE }
    enum class Resources(val connection: Boolean, val auth: Boolean) {
        JOINT(true, true), AUTH_ONLY(false, true), CONNECTION_ONLY(true, false)
    }
    data class Sample(val kind: Kind, val queuedNs: Long, val occupiedNs: Long, val completed: Boolean)
    private class Ticket(val kind: Kind, val resources: Resources, val enqueued: Long) {
        val ready = CompletableDeferred<Unit>()
        var admitted: Long? = null
    }
    private class Owned(val scheduler: G0CommitScheduler) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Owned>
    }
    private val monitor = Any()
    private val waiting = mutableListOf<Ticket>()
    private val active = mutableSetOf<Ticket>()
    private val samples = mutableListOf<Sample>()
    private var foreground = 0
    private var quarantined = false

    fun quarantine() {
        val rejected = synchronized(monitor) { quarantined = true; waiting.toList().also { waiting.clear() } }
        rejected.forEach { it.ready.completeExceptionally(IllegalStateException("G0_DOMAIN_RECOVERY_REQUIRED")) }
    }

    fun samples(): List<Sample> = synchronized(monitor) { samples.toList() }
    fun pendingForTest(): Int = synchronized(monitor) { waiting.size }

    private fun dispatchLocked(): List<Ticket> {
        if (quarantined) return emptyList()
        val granted = mutableListOf<Ticket>()
        while (waiting.isNotEmpty()) {
            val ack = waiting.firstOrNull { it.kind == Kind.ACK }
            // Reserve BEFORE the 500 ms target: a newly admitted stage may take 150 ms.
            val reserve = ack != null && (foreground >= 2 || clock() - ack.enqueued >= 350_000_000L)
            fun fits(ticket: Ticket) = active.none {
                (ticket.resources.connection && it.resources.connection) || (ticket.resources.auth && it.resources.auth)
            }
            val next = if (reserve) ack?.takeIf(::fits) else waiting.firstOrNull(::fits)
            if (next == null) break
            waiting.remove(next)
            next.admitted = clock()
            active.add(next)
            if (next.kind == Kind.ACK) foreground = 0 else if (ack != null) foreground++
            granted.add(next)
        }
        return granted
    }

    suspend fun <T> run(kind: Kind, resources: Resources = Resources.JOINT, block: suspend () -> T): T {
        check(currentCoroutineContext()[Owned] == null) { "G0_REENTRANT_ADMISSION" }
        currentCoroutineContext().ensureActive()
        val ticket = Ticket(kind, resources, clock())
        var completed = false
        try {
            val granted = synchronized(monitor) {
                check(!quarantined) { "G0_DOMAIN_RECOVERY_REQUIRED" }
                waiting.add(ticket); dispatchLocked()
            }
            granted.forEach { it.ready.complete(Unit) }
            ticket.ready.await()
            currentCoroutineContext().ensureActive()
            return withContext(Owned(this)) { block() }.also { completed = true }
        } finally {
            // Includes cancellation between grant and await delivery. Never leak either slot.
            val granted = synchronized(monitor) {
                waiting.remove(ticket)
                if (active.remove(ticket)) {
                    val admitted = checkNotNull(ticket.admitted)
                    samples.add(Sample(kind, admitted - ticket.enqueued, clock() - admitted, completed))
                }
                if (waiting.none { it.kind == Kind.ACK }) foreground = 0
                dispatchLocked()
            }
            granted.forEach { it.ready.complete(Unit) }
        }
    }
}
