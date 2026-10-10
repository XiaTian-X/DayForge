package com.dayforge.ui.screens.settings

import com.dayforge.data.repository.NextRecoveryConfirmation
import com.dayforge.data.repository.NextRecoveryDependencyPreview
import com.dayforge.data.repository.NextRecoveryDependencyReason
import com.dayforge.data.repository.NextRecoveryKey
import com.dayforge.domain.service.SyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal data class RecoveryFieldChange(val field: String, val before: String?, val after: String?)
internal data class RecoveryObjectChange(val type: String, val uuid: String, val name: String?,
    val removed: Boolean, val fields: List<RecoveryFieldChange>)
internal data class RecoveryReview(val graph: NextRecoveryDependencyPreview,
    val changes: List<RecoveryObjectChange>, val contractVersion: Int?, val checkpoint: String?,
    val preparationFailed: Boolean,
    val dependencyReasons: Map<NextRecoveryKey, Set<NextRecoveryDependencyReason>>)

/** One linear pass off the UI thread; compact gates never expand predecessors×successors. */
internal fun recoveryDependencyReasons(preview: NextRecoveryDependencyPreview, check: () -> Unit = {}):
    Map<NextRecoveryKey, Set<NextRecoveryDependencyReason>> {
    val graph = preview.archive
    val aliases = graph.aliases.associate { check(); it.logical to it.actual }
    val selected = preview.affected.map { check(); it.key }.toSet()
    val values = linkedMapOf<NextRecoveryKey, MutableSet<NextRecoveryDependencyReason>>()
    fun add(key: NextRecoveryKey, reason: NextRecoveryDependencyReason) {
        check()
        val actual = aliases[key] ?: key
        if (actual in selected) values.getOrPut(actual) { linkedSetOf() }.add(reason)
    }
    graph.dependencies.forEach { add(it.successor, it.reason) }
    graph.groups.forEach { group -> group.successors.forEach { add(it, group.reason) } }
    return values.mapValues { it.value.toSet() }
}

internal sealed interface RecoveryUiState {
    data object Hidden : RecoveryUiState
    data object Loading : RecoveryUiState
    data object Stale : RecoveryUiState
    data object Unavailable : RecoveryUiState
    data class Review(val data: RecoveryReview, val approved: Boolean = false) : RecoveryUiState
    data class Committing(val data: RecoveryReview) : RecoveryUiState
    /** The outcome may already be durable. Retry the SAME receipt, never infer rollback. */
    data class RetryConfirmation(val data: RecoveryReview) : RecoveryUiState
    data class Completed(val recoveryId: String, val count: Int) : RecoveryUiState
}

/** ViewModel-owned local workflow. No DAO, saved credentials, request construction or automatic HTTP. */
internal class SettingsRecoveryWorkflow(private val sync: SyncManager, private val scope: CoroutineScope) {
    private val monitor = Any()
    private var blocked = false
    private var authority = Any()
    private var revision = Any()
    private var action = Any()
    private var root: NextRecoveryKey? = null
    private var receipt: NextRecoveryConfirmation? = null
    private var committing = false
    private val mutableState = MutableStateFlow<RecoveryUiState>(RecoveryUiState.Hidden)
    val state = mutableState.asStateFlow()

    /** Called synchronously by TokenManager before account/replica/device/permission transitions. */
    fun authenticationTransition(value: Boolean) = synchronized(monitor) {
        blocked = value; authority = Any(); revision = Any(); action = Any()
        root = null; receipt = null; mutableState.value = RecoveryUiState.Hidden
    }

    fun databaseChanged() = synchronized(monitor) {
        revision = Any()
        // Own COMMIT invalidation must not cancel the action or lose its idempotency receipt.
        if (mutableState.value is RecoveryUiState.Review || mutableState.value == RecoveryUiState.Loading) {
            receipt = null; mutableState.value = RecoveryUiState.Stale
        }
    }

