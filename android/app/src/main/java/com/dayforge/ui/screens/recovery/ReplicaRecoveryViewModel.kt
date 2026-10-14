package com.dayforge.ui.screens.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.api.NextSyncHttpFailure
import com.dayforge.data.local.*
import com.dayforge.data.repository.*
import com.dayforge.domain.service.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel

internal enum class ReplicaRecoveryError { AUTHENTICATION, ACCOUNT, ENDPOINT, NETWORK, CHANGED, REVIEW, MATERIAL, NOT_REQUIRED, STORAGE, DAMAGED, FAILED }
internal enum class ReplicaRecoveryNotice { AUTHENTICATED, CANCELLED, WAITING, CHANGED, COMPLETED }

/** One selected record, formatted off Main. Lazy slices expose all text without one huge Text. */
internal class ReplicaRecoveryValue(private val text: String) {
    val pages: Int get() = (text.length + 4095) / 4096
    fun page(index: Int): String {
        require(index in 0 until pages)
        var start = index * 4096; var end = minOf(start + 4096, text.length)
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
        if (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
        return text.substring(start, end)
    }
}
internal data class ReplicaRecoveryDetail(val key: ReplicaRecordKey, val before: ReplicaRecoveryValue?, val after: ReplicaRecoveryValue?, val table: String? = null)

/** Memory only: no SavedStateHandle, DTO export, password, bearer, lease or persisted consent. */
internal data class ReplicaRecoveryUiState(val stamp: Any = Any(), val visible: Boolean = false,
    val busy: Boolean = false, val checked: Boolean = false, val entryAvailable: Boolean = false,
    val stage: ReplicaSwitchStage? = null,
    val preview: ReplicaDifferencePreview? = null, val evidence: ReplicaRecoveryConfirmationEvidence? = null,
    val approved: Boolean = false, val detail: ReplicaRecoveryDetail? = null,
    val material: ReplicaMaterialPreflight? = null, val error: ReplicaRecoveryError? = null,
    val notice: ReplicaRecoveryNotice? = null) {
    val review get() = evidence?.differences ?: preview
    val canPrepare get() = !busy && stage == null && preview != null &&
        !preview.hasUnfinishedTimer && preview.unconfirmedLocalRecords.isEmpty() &&
        preview.assessment.reviewCounts.values.none { it > 0 }
    val canConfirm get() = !busy && evidence != null && approved
    val canAttemptCancel get() = !busy && stage?.phase in setOf(ReplicaSwitchPhase.FREEZING,
        ReplicaSwitchPhase.FROZEN, ReplicaSwitchPhase.ARCHIVE_VERIFIED, ReplicaSwitchPhase.COMMITTING)
}

/** Activity-owned outside the disposable business graph. Startup only reads local control.
 * All network/prepare/COMMIT/forward work requires an explicit event. Invalidation immediately
 * revokes old pixels and consent, but never cancels an owned storage transition halfway through.
 */
@HiltViewModel
internal class ReplicaRecoveryViewModel internal constructor(private val actions: ReplicaRecoveryActions) : ViewModel() {
    @Inject constructor(service: ReplicaRecoveryService) : this(service as ReplicaRecoveryActions)
    private val monitor = Any()
    private var revision = Any()
    private var blocked = false
    private var cleared = false
    private var operation: Any? = null
    private val entryReload = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(ReplicaRecoveryUiState(stamp = revision))
    val state = mutable.asStateFlow()
    private val invalidator = object : AccountIconMemory.Cache {
        override fun authenticationTransition(blocked: Boolean) = synchronized(monitor) {
            if (!cleared) {
                revision = Any(); this@ReplicaRecoveryViewModel.blocked = blocked
                mutable.value = ReplicaRecoveryUiState(revision, visible = mutable.value.visible, busy = operation != null)
                if (!blocked) entryReload.trySend(Unit)
            }
        }
    }
    init {
        actions.observeInvalidation(invalidator)
        viewModelScope.launch {
            merge(actions.entryChanges(), entryReload.receiveAsFlow()).collectLatest { refreshEntry() }
        }
        inspect()
    }

