package com.dayforge.ui.screens.settings

import com.dayforge.data.repository.NextRecoveryConfirmation
import com.dayforge.data.repository.NextRecoveryDependencyPreview
import com.dayforge.data.repository.NextRecoveryDependencyReason
import com.dayforge.data.repository.NextRecoveryKey
import com.dayforge.domain.service.SyncManager
import com.dayforge.data.model.SyncProblemFrame
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

/** An immutable original dialog emission. Identity distinguishes even two Loading/Stale
 * singleton values and two identical reviews. It is never fresh authority. */
internal class RecoveryUiFrame internal constructor(val value: RecoveryUiState,
    internal val source: SyncProblemFrame?)

/** ViewModel-owned workflow. The original problem source and same receipt survive each
 * stage; only fixed/manual mode can use the historical no-frame action API. */
internal class SettingsRecoveryWorkflow(private val sync: SyncManager, private val scope: CoroutineScope,
    private val requireOriginalSource: Boolean = false) {
    private val monitor = Any()
    private var blocked = false
    private var authority = Any()
    private var revision = Any()
    private var action = Any()
    private var root: NextRecoveryKey? = null
    private var receipt: NextRecoveryConfirmation? = null
    private var source: SyncProblemFrame? = null
    private var committing = false
    private val mutableState = MutableStateFlow<RecoveryUiState>(RecoveryUiState.Hidden)
    val state = mutableState.asStateFlow()
    private val mutableFrames = MutableStateFlow(RecoveryUiFrame(RecoveryUiState.Hidden, null))
    val frames = mutableFrames.asStateFlow()

    /** Call only under monitor (and original source fence for personal result assignments). */
    private fun assign(value: RecoveryUiState) {
        mutableState.value = value
        mutableFrames.value = RecoveryUiFrame(value, source)
    }
    private fun clearPrivate() {
        authority = Any(); revision = Any(); action = Any()
        root = null; receipt = null; source = null; assign(RecoveryUiState.Hidden)
        // Accepted confirmation continues. Its finally alone releases committing.
    }
    fun authenticationTransition(value: Boolean) = synchronized(monitor) {
        blocked = value; clearPrivate()
    }
    /** Pure memory only. Never cancel a confirmed write or call SQL/Host/account admission. */
    fun sourceTransition(blocked: Boolean) {
        val original = synchronized(monitor) { source }
        if (blocked || original?.isCurrent() == false) synchronized(monitor) {
            if (source === original) clearPrivate()
        }
    }
    private fun publish(original: SyncProblemFrame?, block: () -> Unit): Boolean {
        if (original == null) {
            if (requireOriginalSource) return false
            synchronized(monitor) { block() }; return true
        }
        // Token memory -> workflow monitor -> Host publication, never the reverse.
        return original.withSourceFence { synchronized(monitor) { original.publish(block) } }
    }
    private fun fixedFrame(): RecoveryUiFrame {
        check(!requireOriginalSource) { "SYNC_RECOVERY_ORIGINAL_INPUT_REQUIRED" }
        return synchronized(monitor) { mutableFrames.value }
    }
    private fun matches(expected: RecoveryUiFrame): Boolean = mutableFrames.value === expected && source === expected.source
    private fun edit(expected: RecoveryUiFrame, change: () -> Unit): Boolean {
        var accepted = false
        publish(expected.source) { if (matches(expected) && !blocked) { change(); accepted = true } }
        return accepted
    }
    fun databaseChanged() = synchronized(monitor) {
        revision = Any()
        // Stale has no private result; invalidation needs no newly issued publication.
        // Own COMMIT and unknown-result retry keep their exact original receipt.
        if (mutableState.value is RecoveryUiState.Review || mutableState.value == RecoveryUiState.Loading) {
            receipt = null; assign(RecoveryUiState.Stale)
        }
    }
    fun preview(key: NextRecoveryKey) { fixedFrame(); beginPreview(null, key) }
    fun previewFrom(original: SyncProblemFrame, key: NextRecoveryKey) {
        original.requireRecovery(key)
        beginPreview(original, key)
    }
    private data class PreviewCapture(val authority: Any, val revision: Any, val action: Any,
        val source: SyncProblemFrame?, val root: NextRecoveryKey)
    private fun beginPreview(original: SyncProblemFrame?, key: NextRecoveryKey,
        expected: RecoveryUiFrame? = null) {
        var capture: PreviewCapture? = null
        publish(original) {
            if (!blocked && !committing && (expected == null || matches(expected))) {
                root = key; receipt = null; source = original; action = Any()
                assign(RecoveryUiState.Loading)
                capture = PreviewCapture(authority, revision, action, original, key)
            }
        }
        val started = capture ?: return
        scope.launch {
            try {
                val graph = if (started.source == null) sync.readRecoveryPreview(started.root)
                    else sync.readRecoveryPreview(started.source, started.root)
                if (!previewCurrent(started)) return@launch
                var ticket: NextRecoveryConfirmation? = null
                var failed = false
                if (graph.canConfirm) {
                    try { ticket = if (started.source == null)
                        sync.prepareRecoveryConfirmation(graph, graph.affected.map { it.key }.toSet())
                    else sync.prepareRecoveryConfirmation(started.source, graph, graph.affected.map { it.key }.toSet()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (error.staleRecovery()) throw error
                        failed = true
                    }
                }
                if (!previewCurrent(started)) return@launch
                val review = withContext(Dispatchers.Default) { describe(graph, ticket, failed) }
                publish(started.source) {
                    if (current(started.authority, started.action, started.source) && revision === started.revision) {
                        receipt = ticket; assign(RecoveryUiState.Review(review))
                    }
                }
            } catch (cancelled: CancellationException) {
                publish(started.source) { if (current(started.authority, started.action, started.source)) {
                    receipt = null; assign(RecoveryUiState.Stale)
                } }
                throw cancelled
            } catch (error: Exception) {
                publish(started.source) { if (current(started.authority, started.action, started.source) && revision === started.revision) {
                    receipt = null
                    assign(if (error.staleRecovery()) RecoveryUiState.Stale else RecoveryUiState.Unavailable)
                } }
            }
        }
    }
    private fun previewCurrent(capture: PreviewCapture): Boolean {
        var valid = false
        publish(capture.source) { valid = current(capture.authority, capture.action, capture.source) && revision === capture.revision }
        return valid
    }
    fun refresh() = refreshFrom(fixedFrame())
    fun refreshFrom(expected: RecoveryUiFrame) {
        var key: NextRecoveryKey? = null
        edit(expected) { if (!committing) key = root }
        key?.let { beginPreview(expected.source, it, expected) }
    }
    fun approve(value: Boolean) = approveFrom(fixedFrame(), value)
    fun approveFrom(expected: RecoveryUiFrame, value: Boolean) {
        edit(expected) {
            val view = mutableState.value as? RecoveryUiState.Review
            if (!committing && receipt != null && view != null && view.data.graph.canConfirm && !view.data.preparationFailed)
                assign(view.copy(approved = value))
        }
    }
    fun confirm() = confirmFrom(fixedFrame())
    fun confirmFrom(expected: RecoveryUiFrame) {
        var captured: ConfirmationCapture? = null
        edit(expected) {
            if (!committing) {
                val data = when (val view = mutableState.value) {
                    is RecoveryUiState.Review -> view.data.takeIf { view.approved }
                    is RecoveryUiState.RetryConfirmation -> view.data
                    else -> null
                }
                val ticket = receipt
                if (data != null && ticket != null) {
                    committing = true; assign(RecoveryUiState.Committing(data))
                    captured = ConfirmationCapture(authority, action, ticket, data, source)
                }
            }
        }
        val capture = captured ?: return
        scope.launch {
            try {
                var valid = false
                publish(capture.source) { valid = current(capture.authority, capture.action, capture.source) }
                if (!valid) return@launch
                val result = if (capture.source == null) sync.confirmRecovery(capture.ticket)
                    else sync.confirmRecovery(capture.source, capture.ticket)
                publish(capture.source) { if (current(capture.authority, capture.action, capture.source)) {
                    receipt = null; assign(RecoveryUiState.Completed(result.recoveryId, result.manifest.requests.size))
                } }
            } catch (cancelled: CancellationException) {
                retryOrStale(capture, false)
                throw cancelled
            } catch (error: Exception) {
                retryOrStale(capture, error.staleRecovery())
            } finally { synchronized(monitor) { committing = false } }
        }
    }
    private data class ConfirmationCapture(val authority: Any, val action: Any,
        val ticket: NextRecoveryConfirmation, val data: RecoveryReview, val source: SyncProblemFrame?)
    private fun retryOrStale(capture: ConfirmationCapture, stale: Boolean) {
        publish(capture.source) { if (current(capture.authority, capture.action, capture.source)) {
            if (stale) receipt = null
            assign(if (stale) RecoveryUiState.Stale else RecoveryUiState.RetryConfirmation(capture.data))
        } }
    }
    fun dismiss() = dismissFrom(fixedFrame())
    fun dismissFrom(expected: RecoveryUiFrame) {
        edit(expected) {
            // Closing UI never cancels the already confirmed transaction.
            action = Any(); root = null; receipt = null; source = null; assign(RecoveryUiState.Hidden)
        }
    }
    private fun current(owner: Any, operation: Any, original: SyncProblemFrame?) =
        !blocked && authority === owner && action === operation && source === original
    private fun Exception.staleRecovery() = message in setOf("SYNC_RECOVERY_PREVIEW_CHANGED",
        "SYNC_RECOVERY_CHECKPOINT_CHANGED", "SYNC_RECOVERY_PROJECTION_CHANGED", "SYNC_RECOVERY_ACCESS_CHANGED",
        "SYNC_ACCOUNT_CHANGED", "SYNC_REPLICA_CHANGED", "SYNC_PROBLEM_INPUT_CHANGED",
        "DATABASE_EPOCH_INPUT_REQUIRED", "DATABASE_EPOCH_INPUT_EXPIRED", "DATABASE_EPOCH_OPERATION_EXPIRED",
        "DATABASE_EPOCH_REPLICA_CHANGED")

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
