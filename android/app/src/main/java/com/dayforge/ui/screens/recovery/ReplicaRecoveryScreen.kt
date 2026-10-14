package com.dayforge.ui.screens.recovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayforge.R
import com.dayforge.data.local.ReplicaSwitchPhase
import com.dayforge.data.repository.*
import com.dayforge.domain.service.*

/** Optional callback supplied by the Activity, never a ViewModel from the disposable graph. */
internal val LocalReplicaRecoveryEntry = staticCompositionLocalOf<(() -> Unit)?> { null }

/** No account theme/pixels/business navigation are composed while this neutral host is visible. */
@Composable
internal fun ReplicaRecoveryRoot(controller: ReplicaPresentationController, model: ReplicaRecoveryViewModel,
    content: @Composable () -> Unit) {
    val presentation by controller.state.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val pending = presentation as? ReplicaPresentationState.Pending
    if (state.visible || pending != null) {
        BackHandler {
            if (!state.busy) {
                if (state.detail != null) model.closeDetail()
                else if (pending == null) model.dismiss()
            }
        }
        MaterialTheme {
            Box(Modifier.fillMaxSize().drawWithContent { if (model.canRender(state)) drawContent() }) {
                ReplicaRecoveryContent(state, pending?.check, { model.inspect(); controller.retry() }, model::preview, model::prepare,
                    model::resume, model::approve, model::confirm, model::cancel, model::reauthenticate,
                    model::detail, model::closeDetail, if (pending == null) model::dismiss else null,
                    isCurrent = { model.canRender(state) })
            }
        }
    } else {
        val open: (() -> Unit)? = if (state.entryAvailable && model.canRender(state)) {
            { if (model.canRender(state) && model.state.value.entryAvailable) model.show() }
        } else null
        CompositionLocalProvider(LocalReplicaRecoveryEntry provides open, content = content)
    }
}

