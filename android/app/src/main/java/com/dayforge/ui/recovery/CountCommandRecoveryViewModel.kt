package com.dayforge.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.repository.CountCommandPendingRepository
import com.dayforge.data.repository.CountCommandReference
import com.dayforge.domain.service.CountCommandRecoveryWorkflow
import com.dayforge.ui.producerEpochFrameState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

@HiltViewModel
internal class CountCommandRecoveryViewModel @Inject constructor(private val host: DatabaseRuntimeHost,
    private val workflow: CountCommandRecoveryWorkflow) : ViewModel() {
    private class Session { @Volatile var active = true }
    private var session: Session? = null
    class Model internal constructor(val page: DatabaseEpochValue<CountCommandPendingRepository.Page>,
        val result: CountCommandPendingRepository.Result? = null,
        val followUp: CountCommandPendingRepository.FollowUp? = null) {
        internal val accepted = CompletableDeferred<Boolean>()
    }
    private val produced = MutableStateFlow<DatabaseEpochValue<Model>?>(null)
    val frames = producerEpochFrameState(viewModelScope, host, SharingStarted.Eagerly, produced, ::publish)
    val busy = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    private var work: Job? = null
    private var invalidation: Any? = host.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            val current = produced.value ?: return
            if (blocked || !current.publication.isCurrentInMemory()) {
                if (produced.compareAndSet(current, null)) current.value.accepted.complete(false)
            }
        }
    })

    private val sessions = java.util.IdentityHashMap<Model, Session>()
    private fun publish(frame: DatabaseEpochValue<Model>?, assignment: () -> Unit): Boolean {
        val accepted = if (frame == null) { assignment(); true } else frame.publication.withSourceFence {
            synchronized(sessions) {
                val owner = sessions[frame.value]
                owner?.active == true && session === owner && produced.value === frame && frame.publication.publish(assignment)
            }
        }
        frame?.value?.accepted?.complete(accepted)
        return accepted
    }
    private suspend fun accept(owner: Session, page: DatabaseEpochValue<CountCommandPendingRepository.Page>,
        result: CountCommandPendingRepository.Result? = null,
        followUp: CountCommandPendingRepository.FollowUp? = null): Boolean {
        val model = Model(page, result, followUp)
        val frame = DatabaseEpochValue(model, page.publication)
        var replaced: Model? = null
        val published = page.publication.withSourceFence { synchronized(sessions) {
            owner.active && session === owner && page.publication.publish {
                replaced = produced.value?.value
                sessions[model] = owner
                produced.value = frame
            }
        } }
        replaced?.let { synchronized(sessions) { sessions.remove(it) }; it.accepted.complete(false) }
        if (!published) { synchronized(sessions) { sessions.remove(model) }; return false }
        return try { model.accepted.await() } finally {
            if (produced.value !== frame) model.accepted.complete(false)
        }
    }
    private fun launch(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            busy.value = true; failed.value = false
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("CountCommandRecovery", "Original pending action remains unresolved", error)
                failed.value = true
            }
            finally { work = null; busy.value = false }
        }
    }
    @androidx.annotation.MainThread
    fun open() {
        close()
        synchronized(sessions) { session = Session() }
        refresh()
    }
    @androidx.annotation.MainThread
    fun close() {
        val old = synchronized(sessions) {
            session?.active = false; session = null
            produced.value.also { produced.value = null; sessions.clear() }
        }
        old?.value?.accepted?.complete(false)
        // Do not cancel an admitted T1 merely to discard its UI result. It remains durable;
        // the invalid session cannot consume it/T2 or launch another window.
    }
    @androidx.annotation.MainThread
    fun refresh() {
        val owner = synchronized(sessions) { session?.takeIf { it.active } } ?: return
        launch { check(accept(owner, workflow.page())) { "DATABASE_EPOCH_SELECTION_CHANGED" } }
    }
    @androidx.annotation.MainThread
    fun next(original: DatabaseEpochValue<Model>) = launch {
        val owner = requireNotNull(synchronized(sessions) { sessions[original.value] }) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
        check(owner.active && session === owner && frames.value === original && original.publication.isCurrentInMemory()) { "DATABASE_EPOCH_SELECTION_CHANGED" }
        check(accept(owner, workflow.page(original.value.page))) { "DATABASE_EPOCH_SELECTION_CHANGED" }
    }
    @androidx.annotation.MainThread
    fun resume(original: DatabaseEpochValue<Model>, reference: CountCommandReference) = launch {
        val owner = requireNotNull(synchronized(sessions) { sessions[original.value] }) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
        check(owner.active && session === owner && frames.value === original && original.publication.isCurrentInMemory()) { "DATABASE_EPOCH_SELECTION_CHANGED" }
        var result: CountCommandPendingRepository.Result? = null
        val followUp = workflow.resume(original.value.page, reference, isConsumerCurrent = {
            val frame = frames.value
            frame != null && frame.publication.withSourceFence { synchronized(sessions) {
                owner.active && session === owner && frames.value === frame && produced.value === frame &&
                    frame.value.result === result && result != null && frame.publication.isCurrentInMemory()
            } }
        }) { value ->
            result = value
            accept(owner, original.value.page, value)
        }
        if (followUp != null) check(accept(owner, original.value.page, result, followUp)) { "DATABASE_EPOCH_SELECTION_CHANGED" }
    }
    @androidx.annotation.MainThread
    fun endIndependent(original: DatabaseEpochValue<Model>) = launch {
        val result = original.value.result as? CountCommandPendingRepository.Result.IndependentEndAvailable
            ?: error("COMMAND_INDEPENDENT_END_SELECTION_CHANGED")
        val owner = requireNotNull(synchronized(sessions) { sessions[original.value] }) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
        fun current(): Boolean = original.publication.withSourceFence { synchronized(sessions) {
            owner.active && session === owner && frames.value === original && produced.value === original &&
                original.value.result === result && original.publication.isCurrentInMemory()
        } }
        var delivered: CountCommandPendingRepository.Result.IndependentEnded? = null
        fun resultCurrent(): Boolean {
            val frame = frames.value ?: return false
            return frame.publication.withSourceFence { synchronized(sessions) {
                owner.active && session === owner && frames.value === frame && produced.value === frame &&
                    frame.value.result === delivered && delivered != null && frame.publication.isCurrentInMemory()
            } }
        }
        check(current()) { "DATABASE_EPOCH_SELECTION_CHANGED" }
        workflow.endIndependent(original.value.page, result, ::current, ::resultCurrent) { ended ->
            if (!current()) false else { delivered = ended; accept(owner, original.value.page, ended) }
        }
    }
    @androidx.annotation.MainThread
    fun endFollowUp(original: DatabaseEpochValue<Model>) = launch {
        val result = original.value.result as? CountCommandPendingRepository.Result.EndAvailable
            ?: error("COMMAND_FOLLOWUP_SELECTION_CHANGED")
        val owner = requireNotNull(synchronized(sessions) { sessions[original.value] }) { "COMMAND_RECOVERY_CONSUMER_CLOSED" }
        fun current(): Boolean = original.publication.withSourceFence { synchronized(sessions) {
            owner.active && session === owner && frames.value === original && produced.value === original &&
                original.value.result === result && original.publication.isCurrentInMemory()
        } }
        check(current()) { "DATABASE_EPOCH_SELECTION_CHANGED" }
        workflow.endFollowUp(original.value.page, result, ::current) { ended ->
            if (!current()) false else accept(owner, original.value.page, ended)
        }
    }
    @androidx.annotation.MainThread
    fun consumeFollowUp(original: DatabaseEpochValue<Model>, action: (CountCommandPendingRepository.FollowUp) -> Unit) {
        val followUp = original.value.followUp ?: return
        // UI task launch is not a durable acknowledgement. Required follow-up remains pending.
        val owner = synchronized(sessions) { sessions[original.value] }
        if (owner?.active != true || session !== owner || frames.value !== original || !original.publication.isCurrentInMemory()) return
        action(followUp)
        if (produced.value === original) {
            val next = Model(original.value.page, original.value.result)
            synchronized(sessions) { sessions.remove(original.value); sessions[next] = owner }
            produced.value = DatabaseEpochValue(next, original.publication)
        }
    }
    override fun onCleared() { close(); invalidation = null; super.onCleared() }
}