    /** An observer may update the doorway, never the user's preview, consent or operation fate. */
    private suspend fun refreshEntry() {
        val stamp = synchronized(monitor) { if (cleared || blocked) return else revision }
        val available = try { withContext(Dispatchers.IO) { actions.control().entryAvailable } }
            catch (error: Exception) { if (error is CancellationException) throw error else false }
        synchronized(monitor) {
            if (!cleared && !blocked && stamp === revision)
                mutable.value = mutable.value.copy(entryAvailable = available)
        }
    }
    fun canRender(value: ReplicaRecoveryUiState): Boolean = synchronized(monitor) {
        !cleared && !blocked && value.stamp === revision
    }
    fun show() {
        synchronized(monitor) { if (!cleared) mutable.value = mutable.value.copy(visible = true, approved = false) }
        inspect()
    }
    fun dismiss() = synchronized(monitor) {
        if (operation == null && !cleared) mutable.value = ReplicaRecoveryUiState(revision,
            checked = mutable.value.checked, entryAvailable = mutable.value.entryAvailable)
    }
    fun inspect() = perform { ReplicaRecoveryUiState(checked = true) }
    fun preview() = perform { ReplicaRecoveryUiState(preview = actions.preview(), checked = true) }
    fun prepare() {
        if (!state.value.canPrepare) return
        perform(mutates = true) { original ->
            val stage = actions.prepare(requireNotNull(original.preview))
            ReplicaRecoveryUiState(stage = stage, evidence = actions.confirmation(stage), checked = true)
        }
    }
    fun resume() {
        if (state.value.stage == null) return
        perform(mutates = true) { original -> when (val next = actions.resume(requireNotNull(original.stage))) {
            is ReplicaRecoveryContinuation.Confirmation -> ReplicaRecoveryUiState(stage = next.evidence.stage, evidence = next.evidence, checked = true)
            is ReplicaRecoveryContinuation.Destination -> outcome(next.outcome)
        } }
    }
    fun approve(value: Boolean) = synchronized(monitor) {
        val current = mutable.value
        if (!blocked && !cleared && operation == null && current.evidence != null)
            mutable.value = current.copy(approved = value)
    }
    fun confirm() {
        if (!state.value.canConfirm) return
        perform(mutates = true) { original ->
            check(original.approved) { "SYNC_RECOVERY_CONFIRMATION_REQUIRED" }
            outcome(actions.confirm(requireNotNull(original.evidence)))
        }
    }
    fun cancel() {
        if (!state.value.canAttemptCancel) return
        perform(mutates = true) { original ->
            actions.cancel(requireNotNull(original.stage))
            ReplicaRecoveryUiState(checked = true, notice = ReplicaRecoveryNotice.CANCELLED)
        }
    }
    fun reauthenticate(username: String, password: String) = execute(mutates = true, login = true) {
        val authenticated = actions.reauthenticate(username, password)
        Result(ReplicaRecoveryUiState(checked = true, notice = ReplicaRecoveryNotice.AUTHENTICATED), authenticated)
    }
    fun detail(key: ReplicaRecordKey, table: String? = null) = perform { original ->
        val review = requireNotNull(original.review)
        val row = if (table == null) review.differences.singleOrNull { it.key == key } else null
        val local = if (table != null) review.unconfirmedLocalRecords.singleOrNull { it.key == key && it.table == table } else null
        require(row != null || local != null)
        original.copy(approved = false, detail = ReplicaRecoveryDetail(key,
            (row?.before ?: local?.rawValues)?.let { ReplicaRecoveryValue(it.toString()) },
            row?.after?.let { ReplicaRecoveryValue(it.toString()) }, table))
    }
    fun closeDetail() = synchronized(monitor) { if (operation == null) mutable.value = mutable.value.copy(detail = null) }

    private fun outcome(value: ReplicaCompletionOutcome) = when (value) {
        is ReplicaCompletionOutcome.Waiting -> ReplicaRecoveryUiState(stage = value.stage, checked = true, notice = ReplicaRecoveryNotice.WAITING)
        is ReplicaCompletionOutcome.Changed -> ReplicaRecoveryUiState(stage = value.stage, checked = true, notice = ReplicaRecoveryNotice.CHANGED)
        is ReplicaCompletionOutcome.Completed -> ReplicaRecoveryUiState(checked = true, notice = ReplicaRecoveryNotice.COMPLETED)
    }
    private class Result(val view: ReplicaRecoveryUiState, val authenticated: AuthenticationSession? = null)

    private fun perform(mutates: Boolean = false, block: suspend (ReplicaRecoveryUiState) -> ReplicaRecoveryUiState) =
        execute(mutates) { Result(block(it)) }