    fun preview(key: NextRecoveryKey) {
        val capture = synchronized(monitor) {
            if (blocked || committing) return
            root = key; receipt = null; action = Any()
            mutableState.value = RecoveryUiState.Loading
            Triple(authority, revision, action)
        }
        scope.launch {
            try {
                val graph = sync.readRecoveryPreview(key)
                if (!synchronized(monitor) { current(capture.first, capture.third) && revision === capture.second }) return@launch
                var ticket: NextRecoveryConfirmation? = null
                var failed = false
                if (graph.canConfirm) {
                    try { ticket = sync.prepareRecoveryConfirmation(graph, graph.affected.map { it.key }.toSet()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (error.staleRecovery()) throw error
                        failed = true
                    }
                }
                if (!synchronized(monitor) { current(capture.first, capture.third) && revision === capture.second }) return@launch
                val review = withContext(Dispatchers.Default) { describe(graph, ticket, failed) }
                synchronized(monitor) {
                    if (current(capture.first, capture.third) && revision === capture.second) {
                        receipt = ticket; mutableState.value = RecoveryUiState.Review(review)
                    }
                }
            } catch (cancelled: CancellationException) {
                synchronized(monitor) {
                    if (current(capture.first, capture.third)) {
                        receipt = null; mutableState.value = RecoveryUiState.Stale
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                synchronized(monitor) {
                    if (current(capture.first, capture.third) && revision === capture.second) {
                        receipt = null
                        mutableState.value = if (error.staleRecovery()) RecoveryUiState.Stale else RecoveryUiState.Unavailable
                    }
                }
            }
        }
    }

    fun refresh() { val key = synchronized(monitor) { root }; if (key != null) preview(key) }

    fun approve(value: Boolean) = synchronized(monitor) {
        val view = mutableState.value as? RecoveryUiState.Review ?: return@synchronized
        if (!blocked && receipt != null && view.data.graph.canConfirm && !view.data.preparationFailed)
            mutableState.value = view.copy(approved = value)
    }

    fun confirm() {
        val capture = synchronized(monitor) {
            if (blocked || committing) return
            val data = when (val view = mutableState.value) {
                is RecoveryUiState.Review -> if (view.approved) view.data else return
                is RecoveryUiState.RetryConfirmation -> view.data
                else -> return
            }
            val ticket = receipt ?: return
            committing = true; mutableState.value = RecoveryUiState.Committing(data)
            ConfirmationCapture(authority, action, ticket, data)
        }
        scope.launch {
            try {
                if (!synchronized(monitor) { current(capture.authority, capture.action) }) return@launch
                val result = sync.confirmRecovery(capture.ticket)
                synchronized(monitor) {
                    if (current(capture.authority, capture.action)) {
                        receipt = null
                        mutableState.value = RecoveryUiState.Completed(result.recoveryId, result.manifest.requests.size)
                    }
                }
            } catch (cancelled: CancellationException) {
                retryOrStale(capture, false)
                throw cancelled
            } catch (error: Exception) {
                retryOrStale(capture, error.staleRecovery())
            } finally { synchronized(monitor) { committing = false } }
        }
    }

    private data class ConfirmationCapture(val authority: Any, val action: Any,
        val ticket: NextRecoveryConfirmation, val data: RecoveryReview)

    private fun retryOrStale(capture: ConfirmationCapture, stale: Boolean) = synchronized(monitor) {
        if (current(capture.authority, capture.action)) {
            if (stale) receipt = null
            mutableState.value = if (stale) RecoveryUiState.Stale else RecoveryUiState.RetryConfirmation(capture.data)
        }
    }

    fun dismiss() = synchronized(monitor) {
        // Closing UI is not permission to cancel a confirmed transaction or replay its projection.
        action = Any(); root = null; receipt = null; mutableState.value = RecoveryUiState.Hidden
    }

    private fun current(owner: Any, operation: Any) = !blocked && authority === owner && action === operation

    private fun Exception.staleRecovery() = message in setOf("SYNC_RECOVERY_PREVIEW_CHANGED",
        "SYNC_RECOVERY_CHECKPOINT_CHANGED", "SYNC_RECOVERY_PROJECTION_CHANGED", "SYNC_RECOVERY_ACCESS_CHANGED",
        "SYNC_ACCOUNT_CHANGED", "SYNC_REPLICA_CHANGED")

    private suspend fun describe(graph: NextRecoveryDependencyPreview, ticket: NextRecoveryConfirmation?,
        failed: Boolean): RecoveryReview {
        fun text(value: JsonElement?) = when (value) { null -> null; is JsonPrimitive -> value.content; else -> value.toString() }
        val changes = ticket?.drafts()?.map { draft ->
            currentCoroutineContext().ensureActive()
            val before = draft.before.body; val after = draft.after.body
            val fields = (before?.keys.orEmpty() + after?.keys.orEmpty()).sorted().mapNotNull { field ->
                currentCoroutineContext().ensureActive()
                if (before?.get(field) == after?.get(field)) null
                else RecoveryFieldChange(field, text(before?.get(field)), text(after?.get(field)))
            }
            RecoveryObjectChange(draft.before.entityType, draft.before.entityUuid, text(before?.get("name")),
                draft.before.localId != null && draft.after.localId == null, fields)
        }.orEmpty()
        val checkpoint = ticket?.checkpoint()
        val caller = currentCoroutineContext()
        return RecoveryReview(graph, changes, checkpoint?.metadata?.challengeContract, checkpoint?.syncHash?.take(12), failed,
            recoveryDependencyReasons(graph) { caller.ensureActive() })
    }
}
