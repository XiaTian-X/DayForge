package com.dayforge.ui

import androidx.compose.runtime.compositionLocalOf
import com.dayforge.data.local.DatabaseEpochPublication
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.repository.CountCommandReceipt
import com.dayforge.data.repository.CountCommandRecords
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow

/** A button's accepted-operation result. It contains no current count or permission and
 * cannot change card/home arithmetic. Unknown is never represented as a saved addition. */
class CountButtonModel private constructor(val kind: Kind, val commandId: String) {
    enum class Kind { SAVED_ADD, SAVED_UNDO, NO_EFFECT }
    companion object {
        internal fun committed(receipt: CountCommandReceipt): CountButtonModel = CountButtonModel(
            if (!receipt.recorded) Kind.NO_EFFECT else when (receipt.committed.outcome.action) {
                CountCommandRecords.Action.COUNT_ADD -> Kind.SAVED_ADD
                CountCommandRecords.Action.COUNT_UNDO -> Kind.SAVED_UNDO
                CountCommandRecords.Action.LINKED_METRICS, CountCommandRecords.Action.METRIC_APPEND -> error("COUNT_BUTTON_COMMAND_REQUIRED")
            }, receipt.commandId)
    }
}
internal val LocalCountButtonModels = compositionLocalOf<Map<String, CountButtonModel>> { emptyMap() }

/** Exact-source producer and actual public button model consumer. T2 may follow acceptance,
 * never raw function return, unrelated overview invalidation or an unobserved private flag.
 * The eager model consumer is independent of pixel/frame timing and never changes counts.
 */
internal class CountButtonModels(scope: CoroutineScope, private val host: DatabaseRuntimeHost?) {
    private val monitor = Any()
    private val produced = MutableStateFlow<DatabaseEpochValue<Map<String, CountButtonModel>>?>(null)
    private val pending = IdentityHashMap<CountButtonModel, CompletableDeferred<Boolean>>()
    private val fixed = MutableStateFlow<Map<String, CountButtonModel>>(emptyMap())
    val fixedState: StateFlow<Map<String, CountButtonModel>> = fixed
    val frames: StateFlow<DatabaseEpochValue<Map<String, CountButtonModel>>?> = if (host != null)
        producerEpochFrameState(scope, host, SharingStarted.Eagerly, produced, ::publish) else MutableStateFlow(null)
    private var invalidation: Any? = host?.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            if (blocked) produced.value = null
        }
    })
    init {
        requireNotNull(scope.coroutineContext[Job]).invokeOnCompletion {
            val waiting = synchronized(monitor) { pending.values.toList().also { pending.clear(); produced.value = null } }
            waiting.forEach { it.complete(false) }
            invalidation = null // Host retains registrations weakly; the completed model no longer owns this listener.
        }
    }
    private fun publish(frame: DatabaseEpochValue<Map<String, CountButtonModel>>?, assignment: () -> Unit): Boolean {
        val accepted = if (frame == null) { assignment(); true } else frame.publication.withSourceFence {
            synchronized(monitor) {
                if (produced.value !== frame) false else frame.publication.publish(assignment)
            }
        }
        // Read the account/Host fence outside our monitor. Producers take that fence
        // before this monitor; reversing the order here would deadlock a concurrent
        // producer. Only apply this observation if its frame identity is still current.
        val observed = synchronized(monitor) { produced.value }
        val observedCurrent = observed?.publication?.isCurrentInMemory() == true
        val completed = arrayListOf<Pair<CompletableDeferred<Boolean>, Boolean>>()
        synchronized(monitor) {
            val latest = produced.value
            val current = latest?.value.orEmpty().values.toSet()
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                // A conflated newer frame can contain the SAME original model. Keep its
                // waiter until that newer frame is actually consumed; no lost completion.
                val consumed = accepted && frame?.value?.values?.any { it === entry.key } == true
                val discarded = latest == null || entry.key !in current || (latest === observed && !observedCurrent)
                if (consumed || discarded) { completed += entry.value to consumed; iterator.remove() }
            }
        }
        // Completion resumes callers only after account/presentation/Host locks have ended.
        completed.forEach { (signal, value) -> signal.complete(value) }
        return accepted
    }
    /** A newly accepted attempt must not expose a previous attempt's Saved label if it
     * later rejects or has an unknown result. Clearing status grants no business permission. */
    fun begin(habitUuid: String, original: DatabaseEpochPublication?) {
        if (host == null) { fixed.value = Collections.unmodifiableMap(fixed.value - habitUuid); return }
        val source = original ?: return
        if (!source.belongsTo(host)) return
        source.withSourceFence { synchronized(monitor) {
            val before = produced.value?.takeIf { it.publication.isCurrentInMemory() }?.value.orEmpty()
            source.publish { produced.value = DatabaseEpochValue(Collections.unmodifiableMap(before - habitUuid), source) }
        } }
    }
    suspend fun accept(habitUuid: String, model: CountButtonModel, original: DatabaseEpochPublication?): Boolean {
        if (host == null) {
            fixed.value = Collections.unmodifiableMap(fixed.value + (habitUuid to model))
            return true
        }
        val source = original ?: return false
        if (!source.belongsTo(host)) return false
        val signal = CompletableDeferred<Boolean>()
        val accepted = source.withSourceFence {
            synchronized(monitor) {
                val before = produced.value?.takeIf { it.publication.isCurrentInMemory() }?.value.orEmpty()
                val frame = DatabaseEpochValue(Collections.unmodifiableMap(before + (habitUuid to model)), source)
                source.publish {
                    pending[model] = signal
                    produced.value = frame
                }
            }
        }
        if (!accepted) return false
        return try { signal.await() } finally { synchronized(monitor) { pending.remove(model) } }
    }
}