    /** Reads local fate even after a lost return, never automatically retries or commits.
     * Owned pointer/epoch changes may revoke the old view; fresh output still requires the
     * SAME authenticated generation and current durable stage. Non-owned late previews cannot
     * publish across any invalidation. Passwords exist only in the explicit call closure.
     */
    private fun execute(mutates: Boolean = false, login: Boolean = false,
        block: suspend (ReplicaRecoveryUiState) -> Result) {
        val id = Any()
        val original = synchronized(monitor) {
            if (cleared || blocked || operation != null) return
            operation = id
            mutable.value.also { mutable.value = it.copy(busy = true, approved = false, error = null, notice = null) }
        }
        viewModelScope.launch {
            var authentication: AuthenticationSession? = null
            var result: Result? = null
            var failure: Exception? = null
            try {
                result = withContext(Dispatchers.IO) {
                    authentication = actions.control().authentication
                    block(original)
                }
            } catch (error: Exception) {
                failure = error
                if (error is CancellationException) throw error
            } finally {
                val stamp = synchronized(monitor) { if (mutates) revision else original.stamp }
                val control = try { withContext(NonCancellable + Dispatchers.IO) { actions.control() } }
                    catch (_: Exception) { null }
                val finished = result
                val problem = failure
                synchronized(monitor) {
                    if (operation === id) {
                        operation = null
                        if (!cleared) {
                            val valid = !blocked && stamp === revision && control != null &&
                                control.authentication == if (login && finished != null) finished.authenticated else authentication
                            val expected = finished?.view
                            val stageMatches = expected == null || expected.stage == control?.stage ||
                                expected.stage == null && expected.evidence == null && expected.preview == null &&
                                expected.notice !in setOf(ReplicaRecoveryNotice.COMPLETED, ReplicaRecoveryNotice.CANCELLED)
                            val view = if (valid && stageMatches && problem == null) requireNotNull(expected)
                                else ReplicaRecoveryUiState(checked = true,
                                    error = if (!valid || !stageMatches) ReplicaRecoveryError.CHANGED else safeError(requireNotNull(problem)),
                                    material = if (valid) (problem as? ReplicaMaterialUnavailable)?.diagnostics else null)
                            mutable.value = view.copy(stamp = revision, visible = mutable.value.visible,
                                busy = false, stage = control?.stage, approved = false,
                                entryAvailable = valid && control?.entryAvailable == true)
                            entryReload.trySend(Unit)
                        }
                    }
                }
            }
        }
    }
    override fun onCleared() = synchronized(monitor) {
        cleared = true; revision = Any(); mutable.value = ReplicaRecoveryUiState(revision); entryReload.close()
        Unit
    }
}

/** Never display raw exception/HTTP bodies, row contents, passwords or tokens. */
private fun safeError(error: Exception): ReplicaRecoveryError {
    if (error is ReplicaMaterialUnavailable) return ReplicaRecoveryError.MATERIAL
    if (error is NextSyncHttpFailure) return when (error.status) {
        401, 403 -> ReplicaRecoveryError.AUTHENTICATION
        409, 426 -> ReplicaRecoveryError.ENDPOINT
        else -> ReplicaRecoveryError.NETWORK
    }
    return when (error.message) {
        "SYNC_RECOVERY_LOGIN_ACCOUNT_MISMATCH" -> ReplicaRecoveryError.ACCOUNT
        "SYNC_RECOVERY_NOT_REQUIRED" -> ReplicaRecoveryError.NOT_REQUIRED
        "SYNC_RECOVERY_REVIEW_REQUIRED" -> ReplicaRecoveryError.REVIEW
        "SYNC_RECOVERY_ENDPOINT_UNAVAILABLE", "SYNC_RECOVERY_NETWORK_UNAVAILABLE" -> ReplicaRecoveryError.NETWORK
        "SYNC_RECOVERY_ENDPOINT_CHANGED", "SYNC_RECOVERY_ENDPOINT_MISMATCH" -> ReplicaRecoveryError.ENDPOINT
        "SYNC_RECOVERY_PREVIEW_CHANGED", "SYNC_ACCOUNT_CHANGED", "SYNC_REPLICA_STAGE_CHANGED",
        "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED", "SYNC_RECOVERY_LOGIN_CHANGED" -> ReplicaRecoveryError.CHANGED
        "SYNC_RECOVERY_AUTHENTICATION_REQUIRED", "ICON_ACCESS_DENIED" -> ReplicaRecoveryError.AUTHENTICATION
        else -> when (error) {
            is android.database.sqlite.SQLiteException, is java.io.IOException -> ReplicaRecoveryError.STORAGE
            else -> ReplicaRecoveryError.FAILED
        }
    }
}