@Composable
internal fun ReplicaRecoveryContent(state: ReplicaRecoveryUiState, diagnostic: ReplicaPresentationCheck?,
    inspect: () -> Unit, preview: () -> Unit, prepare: () -> Unit, resume: () -> Unit,
    approve: (Boolean) -> Unit, confirm: () -> Unit, cancel: () -> Unit,
    authenticate: (String, String) -> Unit, detail: (ReplicaRecordKey, String?) -> Unit,
    closeDetail: () -> Unit, dismiss: (() -> Unit)?, isCurrent: () -> Boolean = { true }) {
    val run: (() -> Unit) -> Unit = { action -> if (isCurrent()) action() }
    val unsafe = diagnostic?.problem in setOf(ReplicaPresentationProblem.ORPHAN_STAGE, ReplicaPresentationProblem.DAMAGED_STATE)
    var login by remember(state.stamp) { mutableStateOf(false) }
    var cancelling by remember(state.stamp) { mutableStateOf(false) }
    val phase = state.stage?.phase ?: diagnostic?.phase
    val review = state.review
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("replica-recovery-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.replica_recovery_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (unsafe) R.string.replica_recovery_damaged else R.string.replica_recovery_intro))
                }
                if (state.busy) item { CircularProgressIndicator(Modifier.testTag("replica-recovery-busy")) }
                if (!unsafe && phase != null) item { Text(stringResource(phaseMessage(phase)), style = MaterialTheme.typography.titleMedium) }
                if (!unsafe) {
                    state.error?.let { error -> item { Text(stringResource(errorMessage(error)), color = MaterialTheme.colorScheme.error) } }
                    state.notice?.let { notice -> item { Text(stringResource(noticeMessage(notice))) } }
                }
                if (!unsafe && state.detail != null) {
                    val selected = state.detail
                    item {
                        Text("${selected.table ?: selected.key.kind} · ${selected.key.identity}")
                        TextButton({ run(closeDetail) }, enabled = !state.busy) { Text(stringResource(R.string.replica_recovery_back_review)) }
                    }
                    item { Text(stringResource(R.string.replica_recovery_before), style = MaterialTheme.typography.titleMedium) }
                    if (selected.before == null) item { Text(stringResource(R.string.replica_recovery_absent)) }
                    else items(selected.before.pages) { Text(selected.before.page(it), style = MaterialTheme.typography.bodySmall) }
                    item { Text(stringResource(R.string.replica_recovery_after), style = MaterialTheme.typography.titleMedium) }
                    if (selected.after == null) item { Text(stringResource(R.string.replica_recovery_absent)) }
                    else items(selected.after.pages) { Text(selected.after.page(it), style = MaterialTheme.typography.bodySmall) }
                } else {
                    if (!unsafe) {
                        state.stage?.archive?.let { archive -> item {
                            Text(stringResource(R.string.replica_recovery_archive, archive.archiveId, archive.byteLength), style = MaterialTheme.typography.bodySmall)
                        } }
                        review?.let { data ->
                            item {
                                Text(stringResource(R.string.replica_recovery_differences, data.differences.size, data.unchangedRecords),
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            if (data.hasUnfinishedTimer) item { Text(stringResource(R.string.replica_recovery_timer_block)) }
                            items(data.assessment.reviewCounts.filterValues { it > 0 }.toList(), key = { it.first.name }) {
                                Text("${stringResource(reviewMessage(it.first))}: ${it.second}")
                            }
                            items(data.unconfirmedLocalRecords, key = { "local:${it.table}:${it.key.kind}:${it.key.identity}" }) { local ->
                                Column {
                                    Text(stringResource(R.string.replica_recovery_unconfirmed))
                                    Text("${local.table} · ${local.key.identity}", style = MaterialTheme.typography.bodySmall)
                                    TextButton({ run { detail(local.key, local.table) } }, enabled = !state.busy) { Text(stringResource(R.string.replica_recovery_values)) }
                                }
                            }
                            items(data.differences, key = { "difference:${it.key.kind}:${it.key.identity}" }) { row ->
                                Column {
                                    Text(stringResource(when (row.kind) {
                                        ReplicaDifferenceKind.ADDED -> R.string.replica_recovery_added
                                        ReplicaDifferenceKind.CHANGED -> R.string.replica_recovery_changed
                                        ReplicaDifferenceKind.MISSING -> R.string.replica_recovery_missing
                                    }), style = MaterialTheme.typography.titleSmall)
                                    Text("${row.key.kind} · ${row.key.identity}", style = MaterialTheme.typography.bodySmall)
                                    if (row.previouslyDeleted) Text(stringResource(R.string.replica_recovery_tombstone))
                                    TextButton({ run { detail(row.key, null) } }, enabled = !state.busy) { Text(stringResource(R.string.replica_recovery_values)) }
                                }
                            }
                        }
                        state.material?.let { gaps ->
                            item { Text(stringResource(R.string.replica_recovery_material_gaps), style = MaterialTheme.typography.titleMedium) }
                            if (gaps.missingPack || gaps.changedPack) item {
                                Text(stringResource(if (gaps.missingPack) R.string.replica_recovery_pack_missing else R.string.replica_recovery_pack_changed))
                                Text(gaps.selection.pack?.let { "${it.packId} / ${it.revision}" }.orEmpty())
                            }
                            items(gaps.missingAssets.toList()) { Text(stringResource(R.string.replica_recovery_asset_missing, it)) }
                            items(gaps.pendingVariants.toList()) { Text(stringResource(R.string.replica_recovery_variants_pending, it.first, it.second.joinToString())) }
                        }
                        if (state.evidence != null) {
                            if (state.evidence.materials.unresolvedRoles.isNotEmpty()) item {
                                Text(stringResource(R.string.replica_recovery_roles_unresolved, state.evidence.materials.unresolvedRoles.joinToString()))
                            }
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(state.approved, { value -> run { approve(value) } }, enabled = !state.busy, modifier = Modifier.testTag("replica-recovery-approve"))
                                    Text(stringResource(R.string.replica_recovery_consent), Modifier.weight(1f))
                                }
                                Button({ run(confirm) }, enabled = state.canConfirm, modifier = Modifier.testTag("replica-recovery-confirm")) {
                                    Text(stringResource(R.string.replica_recovery_confirm))
                                }
                            }
                        }
                        item {
                            if (state.stage == null && state.notice != ReplicaRecoveryNotice.COMPLETED) {
                                OutlinedButton({ run(preview) }, enabled = !state.busy && state.checked, modifier = Modifier.testTag("replica-recovery-preview")) {
                                    Text(stringResource(R.string.replica_recovery_preview))
                                }
                                if (state.preview != null) Button({ run(prepare) }, enabled = state.canPrepare, modifier = Modifier.testTag("replica-recovery-prepare")) {
                                    Text(stringResource(R.string.replica_recovery_prepare))
                                }
                            } else if (state.stage != null) {
                                OutlinedButton({ run(resume) }, enabled = !state.busy, modifier = Modifier.testTag("replica-recovery-resume")) {
                                    Text(stringResource(R.string.replica_recovery_resume))
                                }
                                if (state.canAttemptCancel) TextButton({ run { cancelling = true } }, modifier = Modifier.testTag("replica-recovery-cancel")) {
                                    Text(stringResource(R.string.replica_recovery_cancel))
                                }
                            }
                            if (state.notice != ReplicaRecoveryNotice.COMPLETED) TextButton({ run { login = true } }, enabled = !state.busy,
                                modifier = Modifier.testTag("replica-recovery-login")) { Text(stringResource(R.string.replica_recovery_login)) }
                        }
                    }
                    item {
                        TextButton({ run(inspect) }, enabled = !state.busy, modifier = Modifier.testTag("replica-recovery-inspect")) {
                            Text(stringResource(R.string.replica_display_retry))
                        }
                        if (dismiss != null && !unsafe) TextButton({ run(dismiss) }, enabled = !state.busy, modifier = Modifier.testTag("replica-recovery-close")) {
                            Text(stringResource(R.string.content_description_back))
                        }
                    }
                }
            }
        }
    }
    if (login && !unsafe) RecoveryLoginDialog(state.busy, isCurrent, { login = false }) { username, password ->
        login = false; run { authenticate(username, password) }
    }
    if (cancelling && !unsafe) AlertDialog(onDismissRequest = { cancelling = false },
        modifier = Modifier.drawWithContent { if (isCurrent()) drawContent() },
        title = { Text(stringResource(R.string.replica_recovery_cancel)) },
        text = { Text(stringResource(R.string.replica_recovery_cancel_hint)) },
        confirmButton = { TextButton({ cancelling = false; run(cancel) }, enabled = !state.busy) { Text(stringResource(R.string.replica_recovery_cancel)) } },
        dismissButton = { TextButton({ cancelling = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun RecoveryLoginDialog(busy: Boolean, isCurrent: () -> Boolean, dismiss: () -> Unit, submit: (String, String) -> Unit) {
    // Deliberately NOT rememberSaveable: no credentials in Bundle, navigation or process state.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { username = ""; password = ""; dismiss() },
        modifier = Modifier.drawWithContent { if (isCurrent()) drawContent() },
        title = { Text(stringResource(R.string.replica_recovery_login)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.replica_recovery_login_hint))
            OutlinedTextField(username, { if (isCurrent()) username = it }, label = { Text(stringResource(R.string.replica_recovery_username)) },
                singleLine = true, modifier = Modifier.testTag("replica-recovery-username"))
            OutlinedTextField(password, { if (isCurrent()) password = it }, label = { Text(stringResource(R.string.replica_recovery_password)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.testTag("replica-recovery-password"))
        } }, confirmButton = { TextButton({
            val user = username; val secret = password; username = ""; password = ""; submit(user, secret)
        }, enabled = !busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.testTag("replica-recovery-login-submit")) {
            Text(stringResource(R.string.replica_recovery_login))
        } }, dismissButton = { TextButton({ username = ""; password = ""; dismiss() }) { Text(stringResource(R.string.action_cancel)) } })
}

private fun phaseMessage(phase: ReplicaSwitchPhase) = when (phase) {
    ReplicaSwitchPhase.FREEZING, ReplicaSwitchPhase.FROZEN -> R.string.replica_recovery_saving
    ReplicaSwitchPhase.ARCHIVE_VERIFIED -> R.string.replica_recovery_awaiting_consent
    ReplicaSwitchPhase.COMMITTING -> R.string.replica_recovery_commit_unknown
    ReplicaSwitchPhase.SQL_COMMITTED, ReplicaSwitchPhase.DESTINATION_BOUND, ReplicaSwitchPhase.CATCHUP_COMMITTING,
    ReplicaSwitchPhase.CATCHUP_BOUND, ReplicaSwitchPhase.RELEASING -> R.string.replica_recovery_forward_only
    ReplicaSwitchPhase.COMPLETED -> R.string.replica_recovery_completed
    ReplicaSwitchPhase.ABORTED -> R.string.replica_recovery_cancelled
}
private fun noticeMessage(notice: ReplicaRecoveryNotice) = when (notice) {
    ReplicaRecoveryNotice.AUTHENTICATED -> R.string.replica_recovery_authenticated
    ReplicaRecoveryNotice.CANCELLED -> R.string.replica_recovery_cancelled
    ReplicaRecoveryNotice.WAITING -> R.string.replica_recovery_waiting
    ReplicaRecoveryNotice.CHANGED -> R.string.replica_recovery_server_changed
    ReplicaRecoveryNotice.COMPLETED -> R.string.replica_recovery_completed
}
private fun errorMessage(error: ReplicaRecoveryError) = when (error) {
    ReplicaRecoveryError.AUTHENTICATION -> R.string.replica_recovery_auth_required
    ReplicaRecoveryError.ACCOUNT -> R.string.replica_recovery_wrong_account
    ReplicaRecoveryError.ENDPOINT -> R.string.replica_recovery_wrong_endpoint
    ReplicaRecoveryError.NETWORK -> R.string.replica_recovery_network_error
    ReplicaRecoveryError.CHANGED -> R.string.replica_recovery_stale
    ReplicaRecoveryError.REVIEW -> R.string.replica_recovery_review_required
    ReplicaRecoveryError.MATERIAL -> R.string.replica_recovery_material_gaps
    ReplicaRecoveryError.NOT_REQUIRED -> R.string.replica_recovery_not_required
    ReplicaRecoveryError.STORAGE -> R.string.replica_recovery_storage_error
    ReplicaRecoveryError.DAMAGED -> R.string.replica_recovery_damaged
    ReplicaRecoveryError.FAILED -> R.string.replica_recovery_failed
}
private fun reviewMessage(kind: ReplicaRecoveryReview) = when (kind) {
    ReplicaRecoveryReview.BUSINESS_QUEUE -> R.string.replica_recovery_business_queue
    ReplicaRecoveryReview.TIMER_QUEUE -> R.string.replica_recovery_timer_queue
    ReplicaRecoveryReview.UNKNOWN_RESULT -> R.string.replica_recovery_unknown_result
    ReplicaRecoveryReview.UNVERIFIED_SOURCE -> R.string.replica_recovery_unverified
    ReplicaRecoveryReview.REJECTION -> R.string.replica_recovery_rejections
    ReplicaRecoveryReview.CONFLICT -> R.string.replica_recovery_conflicts
    ReplicaRecoveryReview.UNFINISHED_TIMER -> R.string.replica_recovery_timer_block
    ReplicaRecoveryReview.METRIC_DRAFT -> R.string.replica_recovery_metric_drafts
    ReplicaRecoveryReview.CONFIGURATION_JOURNAL -> R.string.replica_recovery_config_pending
    ReplicaRecoveryReview.SNAPSHOT_CANDIDATE -> R.string.replica_recovery_snapshot_pending
}
