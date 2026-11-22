package com.dayforge.data.repository

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dayforge.data.api.MaterialHttpRoute
import com.dayforge.data.local.TokenCipher
import java.io.IOException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import okhttp3.Call
import okhttp3.EventListener

/** One isolated operation window, including detached HTTP threads. Diagnostics only:
 * every read/decryption still delegates, with no authority or plaintext cache. Overlapping
 * categories are NOT additive. No unrelated work may run in a measured window.
 */
internal class G0SendWindowTrace(private val maxEvents: Int = 1024) {
    data class Event(val kind: String, val start: Long, val end: Long, val ok: Boolean,
        val legacyAttached: Boolean, val threadId: Long)
    data class Snapshot(val started: Long, val ended: Long, val returned: Boolean,
        val events: List<Event>, val openAtClose: Int, val dropped: Int, val roleOverflow: Int,
        val duplicateEnds: Int) {
        val complete get() = openAtClose == 0 && dropped == 0 && roleOverflow == 0 && duplicateEnds == 0
    }
    private class Window(val started: Long) {
        val roles = linkedMapOf<String, String>()
        val events = mutableListOf<Event>()
        var open = 0
        var dropped = 0
        var roleOverflow = 0
        var duplicateEnds = 0
        var closed = false
    }
    private class Ticket(val window: Window, val kind: String, val started: Long,
        val legacyAttached: Boolean, val threadId: Long) { var finished = false }
    private val monitor = Any()
    private var active: Window? = null
    private var completed: Snapshot? = null
    private fun now() = SystemClock.elapsedRealtimeNanos()
    init { require(maxEvents in 1..4096) }
    fun snapshot(): Snapshot = synchronized(monitor) { requireNotNull(completed) }
    fun retainedRoleCount(): Int = synchronized(monitor) { active?.roles?.size ?: 0 }

    suspend fun <T> capture(body: suspend () -> T): T {
        val window = synchronized(monitor) {
            check(active == null) { "G0_SEND_TRACE_OVERLAP" }
            Window(now()).also { active = it; completed = null }
        }
        var returned = false
        return try { body().also { returned = true } } finally {
            synchronized(monitor) {
                window.closed = true
                window.roles.clear()
                completed = Snapshot(window.started, now(), returned, window.events.toList(),
                    window.open, window.dropped, window.roleOverflow, window.duplicateEnds)
                active = null
            }
        }
    }

    private fun begin(kind: String, ciphertext: String? = null): Ticket? = synchronized(monitor) {
        active?.let { window ->
            val label = if (ciphertext == null) kind else "decrypt_${window.roles[ciphertext] ?: "unclassified"}"
            window.open++
            Ticket(window, label, now(), G0PhaseTrace.current.get() != null, android.os.Process.myTid().toLong())
        }
    }
    private fun finish(ticket: Ticket?, ok: Boolean) {
        if (ticket == null) return
        val ended = now()
        synchronized(monitor) {
            if (ticket.finished) { ticket.window.duplicateEnds++; return@synchronized }
            ticket.finished = true
            val window = ticket.window
            window.open--
            if (!window.closed) {
                if (window.events.size < maxEvents) window.events += Event(ticket.kind, ticket.started,
                    ended, ok, ticket.legacyAttached, ticket.threadId)
                else window.dropped++
            }
        }
    }
    private fun roles(ticket: Ticket?, snapshot: Preferences) = synchronized(monitor) {
        val window = ticket?.window ?: return@synchronized
        if (window.closed || active !== window) return@synchronized
        for (role in listOf("access", "refresh")) {
            snapshot[stringPreferencesKey("${role}_token")]?.let { ciphertext ->
                if (ciphertext in window.roles || window.roles.size < 32) window.roles[ciphertext] = role
                else window.roleOverflow++
            }
        }
    }
    fun wrap(data: DataStore<Preferences>): DataStore<Preferences> = object : DataStore<Preferences> {
        override val data = flow {
            val ticket = begin("datastore_first")
            var first = true
            try {
                data.data.collect { snapshot ->
                    roles(ticket, snapshot)
                    if (first) { first = false; finish(ticket, true) }
                    emit(snapshot)
                }
            } finally {
                // first() terminates after its successful emit; that is not a failed read.
                if (first) finish(ticket, false)
            }
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = data.updateData(transform)
    }
    fun wrap(cipher: TokenCipher): TokenCipher = object : TokenCipher {
        override fun encrypt(value: String) = cipher.encrypt(value)
        override fun decrypt(value: String): String? {
            val ticket = begin("decrypt", value)
            var ok = false
            return try { cipher.decrypt(value).also { ok = it != null } }
            finally { finish(ticket, ok) }
        }
    }
    fun listeners() = EventListener.Factory {
        object : EventListener() {
            private var ticket: Ticket? = null
            override fun callStart(call: Call) {
                val endpoint = when (call.request().url.encodedPath) {
                    MaterialHttpRoute.IDENTITY_PATH -> "identity"
                    "/api/v2/sync/push" -> "push"
                    else -> "other"
                }
                ticket = begin("http_$endpoint")
            }
            override fun callEnd(call: Call) { finish(ticket, true) }
            override fun callFailed(call: Call, ioe: IOException) { finish(ticket, false) }
        }
    }
}
